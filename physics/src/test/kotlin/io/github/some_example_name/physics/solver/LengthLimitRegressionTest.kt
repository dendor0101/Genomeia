package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test

class LengthLimitRegressionTest {
    private fun pair(): BodyState {
        val body = BodyDefinition(
            floatArrayOf(0f, 1f), FloatArray(2), FloatArray(2) { 0.5f },
            BooleanArray(2), BooleanArray(2), intArrayOf(0), intArrayOf(1),
            IntArray(0), IntArray(0), IntArray(0), FloatArray(0),
            emptyArray(), emptyArray(), 1f,
        )
        return BodyState().also { it.quiet = true; it.initialize(body); it.resetState() }
    }

    // Ровно 1.5 не проверяется: во float порог LINK_MAX_STRETCH + LINK_TEAR_STRAIN = 1.05f + 0.45f = 1.4999999
    // (сумма двух неточных констант), и на самой границе решает округление, а не закон. Граница — 1.499 и 1.501.
    @Test fun ordinaryLinkTearsOnlyAbove150Percent() {
        for (distance in floatArrayOf(1.26f, 1.499f, 1.501f)) {
            val s = pair()
            s.px[0] = 0.0f
            s.px[1] = distance
            s.py.fill(0.0f)
            s.tearingOn = true
            s.solveLinkMaxLength()
            assertEquals("distance=$distance", distance > 1.5f, s.conDead[0])
        }
    }

    @Test fun relaxedMuscleOnlyYieldsUnderTension() {
        fun afterPull(signal: Float, distance: Float, soft: Boolean): Float {
            val s = pair()
            s.cellScalesOn = true
            s.softnessOn = soft
            s.conCellScale[0] = 1f + signal * 0.5f
            s.px[1] = distance
            s.solveConstraints(DT / SUBSTEPS)
            return s.px[1] - s.px[0]
        }
        for (soft in listOf(false, true)) {
            assertEquals(1.0f, afterPull(1f, 1.0f, soft), F_POS)
            assertEquals(0.5f, afterPull(-1f, 0.5f, soft), F_POS)
            assertTrue(afterPull(-1f, 1.0f, soft) < 1.0f)
            assertEquals(afterPull(0f, 0.95f, soft), afterPull(1f, 0.95f, soft), F_POS)
            val neutral = afterPull(0f, 1.04f, soft)
            val partial = afterPull(0.5f, 1.04f, soft)
            val relaxed = afterPull(1f, 1.04f, soft)
            assertTrue(neutral < partial && partial < relaxed && relaxed < 1.04f)
        }
    }

    @Test fun relaxingDoesNotRaiseTheTearThreshold() {
        for (distance in floatArrayOf(1.249f, 1.25f, 1.251f, 1.499f, 1.501f)) {   // 1.5 — см. ordinaryLinkTearsOnlyAbove150Percent
            val s = pair()
            s.cellScalesOn = true
            s.conCellScale[0] = 1.5f
            s.px[1] = distance
            s.tearingOn = true
            s.solveLinkMaxLength()
            assertEquals(distance > 1.5f, s.conDead[0])
            if (!s.conDead[0]) assertEquals(minOf(distance, 1.25f), s.px[1] - s.px[0], F_POS)
        }
    }

    @Test fun relaxedInteriorLinkAlsoRespects125Percent() {
        val s = pair()
        s.cellScalesOn = true
        s.conCellScale[0] = 1.5f
        s.conBoundary[0] = false
        s.px[1] = 1.5f
        s.solveConstraints(DT / SUBSTEPS)
        assertTrue(s.px[1] - s.px[0] in 1.0..1.25)
    }

    @Test fun immovablePairNeitherTearsNorChangesExtrema() {
        // Проверка масс перенесена за быстрый выход. Даже при нарушенном пределе
        // неподвижная пара должна остаться невидимой и для разрыва, и для диагностики.
        for (diagnostics in booleanArrayOf(false, true)) {
            for (distance in floatArrayOf(0.0f, LINK_MAX_STRETCH + 1.0f)) {
                val s = pair()
                s.invMass.fill(0.0f)
                s.px[0] = 0.0f; s.px[1] = distance
                s.py.fill(0.0f)
                s.tearingOn = true
                s.dbgLinkExtremaOn = diagnostics
                s.solveLinkMaxLength()
                assertEquals(0.0f.toRawBits(), s.px[0].toRawBits())
                assertEquals(distance.toRawBits(), s.px[1].toRawBits())
                assertFalse(s.conDead[0])
                assertFalse(s.linkTorn[0])
                assertFalse(s.tearsPending)
                assertEquals(0, s.linkCapHits)
                assertEquals(1.0f, s.dbgWorstCrush2, 0.0f)
                assertEquals(1.0f, s.dbgWorstStretch2, 0.0f)
            }
        }
    }
}
