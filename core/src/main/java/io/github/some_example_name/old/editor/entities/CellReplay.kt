package io.github.some_example_name.old.editor.entities

import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity

/**
 * Клетки по тикам роста в редакторе. Хранит изменения, а не снимок каждого тика, —
 * почему, см. [TickRecordLog]. Позиции частиц не пишутся: редактор их не двигает, и
 * читаются они прямо из мира редактора.
 */
class CellReplay(
    val particleEntity: ParticleEntity,
    val cellEntity: CellEntity
): EditorReplay {

    @PublishedApi
    internal val log = TickRecordLog(WIDTH)

    override fun byteSize(): Long = log.byteSize()

    override fun reset() = log.reset()

    override fun copy() {
        val cellsAmount = particleEntity.aliveList.size
        val record = log.beginTick(cellsAmount)
        var o = 0
        for (cell in 0 until cellsAmount) {
            val particle = cellEntity.particleIndexes[cell]
            record[o + CELL_TYPE] = cellEntity.cellType[cell].toInt()
            record[o + INDEX] = particle
            record[o + GENOME_ID] = cellEntity.cellGenomeId[cell]
            record[o + ANGLE_COS] = cellEntity.angleCos[cell].toRawBits()
            record[o + ANGLE_SIN] = cellEntity.angleSin[cell].toRawBits()
            record[o + SPECIAL] = cellEntity.specialEntity.specialTypeIndexes[cell]
            record[o + NEURAL] = cellEntity.neuralIndexes[cell]
            record[o + COLOR] = particleEntity.color[cell]
            record[o + PHEROMONE] = cellEntity.pheromoneType[cell]
            o += WIDTH
        }
        log.endTick()
    }

    inline fun forEachInTick(tick: Int, action: (cellType: Byte, index: Int, cellGenomeId: Int, angleCos: Float, angleSin: Float, color: Int) -> Unit) {
        val data = log.view(tick) ?: return
        val count = log.count(tick)
        var o = 0
        for (i in 0 until count) {
            action(
                data[o + CELL_TYPE].toByte(), data[o + INDEX], data[o + GENOME_ID],
                Float.fromBits(data[o + ANGLE_COS]), Float.fromBits(data[o + ANGLE_SIN]), data[o + COLOR]
            )
            o += WIDTH
        }
    }

    fun getTickCount(): Int = log.tickCount

    /** Начало записи клетки [indexInTick] на тике [tick] в ряду [TickRecordLog.view]; -1 — нет такой. */
    private fun offset(tick: Int, indexInTick: Int): Int {
        if (tick < 0 || tick >= log.tickCount) return -1
        if (indexInTick !in 0..<log.count(tick)) return -1
        return indexInTick * WIDTH
    }

    private fun field(tick: Int, indexInTick: Int, field: Int): Int {
        val o = offset(tick, indexInTick)
        if (o < 0) throw Exception("clickedIndex out of range")
        return log.view(tick)!![o + field]
    }

    fun getCellIndex(tick: Int, indexInTick: Int): Int? {
        val o = offset(tick, indexInTick)
        return if (o < 0) null else log.view(tick)!![o + INDEX]
    }

    fun getCellType(tick: Int, indexInTick: Int): Byte = field(tick, indexInTick, CELL_TYPE).toByte()
    fun getAngleCos(tick: Int, indexInTick: Int): Float = Float.fromBits(field(tick, indexInTick, ANGLE_COS))
    fun getAngleSin(tick: Int, indexInTick: Int): Float = Float.fromBits(field(tick, indexInTick, ANGLE_SIN))
    fun getSpecialTypeIndexes(tick: Int, indexInTick: Int): Int = field(tick, indexInTick, SPECIAL)
    fun getNeuralIndexes(tick: Int, indexInTick: Int): Int = field(tick, indexInTick, NEURAL)
    fun getColor(tick: Int, indexInTick: Int): Int = field(tick, indexInTick, COLOR)
    fun getPheromone(tick: Int, indexInTick: Int): Int = field(tick, indexInTick, PHEROMONE)

    companion object {
        const val CELL_TYPE = 0
        const val INDEX = 1
        const val GENOME_ID = 2
        const val ANGLE_COS = 3
        const val ANGLE_SIN = 4
        const val SPECIAL = 5
        const val NEURAL = 6
        const val COLOR = 7
        const val PHEROMONE = 8
        const val WIDTH = 9
    }
}
