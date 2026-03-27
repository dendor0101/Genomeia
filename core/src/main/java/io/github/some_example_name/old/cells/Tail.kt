package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.blueColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class Tail: Cell(
    defaultColor = blueColors.first(),
    cellTypeId = 3,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) = with(cellEntity) {
        if (energy[index] > 0) {
            var impulse = neuronImpulseOutput[index]
            if (impulse < 0f) impulse = 0f
            if (impulse > 1f) impulse = 1f
//            if (speed[index] < impulse) speed[index] += 0.012f else if (speed[index] > impulse) speed[index] -= 0.012f
//            if (speed[index] <= 0.013f) return
            val angleRad = angle[index] + PI.toFloat()
            val cosA = cos(angleRad)
            val sinA = sin(angleRad)

            // Рассчитываем направление движения
            // We calculate the direction of movement
            val directionX = cosA
            val directionY = sinA
            if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать // remove later")

            val tailMaxSpeedCoefficient = substrateSettings.data.tailMaxSpeedCoefficient
            val vx = getVx(index)
            val vy = getVy(index)
            setVx(index, vx + directionX / 2/* * cm.speed[index]*/ * tailMaxSpeedCoefficient)
            setVy(index, vy + directionY / 2/* * cm.speed[index]*/ * tailMaxSpeedCoefficient)
            energy[index] -= substrateSettings.cellsSettings[cellType[index].toInt()].energyActionCost
        }
    }
}
