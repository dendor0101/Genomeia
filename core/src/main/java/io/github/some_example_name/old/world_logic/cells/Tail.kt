package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Directed
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.world_logic.CellManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class Tail: Cell(), Neural, Directed {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] > 0) {
                var impulse = cm.neuronImpulseOutput[id]
                if (impulse < 0f) impulse = 0f
                if (impulse > 1f) impulse = 1f
                if (cm.speed[id] < impulse) cm.speed[id] += 0.012f else if (cm.speed[id] > impulse) cm.speed[id] -= 0.012f

                if (cm.speed[id] <= 0.013f) return

                val angleRad = cm.angle[id] + PI.toFloat() + cm.angleDiff[id]
                val cosA = cos(angleRad)
                val sinA = sin(angleRad)

                // Рассчитываем направление движения
                // We calculate the direction of movement
                val directionX = cosA
                val directionY = sinA
                if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать // remove later")

                val tailMaxSpeedCoefficient = cm.globalSettings.tailMaxSpeedCoefficient
                cm.vx[id] += directionX / 2 * cm.speed[id] * tailMaxSpeedCoefficient
                cm.vy[id] += directionY / 2 * cm.speed[id] * tailMaxSpeedCoefficient
                cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].energyActionCost
            }
        }
    }
}
