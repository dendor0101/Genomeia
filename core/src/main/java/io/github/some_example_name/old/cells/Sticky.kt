package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.pinkColors
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem.Companion.MAX_LINK_AMOUNT

class Sticky(cellTypeId: Int) : Cell(
    defaultColor = pinkColors[3],
    cellTypeId = cellTypeId,
    isNeural = true,
    effectOnContact = true
) {

    //TODO при -1 сброс всех StickyLink, при 0 ничего нового не добавляется и не удаляется, а при 1 создаются новые линки

    override fun onContact(
        cellIndex: Int,
        particleIndexCollided: Int,
        distance: Float,
        threadId: Int
    ) = with(cellEntity) {
        if (activation(cellIndex, neuronImpulseInput[cellIndex]) < 1f) {

            val cellIndex: Int = cellIndex
            val otherCellIndex: Int = particleEntity.holderEntityIndex[particleIndexCollided]
            val linksLength: Float = distance
            val degreeOfShortening: Float = 1f
            val isStickyLink = true
            val isNeuronLink = false
            val isLink1NeuralDirected = false

            worldCommandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.ADD_LINK,
                booleans = booleanArrayOf(
                    isStickyLink,
                    isNeuronLink,
                    isLink1NeuralDirected
                ),
                floats = floatArrayOf(linksLength, degreeOfShortening),
                ints = intArrayOf(cellIndex, otherCellIndex)
            )
            return
        }
    }

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        if (neuronImpulseOutput[cellIndex] >= 1) {
            val base = cellIndex * MAX_LINK_AMOUNT
            val amount = linksAmount[cellIndex]
            if (amount == 0) return

            for (i in 0 until amount) {
                val idx = base + i
                val linkIndex = links[idx]
                if (linkEntity.isStickyLink[linkIndex]) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_LINK,
                        ints = intArrayOf(linkIndex)
                    )
                }
            }
        }
    }

}
