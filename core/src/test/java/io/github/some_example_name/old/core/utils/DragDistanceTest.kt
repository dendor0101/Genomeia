package io.github.some_example_name.old.core.utils

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/** Перетаскивание дочерней клетки: пределы до родителя — в долях суммы радиусов. */
class DragDistanceTest {

    private fun distance(p: Pair<Float, Float>) = sqrt(p.first * p.first + p.second * p.second)

    @Test
    fun maxRadiusKeepsOldLimits() {
        assertEquals(0.75f, distance(setMinMaxDistForChildCellToParent(5f, 0f, 0.5f, 0f, 0f, 0.5f)), 1e-5f)
        assertEquals(0.125f, distance(setMinMaxDistForChildCellToParent(0.01f, 0f, 0.5f, 0f, 0f, 0.5f)), 1e-5f)
    }

    @Test
    fun smallCellsStayTouching() {
        // Две клетки по 0.2: дальше 0.75 × 0.4 = 0.3 не уходят, то есть остаются внахлёст.
        val far = setMinMaxDistForChildCellToParent(0f, 3f, 0.2f, 0f, 0f, 0.2f)
        assertEquals(0.3f, distance(far), 1e-5f)
        assertEquals(0f, far.first, 1e-6f)
        // Мелкая у крупной: 0.75 × 0.7.
        assertEquals(0.525f, distance(setMinMaxDistForChildCellToParent(-2f, 0f, 0.2f, 0f, 0f, 0.5f)), 1e-5f)
        // Внутри пределов — не трогаем.
        assertEquals(0.25f to 0f, setMinMaxDistForChildCellToParent(0.25f, 0f, 0.2f, 0f, 0f, 0.2f))
    }

    @Test
    fun centreOfParentDoesNotGiveNaN() {
        val p = setMinMaxDistForChildCellToParent(1f, 1f, 0.2f, 1f, 1f, 0.2f)
        assertEquals(1.05f, p.first, 1e-5f)
        assertEquals(1f, p.second)
    }
}
