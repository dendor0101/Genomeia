package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.redColors

class Pumper: Cell(
    defaultColor = redColors[2],
    cellTypeId = 11
) {

    override fun onContact(index: Int, indexCollided: Int, threadId: Int) = with(cellEntity) {
        val rateOfEnergy = substrateSettings.data.rateOfEnergyTransferForPumper
        if (energy[index] < substrateSettings.cellsSettings[cellType[index].toInt()].maxEnergy && energy[indexCollided] >= rateOfEnergy) {
            energy[index] += rateOfEnergy
            energy[indexCollided] -= rateOfEnergy
        }
    }
}
