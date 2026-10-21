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
import io.github.some_example_name.old.core.PROFILE_INTERPOLATION
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.WorldRenderer
import io.github.some_example_name.render.debug.RenderSceneDump
import io.github.some_example_name.render.pack.CellInstanceBuffer

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

    /** Переиспользуется между кадрами: аллокаций на кадр быть не должно. */
    private val frame = RenderFrame()

    /**
     * Клетки взятого снимка — в direct-буфере, который уходит на GPU. Снимок уже в
     * раскладке GPU, поэтому это одна копия массива (memcpy) — и только когда снимок новый:
     * прежде GL-поток каждый кадр заново перекладывал все поля каждой клетки.
     */
    private val cellBuffer = CellInstanceBuffer()

    /** Из какого снимка [cellBuffer]: другой слот или первый кадр — копировать заново. */
    private var packedSnapshot: RenderSnapshot? = null

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
        // Снимок берётся ОДИН раз за кадр и целиком: клетки, оверлей, феромоны и строка
        // состояния из одного тика (см. TripleBuffer). fresh — снимок новый, буфер клеток
        // надо перезалить; иначе на GPU уже ровно эти байты, и кадр отличается от прошлого
        // только долей тика.
        val fresh = renderBufferManager.acquireSnapshot()
        val snapshot = renderBufferManager.currentSnapshot()
        val spec = snapshot.spec

        // Один раз на кадр: упаковка, рендер и отладка должны работать в одном режиме.
        val mode = this.mode
        val lag = interpolationLag(snapshot)
        if (PROFILE_INTERPOLATION) logInterpolation(snapshot, fresh, lag)

        if (zoom != camera.zoom || cameraX != camera.position.x || cameraY != camera.position.y) {
            if (!spec.isCellSelected) {
                blurLevel = 4.0f
                cameraX = camera.position.x
                cameraY = camera.position.y
                zoom = camera.zoom
            }
        }

        if (renderBufferManager.simulationData.isPlay) {
            animationTime = RenderFrame.advanceTime(animationTime, Gdx.graphics.deltaTime)
        }

        frame.apply {
            cameraProjection = camera.combined
            cells = cellsOf(snapshot, fresh)
            uploadCells = fresh || packedNow
            interpolationLag = lag
            // Феромоны рисует только постпроцесс, оверлей — только отладка, и только если
            // снимок собран под неё (режим переключили, а нового тика ещё не было).
            pheromones = if (mode.usesPostProcess) snapshot.pheromones.end() else null
            pheromoneK = PheromonesManager.K
            pheromoneP = PheromonesManager.P
            overlay = if (mode.drawsDebugOverlay && snapshot.hasOverlay) snapshot.overlay.end() else null
            time = animationTime
            blurAmount = blurLevel
            zoom = camera.zoom
            vignetteEnabled = 1f
            this.mode = mode
            // Из снимка, а не из выбора игрока: байты фильтра в клетках упакованы под него.
            debugFilter = snapshot.filter
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

        moveCameraAndDrawSelected(spec, lag)

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

    // Диагностика PROFILE_INTERPOLATION: накопители за секунду.
    private var diagSince = 0L
    private var diagFrames = 0
    private var diagFresh = 0
    private var diagSkipped = 0
    private var diagStalls = 0
    private var diagTeleports = 0
    private var diagStepMax = 0f
    private var diagGapMin = Long.MAX_VALUE
    private var diagGapMax = 0L
    private var diagLastSerial = 0
    private var diagLastPublished = 0L

    /**
     * Раз в секунду — строка о снимках и интерполяции (см. PROFILE_INTERPOLATION).
     * «пропущено» — снимки, которых рендер не увидел: симуляция выпустила два подряд
     * быстрее кадра, и движение прыгнуло на тик вперёд. «стоп» — кадры, где снимок уже
     * прожит (lag = 0), а нового нет: картинка стоит.
     */
    private fun logInterpolation(snapshot: RenderSnapshot, fresh: Boolean, lag: Float) {
        val now = System.nanoTime()
        if (diagSince == 0L) diagSince = now
        diagFrames++
        if (fresh) {
            diagFresh++
            if (diagLastSerial != 0 && snapshot.serial > diagLastSerial + 1) diagSkipped += snapshot.serial - diagLastSerial - 1
            if (diagLastPublished != 0L) {
                val gap = snapshot.publishedAtNanos - diagLastPublished
                diagGapMin = minOf(diagGapMin, gap)
                diagGapMax = maxOf(diagGapMax, gap)
            }
            diagLastSerial = snapshot.serial
            diagLastPublished = snapshot.publishedAtNanos
            diagTeleports += snapshot.stepTeleports
            diagStepMax = maxOf(diagStepMax, snapshot.stepMax)
        } else if (lag == 0f) {
            diagStalls++
        }
        if (now - diagSince < 1_000_000_000L) return
        println(
            "INTERP кадров $diagFrames, снимков $diagFresh, пропущено $diagSkipped, стоп $diagStalls, " +
                "промежуток ${diagGapMin / 100_000 / 10.0}..${diagGapMax / 100_000 / 10.0} мс " +
                "(сглаж. ${snapshot.tickNanos / 100_000 / 10.0}), переносов $diagTeleports, " +
                "макс. шаг ${"%.2f".format(diagStepMax)}"
        )
        diagSince = now
        diagFrames = 0; diagFresh = 0; diagSkipped = 0; diagStalls = 0; diagTeleports = 0
        diagStepMax = 0f; diagGapMin = Long.MAX_VALUE; diagGapMax = 0L
    }

    /** Перекопирован ли [cellBuffer] в этом кадре: тогда его и заливать. */
    private var packedNow = false

    private fun cellsOf(snapshot: RenderSnapshot, fresh: Boolean): java.nio.ByteBuffer {
        packedNow = fresh || packedSnapshot !== snapshot
        if (packedNow) {
            cellBuffer.begin(snapshot.instanceCount)
            cellBuffer.putAll(snapshot.instances, snapshot.instanceCount)
            packedSnapshot = snapshot
        }
        return cellBuffer.end()
    }

    /**
     * На какую долю тика кадр отстаёт от снимка: 1 — только что опубликован (рисуем
     * прошлый тик), 0 — снимок прожил свой тик целиком (рисуем его самого).
     *
     * Кадр всегда показывает мир на один тик в прошлом и плавно доходит до снимка к
     * моменту, когда должен прийти следующий. Цена — задержка картинки на тик (16 мс при
     * 60 UPS, 33 мс при 30): это та же цена, что у любой интерполяции между фиксированными
     * шагами, а экстраполяция вперёд промахивалась бы на каждом столкновении и смене силы.
     *
     * Снимок, который опоздал, стоит на нуле: картинка ждёт, а не убегает вперёд. Пауза —
     * тот же случай: снимков нет, мир стоит ровно в последнем.
     */
    private fun interpolationLag(snapshot: RenderSnapshot): Float {
        if (!INTERPOLATION) return 0f
        val tick = snapshot.tickNanos
        if (tick <= 0L) return 0f
        val age = System.nanoTime() - snapshot.publishedAtNanos
        return 1f - (age.toFloat() / tick.toFloat()).coerceIn(0f, 1f)
    }

    /** [lag] — та же доля тика, что у клеток: подсветка едет вместе с интерполированной клеткой. */
    private fun moveCameraAndDrawSelected(spec: RenderSpecificBufferData, lag: Float) {
        if (!spec.isCellSelected) return
        val cellX = spec.grabbedCellX ?: return
        val cellY = spec.grabbedCellY ?: return
        val targetX = cellX + (spec.grabbedPrevX - cellX) * lag
        val targetY = cellY + (spec.grabbedPrevY - cellY) * lag

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)

        shapeRenderer.color = Color.GOLD
        Gdx.gl.glLineWidth(5f)

        shapeRenderer.circle(targetX, targetY, 0.55f, 64)

        shapeRenderer.end()

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

        /**
         * Интерполяция между тиками; -Dgenomeia.interpolation=false выключает её — для
         * сравнения «до/после» глазами. Читается один раз: на Android свойства нет, там null.
         */
        val INTERPOLATION = System.getProperty("genomeia.interpolation") != "false"
    }
}
