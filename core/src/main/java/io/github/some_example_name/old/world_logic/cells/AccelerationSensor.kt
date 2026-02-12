package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import kotlin.math.sqrt

class AccelerationSensor: Cell(), Neural {

    companion object {
        //TODO скорее всего избавлюсь от этой клетки // I'll most likely get rid of this cell
        fun specificToThisType(cm: CellManager, id: Int) {
            val ax = cm.ax[id]
            val ay = cm.ay[id]
            val acceleration = sqrt(ax * ax + ay * ay)
            cm.neuronImpulseOutput[id] = activation(cm, id, acceleration)
            cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].energyActionCost
        }
    }
}
