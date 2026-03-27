package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.pinkColors
import io.github.some_example_name.old.systems.simulation.SimulationSystem
import kotlin.collections.get

class Sticky: Cell(
    defaultColor = pinkColors[3],
    cellTypeId = 10,
    isNeural = true
) {

    override fun onContact(index: Int, indexCollided: Int, threadId: Int) = with(cellEntity) {
        if (cellType[index].toInt() == 11 && activation(index, neuronImpulseInput[index]) < 1f) {
//            addStickyLink(id, collidedId, distance, threadId)
//            addLinks[threadId].add(
//                AddLink(
//                    cellIndex = cellId,
//                    otherCellIndex = otherCellId,
//                    linksLength = distance,
//                    degreeOfShortening = 1f,
//                    isStickyLink = true,
//                    isNeuronLink = false,
//                    isLink1NeuralDirected = false
//                )
//            )
//            commandsManager.addLinks
            return
        }
    }

    override fun doOnTick(index: Int, threadId: Int) {
//        TODO
//    if (isStickyLink[linkId] && !isNeuronLink[linkId]) {
//        if (cellType[linkCell1] == 11 && activation(
//                this,
//                linkCell2,
//                neuronImpulseOutput[linkCell2]
//            ) >= 1
//        ) {
//            addToDeleteList(threadId, linkId)
//        } else if (cellType[linkCell2] == 11 && activation(
//                this,
//                linkCell2,
//                neuronImpulseOutput[linkCell2]
//            ) >= 1
//        ) {
//            addToDeleteList(threadId, linkId)
//        }
//    }
    }

}
