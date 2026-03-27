package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.skyBlueColors

class Eye: Cell(
    defaultColor = skyBlueColors[2],
    cellTypeId = 13,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) = with(cellEntity) {
        if (simEntity.tickCounter % 4 == 0) {

            neuronImpulseOutput[index] = activation(index, 0f)

            energy[index] -= substrateSettings.cellsSettings[cellType[index].toInt()].energyActionCost
        }
    }
}
