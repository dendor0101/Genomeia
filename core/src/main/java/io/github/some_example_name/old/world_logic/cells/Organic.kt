package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.good_one.randomWallSeed
import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.good_one.utils.brownColors
import io.github.some_example_name.old.world_logic.CellManager

class Organic: Cell() {

    companion object {
        const val maxEnergy = 0f

        fun getColor() = brownColors.random(randomWallSeed)

        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] < cm.cellsSettings[cm.cellType[id] + 1].maxEnergy) {
                cm.energy[id] += 0.02f
            }
        }
    }
}
