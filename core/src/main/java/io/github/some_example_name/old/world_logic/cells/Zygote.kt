package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.good_one.utils.primitive_hash_map.UnorderedIntPairMap
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.organisms.Organism

var currentGenomeIndex = 0

//Экспериментальная клетка
// Experimental cell
class Zygote {
    companion object {
        fun specificWhenSpawned(cm: CellManager, id: Int, genomeIndex: Int, threadId: Int) {
            if (!cm.isGenomeEditor) {
                cm.cellGenomeId[id] = 0
            }
            cm.organismIndex[id] = cm.organismManager.organisms.size
            val currentGenome = cm.genomeManager.genomes[genomeIndex]

            val newOrganism = Organism(
                genomeIndex = genomeIndex,
                genomeSize = currentGenome.genomeStageInstruction.size,
                stage = 0,
                dividedTimes = currentGenome.dividedTimes[0],
                mutatedTimes = currentGenome.mutatedTimes[0],
                justChangedStage = true,
                alreadyGrownUp = false,
                divideAmountThisStage = currentGenome.dividedTimes[0],
                mutateAmountThisStage = currentGenome.mutatedTimes[0]
            )
            if (threadId != -1) {
                cm.addOrganisms[threadId].add(newOrganism)
            } else {
                cm.organismManager.organisms.add(newOrganism)
            }
        }

        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] < cm.cellsSettings[cm.cellType[id] + 1].maxEnergy) {
                cm.energy[id] += cm.globalSettings.amountOfSolarEnergy / 3f
            }
        }
    }
}
