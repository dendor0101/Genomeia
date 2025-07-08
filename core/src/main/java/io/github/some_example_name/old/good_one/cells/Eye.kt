package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.invSqrt
import io.github.some_example_name.attempts.game.physics.skyBlueColors
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Directed
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.logic.CellManager
import io.github.some_example_name.old.logic.SubstanceManager.Companion.MAX_SUB_CELL_COUNT
import kotlin.math.*

class Eye : Cell(), Neural, Directed {
    override var angle = 0f
    override var colorCore = skyBlueColors[2]
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
            val distance = if (sqrt > 0) 1.0f / invSqrt(sqrt) else return
            if (distance.isNaN()) throw Exception("TODO потом убрать")

            val baseDirX = dx / distance
            val baseDirY = dy / distance

            val angleRad = (angle + 180) * (PI / 180).toFloat()
            val cosA = cos(angleRad)
            val sinA = sin(angleRad)

            // Рассчитываем направление движения
            val directionX = baseDirX * cosA - baseDirY * sinA
            val directionY = baseDirX * sinA + baseDirY * cosA
            if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать")

//            neuronImpulseImport = findFirstIntersection(x, y, directionX, directionY) ?: 0f

            energy -= 0.005f
        }
    }

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {

            if (cm.tickRestriction[id] == 4) {
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

                    val baseDirX = dx / distance
                    val baseDirY = dy / distance

                    val angleRad = (cm.angle[id] + 180) * (PI / 180).toFloat()
                    val cosA = cos(angleRad)
                    val sinA = sin(angleRad)

                    // Рассчитываем направление движения
                    val directionX = baseDirX * cosA - baseDirY * sinA
                    val directionY = baseDirX * sinA + baseDirY * cosA
                    if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать")

                    val cell = drawThickLineGridTraversalCell(
                        x1 = cm.x[id] + directionX * 21f,
                        y1 = cm.y[id] + directionY * 21f,
                        x2 = cm.x[id] + directionX * 180f,
                        y2 = cm.y[id] + directionY * 180f,
                        cm = cm,
                    )
                    val subXEnd = if (cell != null) {
                        cell.second.first + directionX * 10f
                    } else cm.x[id] + directionX * 180f
                    val subYEnd = if (cell != null) {
                        cell.second.second + directionY * 10f
                    } else cm.y[id] + directionY * 180f
                    val sub = drawThickLineGridTraversalSub(
                        x1 = cm.x[id] + directionX * 21f,
                        y1 = cm.y[id] + directionY * 21f,
                        x2 = subXEnd,
                        y2 = subYEnd,
                        cm = cm,
                    )

                    cm.neuronImpulseImport[id] = when {
                        cell == null && sub == null -> 0f
                        cell != null && sub == null -> {
                            (cm.colorR[cell.first] + cm.colorR[cell.first] + cm.colorR[cell.first]) / 3
                        }

                        cell == null && sub != null -> {
                            (cm.subManager.colorR[sub.first] + cm.subManager.colorR[sub.first] + cm.subManager.colorR[sub.first]) / 3
                        }

                        cell != null && sub != null -> {
                            if (cell.third > sub.third) {
                                (cm.subManager.colorR[sub.first] + cm.subManager.colorR[sub.first] + cm.subManager.colorR[sub.first]) / 3
                            } else {
                                (cm.colorR[cell.first] + cm.colorR[cell.first] + cm.colorR[cell.first]) / 3
                            }
                        }

                        else -> 0f
                    }


                    cm.energy[id] -= 0.005f
                }
            } else {
                cm.tickRestriction[id] += 1
            }
        }
    }

}

fun drawThickLineGridTraversalSub(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    pixelSize: Float = 10f,
    cm: CellManager
): Triple<Int, Pair<Float, Float>, Float>? {
    visitedGrid.clear()

    // Перевод координат в индексы ячеек
    var gx = floor(x1 / pixelSize).toInt()
    var gy = floor(y1 / pixelSize).toInt()
    val gxEnd = floor(x2 / pixelSize).toInt()
    val gyEnd = floor(y2 / pixelSize).toInt()
    visitedGrid.offsetX = min(gx, gxEnd) - 1
    visitedGrid.offsetY = min(gy, gyEnd) - 1

    val dx = x2 - x1
    val dy = y2 - y1

    val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
    val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0

    val tDeltaX = if (dx == 0f) Float.POSITIVE_INFINITY else abs(pixelSize / dx)
    val tDeltaY = if (dy == 0f) Float.POSITIVE_INFINITY else abs(pixelSize / dy)

    val xBound = (gx + if (stepX > 0) 1 else 0) * pixelSize
    val yBound = (gy + if (stepY > 0) 1 else 0) * pixelSize

    var tMaxX = if (dx == 0f) Float.POSITIVE_INFINITY else abs((xBound - x1) / dx)
    var tMaxY = if (dy == 0f) Float.POSITIVE_INFINITY else abs((yBound - y1) / dy)

    val dirLength = 1.0f / invSqrt(dx * dx + dy * dy)
    val normX = -dy / dirLength
    val normY = dx / dirLength
    var objectsCount = 0

    while (true) {
        // Центр и 2 соседние ячейки по нормали для толщины = 3
        checkedObjectListId.fill(-1)
        for (i in -1..1) {
            val nx = gx + round(normX * i).toInt()
            val ny = gy + round(normY * i).toInt()
            if (!visitedGrid.isVisited(nx, ny)) {
                visitedGrid.markVisited(nx, ny)
                val subCellId = cm.subManager.substanceIdMap.get(nx, ny)
                if (subCellId != -1) {
                    for (subI in subCellId * MAX_SUB_CELL_COUNT..<subCellId * MAX_SUB_CELL_COUNT + cm.subManager.amountInCell[subCellId]) {
                        if (!cm.subManager.isNeedToMove[subI]) {
                            if (isSegmentIntersectingCircle(
                                    x1,
                                    y1,
                                    x2,
                                    y2,
                                    cm.subManager.x[subI],
                                    cm.subManager.y[subI],
                                    r = 5f
                                )
                            ) {
                                if (objectsCount < 10) {
                                    checkedObjectListId[objectsCount] = subI
                                    objectsCount++
                                }
                            }
                        }
                    }
                }
            }
        }
        var nearestId = -1
        var nearestHitX = 0f
        var nearestHitY = 0f
        var minDistSq = Float.MAX_VALUE

        for (i in 0 until objectsCount) {
            val index = checkedObjectListId[i]
            val hit = segmentCircleIntersection(
                x1, y1, x2, y2,
                cm.subManager.x[index], cm.subManager.y[index],
                r = 5f
            )
            if (hit != null) {
                val (hx, hy) = hit
                val dxD = hx - x1
                val dyD = hy - y1
                val distSq = dxD * dxD + dyD * dyD
                if (distSq < minDistSq) {
                    minDistSq = distSq
                    nearestId = index
                    nearestHitX = hx
                    nearestHitY = hy
                }
            }
        }
        if (nearestId != -1) {
            return Triple(nearestId, Pair(nearestHitX, nearestHitY), minDistSq)
        }

        if (gx == gxEnd && gy == gyEnd) break

        if (tMaxX < tMaxY) {
            tMaxX += tDeltaX
            gx += stepX
        } else {
            tMaxY += tDeltaY
            gy += stepY
        }
    }
    return null
}


fun drawThickLineGridTraversalCell(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    pixelSize: Float = 40f,
    cm: CellManager
): Triple<Int, Pair<Float, Float>, Float>? {
    visitedGrid.clear()

    // Перевод координат в индексы ячеек
    var gx = floor(x1 / pixelSize).toInt()
    var gy = floor(y1 / pixelSize).toInt()
    val gxEnd = floor(x2 / pixelSize).toInt()
    val gyEnd = floor(y2 / pixelSize).toInt()
    visitedGrid.offsetX = min(gx, gxEnd) - 1
    visitedGrid.offsetY = min(gy, gyEnd) - 1

    val dx = x2 - x1
    val dy = y2 - y1

    val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
    val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0

    val tDeltaX = if (dx == 0f) Float.POSITIVE_INFINITY else abs(pixelSize / dx)
    val tDeltaY = if (dy == 0f) Float.POSITIVE_INFINITY else abs(pixelSize / dy)

    val xBound = (gx + if (stepX > 0) 1 else 0) * pixelSize
    val yBound = (gy + if (stepY > 0) 1 else 0) * pixelSize

    var tMaxX = if (dx == 0f) Float.POSITIVE_INFINITY else abs((xBound - x1) / dx)
    var tMaxY = if (dy == 0f) Float.POSITIVE_INFINITY else abs((yBound - y1) / dy)

    val dirLength = 1.0f / invSqrt(dx * dx + dy * dy)
    val normX = -dy / dirLength
    val normY = dx / dirLength
    var objectsCount = 0

    while (true) {
        // Центр и 2 соседние ячейки по нормали для толщины = 3
        checkedObjectListId.fill(-1)
        for (i in -1..1) {
            val nx = gx + round(normX * i).toInt()
            val ny = gy + round(normY * i).toInt()
            if (!visitedGrid.isVisited(nx, ny)) {
                visitedGrid.markVisited(nx, ny)
                val items = cm.gridManager.getCells(nx, ny)
                if (items.isNotEmpty()) {
                    for (index in items) {
                        if (isSegmentIntersectingCircle(x1, y1, x2, y2, cm.x[index], cm.y[index], r = 20f)) {
                            if (objectsCount < 10) {
                                checkedObjectListId[objectsCount] = index
                                objectsCount++
                            }
                        }
                    }
                }
            }
        }

        var nearestId = -1
        var nearestHitX = 0f
        var nearestHitY = 0f
        var minDistSq = Float.MAX_VALUE

        for (i in 0 until objectsCount) {
            val index = checkedObjectListId[i]
            val hit = segmentCircleIntersection(
                x1, y1, x2, y2,
                cm.x[index], cm.y[index],
                r = 20f
            )
            if (hit != null) {
                val (hx, hy) = hit
                val dxD = hx - x1
                val dyD = hy - y1
                val distSq = dxD * dxD + dyD * dyD
                if (distSq < minDistSq) {
                    minDistSq = distSq
                    nearestId = index
                    nearestHitX = hx
                    nearestHitY = hy
                }
            }
        }

        if (nearestId != -1) {
            return Triple(nearestId, Pair(nearestHitX, nearestHitY), minDistSq)
        }

        if (gx == gxEnd && gy == gyEnd) break

        if (tMaxX < tMaxY) {
            tMaxX += tDeltaX
            gx += stepX
        } else {
            tMaxY += tDeltaY
            gy += stepY
        }
    }
    return null
}

fun segmentCircleIntersection(
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    cx: Float, cy: Float,
    r: Float
): Pair<Float, Float>? {
    val dx = x2 - x1
    val dy = y2 - y1

    val fx = x1 - cx
    val fy = y1 - cy

    val a = dx * dx + dy * dy
    val b = 2 * (fx * dx + fy * dy)
    val c = fx * fx + fy * fy - r * r

    val discriminant = b * b - 4 * a * c
    if (discriminant < 0) return null // нет пересечения

    val sqrtDisc = sqrt(discriminant)
    val t1 = (-b - sqrtDisc) / (2 * a)
    val t2 = (-b + sqrtDisc) / (2 * a)

    val t = when {
        t1 in 0f..1f -> t1
        t2 in 0f..1f -> t2
        else -> return null
    }

    val ix = x1 + t * dx
    val iy = y1 + t * dy
    return ix to iy
}

fun isSegmentIntersectingCircle(
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    cx: Float, cy: Float,
    r: Float = 20f
): Boolean {
    val dx = x2 - x1
    val dy = y2 - y1
    val fx = cx - x1
    val fy = cy - y1

    val lenSq = dx * dx + dy * dy
    val t = ((fx * dx + fy * dy) / lenSq).coerceIn(0f, 1f)

    val closestX = x1 + t * dx
    val closestY = y1 + t * dy

    val distSq = (closestX - cx).pow(2) + (closestY - cy).pow(2)
    return distSq <= r * r
}


//TODO отдельный для каждого потока
val visitedGrid = VisitedGrid()
val checkedObjectListId = IntArray(10) { -1 }

class VisitedGrid(val size: Int = 22) {
    private val grid = BooleanArray(size * size) // Одномерный массив 400 элементов
    var offsetX = 0
    var offsetY = 0

    fun markVisited(x: Int, y: Int) {
        grid[(y - offsetY) * size + (x - offsetX)] = true
    }

    fun isVisited(x: Int, y: Int): Boolean {
        return grid[(y - offsetY) * size + (x - offsetX)]
    }

    fun clear() {
        grid.fill(false) // Быстрая очистка
    }
}

