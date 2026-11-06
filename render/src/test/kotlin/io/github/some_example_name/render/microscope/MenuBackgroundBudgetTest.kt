package io.github.some_example_name.render.microscope

import kotlin.test.*

class MenuBackgroundBudgetTest {
    @Test fun fastGpuIgnoresIsolatedStalls() {
        val budget = MenuBackgroundBudget()
        repeat(89) { assertFalse(budget.shouldMeasure(1920, 1080, true)) }
        assertTrue(budget.shouldMeasure(1920, 1080, true))
        doubleArrayOf(.4, .5, 100.0, .5, .6, 20.0, .4).forEach(budget::record)
        assertTrue(budget.decided)
        assertFalse(budget.useDebug)
        assertEquals(.5, budget.medianMillis)
        repeat(200) { assertFalse(budget.shouldMeasure(1920, 1080, true)) }
    }

    @Test fun slowGpuStaysOnDebugAcrossVisitsAndResize() {
        val budget = MenuBackgroundBudget()
        repeat(7) { budget.record(9.0) }
        assertTrue(budget.useDebug)
        budget.beginVisit()
        repeat(200) { assertFalse(budget.shouldMeasure(2560, 1440, true)) }
        assertTrue(budget.useDebug)
    }

    @Test fun changedResolutionRestartsWarmupAndSampling() {
        val budget = MenuBackgroundBudget()
        repeat(90) { budget.shouldMeasure(900, 900, true) }
        repeat(7) { budget.record(2.0) }
        assertTrue(budget.decided)
        assertFalse(budget.shouldMeasure(3440, 1440, true))
        assertFalse(budget.decided)
        repeat(88) { assertFalse(budget.shouldMeasure(3440, 1440, true)) }
        assertTrue(budget.shouldMeasure(3440, 1440, true))
        repeat(7) { budget.record(10.0) }
        assertTrue(budget.useDebug)
    }

    @Test fun unreadyFramesDoNotCountAndProbesAreSpaced() {
        val budget = MenuBackgroundBudget()
        repeat(1000) { assertFalse(budget.shouldMeasure(900, 900, false)) }
        var probes = 0
        repeat(162) { if (budget.shouldMeasure(900, 900, true)) probes++ }
        assertEquals(7, probes)
    }
}
