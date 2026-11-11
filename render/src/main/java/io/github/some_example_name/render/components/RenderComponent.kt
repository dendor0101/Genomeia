package io.github.some_example_name.render.components

import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.pack.DebugAreaIndices
import java.nio.ByteBuffer

/**
 * Shared context passed between render components.
 * Contains all the data needed by various renderers in the pipeline.
 */
class RenderContext {
    // Shared mesh for fullscreen quad rendering
    lateinit var fullscreenMesh: Mesh

    // Camera/projection
    lateinit var cameraProjection: Matrix4

    /**
     * Клеток в кадре. Сами данные уже лежат в инстансном VBO у [CellGeometry]: их заливает
     * WorldRenderer один раз за кадр, до всех проходов.
     */
    var numInstances: Int = 0

    /** Отставание кадра от снимка в долях тика — униформ u_lag, см. RenderFrame.interpolationLag. */
    var interpolationLag: Float = 0f

    /** Фигуры оверлея отладочного режима, см. RenderFrame.overlay. */
    var overlayData: ByteBuffer? = null
    var numOverlayInstances: Int = 0

    /** Секунды для анимации, см. RenderFrame.time. */
    var time: Float = 0f

    /** Фильтр отладочного режима, см. RenderFrame.debugFilter. */
    var debugFilter: DebugFilter = DebugFilter.ORIGINAL

    /** Индексы вершин заливки в буфере клеток этого снимка. */
    var areaIndices: DebugAreaIndices? = null

    /**
     * Сколько единиц мира приходится на пиксель экранного буфера. По ней проходы, которые
     * сглаживают сами (DebugRenderer), переводят расстояния в пиксели.
     */
    var pixelWorld: Float = 1f

    var mode: RenderMode = RenderMode.DEBUG

    var sceneFbo: FrameBuffer? = null

    /**
     * Куда выводит готовый кадр последний проход (виньетка, вывод ностальгического режима).
     * null — в экран, как в игре; буфер — превью генома (WorldRenderer.renderOffscreen).
     */
    var outputFbo: FrameBuffer? = null

    /** Начать вывод готового кадра — в [outputFbo] или в экран. Парный вызов — [endOutput]. */
    fun beginOutput() {
        outputFbo?.begin()
    }

    fun endOutput() {
        outputFbo?.end()
    }
}

/**
 * Interface for all rendering components in the pipeline.
 * Each component handles a specific rendering responsibility.
 */
interface RenderComponent {
    /**
     * Initialize GPU resources (shaders, FBOs, textures).
     * Called once during setup.
     */
    fun create()

    /**
     * Handle window/viewport resize.
     * Recreate any size-dependent resources like FBOs.
     */
    fun resize(width: Int, height: Int)

    /**
     * Execute the rendering pass.
     * @param context Shared render context with input data and textures
     */
    fun render(context: RenderContext)

    /**
     * Release all GPU resources.
     */
    fun dispose()
}
