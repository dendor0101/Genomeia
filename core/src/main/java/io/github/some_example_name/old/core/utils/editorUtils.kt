package io.github.some_example_name.old.core.utils

import kotlin.math.*

const val START_EDITOR_CELL_X = 64f
const val START_EDITOR_CELL_Y = 64f

// Поиск места для новой клетки — editor/geometry/CellPlacement: расстояния там в долях
// суммы радиусов, а не прежними числами под клетки радиусом 0.5.

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
