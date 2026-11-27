package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.blueColors

class Compass(cellTypeId: Int) : Cell(
    key = "compass",
    defaultColor = blueColors[6],
    cellTypeId = cellTypeId,
    isDirected = true,
    isNeural = true,
    isNeuronTransportable = false
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        cellEntity.neuronImpulseOutput[cellIndex] = activation(cellIndex, angleSin[cellIndex])
        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
        cellEntity.energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }

}
