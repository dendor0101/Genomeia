package io.github.some_example_name.old.systems.genomics

import io.github.some_example_name.old.core.DEBUG_CHECKS
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
                        if (DEBUG_CHECKS) {
                            println("organism grown $index")
                        }
                        //TODO Delete grown organs
                        alreadyGrownUp[index] = true
                    }
                }
            }
        }
    }

    /**
     * Погибшая клетка больше не разделится и не мутирует — стадия перестаёт её ждать. Ждёт стадия только клетки,
     * которым на смене стадии выдано действие (CellSystem.genomicTransformations: счётчик +1 за каждое). Клетка,
     * рождённая посреди стадии, действия не получает и в счёт не входит, хотя флаги «ещё не делилась» и «ещё не
     * мутировала» у неё false (CellEntity.addCell). Раньше её гибель тоже вычиталась: счётчик уходил ниже числа
     * ждущих клеток (мутаций — в −1), и стадия не кончалась никогда. Так встал Motor Worm на стадии 19, когда
     * физика задавила его новорождённые клетки о террейн (повтор bugReplayCheck 21.11, reference.md, находка 34).
     */
    fun cellDeleted(cellIndex: Int) {
        val organIndex = cellEntity.organIndex[cellIndex]
        if (organIndex != -1) {
            val action = cellEntity.cellActions[cellIndex]
            if (action?.divide != null && !cellEntity.isDividedInThisStage[cellIndex]) {
                organEntity.divideCounterThisStage[organIndex]--
            }

            if (action?.mutate != null && !cellEntity.isMutateInThisStage[cellIndex]) {
                organEntity.mutateCounterThisStage[organIndex]--
            }
        }
    }
}
