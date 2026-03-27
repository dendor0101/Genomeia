package io.github.some_example_name.old.cells

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.whiteColors

class Chameleon : Cell(
    defaultColor = whiteColors[0],
    cellTypeId = 12,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) {
//        with(cellEntity) {
//            var r = 0f
//            var g = 0f
//            var b = 0f
//            var counter = 0
//
//            //TODO плохо для производительности, не надо проверять все прилегающие // bad for performance, no need to check all adjacent ones
//            for (i in 0..<linksAmount[index]) {
//                val linkId = links[index * MAX_LINK_AMOUNT + i]
//                if (!linkEntity.isNeuronLink[linkId]) continue
//                val c1 = linkEntity.links1[linkId]
//                val c2 = linkEntity.links2[linkId]
//                val neuralDirectedIndex = if (linkEntity.isLink1NeuralDirected[linkId]) c1 else c2
//                if (neuralDirectedIndex == index) {
//                    counter++
//                    val impulse = activation(
//                        cellEntity, index,
//                        if (c1 != index) {
//                            neuronImpulseOutput[c1]
//                        } else if (c2 != index) {
//                            neuronImpulseOutput[c2]
//                        } else continue
//                    )
//
//                    val color = if (impulse < 0) 0f else if (impulse > 1) 1f else impulse
//
//                    when (counter) {
//                        1 -> r = color
//                        2 -> g = color
//                        3 -> b = color
//                    }
//                }
//            }
//            setColor(index, Color.rgba8888(Color(r, g, b, 1f)))
//
//            energy[index] -= substrateSettings.cellsSettings[cellType[index]].energyActionCost
//        }
    }
}
