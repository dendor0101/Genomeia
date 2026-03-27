package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.redColors
import io.github.some_example_name.old.systems.genomics.genome.Action
import io.github.some_example_name.old.systems.simulation.SimulationSystem
import kotlin.collections.get
import kotlin.collections.set
import kotlin.compareTo
import kotlin.text.compareTo

class Producer: Cell(
    defaultColor = redColors[4],
    cellTypeId = 18,
    isDirected = true,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) = with(cellEntity) {
        val impulse = neuronImpulseOutput[index]

        neuronImpulseOutput[index] = 0f
        if(energy[index] < substrateSettings.cellsSettings[cellType[index].toInt()].maxEnergy || impulse < 1) return

        //TODO Make the impulse increase smoothly from 0 to 1, and have the producer divide at the moment when the impulse equals 1
//        if (cm.tickRestriction[index] <= 0) {
//            val organism = cm.organismManager.organisms[cm.organismIndex[index]]
//            val genome = cm.genomeManager.genomes[organism.genomeIndex]
//            var counter = 0
//            genome.genomeStageInstruction.forEach {
//                counter += it.cellActions.size
//            }
//            cm.tickRestriction[index] = (counter * cm.globalSettings.producerRestoreTimeTickCoefficient).toInt()
//        } else {
//            cm.tickRestriction[index] --
//        }
//
//        if (cm.tickRestriction[index] != 1) return

        //TODO add command to add zygote cell
//        val action = Action(
//            id = 0,
//            angle = 0f,
//            cellType = 18,
//            color = getCellColor(18)
//        )
//
//        cm.addCells[threadId].add(
//            AddCell(
//                action = action,
//                parentX = cm.x[index],
//                parentY = cm.y[index],
//                parentAngle = cm.angle[index],
//                parentId = cm.cellGenomeId[index],
//                parentOrganismId = cm.organismIndex[index],
//                parentIndex = index
//            )
//        )
        neuronImpulseOutput[index] = 1f
        energy[index] -= energy[index]
    }
}
