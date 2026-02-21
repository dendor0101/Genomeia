package io.github.some_example_name.old.world_logic.cells


import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.cells.base.Directed
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import kotlin.math.*

class Compass : Cell(), Neural, Directed {
    companion object {
        //TODO переделать на выдачу сигнала той клдетке, связка которой ближе к сереву
        // TODO: redesign to send a signal to the cell whose connection is closer to the center
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.tickRestriction[id] == 7) {
                cm.tickRestriction[id] = 0
                if (cm.energy[id] > 0) {
                    val angleRad = cm.angle[id]
                    cm.neuronImpulseOutput[id] = activation(cm, id, sin(angleRad))

                    cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].energyActionCost
                }
            } else {
                cm.tickRestriction[id] += 1
            }
        }
    }
}
