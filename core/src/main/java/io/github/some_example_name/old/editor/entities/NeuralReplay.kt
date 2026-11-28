package io.github.some_example_name.old.editor.entities

import io.github.some_example_name.old.entities.NeuralEntity

/** Нейроны по тикам роста в редакторе: изменения, а не снимки, — см. [TickRecordLog]. */
class NeuralReplay(
    val neuralEntity: NeuralEntity
): EditorReplay {

    private val log = TickRecordLog(WIDTH)

    override fun byteSize(): Long = log.byteSize()

    override fun reset() = log.reset()

    override fun copy() {
        val amount = neuralEntity.aliveList.size
        val record = log.beginTick(amount)
        var o = 0
        for (i in 0 until amount) {
            record[o + FUNC] = neuralEntity.activationFuncType[i].toInt()
            record[o + A] = neuralEntity.a[i].toRawBits()
            record[o + B] = neuralEntity.b[i].toRawBits()
            record[o + C] = neuralEntity.c[i].toRawBits()
            record[o + IS_SUM] = if (neuralEntity.isSum[i]) 1 else 0
            o += WIDTH
        }
        log.endTick()
    }

    fun getTickCount(): Int = log.tickCount

    /** Поле [field] нейрона [indexInTick] на тике [tick]; null — нет такого. */
    private fun field(tick: Int, indexInTick: Int, field: Int): Int? {
        if (tick < 0 || tick >= log.tickCount) return null
        if (indexInTick < 0 || indexInTick >= log.count(tick)) return null
        return log.view(tick)!![indexInTick * WIDTH + field]
    }

    fun getActivationFuncType(tick: Int, indexInTick: Int): Byte? = field(tick, indexInTick, FUNC)?.toByte()
    fun getA(tick: Int, indexInTick: Int): Float? = field(tick, indexInTick, A)?.let(Float::fromBits)
    fun getB(tick: Int, indexInTick: Int): Float? = field(tick, indexInTick, B)?.let(Float::fromBits)
    fun getC(tick: Int, indexInTick: Int): Float? = field(tick, indexInTick, C)?.let(Float::fromBits)
    fun getIsSum(tick: Int, indexInTick: Int): Boolean? = field(tick, indexInTick, IS_SUM)?.let { it != 0 }

    private companion object {
        const val FUNC = 0
        const val A = 1
        const val B = 2
        const val C = 3
        const val IS_SUM = 4
        const val WIDTH = 5
    }
}
