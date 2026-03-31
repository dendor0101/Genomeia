package io.github.some_example_name.old.cells

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.redColors

class Punisher(cellTypeId: Int) : Cell(
    defaultColor = redColors[0],
    cellTypeId = cellTypeId,
    effectOnContact = true
) {

    override fun onContact(cellIndex: Int, particleIndexCollided: Int, distance: Float, threadId: Int) = with(cellEntity) {
        if (particleEntity.isCell[particleIndexCollided]) {
            val collidedCellIndex = particleEntity.holderEntityIndex[particleIndexCollided]
            if (organIndex[cellIndex] != organIndex[collidedCellIndex] &&
                cellType[collidedCellIndex].toInt() != -1 &&
                cellType[collidedCellIndex].toInt() != 2 &&
                cellType[collidedCellIndex].toInt() != 24
            ) {
                val maxEnergy = substrateSettings.cellsSettings[cellType[cellIndex].toInt()].maxEnergy
                if (energy[cellIndex] >= maxEnergy) {
                    energy[cellIndex] -= maxEnergy

                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_CELL,
                        ints = intArrayOf(collidedCellIndex)
                    )
                }
            }
        }
    }
}
