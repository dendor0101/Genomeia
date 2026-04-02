package io.github.some_example_name.old.entities

import java.util.BitSet

class OrganEntity(
    organStartMaxAmount: Int
): Entity(organStartMaxAmount) {

    var genomeIndex = IntArray(maxAmount) { -1 }
    var genomeSize = IntArray(maxAmount)
    var stage = IntArray(maxAmount)
    var dividedTimes = IntArray(maxAmount)
    var mutatedTimes = IntArray(maxAmount)
    var alreadyGrownUp = BitSet(maxAmount)
    var divideCounterThisStage = IntArray(maxAmount)
    var mutateCounterThisStage = IntArray(maxAmount)
    var divideAmountThisStage = IntArray(maxAmount)
    var mutateAmountThisStage = IntArray(maxAmount)
    var justChangedStage = BitSet(maxAmount)

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

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        genomeIndex.clear(-1)
        genomeSize.clear()
        stage.clear()
        dividedTimes.clear()
        mutatedTimes.clear()
        alreadyGrownUp.clear()
        divideCounterThisStage.clear()
        mutateCounterThisStage.clear()
        divideAmountThisStage.clear()
        mutateAmountThisStage.clear()
        justChangedStage.clear()
    }

    override fun onResize(oldMax: Int) {
        genomeIndex = genomeIndex.resize(-1)
        genomeSize = genomeSize.resize()
        stage = stage.resize()
        dividedTimes = dividedTimes.resize()
        mutatedTimes = mutatedTimes.resize()
        alreadyGrownUp = alreadyGrownUp.resize()
        divideCounterThisStage = divideCounterThisStage.resize()
        mutateCounterThisStage = mutateCounterThisStage.resize()
        divideAmountThisStage = divideAmountThisStage.resize()
        mutateAmountThisStage = mutateAmountThisStage.resize()
        justChangedStage = justChangedStage.resize()
    }
}
