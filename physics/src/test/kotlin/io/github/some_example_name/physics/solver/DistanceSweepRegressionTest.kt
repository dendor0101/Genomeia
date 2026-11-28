package io.github.some_example_name.physics

import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceSweepRegressionTest {
    private fun state() = BodyState().also {
        it.n = 5
        it.px = FloatArray(5); it.py = FloatArray(5); it.invMass = FloatArray(5)
        // Повторы первого конца, второго конца, пары и петля. Следующая серия
        // также читает вершину, которую предыдущая серия меняла вторым концом.
        it.conA = intArrayOf(0, 0, 0, 1, 1, 1, 3, 3, 2, 2, 2, 4)
        it.conB = intArrayOf(1, 2, 1, 0, 1, 3, 4, 0, 3, 0, 4, 2)
        it.conCount = it.conA.size
        it.conRest = FloatArray(it.conCount) { 0.75f }
        it.conMuscle = IntArray(it.conCount) { c -> if (c % 3 == 0) 0 else -1 }
        it.muscleScaleOf = floatArrayOf(1.0f)
        it.conDead = BooleanArray(it.conCount)
    }

    @Test fun bothSweepDirectionsPreserveScalarUpdateOrder() {
        val old = state(); val current = state()
        val random = Random(9786041L)
        repeat(250) { sample ->
            for (i in 0 until old.n) {
                val x = if (sample % 5 == 0) -0.0f else random.nextDouble().toFloat() * 4.0f
                val y = if (sample % 5 == 0) 0.0f else random.nextDouble().toFloat() * 4.0f
                val w = if (random.nextInt(4) == 0) 0.0f else random.nextDouble().toFloat() + 0.1f
                old.px[i] = x; current.px[i] = x
                old.py[i] = y; current.py[i] = y
                old.invMass[i] = w; current.invMass[i] = w
            }
            val scale = random.nextDouble().toFloat() * 0.8f + 0.2f
            old.muscleScaleOf[0] = scale; current.muscleScaleOf[0] = scale
            for (c in 0 until old.conCount) {
                val dead = random.nextInt(5) == 0
                old.conDead[c] = dead; current.conDead[c] = dead
            }
            repeat(6) { pass ->
                old.sweepBackwards = pass % 2 == 0; current.sweepBackwards = old.sweepBackwards
                scalarReference(old, DT / SUBSTEPS)
                current.solveConstraints(DT / SUBSTEPS)
                for (i in 0 until old.n) {
                    assertEquals("sample=$sample pass=$pass x[$i]", old.px[i].toRawBits(), current.px[i].toRawBits())
                    assertEquals("sample=$sample pass=$pass y[$i]", old.py[i].toRawBits(), current.py[i].toRawBits())
                }
            }
        }
    }

    // Поштучная схема aed2e1b без группировки. Эталон порядка записей: особенно
    // важен общий конец двух соседних связей и смена направления обхода. Арифметика — как у ядра
    // (с 24.11 направление через 1/len, находка 41); проверяется порядок, а не формула.
    private fun scalarReference(s: BodyState, h: Float) {
        val alpha = SOFT_COMPLIANCE / (h * h)
        val order = if (s.sweepBackwards) s.conCount - 1 downTo 0 else 0 until s.conCount
        for (c in order) {
            if (s.conDead[c]) continue
            val i = s.conA[c]; val j = s.conB[c]
            val wi = s.invMass[i]; val wj = s.invMass[j]; val w = wi + wj
            if (w == 0.0f) continue
            var dx = s.px[i] - s.px[j]; var dy = s.py[i] - s.py[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-12f) continue
            val inv = 1.0f / len
            dx *= inv; dy *= inv
            val m = s.conMuscle[c]
            val scale = if (m < 0) 1.0f else s.muscleScaleOf[m]
            var over = len - s.conRest[c] * scale
            // Потолок тяги мышцы — как в ядре, см. MUSCLE_MAX_PULL.
            if (scale < 1.0f && over > MUSCLE_MAX_PULL * s.conRest[c]) over = MUSCLE_MAX_PULL * s.conRest[c]
            val dl = -over / (w + alpha)
            s.px[i] += dx * dl * wi; s.py[i] += dy * dl * wi
            s.px[j] -= dx * dl * wj; s.py[j] -= dy * dl * wj
        }
    }
}
