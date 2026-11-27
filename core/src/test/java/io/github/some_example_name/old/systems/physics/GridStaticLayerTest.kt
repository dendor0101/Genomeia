package io.github.some_example_name.old.systems.physics

import io.github.some_example_name.old.core.DIContext
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/**
 * Неподвижный слой сетки (террейн, стенка у рамки — 27.11): раскладка по клеткам один раз,
 * обходы клетки и отрезка ряда, края сетки; перестраиваемая сетка его не видит.
 */
class GridStaticLayerTest {

    /** Сетке DIContext нужен только для смены размера мира — здесь его не зовут. */
    private val noContext = Proxy.newProxyInstance(
        DIContext::class.java.classLoader, arrayOf(DIContext::class.java)
    ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as DIContext

    /** Сетка 8×6; клетка c = y * 8 + x. */
    private fun grid() = GridManager(gridWidth = 8, gridHeight = 6, diContext = noContext, maxAmountOfParticles = 4)

    private fun staticAt(g: GridManager, x: Int, y: Int): List<Int> {
        val out = ArrayList<Int>()
        g.forEachStaticAt(x, y) { out.add(it) }
        return out
    }

    @Test
    fun particlesLandInTheirCellsInGivenOrder() {
        val g = grid()
        val cell = IntArray(20) { -1 }
        cell[5] = 1 * 8 + 2; cell[3] = 1 * 8 + 2; cell[9] = 0; cell[1] = 5 * 8 + 7
        g.buildStatic(intArrayOf(5, 3, 9, 1), 4, cell)

        assertEquals(4, g.staticCount)
        assertEquals(listOf(5, 3), staticAt(g, 2, 1), "в клетке — в порядке передачи")
        assertEquals(listOf(9), staticAt(g, 0, 0))
        assertEquals(listOf(1), staticAt(g, 7, 5), "последняя клетка сетки")
        assertEquals(emptyList(), staticAt(g, 3, 3))
        // За сеткой — ничего, без выхода за массив.
        assertEquals(emptyList(), staticAt(g, -1, 0))
        assertEquals(emptyList(), staticAt(g, 8, 5))
        assertEquals(emptyList(), staticAt(g, 0, 6))

        val row = ArrayList<Int>()
        g.forEachStaticInRowSegment(1, -3, 100) { row.add(it) }
        assertEquals(listOf(5, 3), row, "отрезок ряда зажат в сетку")
    }

    @Test
    fun bothLayersAreVisibleTogetherAndRebuildIgnoresStatic() {
        val g = grid()
        val alive = BooleanArray(20) { true }
        val inGrid = BooleanArray(20) { true }
        val cell = IntArray(20) { 2 * 8 + 4 }
        // Частица 7 подвижная, 8 — неподвижная в той же клетке: из перестраиваемой сетки вон.
        inGrid[8] = false
        g.registerParticle(7); g.registerParticle(8)
        g.rebuild(alive, cell, inGrid)
        g.buildStatic(intArrayOf(8), 1, cell)

        val dynamic = ArrayList<Int>()
        g.forEachParticleAt(4, 2) { dynamic.add(it) }
        assertEquals(listOf(7), dynamic, "перестраиваемая сетка неподвижную не держит")

        val both = ArrayList<Int>()
        g.forEachParticleOrStaticAt(4, 2) { both.add(it) }
        assertEquals(listOf(7, 8), both)

        // Пересборка тика неподвижный слой не трогает.
        g.rebuild(alive, cell, inGrid)
        assertEquals(listOf(8), staticAt(g, 4, 2))

        g.clearAll()
        assertEquals(0, g.staticCount)
        assertEquals(emptyList(), staticAt(g, 4, 2))
    }
}
