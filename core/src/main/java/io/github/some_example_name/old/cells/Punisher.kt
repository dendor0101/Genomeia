package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.redColors

class Punisher : Cell(
    defaultColor = redColors[0],
    cellTypeId = 23
) {

    override fun onContact(index: Int, indexCollided: Int, threadId: Int) = with(cellEntity) {
        if (organIndex[index] != organIndex[indexCollided] &&
            cellType[indexCollided].toInt() != -1 &&
            cellType[indexCollided].toInt() != 2 &&
            cellType[indexCollided].toInt() != 24) {
            val maxEnergy = substrateSettings.cellsSettings[cellType[index].toInt()].maxEnergy
            if (energy[index] >= maxEnergy) {
                energy[index] -= maxEnergy
                //TODO command to delete cell
                //killCell(indexCollided, threadId)
            }
        }
    }
}
