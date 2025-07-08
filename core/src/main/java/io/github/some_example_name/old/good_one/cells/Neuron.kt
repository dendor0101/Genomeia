package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.pinkColors
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.good_one.cells.base.activation
import io.github.some_example_name.old.logic.CellManager
import io.github.some_example_name.old.logic.CellManager.Companion.MAX_LINK_AMOUNT

class Neuron : Cell(), Neural {

    override var colorCore = pinkColors.random()
    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f

    override fun specificToThisType() {
        if (energy <= 0) return

        neuronImpulseImport = 0f
        val inputNeuronLinks = neuronLinks.filter { it.c1.id == this.id }

        inputNeuronLinks.forEach { link ->
            neuronImpulseImport += link.c2.neuronImpulseImport
        }

        neuronImpulseImport = activation(neuronImpulseImport)

        energy -= 0.001f
    }

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] <= 0) return

            cm.neuronImpulseImport[id] = 0f

            //TODO плохо для производительности, не надо проверять все прилегающие
            for (i in 0..<cm.linksAmount[id]) {
                val linkId = cm.links[id * MAX_LINK_AMOUNT + i]
                if (!cm.isNeuronLink[linkId]) continue
                val c1 = cm.links1[linkId]
                val c2 = cm.links2[linkId]
                if (cm.directedNeuronLink[linkId] == id) {
                    val impulse = if (c1 != id) {
                        cm.neuronImpulseImport[c1]
                    }
                    else if (c2 != id) {
                        cm.neuronImpulseImport[c2]
                    } else continue
                    cm.neuronImpulseImport[id] += impulse
                }
            }

            cm.neuronImpulseImport[id] = activation(cm, id, cm.neuronImpulseImport[id])

            cm.energy[id] -= 0.001f
        }
    }
}

val cellsTypeFormula = arrayOf(
    " y = ax + b",
    " y = c * sin(ax + b)",
    " y = c * cos(ax + b)",
    " y = 1 / (1 + e^(-(ax + b))) + c ",
    " y = b, x <= a; y = c, x > a",
    " y = b, x < a; y = c, x >= a",
    " y = t",
    " y = impulse(a), x>=1",
    " y = x, x is in (a, b) else y = c"
)
