package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.leafColors

class SuctionCup: Cell(
    defaultColor = leafColors[5],
    cellTypeId = 9,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) = with(cellEntity) {
        val impulse = neuronImpulseOutput[index]
        val friction = if (impulse > 1) 1f else if (impulse < 0) 0f else impulse

        setDragCoefficient(index, friction/*((friction * 0.93f) * 100).toInt().toByte()*/)//TODO прочекать Byte
        energy[index] -= substrateSettings.cellsSettings[cellType[index].toInt()].energyActionCost
    }
}
