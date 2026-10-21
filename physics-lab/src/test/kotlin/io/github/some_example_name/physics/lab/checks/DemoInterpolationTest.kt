package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.window.DemoInterpolation
import io.github.some_example_name.physics.BodyState
import org.junit.Assert.*
import org.junit.Test

class DemoInterpolationTest {
    private fun state() = BodyState().also {
        it.n = 1; it.px = doubleArrayOf(2.0); it.py = doubleArrayOf(4.0)
    }

    @Test fun headlessSessionHasNoHistoryObserver() {
        val session = DemoSession("unused-until-load")
        assertNull(session.simulation.observer)
    }

    @Test fun endpointsAndModeSwitchDoNotReadUnwrittenSubsteps() {
        val state = state()
        val history = DemoInterpolation(state)
        assertEquals(0, history.allocatedSlots)
        history.beginTick()
        repeat(SUBSTEPS) { step ->
            // Одинаковое линейное движение 2→18 при любом числе подшагов.
            // Числа ожидаемой картинки ниже остаются прежними.
            state.px[0] = 2.0 + 16.0 * (step + 1) / SUBSTEPS
            state.py[0] = 2 * state.px[0]
            history.afterSubstep(step)
        }
        assertEquals(2, history.allocatedSlots)
        history.prepare(0.25)
        assertEquals(6f.toRawBits(), history.x(0).toRawBits())
        assertEquals(12f.toRawBits(), history.y(0).toRawBits())
        history.mode = 2
        history.prepare(0.5)
        assertEquals(10f.toRawBits(), history.x(0).toRawBits())
        assertEquals(2, history.allocatedSlots)
        history.beginTick()
        assertEquals(SUBSTEPS + 1, history.allocatedSlots)
    }

    @Test fun detailedHistoryFollowsSubstepAndResetImmediatelyShowsCurrentPose() {
        val state = state()
        val history = DemoInterpolation(state)
        history.mode = 2
        state.px[0] = 0.0; state.py[0] = 0.0
        history.beginTick()
        repeat(SUBSTEPS) { step ->
            state.px[0] = 16.0 * (step + 1) / SUBSTEPS; state.py[0] = -state.px[0]
            history.afterSubstep(step)
        }
        history.prepare(0.3)
        assertEquals(4.8f.toRawBits(), history.x(0).toRawBits())
        assertEquals((-4.8f).toRawBits(), history.y(0).toRawBits())
        state.px[0] = 100.0
        history.invalidate()
        history.prepare(0.5)
        assertEquals(100f.toRawBits(), history.x(0).toRawBits())
    }

    @Test fun disabledHistoryDoesNotAllocate() {
        val history = DemoInterpolation(state())
        history.mode = 0
        history.beginTick()
        repeat(SUBSTEPS) { history.afterSubstep(it) }
        assertEquals(0, history.allocatedSlots)
    }
}
