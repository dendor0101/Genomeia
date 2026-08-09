package io.github.some_example_name.old.entities

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.systems.pheromone.PheromonesManager.Companion.MAXIMUM_PHEROMONE_SPREAD_DIAMETER
import io.github.some_example_name.old.systems.pheromone.getSquaredRadius
import io.github.some_example_name.old.systems.physics.GridManager
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

private const val MAX = 3440

@Serializable
class PheromoneEntity(
    @Transient val pheromoneStartMaxAmount: Int = 100
) : Entity(pheromoneStartMaxAmount) {

    @Transient lateinit var gridManager: GridManager

    // вторичный конструктор для обычного создания (сохраняет старый API)
    constructor(startMaxAmount: Int = 100, gridManager: GridManager) : this(startMaxAmount) {
        loadEntity(gridManager)
    }

    @ProtoNumber(1) var x = FloatArray(maxAmount)
    @ProtoNumber(2) var y = FloatArray(maxAmount)
    @ProtoNumber(3) var time = FloatArray(maxAmount)
    @ProtoNumber(4) var radiusSquared = FloatArray(maxAmount)
    @ProtoNumber(5) var emitterIndex = IntArray(maxAmount)
    @ProtoNumber(6) var color = IntArray(maxAmount)
    @ProtoNumber(7) var type = IntArray(maxAmount)

    // Transient-коллекции, восстанавливаются после десериализации
    @Transient
    val pheromoneMapGrid = Int2ObjectOpenHashMap<Int2ObjectOpenHashMap<IntOpenHashSet>>()
    @Transient
    val emitterMap = Int2ObjectOpenHashMap<Int2ObjectOpenHashMap<IntOpenHashSet>>()

    // Сериализуемые представления
    @ProtoNumber(8)
    var pheromoneMapGridData: List<PheromoneGridEntry> = emptyList()

    @ProtoNumber(9)
    var emitterMapData: List<EmitterGridEntry> = emptyList()

    companion object {
        const val MAX_PHEROMONE_TYPES = 32

        private val TYPE_COLORS = IntArray(MAX_PHEROMONE_TYPES) { index ->
            val hue = (index * 360f) / MAX_PHEROMONE_TYPES
            Color().fromHsv(hue, 0.92f, 0.96f).toIntBits()
        }
    }

    fun pack(x: Int, y: Int): Int {
        require(x in 0..MAX && y in 0..MAX)
        return (x shl 12) or y
    }

    private fun addUnique(pType: Int, key: Int, value: Int): Boolean {
        val typeMap = emitterMap.computeIfAbsent(pType) { Int2ObjectOpenHashMap(4) }
        val set = typeMap.computeIfAbsent(key) { IntOpenHashSet(3) }
        return set.add(value)
    }

    fun addPheromone(x: Float, y: Float, emitterIndex: Int, type: Int, time: Float = 0f): Int? {
        if (emitterIndex != -1) {
            val exactKey = pack(x.toInt(), y.toInt())
            val isFirstAdded = addUnique(type, exactKey, emitterIndex)
            if (!isFirstAdded) return null
        }

        val newIndex = add()

        this.x[newIndex] = x
        this.y[newIndex] = y
        this.time[newIndex] = time
        this.radiusSquared[newIndex] = getSquaredRadius(A = time)
        this.emitterIndex[newIndex] = emitterIndex
        this.type[newIndex] = type

        this.color[newIndex] = if (type in 0 until MAX_PHEROMONE_TYPES) {
            TYPE_COLORS[type]
        } else {
            Color.FIREBRICK.toIntBits()
        }

        val bigGridX = x.toInt() / MAXIMUM_PHEROMONE_SPREAD_DIAMETER
        val bigGridY = y.toInt() / MAXIMUM_PHEROMONE_SPREAD_DIAMETER
        val gridKey = pack(bigGridX, bigGridY)

        val typeGridMap = pheromoneMapGrid.computeIfAbsent(type) { Int2ObjectOpenHashMap(8) }
        typeGridMap.computeIfAbsent(gridKey) { IntOpenHashSet(8) }.add(newIndex)

        return newIndex
    }

    fun deletePheromone(pheromoneIndex: Int, pheromoneGeneration: Int) {
        if (isAlive[pheromoneIndex] && getGeneration(pheromoneIndex) == pheromoneGeneration) {

            val px = x[pheromoneIndex]
            val py = y[pheromoneIndex]
            val emitter = emitterIndex[pheromoneIndex]
            val pType = type[pheromoneIndex]

            val gridX = px.toInt() / MAXIMUM_PHEROMONE_SPREAD_DIAMETER
            val gridY = py.toInt() / MAXIMUM_PHEROMONE_SPREAD_DIAMETER
            val gridKey = pack(gridX, gridY)

            pheromoneMapGrid.get(pType)?.let { typeGridMap ->
                typeGridMap.get(gridKey)?.let { set ->
                    set.remove(pheromoneIndex)
                    if (set.isEmpty()) {
                        typeGridMap.remove(gridKey)
                        if (typeGridMap.isEmpty()) {
                            pheromoneMapGrid.remove(pType)
                        }
                    }
                }
            }

            val exactKey = pack(px.toInt(), py.toInt())

            emitterMap.get(pType)?.let { typeEmitterMap ->
                typeEmitterMap.get(exactKey)?.let { set ->
                    set.remove(emitter)
                    if (set.isEmpty()) {
                        typeEmitterMap.remove(exactKey)
                        if (typeEmitterMap.isEmpty()) {
                            emitterMap.remove(pType)
                        }
                    }
                }
            }

            delete(pheromoneIndex)

            x[pheromoneIndex] = 0f
            y[pheromoneIndex] = 0f
            time[pheromoneIndex] = 0f
            radiusSquared[pheromoneIndex] = 0f
            emitterIndex[pheromoneIndex] = -1
            color[pheromoneIndex] = 0
            type[pheromoneIndex] = -1
        }
    }

    fun loadEntity(gridManager: GridManager) {
        this.gridManager = gridManager
    }

    fun serializeEntity() {
        super.saveSerialize()
        pheromoneMapGridData = pheromoneMapGrid.flatMap { (type, gridMap) ->
            gridMap.map { (gridKey, indicesSet) ->
                PheromoneGridEntry(type, gridKey, indicesSet.toList())
            }
        }
        emitterMapData = emitterMap.flatMap { (type, exactMap) ->
            exactMap.map { (exactKey, emittersSet) ->
                EmitterGridEntry(type, exactKey, emittersSet.toList())
            }
        }
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
        rebuildTransient()
    }

    fun copyFrom(other: PheromoneEntity) {
        copyBaseFrom(other)
        copyInto(other.x, x)
        copyInto(other.y, y)
        copyInto(other.time, time)
        copyInto(other.radiusSquared, radiusSquared)
        copyInto(other.emitterIndex, emitterIndex)
        copyInto(other.color, color)
        copyInto(other.type, type)

        pheromoneMapGridData = other.pheromoneMapGridData
        emitterMapData = other.emitterMapData
        rebuildTransient()
    }

    private fun rebuildTransient() {
        pheromoneMapGrid.clear()
        for (entry in pheromoneMapGridData) {
            val gridMap = pheromoneMapGrid.computeIfAbsent(entry.type) { Int2ObjectOpenHashMap() }
            gridMap[entry.gridKey] = IntOpenHashSet(entry.indices)
        }
        emitterMap.clear()
        for (entry in emitterMapData) {
            val exactMap = emitterMap.computeIfAbsent(entry.type) { Int2ObjectOpenHashMap() }
            exactMap[entry.exactKey] = IntOpenHashSet(entry.emitters)
        }
    }

    override fun onCopy() {}
    override fun onPaste() {}

    override fun onClear(bound: Int) {
        x.clear()
        y.clear()
        time.clear()
        radiusSquared.clear()
        emitterIndex.clear(-1)
        color.clear()
        type.clear(-1)

        pheromoneMapGrid.clear()
        emitterMap.clear()
    }

    override fun onResize(oldMax: Int) {
        x = x.resize()
        y = y.resize()
        time = time.resize()
        radiusSquared = radiusSquared.resize()
        emitterIndex = emitterIndex.resize(-1)
        color = color.resize()
        type = type.resize(-1)
    }
}

@Serializable
data class PheromoneGridEntry(
    @ProtoNumber(1) val type: Int,
    @ProtoNumber(2) val gridKey: Int,
    @ProtoNumber(3) val indices: List<Int>
)

@Serializable
data class EmitterGridEntry(
    @ProtoNumber(1) val type: Int,
    @ProtoNumber(2) val exactKey: Int,
    @ProtoNumber(3) val emitters: List<Int>
)
