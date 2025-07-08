package io.github.some_example_name.old.good_one.cells

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.attempts.game.physics.whiteColors
import io.github.some_example_name.old.good_one.Link
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.logic.CellManager
import io.github.some_example_name.old.logic.CellManager.Companion.MAX_LINK_AMOUNT

class Chameleon : Cell(), Neural {
    override var colorCore = whiteColors[0]

    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f

    override fun specificToThisType() {
        var r = 0f
        var g = 0f
        var b = 0f

        //TODO Сделать так, чтобы в UI можно было выбрать порядок, чтобы, например можно было управлять синим цветом при одной нейросвязи
        neuronImpulseImport = 0f
        val inputNeuronLinks = neuronLinks.filter { it.c1.id == this.id }

        when (inputNeuronLinks.size) {
            0 -> return
            1 -> {
                val impulseR = inputNeuronLinks[0].c2.neuronImpulseImport
                r = if (impulseR < 0) 0f else if (impulseR > 1) 1f else impulseR
            }

            2 -> {
                val impulseR = inputNeuronLinks[0].c2.neuronImpulseImport
                r = if (impulseR < 0) 0f else if (impulseR > 1) 1f else impulseR
                val impulseG = inputNeuronLinks[1].c2.neuronImpulseImport
                g = if (impulseG < 0) 0f else if (impulseG > 1) 1f else impulseG
            }

            else -> {
                val impulseR = inputNeuronLinks[0].c2.neuronImpulseImport
                r = if (impulseR < 0) 0f else if (impulseR > 1) 1f else impulseR
                val impulseG = inputNeuronLinks[1].c2.neuronImpulseImport
                g = if (impulseG < 0) 0f else if (impulseG > 1) 1f else impulseG
                val impulseB = inputNeuronLinks[2].c2.neuronImpulseImport
                b = if (impulseB < 0) 0f else if (impulseB > 1) 1f else impulseB
            }
        }

        colorCore = Color(r, g, b, 1.0f)
    }


    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            var r = 0f
            var g = 0f
            var b = 0f
            var counter = 0

            //TODO плохо для производительности, не надо проверять все прилегающие
            for (i in 0..<cm.linksAmount[id]) {
                val linkId = cm.links[id * MAX_LINK_AMOUNT + i]
                if (!cm.isNeuronLink[linkId]) continue
                val c1 = cm.links1[linkId]
                val c2 = cm.links2[linkId]
                if (cm.directedNeuronLink[linkId] == id) {
                    counter++
                    val impulse = if (c1 != id) {
                        cm.neuronImpulseImport[c1]
                    } else if (c2 != id) {
                        cm.neuronImpulseImport[c2]
                    } else continue

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

        }
    }
}
