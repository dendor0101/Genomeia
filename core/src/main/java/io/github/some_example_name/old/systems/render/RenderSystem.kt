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
import io.github.some_example_name.old.commands.ScreenCell
import io.github.some_example_name.old.commands.aliveIn
import io.github.some_example_name.old.core.PROFILE_INTERPOLATION
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.WorldRenderer
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

    /** Граница тел в отладочном режиме; хранится у [renderBufferManager], см. пояснение там. */
    var showBoundary: Boolean
        get() = renderBufferManager.showBoundary
        set(value) {
            renderBufferManager.showBoundary = value
        }

    /** Все связи в отладочном режиме; хранится у [renderBufferManager]. */
    var showLinks: Boolean
        get() = renderBufferManager.showLinks
        set(value) {
            renderBufferManager.showLinks = value
        }

    var fillAreas: Boolean
        get() = renderBufferManager.fillAreas
        set(value) { renderBufferManager.fillAreas = value }

    var genomeColors: Boolean
        get() = renderBufferManager.genomeColors
        set(value) { renderBufferManager.genomeColors = value }

    /** Нейросвязи в отладочном режиме; хранится у [renderBufferManager]. */
    var showNeuralLinks: Boolean
        get() = renderBufferManager.showNeuralLinks
        set(value) { renderBufferManager.showNeuralLinks = value }

    private lateinit var fontMatrix: Matrix4
    private lateinit var spriteBatch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera


    /** Переиспользуется между кадрами: аллокаций на кадр быть не должно. */
    private val frame = RenderFrame()
    private val selection = FrameCellSelection()

    fun displayedCellAt(x: Float, y: Float): ScreenCell =
        renderBufferManager.currentSnapshot().cellAt(x, y, frame.interpolationLag)

    /** Между тиками, одновременно с постановкой Tap в очередь. Мир не меняется. */
    fun previewSelection(cell: ScreenCell) {
        val cells = renderBufferManager.cellEntity
        val live = cell.aliveIn(cells)
        val target = if (live) cell else cell.copy(index = -1)
        val info = selection.preview(target, renderBufferManager.simulationData.tickCounter, renderBufferManager.currentSnapshot())
        if (live) renderBufferManager.writeCellInfo(info, cell.index)
    }

    /**
     * На паузе: выбор, который оставит очередь команд (UserCommandManager.previewQueued),
     * -1 — снять. Как и [previewSelection], держится до снимка следующего тика.
     */
    fun previewSelected(cellIndex: Int) {
        val epoch = io.github.some_example_name.old.journal.SessionJournal.epoch
        previewSelection(
            if (cellIndex < 0) ScreenCell(-1, 0, epoch)
            else ScreenCell(cellIndex, renderBufferManager.cellEntity.getGeneration(cellIndex), epoch)
        )
    }

    /** Призраки команд, ждущих тика, — на паузе (см. [CommandGhosts]). */
    val commandGhosts = CommandGhosts()

    val opticalSettings by lazy(LazyThreadSafetyMode.NONE){io.github.some_example_name.render.microscope.MicroscopeSettings.gameDefaults()}

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

    fun refreshPausedSnapshot()=renderBufferManager.updateBuffer()

    fun render(menu: io.github.some_example_name.render.microscope.MenuMicroscopeBackground? = null) {
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

        if (renderBufferManager.simulationData.isPlay) {
            animationTime = RenderFrame.advanceTime(animationTime, Gdx.graphics.deltaTime)
        }

        frame.apply {
            cameraProjection = camera.combined
            cells = cellsOf(snapshot, fresh)
            val useOptics = mode == RenderMode.TISSUE_MAP && snapshot.hasOptics
            opticalLinks = if(useOptics) snapshot.opticalLinks else null
            opticalGroups = if(useOptics) snapshot.opticalGroups else null
            opticalColors = if(useOptics) snapshot.opticalColors else null
            opticalBirthTicks = if(useOptics) snapshot.opticalBirthTicks else null
            opticalCullRanges = if(useOptics) snapshot.opticalCullRanges else null
            // Рамки тел отладочного снимка — для отсечения по камере (VisibleCells).
            cellCullRanges = if (mode == RenderMode.DEBUG && snapshot.hasCellCull) snapshot.opticalCullRanges else null
            // Неподвижный слой — один объект на мир: пока он тот же, на GPU уже лежат его байты.
            val still = if (mode == RenderMode.DEBUG || mode == RenderMode.NOSTALGIC) snapshot.staticCells else null
            staticCells = still?.cells
            staticCullRanges = still?.cull
            opticalSettings = menu?.settings ?: (if(useOptics) this@RenderSystem.opticalSettings else null)
            areaIndices = if (mode == RenderMode.DEBUG && fillAreas) snapshot.areaIndices else null
            uploadCells = fresh || packedNow
            interpolationLag = lag
            overlay = if (mode.drawsDebugOverlay && snapshot.hasOverlay) snapshot.overlay.end() else null
            pheromones = if (mode.drawsDebugOverlay && snapshot.hasOverlay) snapshot.pheromones.end() else null
            pheromoneK = PheromonesManager.K
            pheromoneP = PheromonesManager.P
            time = animationTime
            this.mode = if(mode==RenderMode.TISSUE_MAP && !useOptics) RenderMode.DEBUG else mode
            // Из снимка, а не из выбора игрока: байты фильтра в клетках упакованы под него.
            debugFilter = snapshot.filter
        }
        if (menu == null) worldRenderer.render(frame) else menu.render(frame)

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

        commandGhosts.draw(shapeRenderer, snapshot)
        val selected = selection.forFrame(snapshot, fresh)
        moveCameraAndDrawSelected(selected, lag)
        drawPlayerMarks()

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST)
        Gdx.gl.glDepthMask(false)
        Gdx.gl.glEnable(GL20.GL_BLEND)

        if (isRenderUi) {
            drawTextSimInfo(spec, selected)
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

    /**
     * Отметки игрока (PlayerMarks): у живой отмеченной клетки — пурпурное кольцо, у погибшей —
     * красный крест на месте гибели.
     */
    private fun drawPlayerMarks() {
        val marks = renderBufferManager.playerMarks ?: return
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        Gdx.gl.glLineWidth(3f)
        marks.forEach { m ->
            if (m.alive || m.cell < 0) {
                shapeRenderer.color = Color.MAGENTA
                shapeRenderer.circle(m.x, m.y, 0.8f, 32)
            } else {
                shapeRenderer.color = Color.RED
                shapeRenderer.line(m.x - 0.6f, m.y - 0.6f, m.x + 0.6f, m.y + 0.6f)
                shapeRenderer.line(m.x - 0.6f, m.y + 0.6f, m.x + 0.6f, m.y - 0.6f)
            }
        }
        shapeRenderer.end()
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

    private fun drawTextSimInfo(spec: RenderSpecificBufferData, selected: RenderSpecificBufferData) {
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
        if (selected.isCellSelected) {
            text.append("\n\nCell type ").append(selected.cellName)
            text.append("\nNeuronImpulseInput ").append(selected.neuronImpulseInput)
            text.append("\nNeuronImpulseOutput ").append(selected.neuronImpulseOutput)
            text.append("\nSelected cell index ").append(selected.selectedCellIndex)
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
