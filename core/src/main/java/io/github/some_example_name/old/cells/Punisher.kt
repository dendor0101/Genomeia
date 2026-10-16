package io.github.some_example_name.old.cells

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.redColors

class Punisher(cellTypeId: Int) : Cell(
    key = "punisher",
    defaultColor = redColors[0],
    cellTypeId = cellTypeId,
    textureName = "punisher.png",
    effectOnContact = true
) {

    override fun onContact(cellIndex: Int, particleIndexCollided: Int, distance: Float, threadId: Int) = with(cellEntity) {
        if (particleEntity.isCell[particleIndexCollided]) {
            val collidedCellIndex = particleEntity.holderEntityIndex[particleIndexCollided]
            val cell = cellList[cellType[collidedCellIndex].toInt()]
            if (organIndex[cellIndex] != organIndex[collidedCellIndex] && cell !is Bone && cell !is Punisher) {
                val maxEnergy = substrateSettings.cellsSettings[cellType[cellIndex].toInt()].maxEnergy
                if (energy[cellIndex] >= maxEnergy) {
                    energy[cellIndex] -= maxEnergy

                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_CELL,
                        ints = intArrayOf(collidedCellIndex, getGeneration(collidedCellIndex))
                    )
                }
            }
        }
    }
}
