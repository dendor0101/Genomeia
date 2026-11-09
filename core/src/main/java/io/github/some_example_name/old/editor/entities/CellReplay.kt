package io.github.some_example_name.old.editor.entities

import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import it.unimi.dsi.fastutil.ints.IntArrayList

class CellReplay(
    startCapacity: Int,
    val particleEntity: ParticleEntity,
    val cellEntity: CellEntity
): EditorReplay {
    private val initialCapacity = startCapacity
    var capacity = startCapacity
    var size = 0

    var cellType = ByteArray(startCapacity)
    var index = IntArray(startCapacity)
    var cellGenomeId = IntArray(startCapacity)
    var angleCos = FloatArray(startCapacity)
    var angleSin = FloatArray(startCapacity)
    var specialTypeIndexes = IntArray(startCapacity)
    var neuralIndexes = IntArray(startCapacity)
    var energyFill = ByteArray(startCapacity)
    var collides = BooleanArray(startCapacity)
    var color = IntArray(startCapacity)
    var pheromoneType = IntArray(startCapacity)

    val replayCellsCounterInTick = IntArrayList(10)
    val tickStartIndices = IntArrayList(10)

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > capacity) {
            val newCapacity = minCapacity.coerceAtLeast(capacity * 2)
            cellType = cellType.copyOf(newCapacity)
            index = index.copyOf(newCapacity)
            cellGenomeId = cellGenomeId.copyOf(newCapacity)
            angleCos = angleCos.copyOf(newCapacity)
            angleSin = angleSin.copyOf(newCapacity)
            specialTypeIndexes = specialTypeIndexes.copyOf(newCapacity)
            neuralIndexes = neuralIndexes.copyOf(newCapacity)
            energyFill = energyFill.copyOf(newCapacity)
            collides = collides.copyOf(newCapacity)
            color = color.copyOf(newCapacity)
            pheromoneType = pheromoneType.copyOf(newCapacity)
            capacity = newCapacity
        }
    }

    override fun reset() {
        size = 0
        capacity = initialCapacity
        replayCellsCounterInTick.clear()
        tickStartIndices.clear()
    }

    override fun copy() {
        val cellsAmount = particleEntity.aliveList.size

        replayCellsCounterInTick.add(cellsAmount)
        tickStartIndices.add(size)

        ensureCapacity(size + cellsAmount)

        System.arraycopy(cellEntity.cellType, 0, cellType, size, cellsAmount)
        System.arraycopy(cellEntity.particleIndexes, 0, index, size, cellsAmount)
        System.arraycopy(cellEntity.cellGenomeId, 0, cellGenomeId, size, cellsAmount)
        System.arraycopy(cellEntity.angleCos, 0, angleCos, size, cellsAmount)
        System.arraycopy(cellEntity.angleSin, 0, angleSin, size, cellsAmount)
        System.arraycopy(cellEntity.specialEntity.specialTypeIndexes, 0, specialTypeIndexes, size, cellsAmount)
        System.arraycopy(cellEntity.neuralIndexes, 0, neuralIndexes, size, cellsAmount)
        System.arraycopy(particleEntity.color, 0, color, size, cellsAmount)
        System.arraycopy(cellEntity.pheromoneType, 0, pheromoneType, size, cellsAmount)


        for (cell in 0 until cellsAmount) {
            energyFill[size + cell] = io.github.some_example_name.render.pack.CellLook.energyFilterByte(
                cellEntity.energy[cell], cellEntity.maxEnergy[cell]
            ).toByte()
            collides[size + cell] = particleEntity.isInGrid[cellEntity.particleIndexes[cell]]
        }
        size += cellsAmount
    }

    inline fun forEachInTick(tick: Int, action: (cellType: Byte, index: Int, cellGenomeId: Int, angleCos: Float, angleSin: Float, color: Int) -> Unit) {
        if (tick < 0 || tick >= tickStartIndices.size) return

        val start = tickStartIndices.getInt(tick)
        val count = replayCellsCounterInTick.getInt(tick)
        val end = start + count

        for (i in start until end) {
            action(cellType[i], index[i], cellGenomeId[i], angleCos[i], angleSin[i], color[i])
        }
    }

    fun getTickCount(): Int = replayCellsCounterInTick.size

    fun getPositionInReplay(
        tick: Int,
        indexInTick: Int
    ): Int? {
        if (tick < 0 || tick >= tickStartIndices.size) return null

        val start = tickStartIndices.getInt(tick)
        val count = replayCellsCounterInTick.getInt(tick)

        if (indexInTick !in 0..<count) return null

        val pos = start + indexInTick

        return pos
    }

    fun getCellIndex(
        tick: Int,
        indexInTick: Int
    ): Int? {
        val index = getPositionInReplay(tick, indexInTick)
        return index?.let { this.index[it] }
    }

    fun getCellType(tick: Int, indexInTick: Int): Byte {
        val index = getPositionInReplay(tick, indexInTick)
        return index?.let { cellType[it] } ?: throw Exception("clickedIndex out of range")
    }
    fun getAngleCos(tick: Int, indexInTick: Int): Float {
        val index = getPositionInReplay(tick, indexInTick)
        return index?.let { angleCos[it] } ?: throw Exception("clickedIndex out of range")
    }
    fun getAngleSin(tick: Int, indexInTick: Int): Float {
        val index = getPositionInReplay(tick, indexInTick)
        return index?.let { angleSin[it] } ?: throw Exception("clickedIndex out of range")
    }
    fun getSpecialTypeIndexes(tick: Int, indexInTick: Int): Int {
        val index = getPositionInReplay(tick, indexInTick)
        return index?.let { specialTypeIndexes[it] } ?: throw Exception("clickedIndex out of range")
    }
    fun getNeuralIndexes(tick: Int, indexInTick: Int): Int {
        val index = getPositionInReplay(tick, indexInTick)
        return index?.let { neuralIndexes[it] } ?: throw Exception("clickedIndex out of range")
    }
    fun getColor(tick: Int, indexInTick: Int): Int {
        val index = getPositionInReplay(tick, indexInTick)
        return index?.let { this.color[it] } ?: throw Exception("clickedIndex out of range")
    }
    fun getPheromone(tick: Int, indexInTick: Int): Int {
        val index = getPositionInReplay(tick, indexInTick)
        return index?.let { this.pheromoneType[it] } ?: throw Exception("clickedIndex out of range")
    }
}
