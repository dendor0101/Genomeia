package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Directed
import io.github.some_example_name.old.world_logic.cells.base.Neural
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.good_one.utils.invSqrt
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.GridManager
import io.github.some_example_name.old.world_logic.ThreadManager.Companion.THREAD_COUNT
import java.util.BitSet
import kotlin.math.*

class Eye : Cell(), Neural, Directed {
    companion object {
        fun specificToThisType(cm: CellManager, id: Int, threadId: Int) {
            //TODO Переделать на сектора - это светочуствительный рецептор, а не лазер // Rework into sectors is a light-sensitive receptor, not a laser
            if (cm.tickRestriction[id] == 4) {
                cm.tickRestriction[id] = 0
                if (cm.energy[id] > 0) {
                    val angleRad = cm.angle[id]

                    // Рассчитываем направление движения
                    val directionX = cos(angleRad)
                    val directionY = sin(angleRad)
                    if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать Eye")

                    val visonDist = cm.visibilityRange[id]

                    val cell = drawThickLineGridTraversalCell(
                        x1 = cm.x[id] + directionX * 21f,
                        y1 = cm.y[id] + directionY * 21f,
                        x2 = cm.x[id] + directionX * visonDist,
                        y2 = cm.y[id] + directionY * visonDist,
                        pixelSize = GridManager.CELL_SIZE,
                        cm = cm,
                        threadId = threadId
                    )

                    val visibleColor = cm.colorDifferentiation[id]

                    val impulse = if (cell != null) {
                        val index = cell.first
                        visibleColor.averageVisibleColor(
                            cm.colorR[index],
                            cm.colorG[index],
                            cm.colorB[index]
                        )
                    } else 0f

                    cm.neuronImpulseOutput[id] = activation(cm, id, impulse)

                    cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].energyActionCost
                }
            } else {
                cm.tickRestriction[id] += 1
            }
        }
    }

}

fun Int.averageVisibleColor(r: Float, g: Float, b: Float): Float {
    var sum = 0f
    var count = 0

    if (this and 1 != 0) {
        sum += r
        count++
    }
    if (this and 2 != 0) {
        sum += g
        count++
    }
    if (this and 4 != 0) {
        sum += b
        count++
    }

    return if (count > 0) sum / count else 0f
}

fun drawThickLineGridTraversalCell(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    pixelSize: Float = GridManager.CELL_SIZE,
    cm: CellManager,
    threadId: Int
): Triple<Int, Pair<Float, Float>, Float>? {
    visitedBits[threadId].clear()

    // Перевод координат в индексы ячеек // Converting coordinates to cell indices
    var gx = floor(x1 / pixelSize).toInt()
    var gy = floor(y1 / pixelSize).toInt()
    val gxEnd = floor(x2 / pixelSize).toInt()
    val gyEnd = floor(y2 / pixelSize).toInt()

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
    val gridWidth = cm.gridManager.gridCellWidthSize
    val gridHeight = cm.gridManager.gridCellHeightSize

    while (true) {
        if (gx < 0 || gx >= gridWidth || gy < 0 || gy >= gridHeight) break

        // Центр и 2 соседние ячейки по нормали для толщины = 3
        // Center and 2 adjacent cells along the normal for thickness = 3
        checkedObjectListId[threadId].fill(-1)
        objectsCount = 0
        for (i in -1..1) {
            val nx = gx + round(normX * i).toInt()
            val ny = gy + round(normY * i).toInt()
            if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue
            val pack = nx * gridHeight + ny
            if (!visitedBits[threadId].get(pack)) {
                visitedBits[threadId].set(pack)
                val items = cm.gridManager.getCells(nx, ny)
                if (items.isNotEmpty()) {
                    for (index in items) {
                        if (isSegmentIntersectingCircle(
                                x1,
                                y1,
                                x2,
                                y2,
                                cm.x[index],
                                cm.y[index],
                                r = 20f
                            )
                        ) {
                            if (objectsCount < checkedObjectListId[threadId].size) {
                                checkedObjectListId[threadId][objectsCount] = index
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
            val index = checkedObjectListId[threadId][i]
            val hit = segmentCircleIntersection(
                x1, y1, x2, y2,
                cm.x[index], cm.y[index],
                r = 20f,
                threadId = threadId
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
    r: Float,
    threadId: Int
): Pair<Float, Float>? {
    val dx = x2 - x1
    val dy = y2 - y1

    val fx = x1 - cx
    val fy = y1 - cy

    val a = dx * dx + dy * dy
    val b = 2 * (fx * dx + fy * dy)
    val c = fx * fx + fy * fy - r * r

    val discriminant = b * b - 4 * a * c
    if (discriminant < 0) return null // нет пересечения // no intersection

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


val visitedBits = Array(THREAD_COUNT) { BitSet(GridManager.GRID_SIZE) }
val checkedObjectListId =  Array(THREAD_COUNT) { IntArray(16) { -1 } }  // Увеличен размер для безопасности
