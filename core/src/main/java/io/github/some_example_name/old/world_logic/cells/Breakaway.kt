package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.cells.base.activation

// Dies when it receives an impulse
// Will be made obsolete by apoptosis
class Breakaway: Cell(), Neural {
    companion object {
        fun specificToThisType(cm: CellManager, id: Int, threadId: Int) {
            val impulse = cm.neuronImpulseOutput[id]
            if (impulse > 0) {
                cm.killCell(id, threadId)
            }
        }
    }
}
