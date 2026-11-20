package io.github.some_example_name.old.editor.geometry

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Числа — из отчёта 17.11 (SM-S938B, сетка triangle(0.6) вокруг (64, 64)). */
class GridNodesTest {

    private val step = 0.6f
    private val rowHeight = step * (sqrt(3f) / 2f)

    private fun triangleAround(cx: Float, cy: Float) = floatArrayOf(
        cx + step, cy,
        cx - step, cy,
        cx + step / 2f, cy + rowHeight,
        cx - step / 2f, cy + rowHeight,
        cx + step / 2f, cy - rowHeight,
        cx - step / 2f, cy - rowHeight
    )

    /**
     * Первый повтор деления из отчёта: родитель стоит в 0.0023 от узла, его кандидат
     * (64.0023, 63.9987) смещён от клетки (64, 64) на те же 0.0023. Прежняя проверка
     * (совпадение до 0.001) ставила дочь поверх этой клетки.
     */
    @Test
    fun cellSlightlyOffNodeStillOwnsIt() {
        val parentX = 64.30227f
        val parentY = 63.47908f
        val nx = floatArrayOf(64.0f, 64.6f)
        val ny = floatArrayOf(64.0f, 64.0f)
        val chosen = assertNotNull(GridNodes.closestFree(triangleAround(parentX, parentY), step, nx, ny, 2, 64f, 64f))
        for (i in nx.indices) {
            val dx = chosen.first - nx[i]
            val dy = chosen.second - ny[i]
            assertTrue(sqrt(dx * dx + dy * dy) > step * 0.9f, "дочь $chosen встала на клетку (${nx[i]}, ${ny[i]})")
        }
    }

    /**
     * Пятый повтор: кандидат (64.3, 63.4804) точно в узле, а клетка там смещена на 0.0023.
     * Прежняя проверка выбирала его — он ближе всех к центру. Теперь он занят, и ближайшие
     * свободные — два узла x = 64.9 выше и ниже оси, на равном расстоянии от центра.
     */
    @Test
    fun nodeIsTakenByNeighbourSlightlyOffIt() {
        val nx = floatArrayOf(63.700005f, 63.400005f, 63.699997f, 64.30227f, 64.0f, 64.299995f)
        val ny = floatArrayOf(63.480385f, 64.0f, 64.519615f, 63.47908f, 64.0f, 64.519615f)
        val chosen = assertNotNull(GridNodes.closestFree(triangleAround(64.6f, 64.0f), step, nx, ny, nx.size, 64f, 64f))
        assertEquals(64.9f, chosen.first, 1e-4f)
        assertEquals(rowHeight, kotlin.math.abs(chosen.second - 64f), 1e-4f)
    }

    @Test
    fun noFreeNodeWhenRingIsFull() {
        val ring = triangleAround(64f, 64f)
        val nx = FloatArray(6) { ring[it * 2] + 0.01f }
        val ny = FloatArray(6) { ring[it * 2 + 1] - 0.01f }
        assertNull(GridNodes.closestFree(ring, step, nx, ny, 6, 64f, 64f))
    }

    /** Клетка у узла не занимает соседний узел: они в шаге друг от друга. */
    @Test
    fun cellOwnsOnlyItsNode() {
        val ring = triangleAround(64f, 64f)
        val nx = floatArrayOf(ring[0])
        val ny = floatArrayOf(ring[1])
        val chosen = assertNotNull(GridNodes.closestFree(ring, step, nx, ny, 1, ring[0], ring[1]))
        val dx = chosen.first - ring[0]
        val dy = chosen.second - ring[1]
        assertEquals(step, sqrt(dx * dx + dy * dy), 1e-4f)
    }
}
