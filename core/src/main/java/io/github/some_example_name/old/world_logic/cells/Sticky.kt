package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.world_logic.process_soa.addStickyLink

class Sticky: Cell(), Neural {

    companion object {
        fun specificToThisType(
            cm: CellManager,
            id: Int,
            collidedId: Int,
            threadId: Int,
            distance: Float
        ) {
            if (cm.cellType[id] == 11 && activation(cm, id, cm.neuronImpulseInput[id]) < 1f) {
                cm.addStickyLink(id, collidedId, distance, threadId)
                return
            }
        }
    }
}
