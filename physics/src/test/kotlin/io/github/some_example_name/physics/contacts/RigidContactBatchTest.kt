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
    private val x0 = floatArrayOf(0.0f, 2.0f, 2.0f, 0.0f, 9.0f)
    private val y0 = floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f, 9.0f)
    private val w = floatArrayOf(1.0f, 0.5f, 2.0f, 1.0f, 1.0f)
    private val ids = arrayOf(intArrayOf(0, 1, 2, 3))

    private class Bone(val mass: FloatArray, val inertia: FloatArray, val cx: FloatArray, val cy: FloatArray)

    private fun bone(x: FloatArray, y: FloatArray): Bone {
        var m = 0.0f; var cx = 0.0f; var cy = 0.0f
        for (i in ids[0]) { m += 1 / w[i]; cx += x[i] / w[i]; cy += y[i] / w[i] }
        cx /= m; cy /= m
        var inertia = 0.0f
        for (i in ids[0]) inertia += ((x[i] - cx) * (x[i] - cx) + (y[i] - cy) * (y[i] - cy)) / w[i]
        return Bone(floatArrayOf(m), floatArrayOf(inertia), floatArrayOf(cx), floatArrayOf(cy))
    }

    @Test fun `позиционная поправка - перенос и конечный поворот, расстояния между клетками сохраняются`() {
        for (push in floatArrayOf(0.01f, 0.4f, 3.0f)) {
            val x = x0.copyOf(); val y = y0.copyOf()
            val b = bone(x, y)
            val cx = b.cx[0]; val cy = b.cy[0]
            val batch = RigidContactBatch(SPIN_MIN_ARM * T_LINK)
            batch.begin(1)
            // Импульс в клетку 1, не через центр масс: и перенос, и поворот.
            val jx = -0.3f * push; val jy = push
            batch.add(0, x[1] - cx, y[1] - cy, jx, jy)
            batch.apply(ids, b.mass, b.inertia, b.cx, b.cy, x, y, w, x, y, positions = true)
            val angle = ((x0[1] - cx) * jy - (y0[1] - cy) * jx) / b.inertia[0]
            for (i in ids[0]) {
                val rx = x0[i] - cx; val ry = y0[i] - cy
                assertEquals("клетка $i, x", cx + jx / b.mass[0] + rx * cos(angle) - ry * sin(angle), x[i], F_POS)
                assertEquals("клетка $i, y", cy + jy / b.mass[0] + rx * sin(angle) + ry * cos(angle), y[i], F_POS)
            }
            for (i in ids[0]) for (j in ids[0]) assertEquals("расстояние $i–$j при толчке $push",
                hypot(x0[i] - x0[j], y0[i] - y0[j]), hypot(x[i] - x[j], y[i] - y[j]), F_POS)
            assertEquals("посторонняя клетка стоит", 9.0f, x[4], 0.0f)
            assertEquals("центр масс кости уехал вместе с костью (x)", cx + jx / b.mass[0], b.cx[0], F_POS)
            assertEquals("центр масс кости уехал вместе с костью (y)", cy + jy / b.mass[0], b.cy[0], F_POS)
            // Центр масс по новым позициям — тот же.
            val after = bone(x, y)
            assertEquals(b.cx[0], after.cx[0], F_POS); assertEquals(b.cy[0], after.cy[0], F_POS)
        }
    }

    @Test fun `скоростная поправка - импульс и момент твёрдого тела, центр не трогается`() {
        val x = x0.copyOf(); val y = y0.copyOf()
        val vx = FloatArray(5); val vy = FloatArray(5)
        val b = bone(x, y)
        val cx = b.cx[0]; val cy = b.cy[0]
        val batch = RigidContactBatch(SPIN_MIN_ARM * T_LINK)
        batch.begin(1)
        batch.add(0, x[1] - cx, y[1] - cy, 0.2f, 0.7f)
        batch.add(0, x[3] - cx, y[3] - cy, -0.5f, 0.1f)
        val omega = ((x[1] - cx) * 0.7f - (y[1] - cy) * 0.2f + (x[3] - cx) * 0.1f - (y[3] - cy) * -0.5f) / b.inertia[0]
        // Скорость точки кости, набранная пакетом, — до раздачи клеткам.
        assertEquals(-0.3f / b.mass[0] - omega * (y[2] - cy), batch.velocityX(0, y[2] - cy, b.mass[0], b.inertia[0]), F_POS)
        assertEquals(0.8f / b.mass[0] + omega * (x[2] - cx), batch.velocityY(0, x[2] - cx, b.mass[0], b.inertia[0]), F_POS)
        batch.apply(ids, b.mass, b.inertia, b.cx, b.cy, x, y, w, vx, vy, positions = false)
        var px = 0.0f; var py = 0.0f; var l = 0.0f
        for (i in ids[0]) {
            assertEquals("клетка $i, vx", -0.3f / b.mass[0] - omega * (y[i] - cy), vx[i], F_POS)
            assertEquals("клетка $i, vy", 0.8f / b.mass[0] + omega * (x[i] - cx), vy[i], F_POS)
            px += vx[i] / w[i]; py += vy[i] / w[i]; l += ((x[i] - cx) * vy[i] - (y[i] - cy) * vx[i]) / w[i]
        }
        assertEquals("импульс кости — сумма толчков", -0.3f, px, F_POS); assertEquals(0.8f, py, F_POS)
        assertEquals("момент импульса кости — сумма моментов толчков", omega * b.inertia[0], l, F_POS)
        assertEquals("скоростная поправка центр не двигает", cx, b.cx[0], 0.0f)
        assertEquals("позиции не тронуты", x0[2], x[2], 0.0f)
        assertEquals(0.0f, vx[4], 0.0f)
    }

    @Test fun `кость без импульса, кость без массы и закреплённая клетка не трогаются, новый проход начинается с нуля`() {
        val x = x0.copyOf(); val y = y0.copyOf()
        val b = bone(x, y)
        val batch = RigidContactBatch(SPIN_MIN_ARM * T_LINK)
        batch.begin(1)
        batch.apply(ids, b.mass, b.inertia, b.cx, b.cy, x, y, w, x, y, positions = true)
        for (i in 0 until 5) { assertEquals(x0[i], x[i], 0.0f); assertEquals(y0[i], y[i], 0.0f) }
        // Кость без массы (все клетки погибли): импульс есть, двигать нечего.
        batch.add(0, 1.0f, 0.0f, 0.5f, 0.5f)
        batch.apply(ids, floatArrayOf(0.0f), b.inertia, b.cx, b.cy, x, y, w, x, y, positions = true)
        for (i in 0 until 5) assertEquals(x0[i], x[i], 0.0f)
        // Закреплённая клетка кости (обратная масса ноль) стоит, остальные едут.
        val pinned = w.copyOf().also { it[0] = 0.0f }
        batch.apply(ids, b.mass, b.inertia, b.cx, b.cy, x, y, pinned, x, y, positions = true)
        assertEquals(x0[0], x[0], 0.0f); assertEquals(y0[0], y[0], 0.0f)
        assertEquals(true, x[1] != x0[1])
        // Новый проход: прежние импульсы забыты.
        val x2 = x0.copyOf(); val y2 = y0.copyOf(); val b2 = bone(x2, y2)
        batch.begin(1)
        batch.apply(ids, b2.mass, b2.inertia, b2.cx, b2.cy, x2, y2, w, x2, y2, positions = true)
        for (i in 0 until 5) assertEquals(x0[i], x2[i], 0.0f)
        // Вырожденная кость без момента инерции (все клетки в одной точке) — только перенос.
        val px = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f, 9.0f); val py = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f, 9.0f)
        val point = bone(px, py)
        batch.begin(1)
        batch.add(0, 0.0f, 0.0f, 0.6f, 0.0f)
        batch.apply(ids, point.mass, point.inertia, point.cx, point.cy, px, py, w, px, py, positions = true)
        for (i in ids[0]) { assertEquals(1.0f + 0.6f / point.mass[0], px[i], F_POS); assertEquals(1.0f, py[i], 0.0f) }
    }
}
