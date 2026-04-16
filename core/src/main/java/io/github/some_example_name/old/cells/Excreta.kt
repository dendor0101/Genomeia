package io.github.some_example_name.old.cells

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.brownColors

class Excreta(cellTypeId: Int): Cell(
    defaultColor = brownColors.first(),
    cellTypeId = cellTypeId,
    isDirected = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        if(energy[cellIndex] < substrateSettings.data.amountOfFoodEnergy) return

        val directionX = angleCos[cellIndex] * 0.05f
        val directionY = angleSin[cellIndex] * 0.05f

        val x = getX(cellIndex) + directionX
        val y = getY(cellIndex) + directionY
        val radius = 0.1f
        val color = getColor(cellIndex)//Color.WHITE.toIntBits()
        val subType = 0

        worldCommandsManager.worldCommandBuffer[threadId].push(
            type = WorldCommandType.ADD_SUBSTANCE,
            ints = intArrayOf(color, subType),
            floats = floatArrayOf(x, y, radius)
        )

        energy[cellIndex] -= substrateSettings.data.amountOfFoodEnergy
    }

}
