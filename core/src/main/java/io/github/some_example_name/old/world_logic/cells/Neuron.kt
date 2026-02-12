package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT

class Neuron : Cell(), Neural {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] <= 0) return

            // All of this is handled in cellManager now
//            cm.neuronImpulseInput[id] = if (cm.isSum[id]) 0f else 1f
//
//            //TODO плохо для производительности, не надо проверять все прилегающие
//            // TODO: bad for performance, no need to check all adjacent ones
//            for (i in 0..<cm.linksAmount[id]) {
//                val linkId = cm.links[id * MAX_LINK_AMOUNT + i]
//                if (!cm.isNeuronLink[linkId]) continue
//                val c1 = cm.links1[linkId]
//                val c2 = cm.links2[linkId]
//                if (cm.directedNeuronLink[linkId] == cm.id[id]) {
//                    val impulse = if (c1 != id) {
//                        cm.neuronImpulseInput[c1]
//                    } else if (c2 != id) {
//                        cm.neuronImpulseInput[c2]
//                    } else continue
//
//                    if (cm.isSum[id]) {
//                        cm.neuronImpulseInput[id] += impulse
//                    } else {
//                        cm.neuronImpulseInput[id] *= impulse
//                    }
//                }
//            }
//
//            cm.neuronImpulseInput[id] = activation(cm, id, cm.neuronImpulseInput[id])

            cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].energyActionCost
        }
    }
}

val formulaType = arrayOf(
    "y = ax + b",
    "y = c * sin(ax + b)",
    "y = c * cos(ax + b)",
    "y = 1 / (1 + e^(-(ax + b))) + c ",
    "y = b, x <= a; y = c, x > a",
    "y = b, x < a; y = c, x >= a",
    "y = t",
    "y = impulse(a), x>=1",
    "y = x, x is in (a, b) else y = c",
    "y = x^(a)",
    "y = remember(x), 0, 1"
)
