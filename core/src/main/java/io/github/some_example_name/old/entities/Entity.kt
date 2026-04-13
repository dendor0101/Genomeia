package io.github.some_example_name.old.entities

import it.unimi.dsi.fastutil.ints.IntArrayList
import java.util.BitSet

abstract class Entity(startMaxAmount: Int) {
    protected var maxAmount = startMaxAmount
    var lastId = -1

    var deadStack = IntArrayList(startMaxAmount)

    var isAlive = BooleanArray(maxAmount)
    private var generation = IntArray(maxAmount)
    fun getGeneration(index: Int) = generation[index]
    fun isAliveAndSameGen(index: Int, gen: Int) = isAlive[index] && generation[index] == gen

    var aliveList = IntArrayList(startMaxAmount)
    var positionInAlive = IntArray(maxAmount) { -1 }
    private var cellBoundBeforeClear = 0
    private var oldMaxBeforeResize = 0

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
        cellBoundBeforeClear = cellBound
        lastId = -1
        deadStack.clear()
        generation.fill(0, 0, cellBound)
        isAlive.fill(false, 0, cellBound)

        aliveList.clear()
        positionInAlive.fill(-1, 0, cellBound)

        onClear(cellBound)
    }

    fun resize() {
        val oldMax = maxAmount
        oldMaxBeforeResize = oldMax
        maxAmount = (oldMax * 5 / 4).coerceAtLeast(oldMax + 1)
        run {
            val old = generation
            generation = IntArray(maxAmount)
            System.arraycopy(old, 0, generation, 0, oldMax)
        }
        run {
            val old = isAlive
            isAlive = BooleanArray(maxAmount)
            System.arraycopy(old, 0, isAlive, 0, oldMax)
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

    protected fun FloatArray.clear(defaultValue: Float = 0f) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }

    protected fun IntArray.clear(defaultValue: Int = 0) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }

    protected fun BooleanArray.clear(defaultValue: Boolean) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }

    protected fun ByteArray.clear(defaultValue: Byte = 0) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }


    protected fun FloatArray.resize(defaultValue: Float = 0f): FloatArray {
        val old = this
        val newArray = if (defaultValue == 0f)
            FloatArray(maxAmount)
        else
            FloatArray(maxAmount) { defaultValue }

        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }

    protected fun IntArray.resize(defaultValue: Int = 0): IntArray {
        val old = this
        val newArray = if (defaultValue == 0)
            IntArray(maxAmount)
        else
            IntArray(maxAmount) { defaultValue }

        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }

    protected fun BooleanArray.resize(defaultValue: Boolean): BooleanArray {
        val old = this
        val newArray = BooleanArray(maxAmount) { defaultValue }

        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }

    protected fun ByteArray.resize(defaultValue: Byte = 0): ByteArray {
        val old = this
        val newArray = if (defaultValue == 0.toByte())
            ByteArray(maxAmount)
        else
            ByteArray(maxAmount) { defaultValue }

        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }

    protected fun BitSet.resize(): BitSet {
        val old = this
        val newArray = BitSet(maxAmount)
        newArray.or(old)
        return newArray
    }
}
