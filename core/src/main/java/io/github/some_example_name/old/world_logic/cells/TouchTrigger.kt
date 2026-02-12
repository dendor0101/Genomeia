package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.good_one.utils.invSqrt
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT

class TouchTrigger(): Neural {
    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] <= 0) return

            var sumStretchingDistance = 0f

            for (i in 0..<cm.linksAmount[id]) {
                val linkId = cm.links[id * MAX_LINK_AMOUNT + i]
                val c1 = cm.links1[linkId]
                val c2 = cm.links2[linkId]

                val dx = cm.x[c1] - cm.x[c2]
                val dy = cm.y[c1] - cm.y[c2]
                val sqrt = dx * dx + dy * dy
                if (sqrt <= 0) return
                val dist = 1.0f / invSqrt(sqrt)

                val stretchingDistance = cm.linksNaturalLength[linkId] * cm.degreeOfShortening[linkId] - dist
                sumStretchingDistance += stretchingDistance
            }

            val impulse = (sumStretchingDistance / cm.linksAmount[id]) / 80

            cm.neuronImpulseOutput[id] = activation(cm, id, impulse)

            cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].energyActionCost
        }
    }
}
