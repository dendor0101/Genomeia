package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.world_logic.CellManager

class PheromoneSensor: Cell(), Neural {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {

            val gridId = cm.gridId[id]

            cm.neuronImpulseOutput[id] = activation(cm, id,
                    cm.colorR[id] * cm.pheromoneR[gridId] +
                    cm.colorG[id] * cm.pheromoneG[gridId] +
                    cm.colorB[id] * cm.pheromoneB[gridId]
                )

            cm.energy[id] -= 0.0001f
        }
    }
}
