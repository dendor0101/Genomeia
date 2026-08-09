package io.github.some_example_name.old.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class OrganEntity(
    @Transient val organStartMaxAmount: Int = 0
) : Entity(organStartMaxAmount) {

    @ProtoNumber(1) var genomeIndex = IntArray(maxAmount) { -1 }
    @ProtoNumber(2) var genomeSize = IntArray(maxAmount)
    @ProtoNumber(3) var stage = IntArray(maxAmount)
    @ProtoNumber(4) var dividedTimes = IntArray(maxAmount)
    @ProtoNumber(5) var mutatedTimes = IntArray(maxAmount)
    @ProtoNumber(6) var alreadyGrownUp = BooleanArray(maxAmount)
    @ProtoNumber(7) var divideCounterThisStage = IntArray(maxAmount)
    @ProtoNumber(8) var mutateCounterThisStage = IntArray(maxAmount)
    @ProtoNumber(9) var divideAmountThisStage = IntArray(maxAmount)
    @ProtoNumber(10) var mutateAmountThisStage = IntArray(maxAmount)
    @ProtoNumber(11) var justChangedStage = BooleanArray(maxAmount)

    fun addOrgan(
        genomeIndex: Int,
        genomeSize: Int,
        dividedTimes: Int = 0,
        mutatedTimes: Int = 0,
    ): Int {
        val organIndex = add()

        this.genomeIndex[organIndex] = genomeIndex
        this.genomeSize[organIndex] = genomeSize
        this.stage[organIndex] = 0
        this.dividedTimes[organIndex] = dividedTimes
        this.mutatedTimes[organIndex] = mutatedTimes
        this.alreadyGrownUp[organIndex] = false
        this.divideCounterThisStage[organIndex] = 0
        this.mutateCounterThisStage[organIndex] = 0
        this.divideAmountThisStage[organIndex] = dividedTimes
        this.mutateAmountThisStage[organIndex] = mutatedTimes
        this.justChangedStage[organIndex] = true
        return organIndex
    }

    fun deleteOrgan(organIndex: Int) {
        delete(organIndex)

        genomeIndex[organIndex] = -1
        genomeSize[organIndex] = 0
        stage[organIndex] = 0
        dividedTimes[organIndex] = 0
        mutatedTimes[organIndex] = 0
        alreadyGrownUp[organIndex] = false
        divideCounterThisStage[organIndex] = 0
        mutateCounterThisStage[organIndex] = 0
        divideAmountThisStage[organIndex] = 0
        mutateAmountThisStage[organIndex] = 0
        justChangedStage[organIndex] = true
    }

    fun serializeEntity() {
        super.saveSerialize()
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
    }

    fun copyFrom(other: OrganEntity) {
        copyBaseFrom(other)
        copyInto(other.genomeIndex, genomeIndex)
        copyInto(other.genomeSize, genomeSize)
        copyInto(other.stage, stage)
        copyInto(other.dividedTimes, dividedTimes)
        copyInto(other.mutatedTimes, mutatedTimes)
        copyInto(other.alreadyGrownUp, alreadyGrownUp)
        copyInto(other.divideCounterThisStage, divideCounterThisStage)
        copyInto(other.mutateCounterThisStage, mutateCounterThisStage)
        copyInto(other.divideAmountThisStage, divideAmountThisStage)
        copyInto(other.mutateAmountThisStage, mutateAmountThisStage)
        copyInto(other.justChangedStage, justChangedStage)
    }

    override fun onCopy() {}
    override fun onPaste() {}

    override fun onClear(bound: Int) {
        genomeIndex.clear(-1)
        genomeSize.clear()
        stage.clear()
        dividedTimes.clear()
        mutatedTimes.clear()
        alreadyGrownUp.clear(false)
        divideCounterThisStage.clear()
        mutateCounterThisStage.clear()
        divideAmountThisStage.clear()
        mutateAmountThisStage.clear()
        justChangedStage.clear(true)
    }

    override fun onResize(oldMax: Int) {
        genomeIndex = genomeIndex.resize(-1)
        genomeSize = genomeSize.resize()
        stage = stage.resize()
        dividedTimes = dividedTimes.resize()
        mutatedTimes = mutatedTimes.resize()
        alreadyGrownUp = alreadyGrownUp.resize(false)
        divideCounterThisStage = divideCounterThisStage.resize()
        mutateCounterThisStage = mutateCounterThisStage.resize()
        divideAmountThisStage = divideAmountThisStage.resize()
        mutateAmountThisStage = mutateAmountThisStage.resize()
        justChangedStage = justChangedStage.resize(true)
    }
}
