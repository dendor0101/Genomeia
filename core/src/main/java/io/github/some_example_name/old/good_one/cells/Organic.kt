package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.brownColors
import io.github.some_example_name.attempts.game.physics.leafColors
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.logic.CellManager

class Organic: Cell() {

    companion object {
        const val maxEnergy = 0f

        fun getColor() = brownColors.random()

        fun specificToThisType(cellManager: CellManager, id: Int) {
            if (cellManager.energy[id] < cellManager.maxEnergy[id]) {
                cellManager.energy[id] += 0.02f
            }
            if (cellManager.frictionLevel[id] <= 0f) {
                cellManager.frictionLevel[id] = 0f
                return
            }
            cellManager.frictionLevel[id] -= 0.09f
        }
    }
}
