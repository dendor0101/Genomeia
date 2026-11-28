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
import io.github.some_example_name.old.core.ui.makeStyledNP
import io.github.some_example_name.old.features.settings.GlobalSettings
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
    attachScreenshot: Boolean = false,
    private val onClosed: () -> Unit
) : VisDialog(bundle.get(titleKey(kind, previousLaunch))) {

    private var bug: FileHandle? = existing
    private var crashLogDialog: CrashLogDialog? = null
    private val textures = mutableListOf<Texture>()
    private lateinit var description: VisTextArea
    private lateinit var status: VisLabel
    private lateinit var imagesLabel: VisLabel
    private lateinit var imagesRow: VisTable
    private lateinit var imagesCell: Cell<Actor>
    private var closed = false
    private var resultDialog: BugMessageDialog? = null

    private var includeScreenshot = attachScreenshot
    private val gameImage = screenshot?.let { BugImage(BugReports.SCREENSHOT_NAME, it) }
    private val attached = ArrayList<BugImage>()

    /** Что уже записано в .bug: перепаковывать, только если что-то поменялось. */
    private var written = ReportDraft()
    private val delivery = ReportDelivery(::upload, { bug?.let(::markHandled) }, ::uploadResult)

    private val bodyCell: Cell<VisTable>
    private val scrollCell: Cell<ScrollPane>
    private val actionsCell: Cell<VisTable>

    init {
        setupTitleSize(game)
        isModal = true
        isMovable = true
        titleLabel.setEllipsis(false)
        titleLabel.setWrap(true)
        titleTable.getCell(titleLabel).minWidth(0f).growX()

        // Прокручивается только содержимое: на телефоне в высоту оно не влезает. Ширина у
        // него фиксированная — иначе длинный текст растянул бы его шире окна. Кнопки
        // действий — под прокруткой, всегда на виду.
        val content = globalVisTable { composeContent() }
        val holder = VisTable()
        bodyCell = holder.add(content)
        val scroll = ScrollPane(holder).apply {
            setScrollingDisabled(true, false)
            setFadeScrollBars(false)
            setOverscroll(false, false)
        }
        scrollCell = contentTable.add(scroll).minHeight(0f)
        contentTable.row()
        actionsCell = contentTable.add(globalVisTable { composeActions() })

        closeOnEscape()
        fitToViewport()
    }

    override fun getPrefWidth() = minOf(super.getPrefWidth(), w)
    override fun getPrefHeight() = minOf(super.getPrefHeight(), h)

    fun fitToViewport() {
        val bodyWidth = minOf(w - padLeft - padRight - 8.dp(), 640.dp()).coerceAtLeast(1f)
        val closeWidth = titleTable.getCell(titleTable.children.peek()).prefWidth
        titleLabel.width = (bodyWidth - titleTable.padLeft - titleTable.padRight - closeWidth).coerceAtLeast(1f)
        titleTable.invalidateHierarchy()
        padTop(titleTable.prefHeight)
        bodyCell.width((bodyWidth - SCROLLBAR_ROOM.dp()).coerceAtLeast(1f))
        actionsCell.width(bodyWidth)
        scrollCell.width(bodyWidth).maxHeight((h - padTop - padBottom - actionsCell.actor.prefHeight - 20.dp()).coerceAtLeast(1f))
        bodyCell.table.invalidateHierarchy()
        scrollCell.table.invalidateHierarchy()
        pack()
        setSize(minOf(width, w), minOf(height, h))
        centerWindow()
        resultDialog?.fitToViewport()
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

    /** Просмотр лога отдельно от пояснения и кнопок отправки отчёта. */
    private fun VisTable.composeCrashText() {
        val button = makeStyledButton(bundle.get("bug.showCrash"), game, textures).apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) = showCrashLog()
            })
        }
        add(button).growX()
        row()
    }

    private fun showCrashLog() {
        val stage = stage ?: return
        crashLogDialog?.let {
            it.toFront()
            return
        }
        val text = bug?.let { runCatching { BugFile.readEntry(it.file(), BugFile.CRASH) }.getOrNull() }
            ?.toString(Charsets.UTF_8) ?: bundle.get("bug.crashMissing")
        val viewer = CrashLogDialog(text) { crashLogDialog = null }
        crashLogDialog = viewer
        viewer.show(stage)
    }

    private fun makeClearButton(): VisTextButton =
        makeStyledButton(bundle.get("bug.clearImages"), game, textures).apply {
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) {
                    attached.clear()
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

            val sendStyle = VisTextButton.VisTextButtonStyle().apply {
                font = game.buttonFont
                fontColor = Color.WHITE
                up = makeStyledNP(Color.valueOf("124741"), Color.valueOf("5ef5cd"), textures)
                over = makeStyledNP(Color.valueOf("196256"), Color.valueOf("baffeb"), textures)
                down = makeStyledNP(Color.valueOf("237769"), Color.WHITE, textures)
            }
            val send = VisTextButton(bundle.get("bug.send"), sendStyle)
            send.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent?, actor: Actor?) = submit()
            })
            add(send).minHeight(48.dp())
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
        return listOfNotNull(gameImage?.takeIf { includeScreenshot }) + attached
    }

    // ==================== .bug ====================

    /** .bug с текущими пояснением и картинками: собрать, если ещё нет, или переписать. */
    private fun draft() = ReportDraft(typedDescription(), images())

    private fun prepare(draft: ReportDraft): FileHandle {
        val current = bug
        val file = when {
            current == null -> BugReports.create(kind, draft.description, images = draft.images)
            draft != written -> current.also { BugReports.rewrite(it, draft.description, draft.images) }
            else -> current
        }
        bug = file
        written = draft
        return file
    }

    private fun ensureBug(): FileHandle? {
        return try {
            prepare(draft())
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

    fun autoSendCrash() {
        if (kind != BugKind.CRASH || !GlobalSettings.AUTO_SEND_CRASH || BugReplay.isActive) return
        showStatus(bundle.get("bug.autoSending"))
        delivery.send(ReportDraft(), manual = false)
    }

    private fun submit() {
        showStatus(bundle.get("bug.sending"))
        delivery.send(draft())
    }

    private fun upload(draft: ReportDraft, done: (Boolean, String) -> Unit) {
        try {
            BugUploader.upload(prepare(draft), done)
        } catch (e: Exception) {
            done(false, e.message ?: e::class.java.simpleName)
        }
    }

    private fun uploadResult(success: Boolean, manual: Boolean, message: String) {
        if (success && manual) BugOverlay.reportSentByPlayer()
        if (closed) return
        val stage = stage ?: return
        val title = if (success) "bug.sent" else "bug.deliveryFailed"
        showStatus(if (success) bundle.get(title) else bundle.get("bug.sendFailed") + message)
        val body = when {
            !success -> "bug.shareInstead"
            manual -> "bug.thankYou"
            else -> "bug.autoReceived"
        }
        resultDialog?.close()
        val notice = BugMessageDialog(title, bundle.get(body)) { resultDialog = null }
        if (!success) {
            val fallback = if (Gdx.app.type == Application.ApplicationType.Desktop) "bug.openFolder" else "bug.share"
            notice.choice(fallback, ::share)
        }
        notice.choice("button.ok")
        resultDialog = notice
        notice.show(stage)
    }

    private fun showStatus(text: String) {
        status.setText(text)
        fitToViewport()
    }

    public override fun close() {
        if (!closed) {
            closed = true
            resultDialog?.close()
            crashLogDialog?.remove()
            crashLogDialog = null
            // Пояснение и картинки, изменённые после последней кнопки, не должны потеряться.
            if (bug != null && draft() != written) ensureBug()
            if (!delivery.sending) bug?.let(::markHandled)
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
