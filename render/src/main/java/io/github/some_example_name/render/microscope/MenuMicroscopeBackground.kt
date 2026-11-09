package io.github.some_example_name.render.microscope

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.WorldRenderer

/** Меню владеет своей оптикой. Рисуем весь мир в половинном разрешении, UI накладывается позже. */
class MenuMicroscopeBackground(
    private val world: WorldRenderer,
    val budget: MenuBackgroundBudget = MenuBackgroundBudget()
) : Disposable {
    val settings = MicroscopeSettings.gameDefaults().apply { layerScale = 1f }
    private var optics: TissueMicroscopeRenderer? = null
    private val quad = Mesh(true, 4, 0, VertexAttribute.Position()).apply {
        setVertices(floatArrayOf(-1f,-1f,0f, 1f,-1f,0f, -1f,1f,0f, 1f,1f,0f))
    }
    private val blur = ShaderProgram(Gdx.files.internal("shaders/common/fullscreen.vert"),
        Gdx.files.internal("shaders/menu/background.frag")).also { check(it.isCompiled) { it.log } }
    private var target: FrameBuffer? = null

    fun render(frame: RenderFrame) {
        val gl = Gdx.gl
        val width = Gdx.graphics.backBufferWidth
        val height = Gdx.graphics.backBufferHeight
        if (width <= 0 || height <= 0) return
        val w = (width+1)/2
        val h = (height+1)/2
        val buffer = target?.takeIf { it.width == w && it.height == h } ?: run {
            target?.dispose()
            FrameBuffer(Pixmap.Format.RGBA8888,w,h,false).also {
                it.colorBufferTexture.setFilter(Texture.TextureFilter.Linear,Texture.TextureFilter.Linear)
                target = it
            }
        }
        val measure = budget.shouldMeasure(width, height,
            frame.mode == RenderMode.TISSUE_MAP && frame.opticalLinks != null)
        // Всего семь раз после прогрева: измеряем завершённый рендер, а не очередь CPU.
        // GL ES 3.0 не гарантирует timer queries. В обычных кадрах синхронизации нет.
        if (measure) gl.glFinish()
        val started = if (measure) System.nanoTime() else 0L
        if (budget.useDebug || frame.mode != RenderMode.TISSUE_MAP) {
            optics?.dispose()
            optics = null
            world.renderOffscreen(frame, buffer)
        } else {
            val renderer = optics ?: TissueMicroscopeRenderer(world.opticalMaterials).also { optics = it }
            renderer.render(frame,buffer,w,h)
        }
        FrameBuffer.unbind()
        gl.glViewport(0,0,width,height)
        gl.glDisable(GL20.GL_DEPTH_TEST)
        gl.glDisable(GL20.GL_SCISSOR_TEST)
        gl.glDisable(GL20.GL_BLEND)
        blur.bind()
        buffer.colorBufferTexture.bind(0)
        blur.setUniformi("uScene",0)
        blur.setUniformf("uStep",2.5f/w,2.5f/h)
        quad.render(blur,GL20.GL_TRIANGLE_STRIP)
        if (measure) {
            gl.glFinish()
            budget.record((System.nanoTime() - started) / 1e6)
            if (budget.decided) {
                Gdx.app.log("MenuBackground", "median=${budget.medianMillis} ms; debug=${budget.useDebug}")
                if (budget.useDebug) { optics?.dispose(); optics = null }
            }
        }
    }

    override fun dispose() {
        optics?.dispose()
        target?.dispose()
        quad.dispose()
        blur.dispose()
    }
}
