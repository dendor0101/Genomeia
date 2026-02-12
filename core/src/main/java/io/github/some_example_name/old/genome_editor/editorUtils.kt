package io.github.some_example_name.old.genome_editor

import io.github.some_example_name.old.genome_editor.GenomeEditorGrowthProcessor.Companion.START_EDITOR_CELL_X
import io.github.some_example_name.old.genome_editor.GenomeEditorGrowthProcessor.Companion.START_EDITOR_CELL_Y
import kotlin.math.*

data class Point(val x: Float, val y: Float)

const val MIN_DISTANCE_TO_CENTER = 24f
const val MAX_DISTANCE_TO_CENTER = 36f
//const val TARGET_X = 240f
//const val TARGET_Y = 160f
const val MIN_DISTANCE_TO_OTHERS = 24f

fun findNewOptimalCellPosition(x: Float, y: Float, xs: List<Float>, ys: List<Float>): Pair<Float, Float>? {
    if (xs.size != ys.size) {
        throw IllegalArgumentException("xs and ys must have the same size")
    }
    val others = xs.indices.map { Point(xs[it], ys[it]) }
    val dx = START_EDITOR_CELL_X - x
    val dy = START_EDITOR_CELL_Y - y
    val d = sqrt(dx.pow(2) + dy.pow(2))

    // Try ideal point first if d > 0
    var bestX: Float? = null
    var bestY: Float? = null
    var minDistToTarget = Float.MAX_VALUE

    if (d > 0f) {
        val idealAngle = atan2(dy, dx)
        val idealR = when {
            d < MIN_DISTANCE_TO_CENTER -> MIN_DISTANCE_TO_CENTER
            d > MAX_DISTANCE_TO_CENTER -> MAX_DISTANCE_TO_CENTER
            else -> d
        }
        val idealPx = x + idealR * cos(idealAngle)
        val idealPy = y + idealR * sin(idealAngle)
        val isValid = others.all { dist(idealPx, idealPy, it.x, it.y) >= MIN_DISTANCE_TO_OTHERS }
        if (isValid) {
            return Pair(idealPx, idealPy)
        }
    }

    // Sampling if ideal not valid or d==0
    val numRadiusSteps = 101 // From MIN_DISTANCE_TO_CENTER to MAX_DISTANCE_TO_CENTER inclusive, step ~0.1
    val numAngleSteps = 360 // 1 degree steps
    val radiusStep = (MAX_DISTANCE_TO_CENTER - MIN_DISTANCE_TO_CENTER) / (numRadiusSteps - 1)
    val angleStep = (2 * PI.toFloat()) / numAngleSteps

    for (i in 0 until numRadiusSteps) {
        val r = MIN_DISTANCE_TO_CENTER + i * radiusStep
        for (j in 0 until numAngleSteps) {
            val a = j * angleStep
            val px = x + r * cos(a)
            val py = y + r * sin(a)
            val isValid = others.all { dist(px, py, it.x, it.y) >= MIN_DISTANCE_TO_OTHERS }
            if (isValid) {
                val distToTarget = dist(px, py, START_EDITOR_CELL_X, START_EDITOR_CELL_Y)
                if (distToTarget < minDistToTarget) {
                    minDistToTarget = distToTarget
                    bestX = px
                    bestY = py
                }
            }
        }
    }

    return if (bestX != null && bestY != null) Pair(bestX, bestY) else null
}

private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    return sqrt((ax - bx).pow(2) + (ay - by).pow(2))
}

fun setMinMaxDistForChildCellToParent(childCellX: Float, childCellY: Float, parentCellX: Float, parentCellY: Float): Pair<Float, Float> {
    val dx = childCellX - parentCellX
    val dy = childCellY - parentCellY
    val distance = sqrt(dx * dx + dy * dy)

    // Ограничиваем расстояние между minRadius (5f) и maxRadius (30f)
    val minRadius = 5f
    val maxRadius = 30f

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
