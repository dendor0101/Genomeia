package io.github.some_example_name.old.bug

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ScreenViewport
import io.github.some_example_name.old.core.DIGameGlobalContainer
import java.io.ByteArrayOutputStream

/**
 * Слой поверх всех экранов: кнопка-жук «сообщить об ошибке» и окна отчёта, а во время
 * повтора бага — строка его прогресса и итога.
 *
 * Свой Stage, который рисуется после экрана (MyGame.render). Ввод: экраны сами ставят
 * себе Gdx.input.inputProcessor, поэтому слой каждый кадр проверяет, не сменился ли он,
 * и встаёт впереди. Нажатие мимо кнопки и окон слой не забирает — оно уходит экрану.
 */
object BugOverlay {

    private var stage: Stage? = null
    private val input = InputMultiplexer()
    private var icon: Texture? = null
    private var bugButton: ImageButton? = null
    private var statusLabel: Label? = null
    private var statusCell: com.badlogic.gdx.scenes.scene2d.ui.Cell<Label>? = null

    /** Кнопку нажали: в следующем кадре снять экран (уже без окон слоя) и открыть отчёт. */
    private var screenshotRequested = false
    /** Открытое окно отчёта или краша. */
    private var dialog: BugReportDialog? = null

    /** Открыто окно отчёта: клавиатура — его полю ввода, а не игре (см. KeyboardFocus). */
    val isDialogOpen: Boolean get() = dialog != null

    fun create() {
        BugReports.rememberEnvironment()
        val stage = Stage(ScreenViewport())
        this.stage = stage

        val pixmap = drawBugIcon()
        val texture = Texture(pixmap, true).apply {
            setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        }
        pixmap.dispose()
        icon = texture

        val size = BUTTON_DP * Gdx.graphics.density
        val button = ImageButton(TextureRegionDrawable(texture)).apply {
            color = Color(1f, 1f, 1f, 0.8f)
            imageCell.size(size)
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    if (dialog == null) screenshotRequested = true
                }
            })
        }
        bugButton = button

        val status = Label("", Label.LabelStyle(DIGameGlobalContainer.game.mediumFont, Color(1f, 0.95f, 0.75f, 1f))).apply {
            setWrap(true)
            setAlignment(Align.topLeft)
        }
        statusLabel = status

        val pad = 8f * Gdx.graphics.density
        val root = Table().apply {
            setFillParent(true)
            top().right()
            add(button).size(size + pad).pad(pad).top().right().row()
            statusCell = add(status).expandX().fillX().pad(pad).top().right().width(Gdx.graphics.width * STATUS_WIDTH)
        }
        stage.addActor(root)
    }

    /** Открыть окно отчёта, как кнопкой-жуком (снимок экрана — в следующем кадре). */
    fun requestReport() {
        if (dialog == null) screenshotRequested = true
    }

    /** После отрисовки экрана. */
    fun render() {
        val stage = stage ?: return
        attachInput(stage)

        if (screenshotRequested) {
            screenshotRequested = false
            // Экран уже нарисован, окон слоя на нём ещё нет — это и есть то, что видел игрок.
            val png = runCatching { captureScreen() }.getOrNull()
            openReport(BugKind.REPORT, existing = null, screenshot = png, onClosed = {})
        }

        val replaying = BugReplay.isActive
        bugButton?.isVisible = !replaying
        statusLabel?.let { label ->
            val text = BugReplay.status
            if (label.text.toString() != text) label.setText(text)
            label.isVisible = text.isNotEmpty()
        }

        stage.act(Gdx.graphics.deltaTime)
        stage.viewport.apply()
        stage.draw()
    }

    fun resize(width: Int, height: Int) {
        BugReports.rememberEnvironment()
        stage?.viewport?.update(width, height, true)
        statusCell?.width(width * STATUS_WIDTH)
        statusCell?.table?.invalidate()
    }

    fun dispose() {
        stage?.dispose()
        icon?.dispose()
        stage = null
        icon = null
    }

    /**
     * Окно краша. Звать из потока рендера. [onClosed] — когда игрок закрыл окно: для краша
     * в фоновом потоке это сигнал завершить игру, см. CrashHandler.
     */
    fun showCrash(bug: FileHandle, previousLaunch: Boolean = false, onClosed: () -> Unit) {
        // Игра упала, пока игрок писал отчёт: окно краша важнее, но набранный текст не
        // должен пропасть — он переезжает в окно краша и уйдёт в его .bug. Раньше окно
        // краша в такой момент просто не открывалось.
        val typed = dialog?.let { open ->
            val text = open.typedDescription()
            open.close()
            text
        } ?: ""
        openReport(BugKind.CRASH, existing = bug, screenshot = null, description = typed, previousLaunch = previousLaunch, onClosed = onClosed)
    }

    private fun openReport(
        kind: BugKind,
        existing: FileHandle?,
        screenshot: ByteArray?,
        description: String = "",
        previousLaunch: Boolean = false,
        onClosed: () -> Unit
    ) {
        val stage = stage ?: return
        if (dialog != null) return
        lateinit var opened: BugReportDialog
        opened = BugReportDialog(kind, existing, screenshot, description, previousLaunch) {
            if (dialog === opened) dialog = null
            onClosed()
        }
        dialog = opened
        opened.show(stage)
    }

    /** Слой впереди обработчика, который поставил себе текущий экран. */
    private fun attachInput(stage: Stage) {
        val current = Gdx.input.inputProcessor
        if (current === input) return
        input.clear()
        input.addProcessor(stage)
        if (current != null) input.addProcessor(current)
        Gdx.input.inputProcessor = input
    }

    /**
     * Снимок экрана для отчёта — уменьшенный до [SCREENSHOT_MAX_SIDE] по длинной стороне:
     * полный кадр 1920×1080 в PNG — это мегабайт, а чтобы увидеть, что на экране, хватит
     * и меньшего. Приложится, только если игрок отметит галочку в окне отчёта.
     */
    private fun captureScreen(): ByteArray {
        val full = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        val scale = minOf(1f, SCREENSHOT_MAX_SIDE.toFloat() / maxOf(full.width, full.height))
        val pixmap = if (scale >= 1f) full else {
            val width = (full.width * scale).toInt()
            val height = (full.height * scale).toInt()
            Pixmap(width, height, full.format).also {
                it.filter = Pixmap.Filter.BiLinear
                it.blending = Pixmap.Blending.None
                it.drawPixmap(full, 0, 0, full.width, full.height, 0, 0, width, height)
                full.dispose()
            }
        }
        try {
            val png = PixmapIO.PNG(pixmap.width * pixmap.height * 4)
            try {
                png.setFlipY(true)
                val out = ByteArrayOutputStream()
                png.write(out, pixmap)
                return out.toByteArray()
            } finally {
                png.dispose()
            }
        } finally {
            pixmap.dispose()
        }
    }

    /**
     * Жук, нарисованный кодом: ассет ради одной иконки не нужен, а так она одинаково
     * чёткая на любой плотности экрана (рисуется крупно, на экране уменьшается).
     */
    private fun drawBugIcon(): Pixmap {
        val size = 128
        val p = Pixmap(size, size, Pixmap.Format.RGBA8888)
        p.blending = Pixmap.Blending.None
        p.setColor(0f, 0f, 0f, 0f)
        p.fill()
        p.blending = Pixmap.Blending.SourceOver

        val outline = Color(0.08f, 0.06f, 0.05f, 1f)
        val body = Color(0.86f, 0.24f, 0.18f, 1f)
        val head = Color(0.95f, 0.9f, 0.8f, 1f)

        // Лапки и усики — толстыми линиями под телом.
        p.setColor(head)
        for ((y, dx) in listOf(54 to 34, 72 to 38, 90 to 34)) {
            thickLine(p, 64, y, 64 - dx, y + (y - 72) / 2 - 6, 5)
            thickLine(p, 64, y, 64 + dx, y + (y - 72) / 2 - 6, 5)
        }
        thickLine(p, 56, 30, 40, 10, 4)
        thickLine(p, 72, 30, 88, 10, 4)

        // Голова.
        p.setColor(outline)
        p.fillCircle(64, 34, 15)
        p.setColor(head)
        p.fillCircle(64, 34, 12)

        // Тело: эллипс с обводкой и линией между надкрыльями.
        fillEllipse(p, 64, 76, 30, 38, outline)
        fillEllipse(p, 64, 76, 26, 34, body)
        p.setColor(outline)
        thickLine(p, 64, 44, 64, 112, 3)
        p.fillCircle(52, 70, 5)
        p.fillCircle(76, 70, 5)
        p.fillCircle(54, 92, 4)
        p.fillCircle(74, 92, 4)
        return p
    }

    private fun fillEllipse(p: Pixmap, cx: Int, cy: Int, rx: Int, ry: Int, color: Color) {
        p.setColor(color)
        for (y in -ry..ry) {
            val half = (rx * Math.sqrt(1.0 - (y.toDouble() / ry).let { it * it })).toInt()
            p.drawLine(cx - half, cy + y, cx + half, cy + y)
        }
    }

    private fun thickLine(p: Pixmap, x1: Int, y1: Int, x2: Int, y2: Int, width: Int) {
        val r = width / 2
        for (ox in -r..r) for (oy in -r..r) {
            if (ox * ox + oy * oy <= r * r) p.drawLine(x1 + ox, y1 + oy, x2 + ox, y2 + oy)
        }
    }

    private const val BUTTON_DP = 36f
    private const val SCREENSHOT_MAX_SIDE = 1280
    private const val STATUS_WIDTH = 0.6f
}
