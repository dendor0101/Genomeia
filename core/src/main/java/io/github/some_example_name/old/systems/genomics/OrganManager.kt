package io.github.some_example_name.old.systems.genomics

import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager

class OrganManager(
    private val organEntity: OrganEntity,
    private val genomeManager: GenomeManager,
    private val cellEntity: CellEntity,
) {

    /*
    * Переход на следющую стадию генома в каждом организме
    * Transition to the next stage of the genome in each organism
    * */
    fun performOrgansNextStage() {
        with(organEntity) {
            for (index in 0..organEntity.lastId) {
                if (alreadyGrownUp[index]) continue
                justChangedStage[index] = false
                if (dividedTimes[index] == divideAmountThisStage[index] - divideCounterThisStage[index]
                    && mutatedTimes[index] == mutateAmountThisStage[index] - mutateCounterThisStage[index]
                ) {
                    if (genomeSize[index] > stage[index] + 1) {
                        stage[index]++
                        val currentGenome = genomeManager.genomes[genomeIndex[index]]
                        justChangedStage[index] = true
                        divideCounterThisStage[index] = 0
                        mutateCounterThisStage[index] = 0
                        divideAmountThisStage[index] = currentGenome.dividedTimes[stage[index]]
                        mutateAmountThisStage[index] = currentGenome.mutatedTimes[stage[index]]
                        dividedTimes[index] = currentGenome.dividedTimes[stage[index]]
                        mutatedTimes[index] = currentGenome.mutatedTimes[stage[index]]
                    } else {
//                        println("organism grown $index")
                        //TODO Delete grown organs
                        alreadyGrownUp[index] = true
                    }
                }
            }
        }
    }

    fun cellDeleted(cellIndex: Int) {
        val organIndex = cellEntity.organIndex[cellIndex]
        if (organIndex != -1) {
            if (!cellEntity.isDividedInThisStage[cellIndex]) {
                organEntity.divideCounterThisStage[organIndex]--
            }

            if (!cellEntity.isMutateInThisStage[cellIndex]) {
                organEntity.mutateCounterThisStage[organIndex]--
            }
        }
    }

    fun clear() {
        organEntity.clear()
    }
}
