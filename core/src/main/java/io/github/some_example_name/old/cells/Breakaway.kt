package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.pinkColors

// Dies when it receives an impulse
// Will be made obsolete by apoptosis
class Breakaway: Cell(
    defaultColor = pinkColors[1],
    cellTypeId = 19,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) {
        val impulse = cellEntity.neuronImpulseOutput[index]
        if (impulse > 0) {
            //killCell(index, threadId)TODO add command to kill cell
        }
    }
}
