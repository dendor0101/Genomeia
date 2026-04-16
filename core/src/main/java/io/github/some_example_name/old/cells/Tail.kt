package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.blueColors

class Tail(cellTypeId: Int): Cell(
    defaultColor = blueColors.first(),
    cellTypeId = cellTypeId,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        if (energy[cellIndex] > 0) {
            var impulse = neuronImpulseOutput[cellIndex]
            if (impulse < 0f) impulse = 0f
            if (impulse > 1f) impulse = 1f

            val speed = with(specialEntity) {
                val speed = getSpeed(cellIndex)
                if (getSpeed(cellIndex) < impulse) {
                    setSpeed(cellIndex, speed + 0.012f)
                } else if (getSpeed(cellIndex) > impulse) {
                    setSpeed(cellIndex, speed - 0.012f)
                }
                if (getSpeed(cellIndex) <= 0.013f) return
                getSpeed(cellIndex)
            }

            val tailMaxSpeedCoefficient = substrateSettings.data.tailMaxSpeedCoefficient
            val vx = getVx(cellIndex)
            val vy = getVy(cellIndex)
            setVx(cellIndex, vx - angleCos[cellIndex] * 0.5f * speed * tailMaxSpeedCoefficient)
            setVy(cellIndex, vy - angleSin[cellIndex] * 0.5f * speed * tailMaxSpeedCoefficient)
            energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
        }
    }
}
