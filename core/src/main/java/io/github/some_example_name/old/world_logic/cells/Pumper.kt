package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.cells.base.Cell

class Pumper: Cell() {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int, collidedId: Int) {
            val rateOfEnergy = cm.globalSettings.rateOfEnergyTransferForPumper
            if (cm.energy[id] < cm.cellsSettings[cm.cellType[id] + 1].maxEnergy && cm.energy[collidedId] >= rateOfEnergy) {
                cm.energy[id] += rateOfEnergy
                cm.energy[collidedId] -= rateOfEnergy
            }
        }
    }
}
