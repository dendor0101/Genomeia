package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.redColors

class Muscle(cellTypeId: Int) : Cell(
    defaultColor = redColors[3],
    cellTypeId = cellTypeId,
    textureName = "muscle.png",
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val impulse = neuronImpulseOutput[cellIndex]

//        degreeOfShortening[cellIndex] = impulse.coerceIn(-1f, 1f) * 0.5f + 1f

        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }

}
