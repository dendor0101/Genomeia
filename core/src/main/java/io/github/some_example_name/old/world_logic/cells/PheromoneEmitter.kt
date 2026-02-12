package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.world_logic.CellManager

class PheromoneEmitter: Cell(), Neural {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            val impulse = cm.neuronImpulseOutput[id]
            val gridId = cm.gridId[id]

            if (gridId < 0 || gridId >= cm.gridManager.GRID_SIZE) return

            cm.pheromoneR[gridId] += cm.colorR[id]*impulse
            cm.pheromoneG[gridId] += cm.colorG[id]*impulse
            cm.pheromoneB[gridId] += cm.colorB[id]*impulse

            cm.energy[id] -= impulse * 0.01f
        }
    }
}
