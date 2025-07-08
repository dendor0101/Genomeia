package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.brownColors
import io.github.some_example_name.attempts.game.physics.invSqrt
import io.github.some_example_name.old.good_one.Link
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Directed
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.good_one.substances.Substance
import io.github.some_example_name.old.good_one.substances
import io.github.some_example_name.old.logic.CellManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Excreta: Cell(), Neural, Directed {
    override var angle = 0f
    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f

    override var colorCore = brownColors.random()

    override fun specificToThisType() {
        if (energy < 4f) return
        if (physicalLinks.isEmpty()) return
        val link = physicalLinks.first()

        val c2 = if (link.c1 != this) link.c1 else link.c2

        val dx = c2.x - x
        val dy = c2.y - y
        val sqrt = dx * dx + dy * dy
        val distance = if (sqrt > 0) 1.0f / invSqrt(sqrt) else return
        if (distance.isNaN()) throw Exception("TODO потом убрать")

        // Рассчитываем направление движения
        val baseDirX = dx / distance
        val baseDirY = dy / distance

        val angleRad = angle * (PI / 180).toFloat()
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)

        // Рассчитываем направление движения
        val directionX = baseDirX * cosA - baseDirY * sinA
        val directionY = baseDirX * sinA + baseDirY * cosA
        if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать")

        if (substances.size > 140) return //TODO Этого ограничения не должно быть, пока из-за шейдеров
        substances.add(
            Substance().apply {
                x = this@Excreta.x
                y = this@Excreta.y
                vx -= directionX * 9
                vy -= directionY * 9
            }
        )
        energy -= 4f
    }

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if(cm.energy[id] < 4f) return
            if (cm.startAngleId[id] == -1) return
            val linkId = cm.linkIdMap.get(id, cm.startAngleId[id])
            if (linkId == -1) return

            val dx = cm.x[cm.startAngleId[id]] - cm.x[id]
            val dy = cm.y[cm.startAngleId[id]] - cm.y[id]
            val sqrt = dx * dx + dy * dy
            val distance = if (sqrt > 0) sqrt(sqrt) else return
            if (distance.isNaN()) throw Exception("TODO потом убрать")

            // Рассчитываем направление движения
            val baseDirX = dx / distance
            val baseDirY = dy / distance

            val angleRad = cm.angle[id] * (PI / 180).toFloat()
            val cosA = cos(angleRad)
            val sinA = sin(angleRad)

            // Рассчитываем направление движения
            val directionX = baseDirX * cosA - baseDirY * sinA
            val directionY = baseDirX * sinA + baseDirY * cosA
            if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать")

            cm.subManager.addCell(
                cm.x[id],
                cm.y[id],
                -directionX * 9 - (Random.nextFloat() - 0.5f) * 3f,
                -directionY * 9 - (Random.nextFloat() - 0.5f) * 3f)
            cm.energy[id] -= 4f
        }
    }
}
