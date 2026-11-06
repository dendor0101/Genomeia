package io.github.some_example_name.render.pack

import org.junit.Test
import kotlin.test.*

class OpticalCullRangesTest {
    @Test fun coversBothEndsOfInterpolationAndInclusiveEdges() {
        val r = OpticalCullRanges()
        r.begin(10); r.include(100f, 200f, -10f, -20f); r.finish(12)
        assertEquals(10, r.first(0)); assertEquals(12, r.end(0))
        assertTrue(r.intersects(0, -11f, -21f, -10f, -20f))
        assertTrue(r.intersects(0, 100f, 200f, 101f, 201f))
        assertTrue(r.intersects(0, 0f, 0f, 10f, 10f))
        assertFalse(r.intersects(0, 101f, 0f, 102f, 201f))
        assertFalse(r.intersects(0, -10f, 201f, 100f, 202f))
    }
    @Test fun reusableEmptyAndGappedRanges() {
        val r = OpticalCullRanges()
        r.begin(0); r.finish(0); assertEquals(0, r.count)
        repeat(100) { i ->
            r.begin(i * 3); r.include(i.toFloat(), 0f, i.toFloat(), 0f); r.finish(i * 3 + 2)
        }
        assertEquals(100, r.count)
        assertEquals(297, r.first(99)); assertEquals(299, r.end(99))
        assertTrue(r.intersects(99, 99f, 0f, 99f, 0f))
        r.clear(); r.begin(2); r.include(300f, 300f, 300f, 300f); r.finish(3)
        assertEquals(1, r.count)
        assertFalse(r.intersects(0, 99f, 0f, 99f, 0f))
    }
}
