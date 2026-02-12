package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.world_logic.CellManager

class SkinCell: Cell(), Neural {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            val impulse = cm.neuronImpulseOutput[id]
            val friction = if (impulse > 1) 1f else  if (impulse < 0) 0f else impulse

            cm.dragCoefficient[id] = friction * 0.93f
            cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].energyActionCost
        }
    }
}
