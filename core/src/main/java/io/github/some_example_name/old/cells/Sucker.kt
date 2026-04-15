package io.github.some_example_name.old.cells

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.orangeColors

class Sucker(cellTypeId: Int) : Cell(
    defaultColor = orangeColors.first(),
    cellTypeId = cellTypeId,
    effectOnContact = true,
    isCollidable = false
) {

    override fun onContact(cellIndex: Int, particleIndexCollided: Int, distance: Float, threadId: Int) {

        if (!particleEntity.isCell[particleIndexCollided]) {
            val cellRadius = cellEntity.getRadius(cellIndex)
            val subRadius = particleEntity.radius[particleIndexCollided]

            if (distance < cellRadius) {
                val collidedSubIndex = particleEntity.holderEntityIndex[particleIndexCollided]

                worldCommandsManager.worldCommandBuffer[threadId].push(
                    type = WorldCommandType.DELETE_SUBSTANCE,
                    ints = intArrayOf(collidedSubIndex, substancesEntity.getGeneration(collidedSubIndex))
                )

                cellEntity.energy[cellIndex] += subRadius * subRadius * substrateSettings.data.amountOfFoodEnergy * 4
            } else {
                val cellX = cellEntity.getX(cellIndex)
                val cellY = cellEntity.getY(cellIndex)
                val subX = particleEntity.x[particleIndexCollided]
                val subY = particleEntity.y[particleIndexCollided]

                particleEntity.vx[particleIndexCollided] += (cellX - subX) * 0.025f
                particleEntity.vy[particleIndexCollided] += (cellY - subY) * 0.025f
            }
        }
    }

}
