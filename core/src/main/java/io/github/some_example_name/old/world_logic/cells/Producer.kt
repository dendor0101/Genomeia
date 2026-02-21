package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.organisms.AddCell
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.getCellColor

class Producer: Cell(), Neural {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int, threadId: Int) {
            val impulse  = cm.neuronImpulseOutput[id]

            cm.neuronImpulseOutput[id] = 0f
            if(cm.energy[id] < cm.cellsSettings[cm.cellType[id] + 1].maxEnergy || impulse < 1) return

            if (cm.tickRestriction[id] <= 0) {
                val organism = cm.organismManager.organisms[cm.organismIndex[id]]
                val genome = cm.genomeManager.genomes[organism.genomeIndex]
                var counter = 0
                genome.genomeStageInstruction.forEach {
                    counter += it.cellActions.size
                }
                cm.tickRestriction[id] = (counter * cm.globalSettings.producerRestoreTimeTickCoefficient).toInt()
            } else {
                cm.tickRestriction[id] --
            }

            if (cm.tickRestriction[id] != 1) return

            val action = Action(
                id = 0,
                angle = 0f,
                cellType = 18,
                color = getCellColor(18)
            )

            cm.addCells[threadId].add(
                AddCell(
                    action = action,
                    parentX = cm.x[id],
                    parentY = cm.y[id],
                    parentAngle = cm.angle[id],
                    parentId = cm.cellGenomeId[id],
                    parentOrganismId = cm.organismIndex[id],
                    parentIndex = id
                )
            )
            cm.neuronImpulseOutput[id] = 1f
            cm.energy[id] -= cm.energy[id]
        }
    }
}
