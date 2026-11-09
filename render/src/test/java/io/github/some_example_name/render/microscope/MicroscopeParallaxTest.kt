package io.github.some_example_name.render.microscope

import org.junit.Test
import kotlin.test.*

class MicroscopeParallaxTest {
    @Test fun cameraPanMovesDepthLayersButZoomDoesNotAccumulateParallax() {
        val p = MicroscopeParallax()
        p.followCamera(800.0, 600.0, .01)
        p.update(1f/60)
        assertEquals(0f, p.x)
        p.followCamera(802.0, 599.0, .01)
        // Игровая камера уже переместилась: слои должны попасть на место сразу,
        // а не после секундного update, скрывавшего лишнее сглаживание.
        assertEquals(2f, p.x); assertEquals(-1f, p.y)
        for(dt in floatArrayOf(1f/30,1f/60,1f/144)) repeat(60) {
            p.followCamera(802.0,599.0,.01);p.update(dt)
            assertEquals(2f,p.x);assertEquals(-1f,p.y)
        }
        // Зум вокруг курсора тоже перемещает центр, но это не сдвиг препарата.
        p.followCamera(801.0, 598.0, .005)
        p.update(1f/60)
        assertEquals(2f, p.x); assertEquals(-1f, p.y)
        p.followCamera(802.0, 600.0, .005)
        p.update(1f/60)
        assertEquals(3f, p.x); assertEquals(1f, p.y)
        // Глубины реально существующих слоёв: скорость относительно столика разная.
        assertTrue(1 + (.5 - .30)*1.6 > 1 + (.5 - .84)*1.6)
        p.reset();p.followCamera(-700.0, -900.0, .005);p.update(1f)
        assertEquals(0f, p.x);assertEquals(0f, p.y)
    }
}
