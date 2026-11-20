package io.github.some_example_name.old.editor.entities

import io.github.some_example_name.old.entities.NeuralLinkEntity

/**
 * Нейросвязи по тикам роста в редакторе: изменения, а не снимки, — см. [TickRecordLog].
 * Ряд идёт по слотам до lastId включительно, с мёртвыми: номер слота — это номер связи.
 */
class NeuralLinkReplay(
    val neuralLinkEntity: NeuralLinkEntity,
) : EditorReplay {

    @PublishedApi
    internal val log = TickRecordLog(WIDTH)

    override fun byteSize(): Long = log.byteSize()

    override fun reset() = log.reset()

    override fun copy() {
        val slots = (neuralLinkEntity.lastId + 1).coerceAtLeast(0)
        val record = log.beginTick(slots)
        var o = 0
        for (i in 0 until slots) {
            record[o + DIRECTED] = if (neuralLinkEntity.isLink1NeuralDirected[i]) 1 else 0
            record[o + COLOR] = neuralLinkEntity.color[i]
            record[o + LINK1] = neuralLinkEntity.links1[i]
            record[o + LINK2] = neuralLinkEntity.links2[i]
            record[o + ALIVE] = if (neuralLinkEntity.isAlive[i]) 1 else 0
            o += WIDTH
        }
        log.endTick()
    }

    inline fun forEachInTick(
        tick: Int,
        action: (isLink1NeuralDirected: Boolean, color: Int, link1: Int, link2: Int) -> Unit
    ) {
        val data = log.view(tick) ?: return
        val slots = log.count(tick)
        var o = 0
        for (i in 0 until slots) {
            if (data[o + ALIVE] != 0) action(data[o + DIRECTED] != 0, data[o + COLOR], data[o + LINK1], data[o + LINK2])
            o += WIDTH
        }
    }

    /** Начало слота [indexInTick] на тике [tick] в ряду; -1 — нет такого. */
    private fun offset(tick: Int, indexInTick: Int): Int {
        if (tick < 0 || tick >= log.tickCount) return -1
        if (indexInTick !in 0..<log.count(tick)) return -1
        return indexInTick * WIDTH
    }

    fun isAlive(tick: Int, indexInTick: Int): Boolean? {
        val o = offset(tick, indexInTick)
        return if (o < 0) null else log.view(tick)!![o + ALIVE] != 0
    }

    fun getIsLink1NeuralDirected(tick: Int, indexInTick: Int): Boolean? {
        val o = offset(tick, indexInTick)
        if (o < 0) return null
        val data = log.view(tick)!!
        if (data[o + ALIVE] == 0) return null
        return data[o + DIRECTED] != 0
    }

    companion object {
        const val DIRECTED = 0
        const val COLOR = 1
        const val LINK1 = 2
        const val LINK2 = 3
        const val ALIVE = 4
        const val WIDTH = 5
    }
}
