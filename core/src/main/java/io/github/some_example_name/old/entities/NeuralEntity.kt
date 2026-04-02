package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.Cell
import java.util.BitSet
import kotlin.collections.fill

class NeuralEntity(
    neuralStartMaxAmount: Int,
    val cellList: List<Cell>
): Entity(neuralStartMaxAmount) {

    var isNeuronTransportable = BitSet(maxAmount)
    var activationFuncType = ByteArray(maxAmount)
    var a = FloatArray(maxAmount) { 1f }
    var b = FloatArray(maxAmount)
    var c = FloatArray(maxAmount)
    var dTime = FloatArray(maxAmount) { -1f }
    var remember = FloatArray(maxAmount)
    var isSum = BitSet(maxAmount)

    fun addNeural(
        cellType: Int,
        a: Float = 1f,
        b: Float = 0f,
        c: Float = 0f,
        isSum: Boolean,
        activationFuncType: Byte
    ): Int {
        val neuralIndex = add()

        this.isNeuronTransportable[neuralIndex] = cellList[cellType].isNeuronTransportable
        this.activationFuncType[neuralIndex] = activationFuncType
        this.a[neuralIndex] = a
        this.b[neuralIndex] = b
        this.c[neuralIndex] = c
        this.dTime[neuralIndex] = -1f
        this.remember[neuralIndex]  = 0f
        this.isSum[neuralIndex] = isSum
        return neuralIndex
    }

    fun deleteNeural(neuralIndex: Int) {
        delete(neuralIndex)

        isNeuronTransportable[neuralIndex] = true
        activationFuncType[neuralIndex] = 0
        a[neuralIndex] = 1f
        b[neuralIndex] = 0f
        c[neuralIndex] = 0f
        dTime[neuralIndex] = -1f
        remember[neuralIndex] = 0f
        isSum[neuralIndex] = true
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        isNeuronTransportable.clear()
        activationFuncType.clear()
        a.clear(1f)
        b.clear()
        c.clear()
        dTime.clear(-1f)
        remember.clear()
        isSum.clear()
    }

    override fun onResize(oldMax: Int) {
        isNeuronTransportable = isNeuronTransportable.resize()
        activationFuncType = activationFuncType.resize()
        a = a.resize(1f)
        b = b.resize()
        c = c.resize()
        dTime = dTime.resize(-1f)
        remember = remember.resize()
        isSum = isSum.resize()
    }
}
