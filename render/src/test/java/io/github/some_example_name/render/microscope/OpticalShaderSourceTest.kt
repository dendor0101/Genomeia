package io.github.some_example_name.render.microscope

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpticalShaderSourceTest {
    @Test fun `производные программы не добавляют второй layout к реальным vertex исходникам`() {
        val root=File("../assets/shaders/microscope")
        for(file in root.walkTopDown().filter{it.extension=="vert"}){
            val s=OpticalShaderSource.attributes(file.readText())
            assertEquals(s,OpticalShaderSource.attributes(s),file.name)
            assertFalse(Regex("layout\\([^)]*\\)\\s*layout").containsMatchIn(s),file.name)
        }
    }

    @Test fun `подготовленный источник сохраняет закреплённые номера атрибутов`() {
        val expected="layout(location=0) in vec2 aCorner;layout(location=1) in vec4 aI0;layout(location=2) in vec4 aI1;layout(location=3) in vec4 aI2;"
        assertEquals(expected,OpticalShaderSource.attributes("in vec2 aCorner;in vec4 aI0,aI1,aI2;"))
        assertEquals(expected,OpticalShaderSource.attributes(expected))
    }
}
