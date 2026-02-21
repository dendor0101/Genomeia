package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.world_logic.cells.base.activation

class Chameleon : Cell() {
    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            var r = 0f
            var g = 0f
            var b = 0f
            var counter = 0

            //TODO плохо для производительности, не надо проверять все прилегающие // bad for performance, no need to check all adjacent ones
            for (i in 0..<cm.linksAmount[id]) {
                val linkId = cm.links[id * MAX_LINK_AMOUNT + i]
                if (!cm.isNeuronLink[linkId]) continue
                val c1 = cm.links1[linkId]
                val c2 = cm.links2[linkId]
                val neuralDirectedIndex = if (cm.isLink1NeuralDirected[linkId]) c1 else c2
                if (neuralDirectedIndex == id) {
                    counter++
                    val impulse = activation(cm, id,
                        if (c1 != id) {
                            cm.neuronImpulseOutput[c1]
                        } else if (c2 != id) {
                            cm.neuronImpulseOutput[c2]
                        } else continue
                    )

                    val color = if (impulse < 0) 0f else if (impulse > 1) 1f else impulse

                    when (counter) {
                        1 -> r = color
                        2 -> g = color
                        3 -> b = color
                    }

                }
            }
            cm.colorR[id] = r
            cm.colorG[id] = g
            cm.colorB[id] = b
            cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].energyActionCost

        }
    }
}
