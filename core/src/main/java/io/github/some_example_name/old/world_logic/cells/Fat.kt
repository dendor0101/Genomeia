package io.github.some_example_name.old.world_logic.cells


import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.world_logic.cells.base.Cell

class Fat: Cell() {

//    companion object {
//        fun specificToThisType(cm: CellManager, id: Int) {
//            // Link length proportional to energy
//            for (i in id * MAX_LINK_AMOUNT..<id * MAX_LINK_AMOUNT + cm.linksAmount[id]) {
//                val linkId = cm.links[i]
//                cm.degreeOfShortening[linkId] = (cm.energy[id]/cm.maxEnergy[id]).coerceIn(0f, 1f) + 0.5f
//            }
//        }
//    }
}
