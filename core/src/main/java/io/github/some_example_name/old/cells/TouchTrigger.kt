package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.genomeEditorColor
import io.github.some_example_name.old.core.utils.invSqrt
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem.Companion.MAX_LINK_AMOUNT

class TouchTrigger(cellTypeId: Int): Cell(
    defaultColor = genomeEditorColor[6],
    cellTypeId = cellTypeId,
    isNeural = true,
    isNeuronTransportable = false
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        var sumStretchingDistance = 0f

        for (i in 0..<cellEntity.linksAmount[cellIndex]) {
            val linkId = links[cellIndex * MAX_LINK_AMOUNT + i]
            val c1 = linkEntity.links1[linkId]
            val c2 = linkEntity.links2[linkId]

            val dx = getX(c1) - getX(c2)
            val dy = getY(c1) - getY(c2)
            val sqrt = dx * dx + dy * dy
            if (sqrt <= 0) return
            val dist = 1.0f / invSqrt(sqrt)

            val stretchingDistance = linkEntity.linksNaturalLength[linkId] * linkEntity.degreeOfShortening[linkId] - dist
            sumStretchingDistance += stretchingDistance
        }

        val impulse = (sumStretchingDistance / linksAmount[cellIndex]) * 0.5f

        neuronImpulseOutput[cellIndex] = activation(cellIndex, impulse)

        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }
}
