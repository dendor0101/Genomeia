package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.blueColors
import io.github.some_example_name.attempts.game.physics.invSqrt
import io.github.some_example_name.old.good_one.Link
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Directed
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.good_one.cells.base.activation
import io.github.some_example_name.old.logic.CellManager
import io.github.some_example_name.old.logic.CellManager.Companion.MAX_LINK_AMOUNT
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Tail: Cell(), Neural, Directed {
    override var angle = 0f
    override var colorCore = blueColors.random()
    override val maxEnergy = 5f
    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f
             var speed = 0f

    override fun specificToThisType() {
        if (energy > 0) {
            if (physicalLinks.isEmpty()) return
            val link = physicalLinks.first()

            val c2 = if (link.c1 != this) link.c1 else link.c2

            var impulse = activation(neuronImpulseImport)

            if (impulse <= 0f) return
            if (impulse > 1) impulse = 1f
            if (speed < impulse) speed += 0.05f else if (speed > impulse) speed -= 0.05f

            val dx = c2.x - x
            val dy = c2.y - y
            val sqrt = dx * dx + dy * dy
            val distance = if (sqrt > 0) sqrt(sqrt) else return
            if (distance.isNaN()) throw Exception("TODO потом убрать")

            val baseDirX = dx / distance
            val baseDirY = dy / distance

            val angleRad = angle * (PI / 180).toFloat()
            val cosA = cos(angleRad)
            val sinA = sin(angleRad)

            // Рассчитываем направление движения
            val directionX = baseDirX * cosA - baseDirY * sinA
            val directionY = baseDirX * sinA + baseDirY * cosA
            if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать")

            vx += directionX / 2 * speed
            vy += directionY / 2 * speed
            energy -= 0.005f
        }
    }

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] > 0) {
                if (cm.startAngleId[id] == -1) return
                val linkId = cm.linkIdMap.get(id, cm.startAngleId[id])
                if (linkId == -1) return

                var impulse = activation(cm, id, cm.neuronImpulseImport[id])
                if (impulse < 0f) impulse = 0f
                if (impulse > 1f) impulse = 1f
                if (cm.speed[id] < impulse) cm.speed[id] += 0.012f else if (cm.speed[id] > impulse) cm.speed[id] -= 0.012f
//                if (cm.id[id] == "42") {
//                    println("${cm.speed[id]} $impulse")
//                }
                if (cm.speed[id] <= 0f) return

                val dx = cm.x[cm.startAngleId[id]] - cm.x[id]
                val dy = cm.y[cm.startAngleId[id]] - cm.y[id]
                val sqrt = dx * dx + dy * dy
                val distance = if (sqrt > 0) 1.0f / invSqrt(sqrt) else return
                if (distance.isNaN()) throw Exception("TODO потом убрать")

                val baseDirX = dx / distance
                val baseDirY = dy / distance

                val angleRad = cm.angle[id] * (PI / 180).toFloat()
                val cosA = cos(angleRad)
                val sinA = sin(angleRad)

                // Рассчитываем направление движения
                val directionX = baseDirX * cosA - baseDirY * sinA
                val directionY = baseDirX * sinA + baseDirY * cosA
                if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать")

                cm.vx[id] += directionX / 2 * cm.speed[id]
                cm.vy[id] += directionY / 2 * cm.speed[id]
                cm.energy[id] -= 0.005f
            }
        }
    }
}
