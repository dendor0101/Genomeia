package io.github.some_example_name.render.microscope

import java.io.File
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpticalFusionShadersTest {
    private fun source() = File("../assets/shaders/microscope/runtime/compose.frag").readText()
    @Test fun `варианты строятся из действующих исходников GLSL ES 300`() {
        val shaders=mapOf(
            "compose" to OpticalFusionShaders.compose(source())
        )
        for((name,s) in shaders){assertTrue(s.startsWith("#version 300 es"))}
        assertFailsWith<IllegalStateException>{OpticalFusionShaders.compose("different source")}
    }
}
