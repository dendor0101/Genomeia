package io.github.some_example_name.old.systems.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.WorldRenderer
import io.github.some_example_name.render.debug.RenderSceneDump
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer

/**
 * Сторона симуляции в отрисовке кадра: взять снимок мира и перевести его на язык,
 * который понимает модуль рендера.
 *
 * Границу проводит просто: всё, что знает про клетки, связи и UPS, живёт здесь; всё,
 * что говорит с GPU, живёт в :render. Поэтому здесь нет ни одного вызова Gdx.gl,
 * кроме переключения состояний вокруг подсветки выбранной клетки, а там — ни одного
 * упоминания клетки. Даже отладочные связи и стрелки уходят туда фигурами
 * (OverlayInstanceBuffer), а не рисуются здесь.
 */
class RenderSystem(
    val worldRenderer: WorldRenderer,
    val renderBufferManager: RenderBufferManager
) {

    var isRenderUi = true

    /** Подробный текст в углу (настройка SHOW_DEBUG_INFO); без него — только FPS и UPS. Ставит экран. */
    var showDebugInfo = false

    /** Нижний край текста в пикселях экрана — над кнопками в левом нижнем углу. Ставит экран. */
    var textBottom = 0f

    /** Текст угла пересобирается каждый кадр в один и тот же буфер, без новой строки на кадр. */
    private val simInfoText = StringBuilder()

    /** Режим рендера этого мира; хранится у [renderBufferManager], см. пояснение там. */
    var mode: RenderMode
        get() = renderBufferManager.mode
        set(value) {
            renderBufferManager.mode = value
        }

    /** Фильтр отладочного режима; хранится у [renderBufferManager], см. пояснение там. */
    var debugFilter: DebugFilter
        get() = renderBufferManager.debugFilter
        set(value) {
            renderBufferManager.debugFilter = value
        }

    private lateinit var fontMatrix: Matrix4
    private lateinit var spriteBatch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera

    private var zoom = 0f
    private var cameraX = 0f
    private var cameraY = 0f
    private var blurLevel = 0f

    /** Всё переиспользуется между кадрами: аллокаций на кадр быть не должно. */
    private val cellBuffer = CellInstanceBuffer()
    private val pheromoneBuffer = PheromoneInstanceBuffer()
    private val overlayBuffer = OverlayInstanceBuffer()
    private val frame = RenderFrame()

    /**
     * Часы анимации оверлея: по ним бегут импульсы нейросвязей. Идут, только пока идёт
     * симуляция, — на паузе сигналы не передаются, и бегущие бусины врали бы.
     */
    private var animationTime = 0f

    /** Ждёт ближайшего кадра, см. [requestPreview]. */
    private var previewRequest: ((Pixmap) -> Unit)? = null
    private var previewSize = 0

    fun create(
        fontMatrix: Matrix4,
        spriteBatch: SpriteBatch,
        font: BitmapFont,
        shapeRenderer: ShapeRenderer,
        camera: OrthographicCamera
    ) {
        worldRenderer.checkResize()
        this.fontMatrix = fontMatrix
        this.spriteBatch = spriteBatch
        this.font = font
        this.shapeRenderer = shapeRenderer
        this.camera = camera
    }

    /**
     * Снять превью мира в следующем кадре и отдать его в [onReady] — там же, в потоке
     * рендера. Отложенно: снять можно лишь кадр, который только что нарисован, а кнопки
     * срабатывают уже после отрисовки мира. Pixmap освобождается после [onReady].
     */
    fun requestPreview(size: Int, onReady: (Pixmap) -> Unit) {
        previewSize = size
        previewRequest = onReady
    }

    fun moveCamera(dx: Float, dy: Float) {
        camera.position.x += dx
        camera.position.y += dy
        camera.update()
    }

    fun resize(width: Int, height: Int) {
        worldRenderer.resize(width, height)
    }

    fun render() {
        // Индекс кадра читается ОДИН раз, и оба буфера берутся по нему: оверлей рисуется
        // по координатам клеток, и клетки с оверлеем обязаны быть из одной сборки. Почему
        // это важно — в RenderBufferManager.frameFrontIndex.
        val frameIndex = renderBufferManager.frontFrameIndex()
        val cellBuf = renderBufferManager.cellBuffer(frameIndex)
        val overlayBuf = renderBufferManager.overlayBuffer(frameIndex)

        val pheromoneBuf = renderBufferManager.getCurrentPheromoneBuffer()
        val spec = renderBufferManager.getCurrentSpecificBufferData()

        // Один раз на кадр: упаковка, рендер и отладка должны работать в одном режиме.
        val mode = this.mode

        if (zoom != camera.zoom || cameraX != camera.position.x || cameraY != camera.position.y) {
            if (!spec.isCellSelected) {
                blurLevel = 4.0f
                cameraX = camera.position.x
                cameraY = camera.position.y
                zoom = camera.zoom
            }
        }

        packCells(cellBuf)
        packPheromones(pheromoneBuf, mode.usesPostProcess)
        packOverlay(overlayBuf, mode.drawsDebugOverlay)

        if (renderBufferManager.simulationData.isPlay) {
            animationTime = RenderFrame.advanceTime(animationTime, Gdx.graphics.deltaTime)
        }

        frame.apply {
            cameraProjection = camera.combined
            cells = cellBuffer.end()
            uploadCells = true
            pheromones = pheromoneBuffer.end()
            pheromoneK = PheromonesManager.K
            pheromoneP = PheromonesManager.P
            overlay = if (mode.drawsDebugOverlay) overlayBuffer.end() else null
            time = animationTime
            blurAmount = blurLevel
            zoom = camera.zoom
            vignetteEnabled = 1f
            this.mode = mode
            // Из снимка, а не из выбора игрока: байты фильтра в клетках упакованы под него.
            debugFilter = cellBuf.filter
        }
        worldRenderer.render(frame)

        // Сразу после конвейера: в кадре пока только мир, без подсветки и текста.
        previewRequest?.let { onReady ->
            previewRequest = null
            val preview = worldRenderer.capturePreview(previewSize)
            try {
                onReady(preview)
            } finally {
                preview.dispose()
            }
        }

        moveCameraAndDrawSelected(spec)

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthMask(false)
        Gdx.gl.glEnable(GL20.GL_BLEND)

        if (isRenderUi) {
            drawTextSimInfo(spec)
        }

        if (blurLevel > 0) {
            blurLevel -= 0.09f
        }
    }

    /**
     * Ёмкость считается по СНИМКУ, который сейчас будет записан, а не по живому
     * aliveList: список принадлежит потоку симуляции и меняется прямо во время кадра.
     * Если в снимке частиц больше, чем в списке на момент чтения (а так бывает при
     * массовой гибели — снимок старше), запись уходила за границу буфера и кадр падал
     * с BufferOverflowException.
     *
     * shape/type уже посчитаны потоком симуляции — здесь только перекладывание
     * байт, без арифметики. См. пояснение в CellInstanceBuffer.
     */
    private fun packCells(cellBuf: RenderCellBufferData) {
        cellBuffer.begin(cellBuf.renderCellBufferSize)
        with(cellBuf) {
            for (i in 0..<renderCellBufferSize) {
                cellBuffer.put(
                    x = x[i],
                    y = y[i],
                    color = color[i],
                    shape = shape[i],
                    type = type[i],
                    look = look[i]
                )
            }
        }
    }

    private fun packPheromones(pheromoneBuf: PheromoneBufferData, usePostProcess: Boolean) {
        pheromoneBuffer.begin(pheromoneBuf.pheromoneBufferSize)
        if (!usePostProcess) return
        with(pheromoneBuf) {
            for (i in 0..<pheromoneBufferSize) {
                pheromoneBuffer.put(
                    x = x[i],
                    y = y[i],
                    a = a[i],
                    color = color[i]
                )
            }
        }
    }

    /**
     * Фигуры оверлея уже упакованы потоком симуляции (RenderBufferManager): здесь только
     * перекладывание, как и в [packCells]. Кадр без отладки оверлей не получает вовсе.
     */
    private fun packOverlay(overlayBuf: RenderOverlayBufferData, drawsOverlay: Boolean) {
        overlayBuffer.begin(overlayBuf.size)
        if (!drawsOverlay) return
        with(overlayBuf) {
            for (i in 0..<size) {
                overlayBuffer.put(
                    x0 = x0[i],
                    y0 = y0[i],
                    x1 = x1[i],
                    y1 = y1[i],
                    color = color[i],
                    params = params[i]
                )
            }
        }
    }

    private fun moveCameraAndDrawSelected(spec: RenderSpecificBufferData) {
        if (!spec.isCellSelected) return

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)

        shapeRenderer.color = Color.GOLD
        Gdx.gl.glLineWidth(5f)

        shapeRenderer.circle(
            spec.grabbedCellX ?: 0f,
            spec.grabbedCellY ?: 0f,
            0.55f,
            64
        )

        shapeRenderer.end()

        val targetX = spec.grabbedCellX ?: return
        val targetY = spec.grabbedCellY ?: return

        val lerpSpeed = 1f
        val delta = Gdx.graphics.deltaTime

        camera.position.x += (targetX - camera.position.x) * lerpSpeed * delta
        camera.position.y += (targetY - camera.position.y) * lerpSpeed * delta

        camera.update()
    }

    private fun drawTextSimInfo(spec: RenderSpecificBufferData) {
        val text = simInfoText
        text.setLength(0)
        text.append("FPS: ").append(Gdx.graphics.framesPerSecond)
        text.append("\nUPS: ").append(spec.ups)
        if (showDebugInfo) {
            text.append("\nUpdate Time: ").append(spec.updateTime).append(" ms")
            text.append("\nCells: ").append(spec.cellsAmount)
            text.append("\nParticles: ").append(spec.particleAmount)
            text.append("\nLinks ").append(spec.linksAmount)
        }
        if (spec.isCellSelected) {
            text.append("\n\nCell type ").append(spec.cellName)
            text.append("\nNeuronImpulseInput ").append(spec.neuronImpulseInput)
            text.append("\nNeuronImpulseOutput ").append(spec.neuronImpulseOutput)
            text.append("\nSelected cell index ").append(spec.selectedCellIndex)
        }
        if (showDebugInfo && spec.detailedPerformance.isNotEmpty()) {
            text.append('\n').append(spec.detailedPerformance)
        }

        var lines = 1
        for (i in 0 until text.length) if (text[i] == '\n') lines++

        spriteBatch.begin()
        // Текст растёт вверх от textBottom: сколько бы в нём ни было строк, он не уходит
        // под кнопки внизу и не упирается в жёсткую высоту, которой нет на маленьком окне.
        font.draw(spriteBatch, text, SIM_INFO_MARGIN, textBottom + SIM_INFO_MARGIN + lines * font.lineHeight)
        font.data.setScale(1f)
        spriteBatch.end()
    }

    /**
     * Выгрузить последний отрисованный кадр в файл — вход рендера, а не картинку.
     *
     * Звать только ПОСЛЕ [render] в том же кадре: пишется тот самый [frame], который
     * только что ушёл в GPU, поэтому дамп и живой кадр совпадают побайтово.
     *
     * Дальше этот файл открывается стендом RenderLab, и шейдеры можно крутить, пересобирая
     * один только :render — без симуляции, потоков и загрузки геномов.
     */
    fun dumpScene(file: com.badlogic.gdx.files.FileHandle) {
        RenderSceneDump.write(
            file = file,
            frame = frame,
            viewportWidth = camera.viewportWidth.toInt(),
            viewportHeight = camera.viewportHeight.toInt(),
            cameraX = camera.position.x,
            cameraY = camera.position.y,
            zoom = camera.zoom,
            texturePaths = worldRenderer.texturePaths
        )
    }

    /**
     * GL-ресурсы принадлежат [worldRenderer], а он один на всю игру и переживает смену
     * экранов — поэтому здесь его НЕ трогаем. Диспозить его должен тот, кто им владеет
     * (DIGameGlobalContainer), при завершении игры.
     *
     * Собственных GL-ресурсов у этого класса нет: буферы инстансов — обычная память.
     */
    fun dispose() {
    }

    private companion object {
        const val SIM_INFO_MARGIN = 12f
    }
}
