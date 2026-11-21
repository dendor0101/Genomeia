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

    @Test fun ordinaryLinkTearsOnlyAbove150Percent() {
        for (distance in doubleArrayOf(1.26, 1.499, 1.5, 1.501)) {
            val s = pair()
            s.px[0] = 0.0
            s.px[1] = distance
            s.py.fill(0.0)
            s.tearingOn = true
            s.solveLinkMaxLength()
            assertEquals("distance=$distance", distance > 1.5, s.conDead[0])
        }
    }

    @Test fun relaxedMuscleOnlyYieldsUnderTension() {
        fun afterPull(signal: Float, distance: Double, soft: Boolean): Double {
            val s = pair()
            s.cellScalesOn = true
            s.softnessOn = soft
            s.conCellScale[0] = 1f + signal * 0.5f
            s.px[1] = distance
            s.solveConstraints(DT / SUBSTEPS)
            return s.px[1] - s.px[0]
        }
        for (soft in listOf(false, true)) {
            assertEquals(1.0, afterPull(1f, 1.0, soft), 1e-12)
            assertEquals(0.5, afterPull(-1f, 0.5, soft), 1e-12)
            assertTrue(afterPull(-1f, 1.0, soft) < 1.0)
            assertEquals(afterPull(0f, 0.95, soft), afterPull(1f, 0.95, soft), 1e-12)
            val neutral = afterPull(0f, 1.04, soft)
            val partial = afterPull(0.5f, 1.04, soft)
            val relaxed = afterPull(1f, 1.04, soft)
            assertTrue(neutral < partial && partial < relaxed && relaxed < 1.04)
        }
    }

    @Test fun relaxingDoesNotRaiseTheTearThreshold() {
        for (distance in doubleArrayOf(1.249, 1.25, 1.251, 1.5, 1.501)) {
            val s = pair()
            s.cellScalesOn = true
            s.conCellScale[0] = 1.5f
            s.px[1] = distance
            s.tearingOn = true
            s.solveLinkMaxLength()
            assertEquals(distance > 1.5, s.conDead[0])
            if (!s.conDead[0]) assertEquals(minOf(distance, 1.25), s.px[1] - s.px[0], 1e-12)
        }
    }

    @Test fun relaxedInteriorLinkAlsoRespects125Percent() {
        val s = pair()
        s.cellScalesOn = true
        s.conCellScale[0] = 1.5f
        s.conBoundary[0] = false
        s.px[1] = 1.5
        s.solveConstraints(DT / SUBSTEPS)
        assertTrue(s.px[1] - s.px[0] in 1.0..1.25)
    }

    @Test fun immovablePairNeitherTearsNorChangesExtrema() {
        // Проверка масс перенесена за быстрый выход. Даже при нарушенном пределе
        // неподвижная пара должна остаться невидимой и для разрыва, и для диагностики.
        for (diagnostics in booleanArrayOf(false, true)) {
            for (distance in doubleArrayOf(0.0, LINK_MAX_STRETCH + 1.0)) {
                val s = pair()
                s.invMass.fill(0.0)
                s.px[0] = 0.0; s.px[1] = distance
                s.py.fill(0.0)
                s.tearingOn = true
                s.dbgLinkExtremaOn = diagnostics
                s.solveLinkMaxLength()
                assertEquals(0.0.toRawBits(), s.px[0].toRawBits())
                assertEquals(distance.toRawBits(), s.px[1].toRawBits())
                assertFalse(s.conDead[0])
                assertFalse(s.linkTorn[0])
                assertFalse(s.tearsPending)
                assertEquals(0, s.linkCapHits)
                assertEquals(1.0, s.dbgWorstCrush2, 0.0)
                assertEquals(1.0, s.dbgWorstStretch2, 0.0)
            }
        }
    }
}
