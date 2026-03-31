package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.leafColors

class Leaf(cellTypeId: Int): Cell(
    defaultColor = leafColors.first(),
    cellTypeId = cellTypeId
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with (cellEntity) {
        if (energy[cellIndex] < substrateSettings.cellsSettings[cellType[cellIndex].toInt()].maxEnergy) {
            energy[cellIndex] += substrateSettings.data.amountOfSolarEnergy
        }
    }

}
