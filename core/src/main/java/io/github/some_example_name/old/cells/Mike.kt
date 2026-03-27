package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.pinkColors

class Mike: Cell(
    defaultColor = pinkColors[0],
    cellTypeId = 24
) {

    override fun doOnTick(index: Int, threadId: Int) = with (cellEntity) {
        if (energy[index] < substrateSettings.cellsSettings[cellType[index].toInt()].maxEnergy) {
            energy[index] += substrateSettings.data.amountOfSolarEnergy
        }
    }
}
