package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.redColors
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem.Companion.MAX_LINK_AMOUNT

class Muscle(cellTypeId: Int) : Cell(
    defaultColor = redColors[3],
    cellTypeId = cellTypeId,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val impulse = neuronImpulseOutput[cellIndex]

        for (i in cellIndex * MAX_LINK_AMOUNT..<cellIndex * MAX_LINK_AMOUNT + linksAmount[cellIndex]) {
            val linkId = links[i]
            linkEntity.degreeOfShortening[linkId] = impulse.coerceIn(-1f, 1f) * 0.5f + 1f
        }

        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }

}
