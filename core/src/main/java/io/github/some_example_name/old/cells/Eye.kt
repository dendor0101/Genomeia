package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.invSqrt
import io.github.some_example_name.old.core.utils.skyBlueColors
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.features.editor.dialog.EyeSection
import io.github.some_example_name.old.systems.genomics.genome.Action
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

class Eye(cellTypeId: Int): Cell(
    key = "eye",
    defaultColor = skyBlueColors[2],
    cellTypeId = cellTypeId,
    isNeural = true,
    isNeuronTransportable = false,
    isDirected = true
) {

    override fun editorSections() = super.editorSections() + EyeSection

    /**
     * Параметры глаза в геноме лежат в общих полях Action (colorRecognition, lengthDirected —
     * так они записаны во всех геномах), а у клетки — объектом EyeData. Объект на клетку
     * создаётся при делении — событие, а не тик.
     */
    override fun createSpecialData(action: Action): SpecialModData =
        if (action.colorRecognition == null && action.lengthDirected == null) EyeData.DEFAULT
        else EyeData(action.colorRecognition ?: EyeData.DEFAULT.colorRecognition, action.lengthDirected ?: EyeData.DEFAULT.visibilityRange)

    /** Мутация глаза в глаз меняет только заданные в геноме поля, остальные — как были. */
    override fun mutateSpecialData(current: SpecialModData, action: Action): SpecialModData? {
        if (action.colorRecognition == null && action.lengthDirected == null) return null
        val eye = current as? EyeData ?: EyeData.DEFAULT
        return EyeData(action.colorRecognition ?: eye.colorRecognition, action.lengthDirected ?: eye.visibilityRange)
    }

    lateinit var visitedBits: Array<BitSet>
    lateinit var checkedObjectListId: Array<IntArray>

    override fun doOnTick(cellIndex: Int, threadId: Int): Unit = with(cellEntity) {
        //TODO у глаза будет другая система с диапозоном
        if (simulationData.tickCounter % 4 == 0) {
            if (energy[cellIndex] > 0) {

                val eye = specialEntity.getSpecialData(cellIndex) as? EyeData ?: return
                val index = firstVisibleParticle(cellIndex, eye.visibilityRange, threadId)

                val visibleColor = eye.colorRecognition

                val impulse = if (index >= 0) {
                    val color = particleEntity.color[index]
                    val r: Float = (color and 0xFF) / 255f
                    val g: Float = ((color ushr 8) and 0xFF) / 255f
                    val b: Float = ((color ushr 16) and 0xFF) / 255f
                    val a: Float = ((color ushr 24) and 0xFF) / 255f


                    visibleColor.averageVisibleColor(
                        r,
                        g,
                        b
                    )
                } else 0f

                neuronImpulseOutput[cellIndex] = activation(cellIndex, impulse)

                energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
            }
        }
    }



    /** Первая видимая частица. Диагностика макета пользуется тем же лучом, что и нейронный выход глаза. */
    fun firstVisibleParticle(cellIndex: Int, range: Float, threadId: Int): Int = with(cellEntity) {
        val x = getX(cellIndex)
        val y = getY(cellIndex)
        val dx = angleCos[cellIndex]
        val dy = angleSin[cellIndex]
        drawThickLineGridTraversalCell(
            x + dx * 0.51f, y + dy * 0.51f, x + dx * range, y + dy * range, 1f, threadId
        )?.first ?: -1
    }

    private fun Int.averageVisibleColor(r: Float, g: Float, b: Float): Float {
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

    private fun drawThickLineGridTraversalCell(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        pixelSize: Float,
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

        // invSqrt возвращает 1/длину — им и надо умножать. Раньше здесь было
        // 1f / invSqrt(...) (то есть деление, чтобы получить длину) и потом ещё два
        // деления на неё же: три деления там, где достаточно двух умножений.
        val invDirLength = invSqrt(dx * dx + dy * dy)
        val normX = -dy * invDirLength
        val normY = dx * invDirLength

        var objectsCount = 0
        val gridWidth = gridManager.gridWidth
        val gridHeight = gridManager.gridHeight

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
                    // Обход без аллокации: раньше getParticles копировал слоты клетки
                    // в новый IntArray на каждый шаг DDA (3 клетки на шаг).
                    gridManager.forEachParticleAt(nx, ny) { index ->
                        if (isSegmentIntersectingCircle(
                                x1,
                                y1,
                                x2,
                                y2,
                                particleEntity.x(index),
                                particleEntity.y(index),
                                r = particleEntity.radius[index]
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

            var nearestId = -1
            var nearestHitX = 0f
            var nearestHitY = 0f
            var minDistSq = Float.MAX_VALUE

            for (i in 0 until objectsCount) {
                val index = checkedObjectListId[threadId][i]
                val hit = segmentCircleIntersection(
                    x1, y1, x2, y2,
                    particleEntity.x(index), particleEntity.y(index),
                    r = particleEntity.radius[index],
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

    private fun segmentCircleIntersection(
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
        if (discriminant < 0) return null

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

    private fun isSegmentIntersectingCircle(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        cx: Float, cy: Float,
        r: Float
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

}

/**
 * Свои данные глаза: какие цвета он различает (биты r = 1, g = 2, b = 4) и как далеко видит.
 * Неизменяемые: меняет их только мутация, новым объектом в слоте клетки.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("eye")
data class EyeData(
    @ProtoNumber(1) val colorRecognition: Int = 7,
    @ProtoNumber(2) val visibilityRange: Float = 4.25f
) : SpecialModData {
    companion object {
        val DEFAULT = EyeData()
    }
}

/** Данные глаза для рендера и редактора; не глаз (данных нет) — значения по умолчанию. */
fun SpecialEntity.eyeData(cellIndex: Int): EyeData = getSpecialData(cellIndex) as? EyeData ?: EyeData.DEFAULT
