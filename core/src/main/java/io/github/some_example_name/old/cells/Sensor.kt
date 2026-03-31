package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.purpleColors

class Sensor(cellTypeId: Int): Cell(
    defaultColor = purpleColors.first(),
    cellTypeId = cellTypeId,
    isNeuronTransportable = false,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
        if (simulationData.tickCounter % 48 == 0) {
            //TODO сделаю через ферамоны
            var senseValue = 0f//subManager.sensor(getX(index), getY(index))
            if (senseValue.isNaN()) throw Exception("TODO потом убрать")
            if (senseValue > 1f) senseValue = 1f
            neuronImpulseOutput[cellIndex] = activation(cellIndex, senseValue)
        }
    }

}
