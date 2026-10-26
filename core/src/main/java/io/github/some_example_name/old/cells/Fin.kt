package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.skyBlueColors
import kotlin.math.abs

class Fin(cellTypeId: Int): Cell(
    key = "fin",
    defaultColor = skyBlueColors[1],
    cellTypeId = cellTypeId,
    textureName = "tail.png",
    isDirected = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val vx = getVx(cellIndex)
        val vy = getVy(cellIndex)
        val cos = angleCos[cellIndex]
        val sin = angleSin[cellIndex]

        // Перпендикулярная компонента скорости (скалярное произведение с нормалью к оси)
        val perp = vx * sin - vy * cos
        val force = abs(perp) * substrateSettings.data.finMaxSpeedCoefficient

        if (force > 1e-6f) {
            addVelocity(cellIndex, -cos * force, -sin * force)
        }
    }
}
