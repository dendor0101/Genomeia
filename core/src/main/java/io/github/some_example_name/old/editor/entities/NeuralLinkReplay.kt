package io.github.some_example_name.old.editor.entities

import io.github.some_example_name.old.entities.NeuralLinkEntity
import it.unimi.dsi.fastutil.ints.IntArrayList

class NeuralLinkReplay(
    startCapacity: Int,
    val neuralLinkEntity: NeuralLinkEntity,
) : EditorReplay {
    var capacity = startCapacity
    var size = 0
    private val initialCapacity = startCapacity

    var isLink1NeuralDirected = BooleanArray(startCapacity)
    var color = IntArray(startCapacity)
    var links1 = IntArray(startCapacity)
    var links2 = IntArray(startCapacity)
    var isAliveSnapshot = BooleanArray(startCapacity)

    val replayCellsCounterInTick = IntArrayList(10)
    val tickStartIndices = IntArrayList(10)

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > capacity) {
            val newCapacity = minCapacity.coerceAtLeast(capacity * 2)
            isLink1NeuralDirected = isLink1NeuralDirected.copyOf(newCapacity)
            color = color.copyOf(newCapacity)
            links1 = links1.copyOf(newCapacity)
            links2 = links2.copyOf(newCapacity)
            isAliveSnapshot = isAliveSnapshot.copyOf(newCapacity)
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
        val cellBound = (neuralLinkEntity.lastId + 1).coerceAtLeast(0)

        replayCellsCounterInTick.add(cellBound)
        tickStartIndices.add(size)

        ensureCapacity(size + cellBound)

        System.arraycopy(neuralLinkEntity.isLink1NeuralDirected, 0, isLink1NeuralDirected, size, cellBound)
        System.arraycopy(neuralLinkEntity.color, 0, color, size, cellBound)
        System.arraycopy(neuralLinkEntity.links1, 0, links1, size, cellBound)
        System.arraycopy(neuralLinkEntity.links2, 0, links2, size, cellBound)
        System.arraycopy(neuralLinkEntity.isAlive, 0, isAliveSnapshot, size, cellBound)

        size += cellBound
    }

    inline fun forEachInTick(
        tick: Int,
        action: (isLink1NeuralDirected: Boolean, color: Int, link1: Int, link2: Int) -> Unit
    ) {
        if (tick < 0 || tick >= tickStartIndices.size) return

        val start = tickStartIndices.getInt(tick)
        val cellBound = replayCellsCounterInTick.getInt(tick)
        val end = start + cellBound

        for (i in start until end) {
            if (isAliveSnapshot[i]) {
                action(
                    isLink1NeuralDirected[i],
                    color[i],
                    links1[i],
                    links2[i]
                )
            }
        }
    }

    fun isAlive(
        tick: Int,
        indexInTick: Int
    ): Boolean? {
        if (tick < 0 || tick >= tickStartIndices.size) return null

        val start = tickStartIndices.getInt(tick)
        val count = replayCellsCounterInTick.getInt(tick)

        if (indexInTick !in 0..<count) return null

        val pos = start + indexInTick

        return isAliveSnapshot[pos]
    }

    fun getIsLink1NeuralDirected(
        tick: Int,
        indexInTick: Int
    ): Boolean? {
        if (tick < 0 || tick >= tickStartIndices.size) return null

        val start = tickStartIndices.getInt(tick)
        val count = replayCellsCounterInTick.getInt(tick)

        if (indexInTick !in 0..<count) return null

        val pos = start + indexInTick
        if (!isAliveSnapshot[pos]) return null

        return isLink1NeuralDirected[pos]
    }

    fun getColor(
        tick: Int,
        indexInTick: Int
    ): Int? {
        if (tick < 0 || tick >= tickStartIndices.size) return null

        val start = tickStartIndices.getInt(tick)
        val count = replayCellsCounterInTick.getInt(tick)

        if (indexInTick !in 0..<count) return null

        val pos = start + indexInTick
        if (!isAliveSnapshot[pos]) return null

        return color[pos]
    }
}
