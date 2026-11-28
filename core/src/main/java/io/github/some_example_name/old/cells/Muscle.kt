package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.redColors

class Muscle(cellTypeId: Int) : Cell(
    key = "muscle",
    defaultColor = redColors[3],
    cellTypeId = cellTypeId,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val impulse = neuronImpulseOutput[cellIndex]

        // Формат команды для физики и сохранений: 0.5 — сокращение, 1 — покой, 1.5 — расслабление.
        degreeOfShortening[cellIndex] = impulse.coerceIn(-1f, 1f) * 0.5f + 1f

        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }

}
