package io.github.some_example_name.old.bug

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Timer
import com.kotcrab.vis.ui.widget.VisDialog
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextArea
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.globalVisTable
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.makeStyledButton
import io.github.some_example_name.old.core.ui.makeStyledTextField
import io.github.some_example_name.old.core.ui.setupTitleSize
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.core.ui.w

/**
 * Окно отчёта об ошибке: пояснение игрока, картинки и что сделать с .bug — сохранить,
 * поделиться, отправить на сервер (см. BugUploader).
 *
 * Отчёт собирается не при открытии окна, а при первой кнопке: в него должно попасть
 * пояснение. Для краша .bug уже собран обработчиком. Поменяли текст или картинки после
 * сохранения — следующая кнопка перепишет файл.
 *
 * КАРТИНКИ
 * --------
 * Снимок экрана игры снимается при нажатии на жука (на нём то, что игрок видел, без этого
 * окна), но прикладывается, только если отметить галочку: часто он не нужен, а ошибки на
 * нём может и не быть. Своё игрок прикладывает сам — из буфера обмена или из галереи
 * (на ПК — файлом).
 *
 * Само окно и всё, что в нём делается, — не команды игры: в журнал оно не пишется.
 */
class BugReportDialog(
    private val kind: BugKind,
    /** Уже собранный .bug (краш) или null — соберётся при первой кнопке. */
    existing: FileHandle?,
    /** Снимок экрана игры на момент нажатия жука, или null (краш — снять нечего). */
    private val screenshot: ByteArray?,
    /** Уже набранное пояснение — когда окно краша сменяет недописанный отчёт. */
    private val initialDescription: String = "",
    /** Краш случился при прошлом запуске, а не только что: окно предупреждает, что игра уже жива. */
    private val previousLaunch: Boolean = false,
    private val onClosed: () -> Unit
) : VisDialog(bundle.get(titleKey(kind, previousLaunch))) {

    private var bug: FileHandle? = existing
    private var crashLabel: VisLabel? = null
    private var crashButton: VisTextButton? = null
    private var crashShown = false
    private val textures = mutableListOf<Texture>()
    private lateinit var description: VisTextArea
    private lateinit var status: VisLabel
    private lateinit var imagesLabel: VisLabel
    private lateinit var imagesRow: VisTable
    private lateinit var imagesCell: Cell<Actor>
    private var closed = false
    private var sending = false

    private var includeScreenshot = false
    private val attached = ArrayList<BugImage>()

    /** Что уже записано в .bug: перепаковывать, только если что-то поменялось. */
    private var writtenDescription = ""
    private var changes = 0
    private var writtenChanges = 0

    init {
        setupTitleSize(game)
        isModal = true
        isMovable = true

        val width = minOf(w * 0.9f, 640f.dp())

        // Прокручивается только содержимое: на телефоне в высоту оно не влезает. Ширина у
        // него фиксированная — иначе длинный текст растянул бы его шире окна. Кнопки
        // действий — под прокруткой, всегда на виду.
        val content = globalVisTable { composeContent() }
        val holder = VisTable().apply { add(content).width(width - SCROLLBAR_ROOM.dp()) }
        val scroll = ScrollPane(holder).apply {
            setScrollingDisabled(true, false)
            setFadeScrollBars(false)
            setOverscroll(false, false)
        }
        contentTable.add(scroll).width(width).maxHeight(h * 0.6f)
        contentTable.row()
        contentTable.add(globalVisTable { composeActions() }).width(width)

        closeOnEscape()
        pack()
        centerWindow()
    }

    private fun VisTable.composeContent() {
        defaults().pad(6.dp())

        // Явная строка вверху: краш это или обращение игрока (жук) — по тексту окна не всегда ясно.
        val crash = kind == BugKind.CRASH
        visLabel(
            text = bundle.get(if (crash) "bug.banner.crash" else "bug.banner.report"),
            font = game.mediumFont,
            textColor = if (crash) CRASH_COLOR else REPORT_COLOR
        ) { growX() }.setWrap(true)
        row()

        val textKey = when {
            !crash -> "bug.text.report"
            previousLaunch -> "bug.text.crashPrevious"
            else -> "bug.text.crash"
        }
        visLabel(text = bundle.get(textKey), font = game.mediumFont) { growX() }.setWrap(true)
        row()

        if (crash) composeCrashText()

        val style = makeStyledTextField(game, textures).style as VisTextField.VisTextFieldStyle
        description = VisTextArea(initialDescription, style).apply {
            messageText = bundle.get("bug.hint")
            setPrefRows(4f)
        }
        add(description).growX().minHeight(110f.dp())
        row()

        // --- Картинки ---
        if (screenshot != null) {
            visToggleButton(
                text = bundle.get("bug.screenshot"),
                checked = includeScreenshot,
                onCheckedChange = {
                    includeScreenshot = it
                    changes++
                }
            ) { growX() }
            row()
        }

        visTable({ growX() }) {
            defaults().pad(4.dp()).growX().uniformX()
            visTextButton(bundle.get("bug.pasteImage"), onClick = { pasteImage() })
            val isPhone = Gdx.app.type == Application.ApplicationType.Android
            visTextButton(bundle.get(if (isPhone) "bug.pickImagePhone" else "bug.pickImage"), onClick = { pickImage() })
        }
        row()

        // Строка «приложено N» — только когда есть что показать: пустая занимала бы место.
        imagesRow = VisTable().apply {
            imagesLabel = VisLabel("", Label.LabelStyle(game.mediumFont, Color.WHITE))
            add(imagesLabel).growX().left()
            add(makeClearButton()).pad(4.dp())
        }
        imagesCell = add().growX()
        row()
        refreshImages()

        // --- Приватность ---
        visLabel(text = bundle.get("bug.privacy"), font = game.mediumFont, textColor = PRIVACY_COLOR) { growX() }
            .setWrap(true)
    }

    /** Кнопка «показать текст краша» и скрытый до нажатия текст из crash.txt внутри .bug. */
    private fun VisTable.composeCrashText() {
        val label = VisLabel("", Label.LabelStyle(game.smallFont, Color.WHITE)).apply { setWrap(true) }
        crashLabel = label
        val button = makeStyledButton(bundle.get("bug.showCrash"), game, textures).apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) = toggleCrashText()
            })
        }
        crashButton = button
        add(button).growX()
        row()
        // Ячейка с текстом пока пустая: пустой label занимал бы место.
        crashCell = add().growX()
        row()
    }

    private var crashCell: Cell<Actor>? = null

    private fun toggleCrashText() {
        val label = crashLabel ?: return
        crashShown = !crashShown
        if (crashShown && label.text.isEmpty()) {
            val text = bug?.let { runCatching { BugFile.readEntry(it.file(), BugFile.CRASH) }.getOrNull() }
                ?.toString(Charsets.UTF_8)
            label.setText(text ?: bundle.get("bug.crashMissing"))
        }
        crashCell?.setActor(if (crashShown) label else null)
        crashButton?.setText(bundle.get(if (crashShown) "bug.hideCrash" else "bug.showCrash"))
        label.invalidateHierarchy()
        pack()
    }

    private fun makeClearButton(): VisTextButton =
        makeStyledButton(bundle.get("bug.clearImages"), game, textures).apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    attached.clear()
                    changes++
                    refreshImages()
                }
            })
        }

    private fun VisTable.composeActions() {
        defaults().pad(6.dp())

        status = visLabel("", font = game.mediumFont, align = Align.center) { growX() }
        status.setWrap(true)
        row()

        // Сеткой 2×2: в один ряд четыре кнопки не влезают в окно на телефоне.
        visTable({ growX() }) {
            defaults().pad(4.dp()).growX().uniformX()
            visTextButton(bundle.get("bug.save"), onClick = { save() })

            val isPhone = Gdx.app.type == Application.ApplicationType.Android
            visTextButton(bundle.get(if (isPhone) "bug.share" else "bug.openFolder"), onClick = { share() })
            row()

            visTextButton(bundle.get("bug.send"), onClick = { send() })
            visTextButton(bundle.get("bug.close"), onClick = { close() })
        }
    }

    /** Что игрок успел написать. */
    fun typedDescription(): String = description.text.trim()

    // ==================== Картинки ====================

    private fun pasteImage() {
        val image = game.multiPlatformFileProvider.clipboardImage()
        if (image == null) {
            showStatus(bundle.get("bug.noClipboardImage"))
            return
        }
        attach(image.first, "clipboard.${image.second}")
    }

    private fun pickImage() {
        game.multiPlatformFileProvider.pickImage { bytes, name ->
            // Окно выбора отвечает не в поток игры.
            Gdx.app.postRunnable {
                if (bytes != null && !closed) attach(bytes, name ?: "image.png")
            }
        }
    }

    private fun attach(bytes: ByteArray, originalName: String) {
        when {
            attached.size >= MAX_IMAGES -> showStatus(bundle.get("bug.tooManyImages"))
            bytes.size > MAX_IMAGE_BYTES -> showStatus(bundle.get("bug.imageTooBig"))
            else -> {
                attached += BugImage("${attached.size + 1}-${safeName(originalName)}", bytes)
                changes++
                refreshImages()
                showStatus("")
            }
        }
    }

    private fun refreshImages() {
        imagesLabel.setText(bundle.get("bug.images") + attached.size)
        imagesCell.setActor(if (attached.isEmpty()) null else imagesRow)
        imagesRow.invalidateHierarchy()
        if (::status.isInitialized) pack()
    }

    /** Снимок (если отмечен) и приложенное игроком — то, что уйдёт в images/ внутри .bug. */
    private fun images(): List<BugImage> {
        val shot = screenshot?.takeIf { includeScreenshot }?.let { BugImage(BugReports.SCREENSHOT_NAME, it) }
        return listOfNotNull(shot) + attached
    }

    // ==================== .bug ====================

    /** .bug с текущими пояснением и картинками: собрать, если ещё нет, или переписать. */
    private fun ensureBug(): FileHandle? {
        val text = description.text.trim()
        return try {
            val current = bug
            when {
                current == null -> BugReports.create(kind, text, images = images()).also { bug = it }
                text != writtenDescription || changes != writtenChanges ->
                    current.also { BugReports.rewrite(it, text, images()) }
                else -> current
            }.also {
                writtenDescription = text
                writtenChanges = changes
            }
        } catch (e: Exception) {
            Gdx.app.error("BugReport", "не удалось собрать отчёт", e)
            showStatus(bundle.get("bug.failed") + (e.message ?: e::class.java.simpleName))
            null
        }
    }

    /**
     * Игрок уже поделился крашем (сохранил, отправил, открыл «поделиться»): при следующем
     * запуске окно повторно не показываем, не дожидаясь закрытия этого окна — игру могут
     * закрыть и раньше.
     */
    private fun markHandled(file: FileHandle) {
        if (kind == BugKind.CRASH) BugReports.clearPendingCrash(file)
    }

    private fun save() {
        val file = ensureBug() ?: return
        markHandled(file)
        showStatus(bundle.get("bug.saved") + file.file().absolutePath)
    }

    private fun share() {
        val file = ensureBug() ?: return
        markHandled(file)
        if (!game.multiPlatformFileProvider.shareFile(file.file(), bundle.get("bug.title.report"))) {
            showStatus(bundle.get("bug.saved") + file.file().absolutePath)
        }
    }

    private fun send() {
        if (sending) return
        val file = ensureBug() ?: return
        sending = true
        showStatus(bundle.get("bug.sending"))
        BugUploader.upload(file) { success, message ->
            sending = false
            if (success) markHandled(file)
            showStatus(if (success) bundle.get("bug.sent") else bundle.get("bug.sendFailed") + message)
        }
    }

    private fun showStatus(text: String) {
        status.setText(text)
        pack()
    }

    public override fun close() {
        if (!closed) {
            closed = true
            // Пояснение и картинки, изменённые после последней кнопки, не должны потеряться.
            if (bug != null && (description.text.trim() != writtenDescription || changes != writtenChanges)) ensureBug()
            if (kind == BugKind.CRASH) bug?.let { BugReports.clearPendingCrash(it) }
            onClosed()
        }
        super.close()
        // Текстуры поля ввода — после того, как окно доиграет исчезновение.
        Timer.schedule(object : Timer.Task() {
            override fun run() {
                textures.forEach { it.dispose() }
                textures.clear()
            }
        }, TEXTURE_DISPOSE_DELAY)
    }

    private companion object {
        const val TEXTURE_DISPOSE_DELAY = 0.6f

        /** Место под полосу прокрутки справа от содержимого, dp. */
        const val SCROLLBAR_ROOM = 16f
        const val MAX_IMAGES = 5
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        val PRIVACY_COLOR = Color(0.78f, 0.74f, 0.66f, 1f)
        val CRASH_COLOR = Color(1f, 0.45f, 0.35f, 1f)
        val REPORT_COLOR = Color(0.55f, 0.85f, 1f, 1f)

        fun titleKey(kind: BugKind, previousLaunch: Boolean): String = when {
            kind != BugKind.CRASH -> "bug.title.report"
            previousLaunch -> "bug.title.crashPrevious"
            else -> "bug.title.crash"
        }

        /** Имя файла внутри .bug: только безопасные символы, расширение сохраняется. */
        fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\')
                .map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
                .joinToString("")
                .takeLast(60)
                .ifEmpty { "image.png" }
    }
}
