package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.blueColors
import kotlin.math.*

class Compass(cellTypeId: Int) : Cell(
    defaultColor = blueColors[6],
    cellTypeId = cellTypeId,
    isDirected = true,
    isNeural = true,
    isNeuronTransportable = false
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        // TODO: redesign to send a signal to the cell whose connection is closer to the center
        if (simulationData.tickCounter % 7 == 0) {
            val angleRad = angle[cellIndex]
            cellEntity.neuronImpulseOutput[cellIndex] = activation(cellIndex, sin(angleRad))

            energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
        }
        cellEntity.energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }

}
