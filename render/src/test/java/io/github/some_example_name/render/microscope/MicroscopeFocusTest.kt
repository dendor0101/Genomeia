package io.github.some_example_name.render.microscope

import org.junit.Test
import kotlin.math.abs
import kotlin.test.*

class MicroscopeFocusTest {
    @Test fun focusWaitsAfterZoomThenOvershootsAndSettles() {
        val f = MicroscopeFocus()
        f.update(1f/60, 0f, 0f, .5f, true)
        f.update(1f/60, 0f, .4f, .5f, true)
        assertTrue(f.hunting)
        val displaced = f.position
        repeat(12) { f.update(1f/60, 0f, 0f, .5f, true) }
        assertEquals(displaced, f.position)
        var crossed = false
        repeat(240) {
            f.update(1f/60, 0f, 0f, .5f, true)
            crossed = crossed || f.position < .5f
        }
        assertTrue(crossed, "The spring must cross focus, not just ease toward it")
        assertEquals(.5f, f.position, .00001f)
        assertFalse(f.hunting)
    }

    @Test fun disabledFocusIsImmediate() {
        val f = MicroscopeFocus()
        assertEquals(.545f, f.update(1f/60, 100f, .5f, .545f, false))
        assertEquals(.545f, f.update(1f/60, 0f, 0f, .545f, true))
        assertFalse(f.hunting)
    }
}
