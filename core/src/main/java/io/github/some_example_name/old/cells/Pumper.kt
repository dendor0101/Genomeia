package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.redColors

class Pumper(cellTypeId: Int): Cell(
    defaultColor = redColors[2],
    cellTypeId = cellTypeId,
    effectOnContact = true
) {

    override fun onContact(cellIndex: Int, particleIndexCollided: Int, distance: Float, threadId: Int) = with(cellEntity) {
        if (particleEntity.isCell[particleIndexCollided]) {
            val collidedCellIndex = particleEntity.holderEntityIndex[particleIndexCollided]
            val rateOfEnergy = substrateSettings.data.rateOfEnergyTransferForPumper
            if (energy[cellIndex] < substrateSettings.cellsSettings[cellType[cellIndex].toInt()].maxEnergy && energy[collidedCellIndex] >= rateOfEnergy) {
                energy[cellIndex] += rateOfEnergy
                energy[collidedCellIndex] -= rateOfEnergy
            }
        }
    }
}
