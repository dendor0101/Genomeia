package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT

class Muscle : Cell(), Neural {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] > 0) {

                val impulse = cm.neuronImpulseOutput[id]

                for (i in id * MAX_LINK_AMOUNT..<id * MAX_LINK_AMOUNT + cm.linksAmount[id]) {
                    val linkId = cm.links[i]
                    cm.degreeOfShortening[linkId] = impulse.coerceIn(-1f, 1f)*0.5f + 1f
                }

                cm.energy[id] -= 0.0005f
            }
        }
    }
}
