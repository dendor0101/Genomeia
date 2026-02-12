package io.github.some_example_name.old.good_one.utils

import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.world_logic.CellManager
import kotlin.math.*

/*

fun calculateAngle(cell: CellCopy, added: CellCopy): Float {
    val deltaX = added.x - cell.x
    val deltaY = added.y - cell.y
    val angleRad = atan2(deltaY, deltaX)
    println("calculateAngle ${angleRad} - ${(cell.angleBase ?: 0f)}")
    return angleRad  - (cell.angleBase ?: 0f)
}

fun findLocationForNewCell(addedCells: HashMap<String, CellCopy>, selectedCell: CellCopy): Pair<Float, Float> {
    if (addedCells.isEmpty()) {
        return Pair(selectedCell.x, selectedCell.y)
    }
    if (addedCells.size == 1) {
        return Pair(selectedCell.x, selectedCell.y + 40f)
    }

    val combinedCells = addedCells.values
    val cellRadius = 20f

    // Вычисляем общий центр масс
    val (totalX, totalY) = combinedCells.fold(0f to 0f) { (accX, accY), cell ->
        accX + cell.x to accY + cell.y
    }
    val centerX = totalX / combinedCells.size
    val centerY = totalY / combinedCells.size

    // Вектор от центра масс к выбранной клетке
    val directionX = selectedCell.x - centerX
    val directionY = selectedCell.y - centerY
    val directionLength = sqrt(directionX * directionX + directionY * directionY)

    // Нормализуем вектор
    val normX = directionX / directionLength
    val normY = directionY / directionLength

    // Начинаем от точки рядом с selectedCell и движемся к центру масс,
    // пока не найдем позицию без коллизий
    var currentDistance = 0f
    val minDistance = cellRadius * 2 // Минимальное расстояние между центрами

    while (true) {
        val testX = selectedCell.x + normX * currentDistance
        val testY = selectedCell.y + normY * currentDistance

        val hasCollision = combinedCells.any { cell ->
            val dx = testX - cell.x
            val dy = testY - cell.y
            dx * dx + dy * dy < minDistance * minDistance
        }

        if (!hasCollision || currentDistance > directionLength * 2) {
            return testX to testY
        }

        currentDistance += 1f // Шаг поиска можно уменьшить для большей точности
    }
}

fun HashMap<String, CellCopy>.generateUniqueId(baseId: String): String {
    var attempt = 1
    var newId = baseId
    while (this.containsKey(newId)) {
        newId = "$baseId-${attempt++}"
    }
    return newId
}

fun HashMap<String, CellCopy>.cellClicked(mouseX: Float, mouseY: Float): Pair<String, CellCopy>? {
    return try {
        this.entries
            .minByOrNull { (_, cell) -> cell.distanceTo(mouseX, mouseY) }
            ?.takeIf { (_, cell) -> cell.distanceTo(mouseX, mouseY) < 20f }
            ?.let { (key, value) -> key to value }
    } catch (e: Exception) {
        null
    }
}
*/

fun dot (x1: Float, y1: Float, x2: Float, y2: Float): Float {
    return (x1 * x2) + (y1 * y2)
}

fun getMouseCoord(): Pair<Float, Float> {
    val mouseX = Gdx.input.x.toFloat()
    val mouseY = (Gdx.graphics.height - Gdx.input.y.toFloat())
    return Pair(mouseX, mouseY)
}

data class CellCo(val x: Int, val y: Int)

fun getIntersectedCells(radius: Double): List<CellCo> {
    val cells = mutableListOf<CellCo>()
    val cellSize = 10
    val radiusInCells = ceil(radius / cellSize).toInt()

    for (x in -radiusInCells..radiusInCells) {
        for (y in -radiusInCells..radiusInCells) {
            val left = x * cellSize - cellSize / 2.0
            val right = x * cellSize + cellSize / 2.0
            val bottom = y * cellSize - cellSize / 2.0
            val top = y * cellSize + cellSize / 2.0

            if (circleIntersectsRectangle(0.0, 0.0, radius, left, right, bottom, top)) {
                cells.add(CellCo(x, y))
            }
        }
    }
    return cells
}

fun distanceTo(px: Float, py: Float, x: Float, y: Float): Float {
    val dx = px - x
    val dy = py - y
    val sqrt = dx * dx + dy * dy
    if (sqrt <= 0) return 0f
    val result = sqrt(sqrt)
    if (result.isNaN()) return 0f//throw Exception("TODO потом убрать")
    return result
}

fun circleIntersectsRectangle(
    circleX: Double, circleY: Double, radius: Double,
    left: Double, right: Double, bottom: Double, top: Double
): Boolean {
    val closestX = max(left, min(circleX, right))
    val closestY = max(bottom, min(circleY, top))
    val distanceX = circleX - closestX
    val distanceY = circleY - closestY
    return (distanceX * distanceX + distanceY * distanceY) <= (radius * radius)
}

fun invSqrt(x: Float): Float {
    val xhalf = 0.5f * x
    var i = java.lang.Float.floatToIntBits(x)
    i = 0x5f3759df - (i shr 1)
    var y = java.lang.Float.intBitsToFloat(i)
    y *= (1.5f - xhalf * y * y)
    return y
}

fun generateMockArrays(radius: Double) {
    val cells = getIntersectedCells(radius)
    val xCoords = cells.map { it.x }.toIntArray()
    val yCoords = cells.map { it.y }.toIntArray()

    println("// Готовый код для вставки:")
    println("val xCoords = intArrayOf(${xCoords.joinToString()})")
    println("val yCoords = intArrayOf(${yCoords.joinToString()})")
}

//fun main() {
//    val radius = 150.0
//    generateMockArrays(radius)
//}
val xCoordsCircleCells = intArrayOf(-15, -15, -15, -15, -15, -15, -15, -15, -15, -14, -14, -14, -14, -14, -14, -14, -14, -14, -14, -14, -14, -14, -14, -14, -13, -13, -13, -13, -13, -13, -13, -13, -13, -13, -13, -13, -13, -13, -13, -13, -13, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -12, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -11, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -10, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -9, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -8, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -7, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14, 15, 15, 15, 15, 15, 15, 15, 15, 15)
val yCoordsCircleCells = intArrayOf(-4, -3, -2, -1, 0, 1, 2, 3, 4, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, -4, -3, -2, -1, 0, 1, 2, 3, 4)
//val xCoordsCircleCells = intArrayOf(-8, -7, -7, -7, -7, -7, -7, -7, -7, -7, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,  0,  0,  0,  0,  0,  0,  0,  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8)
//val yCoordsCircleCells = intArrayOf( 0, -4, -3, -2, -1,  0,  1,  2,  3,  4, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6, -7, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7, -7, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7, -7, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7, -7, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 0)
