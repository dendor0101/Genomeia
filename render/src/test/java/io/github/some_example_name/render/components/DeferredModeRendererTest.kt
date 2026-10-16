package io.github.some_example_name.render.components

import io.github.some_example_name.render.RenderMode
import org.junit.Test
import kotlin.test.assertEquals

class DeferredModeRendererTest {
    @Test
    fun `unused mode allocates nothing even on resize and dispose`() {
        val renderer = DeferredModeRenderer(RenderMode.DEBUG) { error("unused mode created") }
        renderer.create()
        renderer.resize(1280, 720)
        renderer.render(RenderContext().apply { mode = RenderMode.REALISTIC })
        renderer.dispose()
    }

    @Test
    fun `first frame creates once with latest size and switching preserves resources`() {
        val calls = mutableListOf<String>()
        val renderer = DeferredModeRenderer(RenderMode.DEBUG) {
            calls += "factory"
            object : RenderComponent {
                override fun create() { calls += "create" }
                override fun resize(width: Int, height: Int) { calls += "$width:$height" }
                override fun render(context: RenderContext) { calls += "render" }
                override fun dispose() { calls += "dispose" }
            }
        }
        renderer.create()
        renderer.resize(800, 600)
        renderer.resize(1280, 720)
        val context = RenderContext().apply { mode = RenderMode.DEBUG }
        renderer.render(context)
        context.mode = RenderMode.NOSTALGIC
        renderer.render(context)
        renderer.resize(900, 900)
        context.mode = RenderMode.DEBUG
        renderer.render(context)
        renderer.dispose()
        renderer.dispose()
        assertEquals(listOf("factory", "create", "1280:720", "render", "900:900", "render", "dispose"), calls)
    }
}
