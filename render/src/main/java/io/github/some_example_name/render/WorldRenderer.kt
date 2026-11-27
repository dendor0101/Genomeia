package io.github.some_example_name.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.render.components.CellGeometry
import io.github.some_example_name.render.components.DebugRenderer
import io.github.some_example_name.render.components.DeferredModeRenderer
import io.github.some_example_name.render.components.NostalgicRenderer
import io.github.some_example_name.render.components.RenderComponent
import io.github.some_example_name.render.components.RenderContext
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer
import java.nio.ByteBuffer

/** Общий рендерер симуляции, редактора и превью. Ресурсы микроскопа создаются по запросу. */
class WorldRenderer(
    /** Ключи типов в порядке игровых инстансов. */
    typeKeys: List<String>,
    materials: IntArray? = null,
    materialFactory: (() -> IntArray)? = null
) {
    val opticalMaterials by lazy(LazyThreadSafetyMode.NONE){ materials ?: materialFactory?.invoke() ?: IntArray(typeKeys.size){27} }

    // Общие ресурсы
    private lateinit var mesh: Mesh
    private val cellGeometry = CellGeometry()
    private var fbo: FrameBuffer? = null

    /** Промежуточные FBO превью, см. [renderOffscreen]. null — превью ещё не рисовали. */
    private var offscreenScene: FrameBuffer? = null
    private var offscreenWidth = 0
    private var offscreenHeight = 0

    /** Переиспользуется между кадрами: на кадр не должно быть ни одной аллокации. */
    private val renderContext = RenderContext()

    /**
     * Чей буфер клеток сейчас лежит в VBO у [cellGeometry]. Геометрия одна на экран, превью
     * и все экраны игры, а заливать её каждый кадр больше не обязательно (RenderFrame.uploadCells):
     * без этой пометки кадр после превью нарисовал бы клетки превью.
     */
    private var uploadedCells: ByteBuffer? = null
    private var tissue: io.github.some_example_name.render.microscope.TissueMicroscopeRenderer? = null
    private var tissuePreview: io.github.some_example_name.render.microscope.TissueMicroscopeRenderer? = null

    private val components = mutableListOf<RenderComponent>()

    /** Номер GL-контекста, под который созданы ресурсы, — см. [checkContext]. */
    private var glGeneration = 0

    var currentWidth = Gdx.graphics.width.coerceAtLeast(1)
        private set
    var currentHeight = Gdx.graphics.height.coerceAtLeast(1)
        private set

    init {
        create()
    }

    /**
     * Пересоздать FBO, если окно изменилось мимо [resize].
     *
     * Нужно потому, что экземпляр переживает смену экранов: пока играли в симуляцию и
     * ресайзили окно, редактор генома об этом не знал.
     */
    fun checkResize() {
        val width = Gdx.graphics.width.coerceAtLeast(1)
        val height = Gdx.graphics.height.coerceAtLeast(1)
        if (currentWidth != width || currentHeight != height) {
            resize(width, height)
        }
    }

    private fun create() {
        GlContextWatch.ensureStarted()
        glGeneration = GlContextWatch.generation
        createFullscreenMesh()
        cellGeometry.create()

        // Порядок в списке = порядок проходов.
        components.add(DeferredModeRenderer(RenderMode.DEBUG) { DebugRenderer(cellGeometry) })
        components.add(DeferredModeRenderer(RenderMode.NOSTALGIC) { NostalgicRenderer(cellGeometry) })

        components.forEach { it.create() }
    }

    private fun createFullscreenMesh() {
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val attributes = VertexAttributes(
            VertexAttribute(
                VertexAttributes.Usage.Position,
                2,
                ShaderProgram.POSITION_ATTRIBUTE
            )
        )
        mesh = Mesh(false, 4, 0, attributes).apply { setVertices(vertices) }
    }

    /**
     * Scene FBO — с глубиной И stencil.
     *
     * Stencil нужен ностальгическому режиму: по нему считается контур (NostalgicRenderer).
     * Остальные режимы его не трогают — тест stencil у них выключен. Глубина с ним
     * становится пакованной D24S8 вместо отдельного D16, то есть заодно точнее; лишняя
     * память — два байта на пиксель.
     */
    private fun createSceneFbo(width: Int, height: Int): FrameBuffer =
        FrameBuffer(Pixmap.Format.RGBA8888, width, height, true, true)

    fun resize(width: Int, height: Int) {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)

        fbo?.dispose()
        fbo = null
        currentWidth = safeW
        currentHeight = safeH

        components.forEach { it.resize(safeW, safeH) }
    }

    /**
     * Восстановиться, если GL-контекст пересоздан (Android, см. GlContextWatch). Звать в
     * начале кадра, до первого обращения к FBO и геометрии. Цена — сравнение двух int.
     */
    private fun checkContext() {
        if (GlContextWatch.generation != glGeneration) recoverLostContext()
    }

    /**
     * Контекст потерян и создан заново: libGDX восстановил шейдеры, Mesh и текстуры, а всё
     * остальное здесь. Без этого после возврата в игру мир не рисовался до перезапуска.
     */
    private fun recoverLostContext() {
        glGeneration = GlContextWatch.generation
        Gdx.app.log("WorldRenderer", "GL context recreated: rebuilding render resources")

        cellGeometry.recreateAfterContextLoss()
        uploadedCells = null
        components.forEach { it.contextLost() }

        fbo?.let { releaseRebuiltFrameBuffer(it, attachmentCount = 1) }
        fbo = null
        offscreenScene?.let { releaseRebuiltFrameBuffer(it, attachmentCount = 1) }
        offscreenScene = null

        // Микроскоп целиком на сыром GL: удалять его объекты нельзя (номера в новом
        // контексте уже могут быть чужими), поэтому он просто брошен и строится заново.
        // Его шейдеры остаются в списке управляемых libGDX — утечка в несколько программ
        // на каждую потерю контекста, а не на кадр.
        tissue = null
        tissuePreview = null
    }

    fun render(frame: RenderFrame) {
        // GLFW может запросить кадр во время сворачивания окна с размером 0×0.
        val width = Gdx.graphics.backBufferWidth
        if (width <= 0 || Gdx.graphics.backBufferHeight <= 0) return
        checkContext()
        if (frame.mode == RenderMode.NOSTALGIC && fbo == null) {
            fbo = createSceneFbo(currentWidth, currentHeight)
            fbo!!.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        draw(frame, fbo, null, width)
    }

    /** Превью Debug и микроскоп выводят прямо в output; Nostalgic нужен промежуточный depth/stencil. */
    fun renderOffscreen(frame: RenderFrame, output: FrameBuffer) {
        checkContext()
        if (frame.mode == RenderMode.NOSTALGIC && (offscreenScene == null ||
            offscreenWidth != output.width || offscreenHeight != output.height)) {
            offscreenScene?.dispose()
            offscreenScene = createSceneFbo(output.width, output.height)
            offscreenWidth = output.width
            offscreenHeight = output.height
        }
        draw(frame, offscreenScene, output, output.width)
    }

    private fun draw(frame: RenderFrame, scene: FrameBuffer?, output: FrameBuffer?, pixelWidth: Int) {
        if (frame.mode == RenderMode.TISSUE_MAP) {
            uploadedCells=null // После микроскопа VBO обычного режима надо заполнить заново.
            val renderer = if (output == null) {
                tissue ?: io.github.some_example_name.render.microscope.TissueMicroscopeRenderer(opticalMaterials).also { tissue = it }
            } else {
                tissuePreview ?: io.github.some_example_name.render.microscope.TissueMicroscopeRenderer(opticalMaterials).also { tissuePreview = it }
            }
            renderer.render(frame, output, pixelWidth, output?.height ?: currentHeight)
            return
        }
        // Освобождаем карты и программы при выходе из микроскопа.
        if(output==null && tissue!=null){ tissue!!.dispose(); tissue=null }
        if(output!=null && tissuePreview!=null){ tissuePreview!!.dispose(); tissuePreview=null }
        val cells = frame.cells

        // Один раз за кадр и до всех проходов: сколько бы их ни рисовало клетки, байты
        // едут на GPU единожды. И не каждый кадр: если буфер тот же и не менялся
        // (uploadCells = false), в VBO уже лежат ровно эти байты. Чужой буфер — превью
        // генома или другой экран — заливается всегда, иначе кадр нарисовал бы их клетки.
        if (cells != null && (frame.uploadCells || cells !== uploadedCells)) {
            cellGeometry.upload(cells)
            uploadedCells = cells
        }

        renderContext.apply {
            fullscreenMesh = mesh
            cameraProjection = frame.cameraProjection

            numInstances = cells?.let { it.remaining() / CellInstanceBuffer.STRUCT_SIZE } ?: 0
            interpolationLag = frame.interpolationLag

            overlayData = frame.overlay
            numOverlayInstances =
                frame.overlay?.let { it.remaining() / OverlayInstanceBuffer.STRUCT_SIZE } ?: 0
            pheromoneData = frame.pheromones
            numPheromoneInstances =
                frame.pheromones?.let { it.remaining() / PheromoneInstanceBuffer.STRUCT_SIZE } ?: 0
            pheromoneK = frame.pheromoneK
            pheromoneP = frame.pheromoneP
            time = frame.time
            pixelWorld = pixelWorld(frame.cameraProjection, pixelWidth)

            mode = frame.mode
            debugFilter = frame.debugFilter
            areaIndices = frame.areaIndices
            // Без фона — только в свой буфер: в экран пустота всегда фоновая.
            transparentBackground = frame.transparentBackground && output != null

            sceneFbo = scene
            outputFbo = output
        }

        components.forEach { component ->
            component.render(renderContext)
        }

        Gdx.gl.glUseProgram(0)
    }

    /**
     * Единиц мира на пиксель буфера, в который рисуем.
     *
     * Камера ортографическая: M00 = 2 / ширина_видимого_мира, а буфер — [pixelWidth]
     * пикселей. Для экрана это backBufferWidth, а не логический размер окна: на экране с
     * масштабом (HiDPI) они различаются, а растеризуются фигуры именно в пикселях буфера.
     */
    private fun pixelWorld(projection: Matrix4, pixelWidth: Int): Float {
        val scaleX = projection.`val`[Matrix4.M00]
        val width = pixelWidth.coerceAtLeast(1)
        if (scaleX == 0f) return 1f
        return kotlin.math.abs(2f / (scaleX * width))
    }

    /**
     * Квадратное превью кадра мира размером [size]×[size], строки сверху вниз.
     *
     * Звать сразу после [render] в том же кадре, до интерфейса: в бэкбуфере сейчас ровно
     * то, что нарисовали проходы конвейера, без кнопок и текста поверх. Берётся центральный
     * квадрат кадра, и он уменьшается усреднением по блокам, а не выборкой через пиксель:
     * при уменьшении в 8-10 раз выборка дала бы мерцающую «лесенку» на границах клеток.
     *
     * Pixmap принадлежит вызывающему.
     */
    fun capturePreview(size: Int): Pixmap {
        val width = Gdx.graphics.backBufferWidth
        val height = Gdx.graphics.backBufferHeight
        val side = minOf(width, height)
        val frame = Pixmap.createFromFrameBuffer((width - side) / 2, (height - side) / 2, side, side)
        try {
            return downscaleFlipped(frame, side, size)
        } finally {
            frame.dispose()
        }
    }

    private fun downscaleFlipped(frame: Pixmap, side: Int, size: Int): Pixmap {
        val preview = Pixmap(size, size, Pixmap.Format.RGBA8888)
        val src = frame.pixels
        val dst = preview.pixels

        for (outY in 0 until size) {
            val y0 = outY * side / size
            val y1 = maxOf(y0 + 1, (outY + 1) * side / size)
            // Кадровый буфер читается снизу вверх, превью пишется сверху вниз.
            val dstRow = (size - 1 - outY) * size

            for (outX in 0 until size) {
                val x0 = outX * side / size
                val x1 = maxOf(x0 + 1, (outX + 1) * side / size)

                var r = 0
                var g = 0
                var b = 0
                for (y in y0 until y1) {
                    var offset = (y * side + x0) * 4
                    for (x in x0 until x1) {
                        r += src.get(offset).toInt() and 0xFF
                        g += src.get(offset + 1).toInt() and 0xFF
                        b += src.get(offset + 2).toInt() and 0xFF
                        offset += 4
                    }
                }
                val count = (y1 - y0) * (x1 - x0)
                val out = (dstRow + outX) * 4
                dst.put(out, (r / count).toByte())
                dst.put(out + 1, (g / count).toByte())
                dst.put(out + 2, (b / count).toByte())
                // Альфа в бэкбуфере после смешивания проходов не обязана быть единицей,
                // а прозрачное превью в PNG выглядело бы дырой.
                dst.put(out + 3, 0xFF.toByte())
            }
        }
        return preview
    }

    fun dispose() {
        tissue?.dispose(); tissue = null
        tissuePreview?.dispose(); tissuePreview = null
        components.forEach { it.dispose() }
        components.clear()

        cellGeometry.dispose()
        uploadedCells = null
        if (::mesh.isInitialized) mesh.dispose()

        fbo?.dispose(); fbo = null
        offscreenScene?.dispose(); offscreenScene = null
    }

    // === Управление конвейером ===

    /** Добавить проход в конец конвейера. */
    fun addComponent(component: RenderComponent) {
        component.create()
        components.add(component)
    }

    /** Вставить проход на позицию [index] (0 — первым). */
    fun insertComponent(index: Int, component: RenderComponent) {
        component.create()
        components.add(index, component)
    }

    fun removeComponent(component: RenderComponent) {
        if (components.remove(component)) {
            component.dispose()
        }
    }

    fun getComponentCount(): Int = components.size
}
