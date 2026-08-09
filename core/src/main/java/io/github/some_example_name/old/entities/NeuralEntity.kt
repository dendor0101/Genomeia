package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.Cell
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class NeuralEntity(
    @Transient val neuralStartMaxAmount: Int = 0
) : Entity(neuralStartMaxAmount) {

    @Transient lateinit var cellList: List<Cell>

    constructor(
        neuralStartMaxAmount: Int,
        cellList: List<Cell>
    ) : this(neuralStartMaxAmount) {
        loadEntity(cellList)
    }

    @ProtoNumber(1) var isNeuronTransportable = BooleanArray(maxAmount)
    @ProtoNumber(2) var activationFuncType = ByteArray(maxAmount)
    @ProtoNumber(3) var a = FloatArray(maxAmount) { 1f }
    @ProtoNumber(4) var b = FloatArray(maxAmount)
    @ProtoNumber(5) var c = FloatArray(maxAmount)
    @ProtoNumber(6) var dTime = FloatArray(maxAmount) { -1f }
    @ProtoNumber(7) var remember = FloatArray(maxAmount)
    @ProtoNumber(8) var isSum = BooleanArray(maxAmount)
    @ProtoNumber(9) var tickPain = IntArray(maxAmount)
    @ProtoNumber(10) var tickRed = IntArray(maxAmount)
    @ProtoNumber(11) var weight = FloatArray(maxAmount)

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
        this.remember[neuralIndex] = 0f
        this.isSum[neuralIndex] = isSum
        this.tickPain[neuralIndex] = -1
        this.tickRed[neuralIndex] = -1
        this.weight[neuralIndex] = 0.5f
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
        tickPain[neuralIndex] = -1
        tickRed[neuralIndex] = -1
        weight[neuralIndex] = 0.5f
    }

    fun loadEntity(cellList: List<Cell>) {
        this.cellList = cellList
    }

    fun serializeEntity() {
        super.saveSerialize()
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
    }

    fun copyFrom(other: NeuralEntity) {
        copyBaseFrom(other)
        copyInto(other.isNeuronTransportable, isNeuronTransportable)
        copyInto(other.activationFuncType, activationFuncType)
        copyInto(other.a, a)
        copyInto(other.b, b)
        copyInto(other.c, c)
        copyInto(other.dTime, dTime)
        copyInto(other.remember, remember)
        copyInto(other.isSum, isSum)
        copyInto(other.tickPain, tickPain)
        copyInto(other.tickRed, tickRed)
        copyInto(other.weight, weight)
    }

    override fun onCopy() {}
    override fun onPaste() {}

    override fun onClear(bound: Int) {
        isNeuronTransportable.clear(true)
        activationFuncType.clear()
        a.clear(1f)
        b.clear()
        c.clear()
        dTime.clear(-1f)
        remember.clear()
        isSum.clear(true)
        tickPain.clear()
        tickRed.clear()
        weight.clear(0.5f)
    }

    override fun onResize(oldMax: Int) {
        isNeuronTransportable = isNeuronTransportable.resize(true)
        activationFuncType = activationFuncType.resize()
        a = a.resize(1f)
        b = b.resize()
        c = c.resize()
        dTime = dTime.resize(-1f)
        remember = remember.resize()
        isSum = isSum.resize(true)
        tickPain = tickPain.resize()
        tickRed = tickRed.resize()
        weight = weight.resize(0.5f)
    }
}
