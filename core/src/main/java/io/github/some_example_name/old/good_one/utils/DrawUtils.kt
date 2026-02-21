package io.github.some_example_name.old.good_one.utils

import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import kotlin.math.*

fun ShapeRenderer.drawArrowWithRotation(
    startX: Float, startY: Float,  // Начальная точка стрелки
    targetX: Float, targetY: Float, // Целевая точка (направление при angle=0)
    angle: Float,                  // Угол поворота в градусах относительно линии к цели
    length: Float = 30f,            // Длина стрелки,
    isDrawWithoutTriangle: Boolean = false
) {
    // 1. Вычисляем базовое направление (от start к target)
    val dx = targetX - startX
    val dy = targetY - startY
    val baseAngle = atan2(dy, dx)  // Угол к цели в радианах

    val totalAngle = baseAngle + angle + PI.toFloat()

    val endX = startX + cos(totalAngle) * length
    val endY = startY + sin(totalAngle) * length

    this.line(startX, startY, endX, endY)

    if (!isDrawWithoutTriangle)
        this.drawTriangleEnd(startX, startY, endX, endY)
}

fun ShapeRenderer.drawArrowWithRotationAngle(
    startX: Float, startY: Float,
    baseAngle: Float,
    length: Float = 30f,
    isDrawWithoutTriangle: Boolean = false
) {
    val endX = startX + cos(baseAngle) * length
    val endY = startY + sin(baseAngle) * length

    this.line(startX, startY, endX, endY)

    if (!isDrawWithoutTriangle)
        this.drawTriangleEnd(startX, startY, endX, endY)
}

fun ShapeRenderer.drawTriangleEnd(
    c1x: Float,
    c1y: Float,
    c2x: Float,
    c2y: Float,
    arrowSize: Float = 2.5f
) {
    // Вектор направления стрелки
    val dx = c2x - c1x
    val dy = c2y - c1y

    // Нормализуем вектор направления
    val length = sqrt(dx * dx + dy * dy)
    if (length == 0f) return // избегаем деления на ноль

    val dirX = dx / length
    val dirY = dy / length

    // Перпендикулярный вектор
    val perpX = -dirY
    val perpY = dirX

    // Вершина треугольника (смещена немного назад от конца линии)
    val tipX = c2x - dirX * arrowSize * 0.5f
    val tipY = c2y - dirY * arrowSize * 0.5f

    // Углы основания треугольника
    val base1X = tipX - dirX * arrowSize + perpX * arrowSize
    val base1Y = tipY - dirY * arrowSize + perpY * arrowSize

    val base2X = tipX - dirX * arrowSize - perpX * arrowSize
    val base2Y = tipY - dirY * arrowSize - perpY * arrowSize

    // Отрисовка треугольника
    this.triangle(tipX, tipY, base1X, base1Y, base2X, base2Y)
}

fun ShapeRenderer.drawTriangleMiddle(
    c1x: Float,
    c1y: Float,
    c2x: Float,
    c2y: Float,
    arrowSize: Float = 2f
) {
    // Координаты начала и конца отрезка
    val x1 = c1x
    val y1 = c1y
    val x2 = c2x
    val y2 = c2y

// Находим середину отрезка
    val midX = (x1 + x2) / 2
    val midY = (y1 + y2) / 2

// Вектор направления отрезка
    val dx = x2 - x1
    val dy = y2 - y1

// Нормализуем вектор направления
    val length = sqrt(dx * dx + dy * dy)
    val dirX = dx / length
    val dirY = dy / length

// Перпендикулярный вектор (для смещения углов треугольника)
    val perpX = -dirY
    val perpY = dirX


// Координаты углов треугольника
    val tipX = midX + dirX * arrowSize // Вершина стрелки
    val tipY = midY + dirY * arrowSize

    val base1X = midX - dirX * arrowSize + perpX * arrowSize // Левый угол основания
    val base1Y = midY - dirY * arrowSize + perpY * arrowSize

    val base2X = midX - dirX * arrowSize - perpX * arrowSize // Правый угол основания
    val base2Y = midY - dirY * arrowSize - perpY * arrowSize

// Отрисовка треугольника
    this.triangle(tipX, tipY, base1X, base1Y, base2X, base2Y)
}
