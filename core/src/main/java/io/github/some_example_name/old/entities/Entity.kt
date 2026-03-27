package io.github.some_example_name.old.entities

import it.unimi.dsi.fastutil.ints.IntArrayList
import java.util.BitSet

abstract class Entity(startMaxAmount: Int) {
    protected var maxAmount = startMaxAmount
    var lastId = -1

    var deadStack = IntArrayList(startMaxAmount)

    var isAlive = BitSet(maxAmount)
    private var generation = IntArray(maxAmount)
    fun getGeneration(index: Int) = generation[index]

    var aliveList = IntArrayList(startMaxAmount)
    private var positionInAlive = IntArray(maxAmount) { -1 }

    protected fun add(): Int {
        val cellIndex = if (!deadStack.isEmpty()) {
            deadStack.removeInt(deadStack.size - 1)
        } else {
            ++lastId
        }

        isAlive[cellIndex] = true
        generation[cellIndex]++

        val pos = aliveList.size
        aliveList.add(cellIndex)
        positionInAlive[cellIndex] = pos

        if (maxAmount - 2 < lastId) {
            resize()
        }
        return cellIndex
    }

    protected fun delete(index: Int) {
        if (!isAlive[index]) throw IllegalStateException("Entity $index is already dead")

        isAlive[index] = false
        deadStack.add(index)

        val pos = positionInAlive[index]
        if (pos >= 0) {
            val lastPos = aliveList.size - 1
            val lastEntity = aliveList.getInt(lastPos)

            aliveList.set(pos, lastEntity)
            positionInAlive[lastEntity] = pos

            aliveList.removeInt(lastPos)

            positionInAlive[index] = -1
        }
    }

    fun clear() {
        val cellBound = (lastId + 1).coerceAtLeast(0)
        lastId = -1
        deadStack.clear()
        generation.fill(0, 0, cellBound)
        isAlive.clear()

        aliveList.clear()
        positionInAlive.fill(-1, 0, cellBound)

        onClear(cellBound)
    }

    fun resize() {
        val oldMax = maxAmount
        maxAmount = (oldMax * 5 / 4).coerceAtLeast(oldMax + 1)
        run {
            val old = generation
            generation = IntArray(maxAmount)
            System.arraycopy(old, 0, generation, 0, oldMax)
        }
        run {
            val old = isAlive
            isAlive = BitSet(maxAmount)
            isAlive.or(old)
        }
        run {
            val old = positionInAlive
            positionInAlive = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, positionInAlive, 0, oldMax)
        }

        aliveList.ensureCapacity(maxAmount)

        onResize(oldMax)
    }

    protected abstract fun onCopy()
    protected abstract fun onPaste()
    protected abstract fun onClear(bound: Int)
    protected abstract fun onResize(oldMax: Int)
}
