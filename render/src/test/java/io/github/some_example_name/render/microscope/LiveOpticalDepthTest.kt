package io.github.some_example_name.render.microscope

import java.io.File
import org.junit.Test
import kotlin.test.*

class LiveOpticalDepthTest {
    @Test fun allAbsoluteDepthExpressionsBecomeCameraRelativeIncludingFusion() {
        val shaders = linkedMapOf(
            "resolve" to File("../assets/shaders/microscope/runtime/resolve.frag").readText(),
            "compose" to File("../assets/shaders/microscope/runtime/compose.frag").readText()
        )
        shaders["fused-compose"] = OpticalFusionShaders.compose(shaders.getValue("compose"))
        for ((name, original) in shaders) {
            val relative = LiveOpticalDepth.source(original)
            assertNotEquals(original, relative, name)
            assertFalse(Regex("\\.0018\\*(p|wp|vFiber)\\.x").containsMatchIn(relative), name)
            assertEquals(relative, LiveOpticalDepth.source(relative), "idempotent $name")
        }
    }
}
