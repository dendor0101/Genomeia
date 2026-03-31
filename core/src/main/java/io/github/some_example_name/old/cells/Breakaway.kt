package io.github.some_example_name.old.cells

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.pinkColors

// Dies when it receives an impulse
// Will be made obsolete by apoptosis
class Breakaway(cellTypeId: Int): Cell(
    defaultColor = pinkColors[1],
    cellTypeId = cellTypeId,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) {
        val impulse = cellEntity.neuronImpulseOutput[cellIndex]
        if (impulse > 0) {
            worldCommandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.DELETE_CELL,
                ints = intArrayOf(cellIndex)
            )
        }
    }
}
