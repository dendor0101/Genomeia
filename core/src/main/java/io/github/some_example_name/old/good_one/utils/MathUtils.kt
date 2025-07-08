package io.github.some_example_name.old.good_one.utils

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.editor.CellCopy
import kotlin.math.*

fun calculateAngle(cell: Cell, added: Cell): Float {
    val deltaX = added.x - cell.x
    val deltaY = added.y - cell.y
    val angleRad = atan2(deltaY, deltaX)
    return angleRad
}

fun calculateAngle(cell: CellCopy, added: CellCopy): Float {
    val deltaX = added.x - cell.x
    val deltaY = added.y - cell.y
    val angleRad = atan2(deltaY, deltaX)
    return angleRad
}

//fun findOppositeGravityCenter(addedCells: MutableList<Cell>, selectedCell: Cell): Pair<Float, Float> { //TODO сделать что-то поадекватнее
//    var x = 10f
//    var y = 10f
//    val allCells = (cells + addedCells)
//    allCells.forEach {
//        x += it.x
//        y += it.y
//    }
//    return Pair(selectedCell.x - x / allCells.size, selectedCell.y - y / allCells.size)
//}

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

fun List<Cell>.cellClicked(): Cell? {
    val (mouseX, mouseY) = getMouseCoord()
    return this.minByOrNull { it.distanceTo(mouseX, mouseY) }
        ?.takeIf { it.distanceTo(mouseX, mouseY) < 20f }
}

fun HashMap<String, CellCopy>.cellClicked(mouseX: Float, mouseY: Float): Pair<String, CellCopy>? {

    return this.entries
        .minByOrNull { (_, cell) -> cell.distanceTo(mouseX, mouseY) }
        ?.takeIf { (_, cell) -> cell.distanceTo(mouseX, mouseY) < 20f }
        ?.let { (key, value) -> key to value }
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

fun generateMockArrays(radius: Double) {
    val cells = getIntersectedCells(radius)
    val xCoords = cells.map { it.x }.toIntArray()
    val yCoords = cells.map { it.y }.toIntArray()

    println("// Готовый код для вставки:")
    println("val xCoords = intArrayOf(${xCoords.joinToString()})")
    println("val yCoords = intArrayOf(${yCoords.joinToString()})")
}

//fun main() {
//    val radius = 75.0
//    generateMockArrays(radius)
//}

val xCoordsCircleCells = intArrayOf(-8, -7, -7, -7, -7, -7, -7, -7, -7, -7, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -6, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -5, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -3, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,  0,  0,  0,  0,  0,  0,  0,  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8)
val yCoordsCircleCells = intArrayOf( 0, -4, -3, -2, -1,  0,  1,  2,  3,  4, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6, -7, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7, -7, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7, -7, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7, -7, -6, -5, -4, -3, -2, -1,  0,  1,  2,  3,  4,  5,  6,  7, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 0)

fun main() {
    var previousVelocity = 0.0
    var previousTime = System.nanoTime() / 1e9
    var emaAcceleration = 0.0
    val alpha = 0.3 // Коэффициент сглаживания (0.1–0.5)

    repeat(100) { // 100 итераций для примера
        val currentVelocity = getCurrentVelocity()
        val currentTime = System.nanoTime() / 1e9
        val deltaTime = (currentTime - previousTime).coerceAtLeast(0.001) // Защита от деления на 0

        // 1. Расчёт "сырого" ускорения
        val rawAcceleration = (currentVelocity - previousVelocity) / deltaTime

        // 2. Сглаживание через EMA
        emaAcceleration = alpha * rawAcceleration + (1 - alpha) * emaAcceleration

        println("Raw: %.2f, EMA: %.2f".format(rawAcceleration, emaAcceleration))

        previousVelocity = currentVelocity
        previousTime = currentTime
        Thread.sleep(16) // ~60 FPS
    }
}

fun getCurrentVelocity(): Double {
    // Пример: разгон и торможение
    return 10.0 * sin(System.nanoTime() / 1e9 * 2.0)
}
