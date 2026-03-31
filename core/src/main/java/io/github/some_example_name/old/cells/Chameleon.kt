package io.github.some_example_name.old.cells

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.whiteColors
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem.Companion.MAX_LINK_AMOUNT

class Chameleon(cellTypeId: Int) : Cell(
    defaultColor = whiteColors[0],
    cellTypeId = cellTypeId,
    isNeural = true,
    isNeuronTransportable = false
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) {
        with(cellEntity) {
            var r = 0f
            var g = 0f
            var b = 0f
            var counter = 0

            //TODO плохо для производительности, не надо проверять все прилегающие // bad for performance, no need to check all adjacent ones
            //TODO + нет настройки какой линк за какой цвет отвечает
            for (i in 0..<linksAmount[cellIndex]) {
                val linkId = links[cellIndex * MAX_LINK_AMOUNT + i]
                if (!linkEntity.isNeuronLink[linkId]) continue
                val c1 = linkEntity.links1[linkId]
                val c2 = linkEntity.links2[linkId]
                val neuralDirectedIndex = if (linkEntity.isLink1NeuralDirected[linkId]) c1 else c2
                if (neuralDirectedIndex == cellIndex) {
                    counter++
                    val outputImpulse = if (c1 != cellIndex) {
                        neuronImpulseOutput[c1]
                    } else if (c2 != cellIndex) {
                        neuronImpulseOutput[c2]
                    } else continue
                    val impulse = activation(cellIndex, outputImpulse)

                    val color = if (impulse < 0) 0f else if (impulse > 1) 1f else impulse

                    when (counter) {
                        1 -> r = color
                        2 -> g = color
                        3 -> b = color
                    }
                }
            }
            setColor(cellIndex, Color(r, g, b, 1f).toIntBits())

            energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
        }
    }
}
