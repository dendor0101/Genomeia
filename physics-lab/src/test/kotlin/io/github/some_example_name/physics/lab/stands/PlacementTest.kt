package io.github.some_example_name.physics.lab.stands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Расстановка по настоящему контуру (Placement.kt) и основные свойства фигур. */
class PlacementTest {
    /** Наименьшее расстояние между центрами клеток двух фигур — по всем клеткам, без упрощения до контура. */
    private fun nearest(a: ShapeSpec, ax: Double, ay: Double, angleA: Double, b: ShapeSpec, bx: Double, by: Double, angleB: Double): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until a.count) {
            val x = ax + a.x[i] * cos(angleA) - a.y[i] * sin(angleA); val y = ay + a.x[i] * sin(angleA) + a.y[i] * cos(angleA)
            for (j in 0 until b.count) {
                val u = bx + b.x[j] * cos(angleB) - b.y[j] * sin(angleB); val v = by + b.x[j] * sin(angleB) + b.y[j] * cos(angleB)
                best = minOf(best, Math.hypot(x - u, y - v))
            }
        }
        return best
    }

    private val shapes = listOf(Shapes.square(6), Shapes.disc(4), Shapes.crescent(7), Shapes.uShape(12, 9, 3), Shapes.star(5, 8, 3), Shapes.rope(12), Shapes.comb(4, 2, 5, 3))

    @Test
    fun `между ближайшими клетками двух тел ровно заданный просвет`() {
        for (a in shapes) for (b in shapes) for (dist in doubleArrayOf(0.5, 0.8, 2.4)) {
            val apart = separation(a, b, dist)
            assertEquals("${a.id} и ${b.id}, просвет $dist", dist, nearest(a, 0.0, 0.0, 0.0, b, apart, 0.0, 0.0), 1e-9)
        }
    }

    @Test
    fun `поворот и сдвиг по высоте учитываются`() {
        val a = Shapes.crescent(7); val b = Shapes.uShape(12, 9, 3)
        val apart = separation(a, b, 1.0, offset = 1.3, angleA = 0.7, angleB = Math.PI / 2)
        assertEquals(1.0, nearest(a, 0.0, 1.3, 0.7, b, apart, 0.0, Math.PI / 2), 1e-9)
    }

    @Test
    fun `до первого касания телу лететь ровно зазор сцены`() {
        val w = StandWorld()
        val (a, b) = w.duel(Shapes.crescent(7), Shapes.crescent(7), 0.6)
        val ca = w.centerOf(a); val cb = w.centerOf(b)
        // Центр масс и середина рамки у полумесяца не совпадают: берём положение по первой клетке.
        val ax = w.gameX(a.particle(0)) - a.shape.x[0]; val bx = w.gameX(b.particle(0)) - b.shape.x[0]
        val travel = travelToContact(a.shape, ax, 0.0, 0.0, b.shape, bx, 0.0, 0.0, 1.0, 0.0)
        assertEquals(START_GAP, travel, 1e-6)
        assertTrue(ca[0] < cb[0])
    }

    @Test
    fun `тело за спиной не считается препятствием`() {
        val s = Shapes.square(6)
        assertTrue(travelToContact(s, 0.0, 0.0, 0.0, s, -20.0, 0.0, 0.0, 1.0, 0.0).isInfinite())
        assertFalse(travelToContact(s, 0.0, 0.0, 0.0, s, 20.0, 0.0, 0.0, 1.0, 0.0).isInfinite())
    }

    @Test
    fun `простые фигуры имеют задуманное число клеток и связей`() {
        // «Квадрат 6» — геометрический квадрат со стороной в пять связей на шестиугольной решётке:
        // пять рядов по пять-шесть клеток, а не 6×6 клеток.
        val square = Shapes.square(6)
        assertEquals(27, square.count)
        assertTrue(square.halfWidth <= 2.5 * GAME_LINK + 1e-9 && square.halfHeight <= 2.5 * GAME_LINK + 1e-9)
        assertEquals(12 * 3, Shapes.rect(12, 3).count)
        assertEquals(12, Shapes.rope(12).count)
        assertEquals(11, Shapes.rope(12).linkCount)
        assertFalse("у нити нет ткани", Shapes.rope(12).hasTissue)
        assertFalse("нить не жёсткая", Shapes.rope(12).rigid)
        assertTrue(Shapes.square(6).rigid)
        // Шестиугольник радиуса r на решётке: 3r(r+1)+1 клеток — проверка, что круг не потерял ряд.
        assertTrue(Shapes.disc(3).count in 31..43)
    }

    @Test
    fun `порядок роста обходит все клетки, и каждая рождается от уже рождённой соседки`() {
        for (shape in shapes) {
            val g = shape.growthFrom(shape.nearest(0.0, 0.0))
            val born = BooleanArray(shape.count)
            born[g.order[0]] = true
            for (k in 1 until shape.count) {
                val cell = g.order[k]
                assertTrue("${shape.id}: клетка $cell рождается раньше родителя", born[g.parent[cell]])
                assertTrue("${shape.id}: родитель клетки $cell ей не сосед", shape.linked(cell, g.parent[cell]))
                born[cell] = true
            }
            assertTrue(born.all { it })
        }
    }

    @Test
    fun `кости меньше трёх клеток не остаются`() {
        for (shape in boneShapes()) for (cluster in shape.boneClusters()) assertTrue("${shape.id}: кость из ${cluster.size} клеток", cluster.size >= 3)
    }
}
