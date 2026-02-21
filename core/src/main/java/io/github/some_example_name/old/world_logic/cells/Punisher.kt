package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.cells.base.Cell


class Punisher: Cell() {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int, collidedId: Int, threadId: Int) {
            if (cm.organismIndex[id] != cm.organismIndex[collidedId] &&
                cm.cellType[collidedId] != -1 &&
                cm.cellType[collidedId] != 2 &&
                cm.cellType[collidedId] != 24) {
                if (cm.energy[id] >= cm.cellsSettings[cm.cellType[id] + 1].maxEnergy) {
                    cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].maxEnergy
                    cm.killCell(collidedId, threadId)
                }
            }

            if (cm.energy[id] < cm.cellsSettings[cm.cellType[id] + 1].maxEnergy) {
                cm.energy[id] += cm.globalSettings.amountOfSolarEnergy
            }
        }
    }
}
