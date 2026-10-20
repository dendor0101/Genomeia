package io.github.some_example_name.old.editor.system

import kotlinx.serialization.Serializable
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.gridHeight
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.gridWidth
import io.github.some_example_name.old.core.utils.START_EDITOR_CELL_X
import io.github.some_example_name.old.core.utils.START_EDITOR_CELL_Y
import io.github.some_example_name.old.editor.geometry.CellPlacement
import io.github.some_example_name.old.editor.entities.EditorCell
import io.github.some_example_name.old.entities.ParticleEntity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

private const val AXIAL_LINE_Y = 64f
private const val EPSILON = 0.001f

class SymmetryManager(
    val particleEntity: ParticleEntity,
    val cellSearchManager: CellSearchManager
) {

    var symmetryMode: SymmetryMode = NoSymmetry

    fun drawSymmetry(shapeRenderer: ShapeRenderer) {
        val symmetryMode = symmetryMode
        when (symmetryMode) {
            Axial -> {
                shapeRenderer.line(
                    0f,
                    gridHeight.toFloat() / 2f,
                    gridWidth.toFloat(),
                    gridHeight.toFloat() / 2f
                )
            }
            NoSymmetry -> {
                return
            }
            is SquareGrid -> {
                val step = symmetryMode.step
                val ox = symmetryMode.offsetX
                val oy = symmetryMode.offsetY

                // === ВЕРТИКАЛЬНЫЕ ЛИНИИ ===
                var i = floor(((0f - ox) / step)).toInt()   // начинаем с первой линии слева от 0
                while (true) {
                    val x = ox + i * step
                    if (x > gridWidth) break
                    if (x >= 0f) {
                        shapeRenderer.line(x, 0f, x, gridHeight.toFloat())
                    }
                    i++
                }

                // === ГОРИЗОНТАЛЬНЫЕ ЛИНИИ ===
                i = floor(((0f - oy) / step)).toInt()
                while (true) {
                    val y = oy + i * step
                    if (y > gridHeight) break
                    if (y >= 0f) {
                        shapeRenderer.line(0f, y, gridWidth.toFloat(), y)
                    }
                    i++
                }
            }
            is TriangleGrid -> {
                val step = symmetryMode.step
                val ox = symmetryMode.offsetX
                val oy = symmetryMode.offsetY

                val regionSize = 20f          // ← именно та область 20f, которую ты просил
                val h = step * (sqrt(3f) / 2f) // высота маленького треугольника

                // Ограничиваем количество линий — теперь их будет очень мало (обычно 3–15 в каждом направлении)
                val maxLines = (regionSize / h * 1.1f + 2f).toInt() // запас, чтобы покрыть ±20f

                // === 1. ГОРИЗОНТАЛЬНЫЕ ЛИНИИ (короткие отрезки вокруг ox, oy) ===
                val iMin = -maxLines
                val iMax = maxLines
                for (i in iMin..iMax) {
                    val y = oy + i * h
                    if (y < 0f || y > gridHeight) continue

                    // рисуем только в области ±regionSize по X
                    val xLeft = (ox - regionSize).coerceAtLeast(0f)
                    val xRight = (ox + regionSize).coerceAtMost(gridWidth.toFloat())
                    if (xLeft < xRight) {
                        shapeRenderer.line(xLeft, y, xRight, y)
                    }
                }

                // === 2. ДИАГОНАЛЬНЫЕ ЛИНИИ / (60°) ===
                val m1 = sqrt(3f)
                val db1 = step * sqrt(3f)
                val b0 = oy - m1 * ox

                // тот же диапазон k — количество линий минимально
                for (k in -maxLines..maxLines) {
                    val b = b0 + k * db1

                    // короткий отрезок по X вокруг ox (y автоматически будет рядом с oy)
                    val xLeft =
                        (ox - regionSize * 1.15f).coerceAtLeast(0f)   // небольшой запас для диагоналей
                    val xRight = (ox + regionSize * 1.15f).coerceAtMost(gridWidth.toFloat())

                    val yLeft = m1 * xLeft + b
                    val yRight = m1 * xRight + b

                    // рисуем только если отрезок хотя бы немного попадает на экран
                    if ((yLeft in 0f..gridHeight.toFloat() || yRight in 0f..gridHeight.toFloat()) ||
                        (yLeft < 0f && yRight > 0f) || (yLeft > gridHeight && yRight < gridHeight)
                    ) {
                        shapeRenderer.line(xLeft, yLeft, xRight, yRight)
                    }
                }

                // === 3. ДИАГОНАЛЬНЫЕ ЛИНИИ \ (-60°) ===
                val m2 = -sqrt(3f)
                val db2 = step * sqrt(3f)
                val b0_2 = oy - m2 * ox

                for (k in -maxLines..maxLines) {
                    val b = b0_2 + k * db2

                    val xLeft = (ox - regionSize * 1.15f).coerceAtLeast(0f)
                    val xRight = (ox + regionSize * 1.15f).coerceAtMost(gridWidth.toFloat())

                    val yLeft = m2 * xLeft + b
                    val yRight = m2 * xRight + b

                    if ((yLeft in 0f..gridHeight.toFloat() || yRight in 0f..gridHeight.toFloat()) ||
                        (yLeft < 0f && yRight > 0f) || (yLeft > gridHeight && yRight < gridHeight)
                    ) {
                        shapeRenderer.line(xLeft, yLeft, xRight, yRight)
                    }
                }
            }
        }
    }

    fun snapPosition(
        x: Float,
        y: Float,
        cellIndex: Int
    ): Pair<Float, Float> {
        val symmetryMode = symmetryMode
        return when (symmetryMode) {
            Axial -> {
                findAxialSymmetryPoint(Pair(x, y),  cellIndex)
            }
            NoSymmetry -> Pair(x, y)
            is SquareGrid -> {
                val step = symmetryMode.step
                val ox = symmetryMode.offsetX
                val oy = symmetryMode.offsetY

                val resultX = ((x - ox) / step).roundToInt() * step + ox
                val resultY = ((y - oy) / step).roundToInt() * step + oy

                if (isFreePosition(resultX, resultY, cellIndex)) {
                    Pair(resultX, resultY)
                } else Pair(x, y)
            }
            is TriangleGrid -> {
                val step = symmetryMode.step
                val ox = symmetryMode.offsetX
                val oy = symmetryMode.offsetY

                val rowHeight = step * (sqrt(3f) / 2f)

                // staggered rows (сдвиг на половину стороны в нечётных рядах)
                val row = round((y - oy) / rowHeight).toLong()
                val resultY = oy + row * rowHeight

                val xShift = if (row % 2L == 0L) 0f else step / 2f

                val resultX = ox + xShift + round((x - ox - xShift) / step) * step

                if (isFreePosition(resultX, resultY, cellIndex)) {
                    Pair(resultX, resultY)
                } else Pair(x, y)
            }
        }
    }

    private fun findAxialSymmetryPoint(
        it: Pair<Float, Float>,
        cellIndex: Int?
    ): Pair<Float, Float> {
        val distToLine = abs(it.second - AXIAL_LINE_Y)

        return if (distToLine <= 0.2f) {
            Pair(it.first, AXIAL_LINE_Y)           // прилипание к оси
        } else {
            val searchSymmetryX = it.first
            val searchSymmetryY = 2f * AXIAL_LINE_Y - it.second

            val symmetryCellIndex = cellSearchManager.getClickedCellIndex(
                clickX = searchSymmetryX,
                clickY = searchSymmetryY
            )?.first

            val result = if (symmetryCellIndex != null) {
                val y = 2f * AXIAL_LINE_Y - particleEntity.y[symmetryCellIndex]

                Pair(particleEntity.x[symmetryCellIndex], y)
            } else it

            if (isFreePosition(result.first, result.second, cellIndex)) {
                Pair(result.first, result.second)
            } else it
        }
    }

    /**
     * Место для дочери радиуса [childRadius] у клетки [clickedCell] с учётом симметрии.
     * Сетки (квадратная, треугольная) ставят дочь в свой узел, какого бы радиуса она ни была:
     * шаг сетки игрок выбирает сам.
     */
    fun newPoint(
        clickedCell: EditorCell,
        childRadius: Float,
        neighbours: PlacementNeighbours
    ): Pair<Float, Float>? {
        val symmetryMode = symmetryMode

        return when (symmetryMode) {
            Axial -> {
                findOptimalPosition(clickedCell, childRadius, neighbours)?.let {
                    findAxialSymmetryPoint(it, null)
                }
            }

            NoSymmetry -> findOptimalPosition(clickedCell, childRadius, neighbours)

            is SquareGrid -> {
                val step = symmetryMode.step
                val ox = symmetryMode.offsetX
                val oy = symmetryMode.offsetY
                val cx = clickedCell.x
                val cy = clickedCell.y

                // 8 соседних позиций (включая диагонали) — все точно на сетке
                val candidates = listOf(
                    Pair(cx - step, cy - step),
                    Pair(cx, cy - step),
                    Pair(cx + step, cy - step),
                    Pair(cx - step, cy),
                    Pair(cx + step, cy),
                    Pair(cx - step, cy + step),
                    Pair(cx, cy + step),
                    Pair(cx + step, cy + step)
                )

                findBestFreePosition(candidates, neighbours, ox, oy)
            }

            is TriangleGrid -> {
                val step = symmetryMode.step
                val ox = symmetryMode.offsetX
                val oy = symmetryMode.offsetY
                val cx = clickedCell.x
                val cy = clickedCell.y

                val rowHeight = step * (sqrt(3f) / 2f)

                // 6 соседних вершин равностороннего треугольника — все точно на сетке
                val candidates = listOf(
                    Pair(cx + step, cy),               // 0°
                    Pair(cx - step, cy),               // 180°
                    Pair(cx + step / 2f, cy + rowHeight),   // 60°
                    Pair(cx - step / 2f, cy + rowHeight),   // 120°
                    Pair(cx + step / 2f, cy - rowHeight),   // -60°
                    Pair(cx - step / 2f, cy - rowHeight)    // -120°
                )

                findBestFreePosition(candidates, neighbours, ox, oy)
            }
        }
    }

    /**
     * Как далеко от родителя лежат узлы-кандидаты сетки (с запасом): соседей для проверки
     * занятости узла надо собирать хотя бы так далеко. Без сетки — 0.
     */
    fun candidateReach(): Float = when (val mode = symmetryMode) {
        is SquareGrid -> mode.step * 1.5f
        is TriangleGrid -> mode.step * 1.1f
        else -> 0f
    }

    private fun findOptimalPosition(
        clickedCell: EditorCell,
        childRadius: Float,
        neighbours: PlacementNeighbours
    ): Pair<Float, Float>? = CellPlacement.findPosition(
        parentX = clickedCell.x,
        parentY = clickedCell.y,
        parentRadius = clickedCell.radius,
        childRadius = childRadius,
        nx = neighbours.x,
        ny = neighbours.y,
        nr = neighbours.r,
        count = neighbours.count,
        targetX = START_EDITOR_CELL_X,
        targetY = START_EDITOR_CELL_Y
    )

    private fun findBestFreePosition(
        candidates: List<Pair<Float, Float>>,
        neighbours: PlacementNeighbours,
        targetX: Float,
        targetY: Float
    ): Pair<Float, Float>? {
        var best: Pair<Float, Float>? = null
        var bestDistSq = Float.MAX_VALUE

        for (cand in candidates) {
            // === ПРОВЕРКА ЗАНЯТОСТИ ЧЕРЕЗ РАССТОЯНИЕ (а не ==) ===
            var occupied = false
            for (i in 0 until neighbours.count) {
                if (abs(neighbours.x[i] - cand.first) < EPSILON && abs(neighbours.y[i] - cand.second) < EPSILON) {
                    occupied = true
                    break
                }
            }
            if (occupied) continue

            // расстояние до offset (64, 64)
            val dx = cand.first - targetX
            val dy = cand.second - targetY
            val distSq = dx * dx + dy * dy

            if (distSq < bestDistSq) {
                bestDistSq = distSq
                best = cand
            }
        }

        return best
    }


    private fun isFreePosition(
        x: Float,
        y: Float,
        itselfIndex: Int?
    ): Boolean {
        val cellIndex = cellSearchManager.getClickedCellIndex(
            clickX = x,
            clickY = y,
            itselfIndex = itselfIndex
        )?.first ?: return true

        return !(abs(particleEntity.x[cellIndex] - x) < EPSILON && abs(particleEntity.y[cellIndex] - y) < EPSILON)
    }
}

@Serializable
sealed class SymmetryMode

@Serializable
object NoSymmetry : SymmetryMode()
@Serializable
data class SquareGrid(
    val step: Float,
    val offsetX: Float = 64f,
    val offsetY: Float = 64f
) : SymmetryMode()

@Serializable
object Axial : SymmetryMode()
@Serializable
data class TriangleGrid(
    val step: Float,
    val offsetX: Float = 64f,
    val offsetY: Float = 64f
) : SymmetryMode()
