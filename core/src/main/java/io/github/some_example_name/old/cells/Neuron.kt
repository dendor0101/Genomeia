package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.pinkColors

class Neuron(cellTypeId: Int) : Cell(
    defaultColor = pinkColors.first(),
    cellTypeId = cellTypeId,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) {
        cellEntity.energy[cellIndex] -= substrateSettings
            .cellsSettings[cellEntity.cellType[cellIndex].toInt()]
            .energyActionCost
    }

}

