package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.world_logic.cells.base.Directed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Vascular : Cell(), Neural, Directed {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] <= 0) return
            var impulse = cm.neuronImpulseOutput[id]
            if (impulse < 0f) impulse = 0f
            if (impulse > 1f) impulse = 1f
            if (cm.speed[id] < impulse) cm.speed[id] += 0.012f else if (cm.speed[id] > impulse) cm.speed[id] -= 0.012f

            if (cm.speed[id] <= 0.013f) return

            val angleRad = cm.angle[id] + PI.toFloat()
            val cosA = cos(angleRad)
            val sinA = sin(angleRad)

            val directionX = cosA
            val directionY = sinA
            if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать // remove later")

            // Energy transfer rate dependent on the speed and dot product between its direction and the link's direction
            // TODO: Add a checkbox for restricting the natural energy flow
            for (i in 0..<cm.linksAmount[id]) {
                val linkId = cm.links[id * MAX_LINK_AMOUNT + i]
                val c1 = cm.links1[linkId]
                val c2 = cm.links2[linkId]
                val dx = cm.x[c1] - cm.x[c2]
                val dy = cm.y[c1] - cm.y[c2]
                if (dx == 0f && dy == 0f) continue
                val length = sqrt(dx * dx + dy * dy)
                var energyTransferRate = 0.1f*cm.speed[id]*(dx * directionX + dy * directionY)/length
                if (c2 == id) {
                    energyTransferRate *= -1f
                }
                if (cm.energy[c1] <= 0.01f && energyTransferRate < 0 || cm.energy[c2] <= 0.01f && energyTransferRate > 0 ||
                    cm.energy[c1] >= cm.cellsSettings[cm.cellType[c1] + 1].maxEnergy && energyTransferRate > 0 ||
                    cm.energy[c2] >= cm.cellsSettings[cm.cellType[c2] + 1].maxEnergy && energyTransferRate < 0) continue
                cm.energy[c1] += energyTransferRate
                cm.energy[c2] -= energyTransferRate
            }

            cm.energy[id] -= cm.speed[id] * 0.001f
        }
    }
}
