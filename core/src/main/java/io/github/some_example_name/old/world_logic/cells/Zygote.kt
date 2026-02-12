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
                cm.id[id] = 0
            }
            cm.organismId[id] = cm.organismManager.organisms.size
            //TODO временно пока не понятно что за баг // It's not clear yet what the bug is.
            val safeGenomeIndex = if (cm.genomeManager.genomes.size == 1) 0 else genomeIndex
            val currentGenome = cm.genomeManager.genomes[safeGenomeIndex]

            var linkCounter = 0
            currentGenome.genomeStageInstruction.forEach {
                it.cellActions.forEach {  action ->
                    action.value.mutate?.physicalLink?.forEach { (_, value) -> if (value != null) linkCounter ++ }
                    action.value.divide?.physicalLink?.forEach { (_, value) -> if (value != null) linkCounter ++ }
                }
            }

            val newOrganism = Organism(
                genomeIndex = safeGenomeIndex,
                genomeSize = currentGenome.genomeStageInstruction.size,
                stage = 0,
                dividedTimes = currentGenome.dividedTimes.copyOf(),
                mutatedTimes = currentGenome.mutatedTimes.copyOf(),
                linkIdMap = UnorderedIntPairMap(linkCounter)
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
