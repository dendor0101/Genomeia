package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.leafColors

class SuctionCup(cellTypeId: Int): Cell(
    defaultColor = leafColors[5],
    cellTypeId = cellTypeId,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val impulse = neuronImpulseOutput[cellIndex]
        val friction = if (impulse > 1) 1f else if (impulse < 0) 0f else impulse

        setDragCoefficient(cellIndex, friction)
        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }
}
