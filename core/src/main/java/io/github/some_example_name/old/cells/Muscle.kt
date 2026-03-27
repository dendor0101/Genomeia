package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.redColors

class Muscle : Cell(
    defaultColor = redColors[3],
    cellTypeId = 5,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) = with(cellEntity) {
//        val impulse = neuronImpulseOutput[index]
//
//        for (i in index * MAX_LINK_AMOUNT..<index * MAX_LINK_AMOUNT + linksAmount[index]) {
//            val linkId = links[i]
//            linkEntity.degreeOfShortening[linkId] = impulse.coerceIn(-1f, 1f) * 0.5f + 1f
//        }
//
//        energy[index] -= substrateSettings.cellsSettings[cellType[index]].energyActionCost//0.0005f
    }

}
