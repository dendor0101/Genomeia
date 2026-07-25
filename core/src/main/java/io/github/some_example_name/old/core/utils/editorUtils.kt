package io.github.some_example_name.old.core.utils

import com.badlogic.gdx.Gdx
import kotlin.math.*

data class Point(val x: Float, val y: Float)
const val START_EDITOR_CELL_X = 64f
const val START_EDITOR_CELL_Y = 64f

const val MIN_DISTANCE_TO_CENTER = 0.6f
const val MAX_DISTANCE_TO_CENTER = 0.9f
//const val TARGET_X = 240f
//const val TARGET_Y = 160f
const val MIN_DISTANCE_TO_OTHERS = 0.6

fun findNewOptimalCellPosition(
    x: Float,
    y: Float,
    xs: List<Float>,
    ys: List<Float>
): Pair<Float, Float>? {
    if (xs.size != ys.size) {
        throw IllegalArgumentException("xs and ys must have the same size")
    }

    val others = xs.indices.map { Point(xs[it], ys[it]) }

    val dx = START_EDITOR_CELL_X - x
    val dy = START_EDITOR_CELL_Y - y
    val d = sqrt(dx * dx + dy * dy)

    // 1. Сначала пробуем идеальную точку
    if (d > 1e-5f) {
        val idealAngle = atan2(dy, dx)
        val idealR = d.coerceIn(MIN_DISTANCE_TO_CENTER, MAX_DISTANCE_TO_CENTER)

        val idealPx = x + idealR * cos(idealAngle)
        val idealPy = y + idealR * sin(idealAngle)

        if (isPositionValid(idealPx, idealPy, others)) {
            return idealPx to idealPy
        }
    }

    // 2. Сэмплирование
    val numRadiusSteps = 101
    val numAngleSteps = 360
    val radiusStep = (MAX_DISTANCE_TO_CENTER - MIN_DISTANCE_TO_CENTER) / (numRadiusSteps - 1)
    val angleStep = (2f * PI.toFloat()) / numAngleSteps

    var bestX: Float? = null
    var bestY: Float? = null
    var bestDist = Float.MAX_VALUE
    var bestAngle = Float.MAX_VALUE   // для стабильного выбора при почти равных расстояниях

    for (i in 0 until numRadiusSteps) {
        val r = MIN_DISTANCE_TO_CENTER + i * radiusStep

        for (j in 0 until numAngleSteps) {
            val a = j * angleStep
            val px = x + r * cos(a)
            val py = y + r * sin(a)

            if (!isPositionValid(px, py, others)) continue

            val distToTarget = dist(px, py, START_EDITOR_CELL_X, START_EDITOR_CELL_Y)

            // Выбираем точку по двум критериям:
            // 1. Меньшее расстояние до целевой точки
            // 2. При почти равном расстоянии — меньший угол (детерминированность)
            val isBetter = distToTarget < bestDist - 1e-4f ||
                (abs(distToTarget - bestDist) <= 1e-4f && a < bestAngle)

            if (isBetter) {
                bestDist = distToTarget
                bestAngle = a
                bestX = px
                bestY = py
            }
        }
    }

    return if (bestX != null && bestY != null) bestX to bestY else null
}

// Вспомогательная функция
private fun isPositionValid(px: Float, py: Float, others: List<Point>): Boolean {
    return others.all { dist(px, py, it.x, it.y) >= MIN_DISTANCE_TO_OTHERS }
}

private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    return sqrt((ax - bx).pow(2) + (ay - by).pow(2))
}

fun setMinMaxDistForChildCellToParent(childCellX: Float, childCellY: Float, parentCellX: Float, parentCellY: Float): Pair<Float, Float> {
    val dx = childCellX - parentCellX
    val dy = childCellY - parentCellY
    val distance = sqrt(dx * dx + dy * dy)

    // Ограничиваем расстояние между minRadius (5f) и maxRadius (30f)
    val minRadius = 5f / 40f
    val maxRadius = 30f / 40f

    return when {
        distance < minRadius -> {
            // Если слишком близко, перемещаем на границу minRadius
            val scale = minRadius / distance
            val newX = parentCellX + dx * scale
            val newY = parentCellY + dy * scale
            Pair(newX, newY)
        }
        distance > maxRadius -> {
            // Если слишком далеко, перемещаем на границу maxRadius
            val scale = maxRadius / distance
            val newX = parentCellX + dx * scale
            val newY = parentCellY + dy * scale
            Pair(newX, newY)
        }
        else -> {
            // Если расстояние в пределах, оставляем как есть
            Pair(childCellX, childCellY)
        }
    }
}
