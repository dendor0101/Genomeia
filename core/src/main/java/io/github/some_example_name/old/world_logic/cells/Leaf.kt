package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.good_one.utils.leafColors
import io.github.some_example_name.old.world_logic.CellManager

class Leaf: Cell() {
    companion object {
        const val maxEnergy = 5f
        fun getColor() = leafColors.first()
        //TODO клетка leaf должна жрать много кислорода // The leaf cell must consume a lot of oxygen.
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] < cm.cellsSettings[cm.cellType[id] + 1].maxEnergy) {
                cm.energy[id] += cm.globalSettings.amountOfSolarEnergy
            }
        }
    }
}
