package io.github.some_example_name.old.entities

import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS
import io.github.some_example_name.old.systems.physics.GridManager
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.math.PI

@Serializable
class ParticleEntity(
    @Transient val particlesStartMaxAmount: Int = 0
) : Entity(particlesStartMaxAmount) {

    @Transient lateinit var gridManager: GridManager

    constructor(particlesStartMaxAmount: Int, gridManager: GridManager) : this(particlesStartMaxAmount) {
        loadEntity(gridManager)
    }

    @ProtoNumber(1) var gridId = IntArray(maxAmount) { -1 }
    @ProtoNumber(2) var x = FloatArray(maxAmount)
    @ProtoNumber(3) var y = FloatArray(maxAmount)
    @ProtoNumber(4) var vx = FloatArray(maxAmount)
    @ProtoNumber(5) var vy = FloatArray(maxAmount)
    @ProtoNumber(6) var radius = FloatArray(maxAmount) { PARTICLE_MAX_RADIUS }
    @ProtoNumber(7) var mass = FloatArray(maxAmount)
    @ProtoNumber(8) var color = IntArray(maxAmount)
    @ProtoNumber(9) var dragCoefficient = FloatArray(maxAmount) { 0.003f }
    @ProtoNumber(10) var effectOnContact = BooleanArray(maxAmount)
    @ProtoNumber(11) var isCollidable = BooleanArray(maxAmount)
    @ProtoNumber(12) var cellStiffness = FloatArray(maxAmount) { 0.5f }
    @ProtoNumber(13) var isCell = BooleanArray(maxAmount) { false }
    @ProtoNumber(14) var isSub = BooleanArray(maxAmount) { false }
    @ProtoNumber(15) var holderEntityIndex = IntArray(maxAmount) { -1 }
    @ProtoNumber(16) var isPheromoneEmitter = BooleanArray(maxAmount) { false }

    fun addParticle(
        x: Float,
        y: Float,
        radius: Float,
        color: Int,
        vx: Float = 0f,
        vy: Float = 0f,
        dragCoefficient: Float = 0.03f,
        effectOnContact: Boolean = false,
        isCollidable: Boolean = true,
        cellStiffness: Float = 0.02f,
        isCell: Boolean,
        isSub: Boolean,
        isPheromoneEmitter: Boolean = false,
        holderEntityIndex: Int
    ): Int {
        val particleIndex = add()

        gridId[particleIndex] = gridManager.addParticle(x.toInt(), y.toInt(), particleIndex)
        this.x[particleIndex] = x
        this.y[particleIndex] = y
        this.vx[particleIndex] = vx
        this.vy[particleIndex] = vy
        this.radius[particleIndex] = radius
        this.mass[particleIndex] = radius * radius * PI.toFloat()
        this.color[particleIndex] = color
        this.dragCoefficient[particleIndex] = dragCoefficient
        this.effectOnContact[particleIndex] = effectOnContact
        this.isCollidable[particleIndex] = isCollidable
        this.cellStiffness[particleIndex] = cellStiffness
        this.isCell[particleIndex] = isCell
        this.isSub[particleIndex] = isSub
        this.holderEntityIndex[particleIndex] = holderEntityIndex
        this.isPheromoneEmitter[particleIndex] = isPheromoneEmitter
        return particleIndex
    }

    fun deleteParticle(particleIndex: Int) {
        delete(particleIndex)

        gridManager.removeParticle(gridId[particleIndex], particleIndex)
        gridId[particleIndex] = -1
        x[particleIndex] = 0f
        y[particleIndex] = 0f
        vx[particleIndex] = 0f
        vy[particleIndex] = 0f
        radius[particleIndex] = PARTICLE_MAX_RADIUS
        mass[particleIndex] = 0f
        color[particleIndex] = 0
        dragCoefficient[particleIndex] = 0.93f
        effectOnContact[particleIndex] = false
        isCollidable[particleIndex] = true
        cellStiffness[particleIndex] = 0.5f
        isCell[particleIndex] = false
        isSub[particleIndex] = false
        holderEntityIndex[particleIndex] = -1
        isPheromoneEmitter[particleIndex] = false
    }

    fun loadEntity(gridManager: GridManager) {
        this.gridManager = gridManager
    }

    fun serializeEntity() {
        super.saveSerialize()
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
        // ВНИМАНИЕ: после вызова loadSerializedEntity и loadEntity(gridManager)
        // необходимо вызвать restoreGridManager() для восстановления состояния GridManager.
    }

    /**
     * Восстанавливает структуры GridManager по загруженным данным.
     * Должен вызываться после loadEntity(gridManager) и loadSerializedEntity().
     *
     * Заодно чинит частицы, у которых в сохранении оказались NaN или координаты за границами
     * сетки: NaN.toInt() == 0, поэтому без починки все такие частицы свалились бы в ячейку 0,
     * а координаты за границей уронили бы addParticle с "Out of grid bounds".
     *
     * @return количество починенных частиц
     */
    fun restoreGridManager(): Int {
        val maxX = gridManager.gridWidth - 1f
        val maxY = gridManager.gridHeight - 1f
        var repaired = 0

        for (i in 0..lastId) {
            if (!isAlive[i]) continue

            val fixedX = sanitizeCoordinate(x[i], maxX)
            val fixedY = sanitizeCoordinate(y[i], maxY)
            val fixedVx = sanitizeVelocity(vx[i])
            val fixedVy = sanitizeVelocity(vy[i])

            // NaN != NaN, поэтому битые значения тоже попадают в счётчик
            if (fixedX != x[i] || fixedY != y[i] || fixedVx != vx[i] || fixedVy != vy[i]) {
                repaired++
            }

            x[i] = fixedX
            y[i] = fixedY
            vx[i] = fixedVx
            vy[i] = fixedVy

            gridId[i] = gridManager.addParticle(fixedX.toInt(), fixedY.toInt(), i)
        }
        return repaired
    }

    private fun sanitizeCoordinate(value: Float, max: Float) =
        if (value.isFinite()) value.coerceIn(0f, max) else max * 0.5f

    private fun sanitizeVelocity(value: Float) = if (value.isFinite()) value else 0f

    override fun onCopy() {}
    override fun onPaste() {}

    fun copyFrom(other: ParticleEntity) {
        this.maxAmount = other.maxAmount
        this.lastId = other.lastId

        // Вспомогательная функция для безопасного копирования БЕЗ замены ссылок
        fun safeCopy(src: IntArray, dst: IntArray) {
            System.arraycopy(src, 0, dst, 0, src.size.coerceAtMost(dst.size))
        }
        fun safeCopy(src: FloatArray, dst: FloatArray) {
            System.arraycopy(src, 0, dst, 0, src.size.coerceAtMost(dst.size))
        }
        fun safeCopy(src: BooleanArray, dst: BooleanArray) {
            System.arraycopy(src, 0, dst, 0, src.size.coerceAtMost(dst.size))
        }

        // 1. Копируем базовые данные Entity в существующие массивы
        safeCopy(other.generation, this.generation)
        safeCopy(other.isAlive, this.isAlive)
        safeCopy(other.positionInAlive, this.positionInAlive)

        // Для fastutil коллекций очищаем и добавляем, а не заменяем ссылку
        this.aliveList.clear()
        this.aliveList.addAll(other.aliveList)

        this.deadStack.clear()
        this.deadStack.addAll(other.deadStack)

        // 2. Копируем специфичные данные ParticleEntity в существующие массивы
        safeCopy(other.gridId, this.gridId)
        safeCopy(other.x, this.x)
        safeCopy(other.y, this.y)
        safeCopy(other.vx, this.vx)
        safeCopy(other.vy, this.vy)
        safeCopy(other.radius, this.radius)
        safeCopy(other.mass, this.mass)
        safeCopy(other.color, this.color)
        safeCopy(other.dragCoefficient, this.dragCoefficient)
        safeCopy(other.effectOnContact, this.effectOnContact)
        safeCopy(other.isCollidable, this.isCollidable)
        safeCopy(other.cellStiffness, this.cellStiffness)
        safeCopy(other.isCell, this.isCell)
        safeCopy(other.isSub, this.isSub)
        safeCopy(other.holderEntityIndex, this.holderEntityIndex)
        safeCopy(other.isPheromoneEmitter, this.isPheromoneEmitter)
    }

    override fun onClear(bound: Int) {
        gridId.clear(-1)
        x.clear()
        y.clear()
        vx.clear()
        vy.clear()
        radius.clear(PARTICLE_MAX_RADIUS)
        mass.clear()
        color.clear()
        dragCoefficient.clear(0.03f)
        effectOnContact.clear(false)
        isCollidable.clear(true)
        // Дефолт должен совпадать с инициализатором поля: нулевая жёсткость у обеих частиц
        // даёт 0 / 0 = NaN в CollisionManager.repulse
        cellStiffness.clear(0.5f)
        isCell.clear(false)
        isSub.clear(false)
        holderEntityIndex.clear(-1)
        isPheromoneEmitter.clear(false)
    }

    override fun onResize(oldMax: Int) {
        gridId = gridId.resize(-1)
        x = x.resize()
        y = y.resize()
        vx = vx.resize()
        vy = vy.resize()
        radius = radius.resize(PARTICLE_MAX_RADIUS)
        mass = mass.resize()
        color = color.resize()
        dragCoefficient = dragCoefficient.resize(0.03f)
        effectOnContact = effectOnContact.resize(false)
        isCollidable = isCollidable.resize(true)
        cellStiffness = cellStiffness.resize(0.5f)
        isCell = isCell.resize(false)
        isSub = isSub.resize(false)
        holderEntityIndex = holderEntityIndex.resize(-1)
        isPheromoneEmitter = isPheromoneEmitter.resize(false)
    }
}
