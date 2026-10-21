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
