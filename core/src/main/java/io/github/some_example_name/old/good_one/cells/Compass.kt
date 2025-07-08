package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.blueColors
import io.github.some_example_name.attempts.game.physics.invSqrt
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Directed
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.logic.CellManager
import kotlin.math.*

class Compass : Cell(), Neural, Directed {
    override var angle = 0f
    override var colorCore = blueColors[6]
    override val maxEnergy = 5f
    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f

    override fun specificToThisType() {
        if (energy > 0) {
            if (physicalLinks.isEmpty()) return
            val link = physicalLinks.first()

            val c2 = if (link.c1 != this) link.c1 else link.c2

            val dx = c2.x - x
            val dy = c2.y - y
            val sqrt = dx * dx + dy * dy
            val distance = if (sqrt > 0) sqrt(sqrt) else return
            if (distance.isNaN()) throw Exception("TODO потом убрать")

            val originalAngle = atan2(dy, dx)

            val angleRad = originalAngle + (angle + 180) * (PI / 180).toFloat()
            neuronImpulseImport = sin(angleRad)

            energy -= 0.005f
        }
    }


    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.tickRestriction[id] == 7) {
                cm.tickRestriction[id] = 0
                if (cm.energy[id] > 0) {
                    if (cm.startAngleId[id] == -1) return
                    val linkId = cm.linkIdMap.get(id, cm.startAngleId[id])
                    if (linkId == -1) return
                    val dx = cm.x[cm.startAngleId[id]] - cm.x[id]
                    val dy = cm.y[cm.startAngleId[id]] - cm.y[id]
                    val sqrt = dx * dx + dy * dy
                    val distance = if (sqrt > 0) 1.0f / invSqrt(sqrt) else return
                    if (distance.isNaN()) throw Exception("TODO потом убрать")
                    val originalAngle = atan2(dy, dx)
                    val angleRad = originalAngle + (cm.angle[id] + 180) * (PI / 180).toFloat()
                    cm.neuronImpulseImport[id] = sin(angleRad)

                    cm.energy[id] -= 0.005f
                }
            } else {
                cm.tickRestriction[id] += 1
            }
        }
    }
}
