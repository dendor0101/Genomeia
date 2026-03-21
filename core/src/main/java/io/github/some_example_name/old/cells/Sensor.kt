package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.purpleColors

class Sensor: Cell(
    defaultColor = purpleColors.first(),
    cellTypeId = 6,
    isNeuronTransportable = false,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) = with(cellEntity) {
        energy[index] -= substrateSettings.cellsSettings[cellType[index].toInt()].energyActionCost
        if (simEntity.tickCounter % 48 == 0) {
            //TODO сделаю через ферамоны
            var senseValue = 0f//subManager.sensor(getX(index), getY(index))
            if (senseValue.isNaN()) throw Exception("TODO потом убрать")
            if (senseValue > 1f) senseValue = 1f
            neuronImpulseOutput[index] = activation(index, senseValue)
        }
    }

}
