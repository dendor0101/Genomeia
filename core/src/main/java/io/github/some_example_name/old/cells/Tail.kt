package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.blueColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
//            if (speed[index] < impulse) speed[index] += 0.012f else if (speed[index] > impulse) speed[index] -= 0.012f
//            if (speed[index] <= 0.013f) return
            val angleRad = angle[cellIndex] + PI.toFloat()
            val cosA = cos(angleRad)
            val sinA = sin(angleRad)

            // Рассчитываем направление движения
            // We calculate the direction of movement
            val directionX = cosA
            val directionY = sinA
            if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать // remove later")

            val tailMaxSpeedCoefficient = substrateSettings.data.tailMaxSpeedCoefficient
            val vx = getVx(cellIndex)
            val vy = getVy(cellIndex)
            setVx(cellIndex, vx + directionX / 2/* * cm.speed[index]*/ * tailMaxSpeedCoefficient)
            setVy(cellIndex, vy + directionY / 2/* * cm.speed[index]*/ * tailMaxSpeedCoefficient)
            energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
        }
    }
}
