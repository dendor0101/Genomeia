package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.CellManager

class Sucker : Cell() {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.tickRestriction[id] == 3) {
                cm.tickRestriction[id] = 0
                val isEaten = cm.subManager.suckIt(cm.x[id], cm.y[id])
                if (isEaten)
                    cm.energy[id] += cm.globalSettings.amountOfFoodEnergy
                if (cm.energy[id] > cm.cellsSettings[cm.cellType[id] + 1].maxEnergy) cm.energy[id] = cm.cellsSettings[cm.cellType[id] + 1].maxEnergy
            } else {
                cm.tickRestriction[id] += 1
            }
        }
    }
}
