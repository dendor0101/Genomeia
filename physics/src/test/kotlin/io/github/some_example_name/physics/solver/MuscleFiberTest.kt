package io.github.some_example_name.physics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

/**
 * Сокращение мышцы по волокну с утолщением поперёк (config/FiberParameters.kt, solver/MuscleFiber.kt): показатель
 * k = 0.5 — настройка мира FIBER_THICKEN для опытов (закон стендов до находки 34). Закон по умолчанию (k = 0, один
 * на игру и стенды) — FiberTest.
 */
class MuscleFiberTest {
    /** Масштаб мира игры: связь 0.6 и радиус 0.5 клетки игры при 0.048 единицы физики на клетку. */
    private val link = 0.0288f
    private val r = 0.024f
    /** Настройка мира опыта: показатель утолщения поперёк волокна k = 0.5. */
    private val stands = mapOf("FIBER_THICKEN" to "0.5f")
    private fun world(config: Map<String, String> = stands) = PhysicsWorld(PhysicsConfig(config), workers = null,
        scale = WorldScale(meanLinkLength = link.toFloat(), meanCellArea = r * r)).apply { state.quiet = true }

    private val cols = 6
    private val rows = 4

    /** Решётка cols × rows клеток: связи по x, по y и диагональ, по два треугольника на ячейку. */
    private fun grid(): BodyDefinition {
        val n = cols * rows
        fun id(c: Int, row: Int) = row * cols + c
        val la = ArrayList<Int>(); val lb = ArrayList<Int>()
        val ta = ArrayList<Int>(); val tb = ArrayList<Int>(); val tc = ArrayList<Int>(); val area = ArrayList<Float>()
        for (row in 0 until rows) for (c in 0 until cols) {
            if (c + 1 < cols) { la += id(c, row); lb += id(c + 1, row) }
            if (row + 1 < rows) { la += id(c, row); lb += id(c, row + 1) }
            if (c + 1 < cols && row + 1 < rows) {
                la += id(c, row); lb += id(c + 1, row + 1)
                ta += id(c, row); tb += id(c + 1, row); tc += id(c + 1, row + 1); area += (link * link).toFloat()
                ta += id(c, row); tb += id(c + 1, row + 1); tc += id(c, row + 1); area += (link * link).toFloat()
            }
        }
        return BodyDefinition(
            FloatArray(n) { (1.0f + (it % cols) * link).toFloat() }, FloatArray(n) { (1.0f + (it / cols) * link).toFloat() },
            FloatArray(n) { r.toFloat() }, BooleanArray(n), BooleanArray(n),
            la.toIntArray(), lb.toIntArray(), ta.toIntArray(), tb.toIntArray(), tc.toIntArray(), area.toFloatArray(),
            emptyArray(), emptyArray(), 1f,
        )
    }

    /** Тело-решётка, все клетки — мышца под командой [command]; [fiberX] — волокно вдоль x у всех клеток. */
    private fun contracted(w: PhysicsWorld, fiberX: Boolean, command: Float = 0.5f): Int {
        val first = w.bodyFirst(w.addBody(grid()))
        for (k in 0 until cols * rows) {
            if (fiberX) w.setCellFiber(first + k, 1f, 0f)
            w.setCellScale(first + k, command)
        }
        return first
    }

    /** Средняя длина связей решётки вдоль x и вдоль y, в длинах покоя. */
    private fun meanLengths(w: PhysicsWorld, first: Int): FloatArray {
        val st = w.state
        var sx = 0.0f; var nx = 0; var sy = 0.0f; var ny = 0
        for (row in 0 until rows) for (c in 0 until cols) {
            val i = first + row * cols + c
            if (c + 1 < cols) { val j = i + 1; sx += sqrt(sq(st.px[j] - st.px[i]) + sq(st.py[j] - st.py[i])); nx++ }
            if (row + 1 < rows) { val j = i + cols; sy += sqrt(sq(st.px[j] - st.px[i]) + sq(st.py[j] - st.py[i])); ny++ }
        }
        return floatArrayOf(sx / nx / link, sy / ny / link)
    }

    private fun sq(v: Float) = v * v

    /**
     * Волокно вдоль x: связи вдоль волокна укорачиваются к s = 0.5, поперёк — удлиняются к s^−k = √2 (k = 0.5), площадь
     * — к √0.5. Без волокна — сокращение по площади: обе стороны к 0.5.
     */
    @Test fun fiberShortensAlongAndThickensAcross() {
        val w = world()
        val first = contracted(w, fiberX = true)
        repeat(400) { w.step() }
        val (along, across) = meanLengths(w, first).let { it[0] to it[1] }
        println("волокно вдоль x: вдоль ${"%.3f".format(along)}, поперёк ${"%.3f".format(across)} длины покоя")
        assertTrue("вдоль волокна укорачивается: $along", along < 0.7f)
        assertTrue("поперёк волокна утолщается: $across", across > 1.2f)

        val v = world()
        val f2 = contracted(v, fiberX = false)
        repeat(400) { v.step() }
        val (x, y) = meanLengths(v, f2).let { it[0] to it[1] }
        println("без волокна: по x ${"%.3f".format(x)}, по y ${"%.3f".format(y)} длины покоя")
        assertTrue("без волокна сжимается и по x: $x", x < 0.7f)
        assertTrue("без волокна сжимается и по y: $y", y < 0.7f)
    }

    /** Мир из снимка посреди сокращения по волокну идёт дальше побитово так же, как мир, который снимок писал. */
    @Test fun snapshotKeepsFibers() {
        val a = world()
        val first = contracted(a, fiberX = true)
        repeat(40) { a.step() }
        val bytes = ByteArrayOutputStream().also { a.write(DataOutputStream(it)) }.toByteArray()
        val b = world()
        b.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertArrayEquals(a.state.cellFiberX, b.state.cellFiberX, 0f)
        assertArrayEquals(a.state.cellFiberY, b.state.cellFiberY, 0f)
        assertTrue(b.state.fibersOn)
        repeat(40) { a.step(); b.step() }
        assertArrayEquals("x после загрузки", a.state.px, b.state.px, 0.0f)
        assertArrayEquals("y после загрузки", a.state.py, b.state.py, 0.0f)
    }

    /**
     * Без волокон закон не действует: мир с законом стендов (k = 0.5) и с законом по умолчанию (k = 0) без волокон
     * побитово один и тот же — пока волокон нет, показатель ни на что не влияет.
     */
    @Test fun noFibersSameForAnyLaw() {
        val on = world()
        val f1 = contracted(on, fiberX = false)
        repeat(120) { on.step() }
        val px = on.state.px.copyOf(); val py = on.state.py.copyOf()
        val off = world(emptyMap())
        val f2 = contracted(off, fiberX = false)
        repeat(120) { off.step() }
        assertEquals(f1, f2)
        assertArrayEquals(px, off.state.px, 0.0f)
        assertArrayEquals(py, off.state.py, 0.0f)
    }

    /**
     * Закон — у каждого мира свой (BodyState.fiberThicken, настройка мира): два мира с разными k, которые шагают
     * вперемешку, идут побитово так же, как каждый в одиночку. До слияния 21.11 показатель был общим на процесс.
     */
    @Test fun lawIsPerWorld() {
        fun alone(config: Map<String, String>): FloatArray {
            val w = world(config); contracted(w, fiberX = true); repeat(60) { w.step() }; return w.state.px.copyOf()
        }
        val thick = alone(stands); val flat = alone(emptyMap())
        assertTrue("законы различаются", !thick.contentEquals(flat))
        val a = world(stands); val b = world(emptyMap())
        contracted(a, fiberX = true); contracted(b, fiberX = true)
        repeat(60) { a.step(); b.step() }
        assertArrayEquals("k = 0.5f рядом с k = 0", thick, a.state.px, 0.0f)
        assertArrayEquals("k = 0 рядом с k = 0.5f", flat, b.state.px, 0.0f)
    }
}
