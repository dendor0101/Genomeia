package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.world_logic.CellManager

class Sensor: Cell(), Neural {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].energyActionCost
            if (cm.tickRestriction[id] == 48) {
                cm.tickRestriction[id] = 0
                var senseValue = cm.subManager.sensor(cm.x[id], cm.y[id])
                if (senseValue.isNaN()) throw Exception("TODO потом убрать")
                if (senseValue > 1f) senseValue = 1f
                cm.neuronImpulseOutput[id] = activation(cm, id, senseValue)
            } else {
                cm.tickRestriction[id] += 1
            }
        }
    }
}
