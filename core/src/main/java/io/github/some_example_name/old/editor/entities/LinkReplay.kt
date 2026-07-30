package io.github.some_example_name.old.editor.entities

import io.github.some_example_name.old.entities.LinkEntity
import it.unimi.dsi.fastutil.ints.IntArrayList

class LinkReplay(
    startCapacity: Int,
    val linkEntity: LinkEntity,
): EditorReplay {
    var capacity = startCapacity
    var size = 0
    private val initialCapacity = startCapacity

//    var isNeuronLink = BooleanArray(startCapacity)
//    var isLink1NeuralDirected = BooleanArray(startCapacity)
//    var color = IntArray(startCapacity)
    var links1 = IntArray(startCapacity)
    var links2 = IntArray(startCapacity)

    val replayCellsCounterInTick = IntArrayList(10)
    val tickStartIndices = IntArrayList(10)   // ← НОВОЕ: стартовые индексы для каждого тика

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > capacity) {
            val newCapacity = minCapacity.coerceAtLeast(capacity * 2)
//            isNeuronLink = isNeuronLink.copyOf(newCapacity)
//            isLink1NeuralDirected = isLink1NeuralDirected.copyOf(newCapacity)
//            color = color.copyOf(newCapacity)
            links1 = links1.copyOf(newCapacity)
            links2 = links2.copyOf(newCapacity)
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
        val cellBound = (linkEntity.lastId + 1).coerceAtLeast(0)

        replayCellsCounterInTick.add(cellBound)
        tickStartIndices.add(size)

        ensureCapacity(size + cellBound)

//        System.arraycopy(linkEntity.isNeuronLink,          0, isNeuronLink,          size, cellBound)
//        System.arraycopy(linkEntity.isLink1NeuralDirected, 0, isLink1NeuralDirected, size, cellBound)
//        System.arraycopy(linkEntity.color,                 0, color,                 size, cellBound)
        System.arraycopy(linkEntity.links1,                0, links1,                size, cellBound)
        System.arraycopy(linkEntity.links2,                0, links2,                size, cellBound)

        size += cellBound
    }

    inline fun forEachInTick(tick: Int, action: (link1: Int, link2: Int) -> Unit) {
        if (tick < 0 || tick >= tickStartIndices.size) return

        val start = tickStartIndices.getInt(tick)
        val cellBound = replayCellsCounterInTick.getInt(tick)
        val end = start + cellBound

        for (i in start until end) {
            action(
//                isNeuronLink[i],
//                isLink1NeuralDirected[i],
//                color[i],
                links1[i],
                links2[i]
            )
        }
    }

}
