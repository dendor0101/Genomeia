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
        TODO("Not yet implemented")
    }

    override fun onPaste() {
        TODO("Not yet implemented")
    }

    override fun onClear(bound: Int) {
        genomeIndex.fill(-1, 0, bound)
        genomeSize.fill(0, 0, bound)
        stage.fill(0, 0, bound)
        dividedTimes.fill(0, 0, bound)
        mutatedTimes.fill(0, 0, bound)
        alreadyGrownUp.clear()
        divideCounterThisStage.fill(0, 0, bound)
        mutateCounterThisStage.fill(0, 0, bound)
        divideAmountThisStage.fill(0, 0, bound)
        mutateAmountThisStage.fill(0, 0, bound)
        justChangedStage.clear()
    }

    override fun onResize(oldMax: Int) {
        run {
            val old = genomeIndex
            genomeIndex = IntArray(maxAmount)
            System.arraycopy(old, 0, genomeIndex, 0, oldMax)
        }
        run {
            val old = genomeSize
            genomeSize = IntArray(maxAmount)
            System.arraycopy(old, 0, genomeSize, 0, oldMax)
        }
        run {
            val old = stage
            stage = IntArray(maxAmount)
            System.arraycopy(old, 0, stage, 0, oldMax)
        }
        run {
            val old = dividedTimes
            dividedTimes = IntArray(maxAmount)
            System.arraycopy(old, 0, dividedTimes, 0, oldMax)
        }
        run {
            val old = mutatedTimes
            mutatedTimes = IntArray(maxAmount)
            System.arraycopy(old, 0, mutatedTimes, 0, oldMax)
        }
        run {
            val old = alreadyGrownUp
            alreadyGrownUp = BitSet(maxAmount)
            alreadyGrownUp.or(old)
        }
        run {
            val old = divideCounterThisStage
            divideCounterThisStage = IntArray(maxAmount)
            System.arraycopy(old, 0, divideCounterThisStage, 0, oldMax)
        }
        run {
            val old = mutateCounterThisStage
            mutateCounterThisStage = IntArray(maxAmount)
            System.arraycopy(old, 0, mutateCounterThisStage, 0, oldMax)
        }
        run {
            val old = divideAmountThisStage
            divideAmountThisStage = IntArray(maxAmount)
            System.arraycopy(old, 0, divideAmountThisStage, 0, oldMax)
        }
        run {
            val old = mutateAmountThisStage
            mutateAmountThisStage = IntArray(maxAmount)
            System.arraycopy(old, 0, mutateAmountThisStage, 0, oldMax)
        }
        run {
            val old = justChangedStage
            justChangedStage = BitSet(maxAmount)
            justChangedStage.or(old)
        }
    }
}
