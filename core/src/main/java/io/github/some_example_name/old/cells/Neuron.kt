package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.pinkColors

class Neuron : Cell(
    defaultColor = pinkColors.first(),
    cellTypeId = 4,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) {
        cellEntity.energy[index] -= substrateSettings
            .cellsSettings[cellEntity.cellType[index] + 1]
            .energyActionCost
    }

}

