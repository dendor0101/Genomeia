package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * ПАКЕТ ЖЁСТКИХ ПОПРАВОК КОСТИ: импульсы, пришедшие в клетки кости, складываются в импульс и момент
 * твёрдого тела и раздаются всем клеткам разом.
 *
 *  - Позиции: перенос на J/M и поворот на угол (r × J)/I вокруг центра масс — КОНЕЧНЫЙ поворот,
 *    расстояния между клетками сохраняются при любом угле.
 *  - Скорости: v += J/M + ω × r.
 *  - Центр масс кости после позиционной поправки едет вместе с костью.
 *  - Кость без массы и кость без импульса не трогаются; закреплённая клетка стоит.
 */
class RigidContactBatchTest {
    // Четыре клетки кости разной массы и одна посторонняя клетка, в кость не входящая.
    private val x0 = doubleArrayOf(0.0, 2.0, 2.0, 0.0, 9.0)
    private val y0 = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 9.0)
    private val w = doubleArrayOf(1.0, 0.5, 2.0, 1.0, 1.0)
    private val ids = arrayOf(intArrayOf(0, 1, 2, 3))

    private class Bone(val mass: DoubleArray, val inertia: DoubleArray, val cx: DoubleArray, val cy: DoubleArray)

    private fun bone(x: DoubleArray, y: DoubleArray): Bone {
        var m = 0.0; var cx = 0.0; var cy = 0.0
        for (i in ids[0]) { m += 1 / w[i]; cx += x[i] / w[i]; cy += y[i] / w[i] }
        cx /= m; cy /= m
        var inertia = 0.0
        for (i in ids[0]) inertia += ((x[i] - cx) * (x[i] - cx) + (y[i] - cy) * (y[i] - cy)) / w[i]
        return Bone(doubleArrayOf(m), doubleArrayOf(inertia), doubleArrayOf(cx), doubleArrayOf(cy))
    }

    @Test fun `позиционная поправка - перенос и конечный поворот, расстояния между клетками сохраняются`() {
        for (push in doubleArrayOf(0.01, 0.4, 3.0)) {
            val x = x0.copyOf(); val y = y0.copyOf()
            val b = bone(x, y)
            val cx = b.cx[0]; val cy = b.cy[0]
            val batch = RigidContactBatch()
            batch.begin(1)
            // Импульс в клетку 1, не через центр масс: и перенос, и поворот.
            val jx = -0.3 * push; val jy = push
            batch.add(0, x[1] - cx, y[1] - cy, jx, jy)
            batch.apply(ids, b.mass, b.inertia, b.cx, b.cy, x, y, w, x, y, positions = true)
            val angle = ((x0[1] - cx) * jy - (y0[1] - cy) * jx) / b.inertia[0]
            for (i in ids[0]) {
                val rx = x0[i] - cx; val ry = y0[i] - cy
                assertEquals("клетка $i, x", cx + jx / b.mass[0] + rx * cos(angle) - ry * sin(angle), x[i], 1e-12)
                assertEquals("клетка $i, y", cy + jy / b.mass[0] + rx * sin(angle) + ry * cos(angle), y[i], 1e-12)
            }
            for (i in ids[0]) for (j in ids[0]) assertEquals("расстояние $i–$j при толчке $push",
                hypot(x0[i] - x0[j], y0[i] - y0[j]), hypot(x[i] - x[j], y[i] - y[j]), 1e-12)
            assertEquals("посторонняя клетка стоит", 9.0, x[4], 0.0)
            assertEquals("центр масс кости уехал вместе с костью (x)", cx + jx / b.mass[0], b.cx[0], 1e-12)
            assertEquals("центр масс кости уехал вместе с костью (y)", cy + jy / b.mass[0], b.cy[0], 1e-12)
            // Центр масс по новым позициям — тот же.
            val after = bone(x, y)
            assertEquals(b.cx[0], after.cx[0], 1e-12); assertEquals(b.cy[0], after.cy[0], 1e-12)
        }
    }

    @Test fun `скоростная поправка - импульс и момент твёрдого тела, центр не трогается`() {
        val x = x0.copyOf(); val y = y0.copyOf()
        val vx = DoubleArray(5); val vy = DoubleArray(5)
        val b = bone(x, y)
        val cx = b.cx[0]; val cy = b.cy[0]
        val batch = RigidContactBatch()
        batch.begin(1)
        batch.add(0, x[1] - cx, y[1] - cy, 0.2, 0.7)
        batch.add(0, x[3] - cx, y[3] - cy, -0.5, 0.1)
        val omega = ((x[1] - cx) * 0.7 - (y[1] - cy) * 0.2 + (x[3] - cx) * 0.1 - (y[3] - cy) * -0.5) / b.inertia[0]
        // Скорость точки кости, набранная пакетом, — до раздачи клеткам.
        assertEquals(-0.3 / b.mass[0] - omega * (y[2] - cy), batch.velocityX(0, y[2] - cy, b.mass[0], b.inertia[0]), 1e-12)
        assertEquals(0.8 / b.mass[0] + omega * (x[2] - cx), batch.velocityY(0, x[2] - cx, b.mass[0], b.inertia[0]), 1e-12)
        batch.apply(ids, b.mass, b.inertia, b.cx, b.cy, x, y, w, vx, vy, positions = false)
        var px = 0.0; var py = 0.0; var l = 0.0
        for (i in ids[0]) {
            assertEquals("клетка $i, vx", -0.3 / b.mass[0] - omega * (y[i] - cy), vx[i], 1e-12)
            assertEquals("клетка $i, vy", 0.8 / b.mass[0] + omega * (x[i] - cx), vy[i], 1e-12)
            px += vx[i] / w[i]; py += vy[i] / w[i]; l += ((x[i] - cx) * vy[i] - (y[i] - cy) * vx[i]) / w[i]
        }
        assertEquals("импульс кости — сумма толчков", -0.3, px, 1e-12); assertEquals(0.8, py, 1e-12)
        assertEquals("момент импульса кости — сумма моментов толчков", omega * b.inertia[0], l, 1e-12)
        assertEquals("скоростная поправка центр не двигает", cx, b.cx[0], 0.0)
        assertEquals("позиции не тронуты", x0[2], x[2], 0.0)
        assertEquals(0.0, vx[4], 0.0)
    }

    @Test fun `кость без импульса, кость без массы и закреплённая клетка не трогаются, новый проход начинается с нуля`() {
        val x = x0.copyOf(); val y = y0.copyOf()
        val b = bone(x, y)
        val batch = RigidContactBatch()
        batch.begin(1)
        batch.apply(ids, b.mass, b.inertia, b.cx, b.cy, x, y, w, x, y, positions = true)
        for (i in 0 until 5) { assertEquals(x0[i], x[i], 0.0); assertEquals(y0[i], y[i], 0.0) }
        // Кость без массы (все клетки погибли): импульс есть, двигать нечего.
        batch.add(0, 1.0, 0.0, 0.5, 0.5)
        batch.apply(ids, doubleArrayOf(0.0), b.inertia, b.cx, b.cy, x, y, w, x, y, positions = true)
        for (i in 0 until 5) assertEquals(x0[i], x[i], 0.0)
        // Закреплённая клетка кости (обратная масса ноль) стоит, остальные едут.
        val pinned = w.copyOf().also { it[0] = 0.0 }
        batch.apply(ids, b.mass, b.inertia, b.cx, b.cy, x, y, pinned, x, y, positions = true)
        assertEquals(x0[0], x[0], 0.0); assertEquals(y0[0], y[0], 0.0)
        assertEquals(true, x[1] != x0[1])
        // Новый проход: прежние импульсы забыты.
        val x2 = x0.copyOf(); val y2 = y0.copyOf(); val b2 = bone(x2, y2)
        batch.begin(1)
        batch.apply(ids, b2.mass, b2.inertia, b2.cx, b2.cy, x2, y2, w, x2, y2, positions = true)
        for (i in 0 until 5) assertEquals(x0[i], x2[i], 0.0)
        // Вырожденная кость без момента инерции (все клетки в одной точке) — только перенос.
        val px = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 9.0); val py = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 9.0)
        val point = bone(px, py)
        batch.begin(1)
        batch.add(0, 0.0, 0.0, 0.6, 0.0)
        batch.apply(ids, point.mass, point.inertia, point.cx, point.cy, px, py, w, px, py, positions = true)
        for (i in ids[0]) { assertEquals(1.0 + 0.6 / point.mass[0], px[i], 1e-12); assertEquals(1.0, py[i], 0.0) }
    }
}
