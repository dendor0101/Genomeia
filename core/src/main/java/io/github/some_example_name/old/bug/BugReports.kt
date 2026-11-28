package io.github.some_example_name.old.bug

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.GL20
import io.github.some_example_name.old.bug.BugFile.Content
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.core.BuildInfo
import io.github.some_example_name.old.core.GameVersion
import io.github.some_example_name.old.core.log.CrashReport
import io.github.some_example_name.old.journal.Channel
import io.github.some_example_name.old.journal.Fnv
import io.github.some_example_name.old.journal.JournalFormat
import io.github.some_example_name.old.journal.SessionAttachments
import io.github.some_example_name.old.journal.SessionJournal
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Картинка в отчёте: снимок экрана игры или то, что игрок приложил сам.
 * [name] — имя файла внутри .bug (в images/).
 */
class BugImage(val name: String, val bytes: ByteArray)

enum class BugKind(val label: String) {
    /** Игра упала: .bug собирается сам, в обработчике исключения. */
    CRASH("crash"),

    /** Игрок нажал кнопку-жука. */
    REPORT("report")
}

/**
 * Сборка .bug из текущей сессии (формат — BugFile) и пометка «был краш».
 *
 * Собирается всегда в папку bugs рядом с игрой (на Android — в личную папку приложения):
 * на ПК файл можно достать руками, на телефоне им делятся через системное окно.
 */
object BugReports {

    private var sessionStartedAt = Date()

    /**
     * Окружение для манифеста — запоминается заранее, пока игра жива.
     *
     * При краше в потоке рендера окно уже закрыто, и любой вопрос к Gdx.graphics, который
     * идёт в GLFW (плотность экрана, монитор), роняет уже саму JVM — нативно, мимо всех
     * try/catch, и .bug не успевает записаться. Поэтому в момент краша здесь только
     * готовые строки.
     */
    @Volatile
    private var environment: Map<String, String> = emptyMap()

    /** Начало сессии — для манифеста. */
    fun startSession() {
        sessionStartedAt = Date()
        DeviceId.get()
    }

    /**
     * Временные файлы недособранных .bug (снимок журнала, .tmp): остаются, если процесс
     * умер посреди сборки — например, JVM упала нативно вместе с окном игры.
     */
    fun removeLeftovers() {
        runCatching {
            GameFiles.bugsDir().list()
                .filter { !it.isDirectory && (it.name().endsWith(".tmp") || it.name().endsWith(JOURNAL_SNAPSHOT)) }
                .forEach { it.delete() }
        }
    }

    /**
     * Поток рендера: запомнить экран, язык, устройство и видеокарту (на старте и при
     * каждом изменении размера окна).
     */
    fun rememberEnvironment() {
        environment = runCatching {
            val values = linkedMapOf(
                "platform" to Gdx.app.type.name,
                "platformVersion" to Gdx.app.version.toString(),
                "screen" to "${Gdx.graphics.width}x${Gdx.graphics.height}",
                "density" to Gdx.graphics.density.toString(),
                "locale" to DIGameGlobalContainer.currentLocale.toLanguageTag()
            )
            // Видеокарта — частая причина багов рендера: «только на Mali», «только на Intel».
            runCatching {
                values["gpu"] = Gdx.gl.glGetString(GL20.GL_RENDERER) ?: "?"
                values["gpu.vendor"] = Gdx.gl.glGetString(GL20.GL_VENDOR) ?: "?"
                values["gl"] = Gdx.gl.glGetString(GL20.GL_VERSION) ?: "?"
            }
            runCatching {
                DIGameGlobalContainer.game.multiPlatformFileProvider.deviceInfo().forEach { (key, value) ->
                    values[BugMeta.DEVICE_PREFIX + key] = value
                }
            }
            values
        }.getOrDefault(emptyMap())
    }

    /**
     * Собрать .bug. Каждая часть — по возможности: в аварийном состоянии лучше отчёт без
     * лога, чем никакого отчёта.
     */
    fun create(
        kind: BugKind,
        description: String,
        crash: Throwable? = null,
        crashThread: Thread? = null,
        images: List<BugImage> = emptyList()
    ): FileHandle {
        val now = Date()
        val target = GameFiles.bugsDir().child(fileName(kind, now)).file()
        val temporary = ArrayList<File>()

        try {
            val entries = ArrayList<Pair<String, Content>>()

            val snapshot = SessionJournal.snapshot(File(target.parentFile, target.name + JOURNAL_SNAPSHOT).also { temporary += it })

            val manifest = manifest(kind, now, description, crash, crashThread, snapshot, images)
            entries += BugFile.MANIFEST to Content.Bytes(BugFile.formatManifest(manifest))
            entries += BugFile.META to Content.Bytes(BugMeta.json(manifest, target.name, null).toByteArray(Charsets.UTF_8))
            if (description.isNotBlank()) {
                entries += BugFile.DESCRIPTION to Content.Bytes(description.toByteArray(Charsets.UTF_8))
            }
            if (crash != null) {
                val report = runCatching { CrashReport.build(crash) }.getOrElse { crash.stackTraceToString() }
                entries += BugFile.CRASH to Content.Bytes(report.toByteArray(Charsets.UTF_8))
            }
            runCatching {
                SessionLog.files().forEach { entries += it.name to Content.Bytes(SessionLog.readUtf8(it)) }
            }
            if (snapshot != null) {
                entries += BugFile.JOURNAL to Content.FromFile(snapshot.file)
                runCatching { entries += BugFile.ACTIONS to Content.Bytes(renderActions(snapshot.file)) }
            }
            images.forEach { entries += (BugFile.IMAGES + it.name) to Content.Bytes(it.bytes) }
            SessionAttachments.dir?.file()?.takeIf { it.isDirectory }?.let { dir ->
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    entries += (BugFile.ATTACHMENTS + file.relativeTo(dir).invariantSeparatorsPath) to Content.FromFile(file)
                }
            }

            BugFile.write(target, entries)
        } finally {
            temporary.forEach { it.delete() }
        }
        return Gdx.files.absolute(target.absolutePath)
    }

    /**
     * Игрок поменял пояснение или картинки уже после того, как .bug собран (краш собирается
     * сам, отчёт — при первой кнопке): перепаковать с новыми.
     */
    fun rewrite(bug: FileHandle, description: String, images: List<BugImage>) {
        val file = bug.file()
        val entries = BugFile.readAll(file)
        val manifest = entries[BugFile.MANIFEST]?.let { BugFile.parseManifest(it) }.orEmpty().toMutableMap()
        manifest["description"] = descriptionLine(description)
        putImages(manifest, images)
        entries[BugFile.MANIFEST] = BugFile.formatManifest(manifest)
        entries[BugFile.META] = BugMeta.json(manifest, file.name, null).toByteArray(Charsets.UTF_8)

        if (description.isBlank()) entries.remove(BugFile.DESCRIPTION)
        else entries[BugFile.DESCRIPTION] = description.toByteArray(Charsets.UTF_8)

        entries.keys.removeAll { it.startsWith(BugFile.IMAGES) || it == LEGACY_SCREENSHOT }
        images.forEach { entries[BugFile.IMAGES + it.name] = it.bytes }

        BugFile.write(file, entries.map { (name, data) -> name to Content.Bytes(data) })
    }

    /** meta.json из .bug — для отправки на сервер вместе с файлом. */
    fun meta(bug: FileHandle): String {
        val file = bug.file()
        BugFile.readEntry(file, BugFile.META)?.let { return it.toString(Charsets.UTF_8) }
        // .bug старой сборки, без meta.json: собрать из манифеста.
        val manifest = BugFile.readEntry(file, BugFile.MANIFEST)?.let { BugFile.parseManifest(it) }.orEmpty()
        return BugMeta.json(manifest, file.name, file.length())
    }

    // ==================== Краш при прошлом запуске ====================

    /** Отметить: этот .bug — краш, о котором игрок ещё не знает. */
    fun markPendingCrash(bug: FileHandle) {
        runCatching { pendingMarker().writeString(bug.name(), false, "UTF-8") }
    }

    /** .bug краша, о котором игроку ещё не показали окно, или null. */
    fun pendingCrash(): FileHandle? {
        val marker = pendingMarker()
        if (!marker.exists()) return null
        val name = runCatching { marker.readString("UTF-8").trim() }.getOrNull()
        val bug = name?.let { GameFiles.bugsDir().child(it) }
        return bug?.takeIf { it.exists() } ?: run {
            marker.delete()
            null
        }
    }

    /**
     * Игрок видел окно краша [bug] — снять пометку. Только если она про него: пока окно
     * было открыто, мог случиться новый краш, и пометка уже про новый.
     */
    fun clearPendingCrash(bug: FileHandle) {
        val marker = pendingMarker()
        val name = runCatching { marker.readString("UTF-8").trim() }.getOrNull()
        if (name == bug.name()) marker.delete()
    }

    private fun pendingMarker(): FileHandle = GameFiles.bugsDir().child(".pending-crash")

    private const val JOURNAL_SNAPSHOT = ".journal"

    /** Имя снимка экрана игры внутри images/. */
    const val SCREENSHOT_NAME = "screenshot.png"

    /** Где снимок лежал в .bug первых сборок — убирается при перепаковке. */
    private const val LEGACY_SCREENSHOT = "screenshot.png"

    private const val TOP_FRAMES = 8
    private const val SIGNATURE_FRAMES = 5

    // ==================== Части .bug ====================

    /** Дата и время в имени — чтобы файлы ложились по порядку; ID — чтобы видеть, чей это. */
    private fun fileName(kind: BugKind, time: Date): String =
        "genomeia-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(time)}-${DeviceId.get()}-${kind.label}.${BugFile.EXTENSION}"

    private fun manifest(
        kind: BugKind,
        time: Date,
        description: String,
        crash: Throwable?,
        crashThread: Thread?,
        journal: SessionJournal.Snapshot?,
        images: List<BugImage>
    ): Map<String, String> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT)
        val values = LinkedHashMap<String, String>()
        values["format"] = BugFile.VERSION.toString()
        values["game"] = GameVersion.NAME
        // Из какого коммита собрана игра: повтор, не сошедшийся на свежей ветке, можно
        // запустить на ней (BugReplay предупредит о другом коммите). dirty — сборка с
        // незакоммиченными правками, коммит описывает её не целиком.
        values["build.commit"] = BuildInfo.COMMIT
        values["build.branch"] = BuildInfo.BRANCH
        values["build.dirty"] = BuildInfo.DIRTY.toString()
        // Движок физики (RustEngine): мир у Kotlin и Rust побитово один, повтор идёт на любом — поле, чтобы знать, на
        // чём случились падение или тормоза. Отчёты до 27.11 его не знают.
        values["physics.engine"] = runCatching { io.github.some_example_name.physics.RustEngine.name }.getOrDefault("?")
        values["kind"] = kind.label
        values["created"] = iso.format(time)
        values["session.started"] = iso.format(sessionStartedAt)
        values["deviceId"] = DeviceId.get()
        values += environment
        values["os"] = "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}"
        values["java"] = System.getProperty("java.version") ?: "?"
        values["description"] = descriptionLine(description)
        putImages(values, images)
        values["route"] = runCatching {
            DIGameGlobalContainer.navigationCommandsManager.currentPath().joinToString(" -> ")
        }.getOrDefault("?")
        values["final.epoch"] = SessionJournal.epoch.toString()
        values["final.tick"] = (SessionJournal.simTick?.invoke() ?: -1).toString()
        if (journal != null) {
            values["journal.complete"] = journal.complete.toString()
            values["journal.ui"] = journal.uiCount.toString()
            values["journal.uiHash"] = Fnv.hex(journal.uiHash)
            values["journal.sim"] = journal.simCount.toString()
            values["journal.simHash"] = Fnv.hex(journal.simHash)
        }
        if (SessionJournal.unregistered > 0) values["journal.unregistered"] = SessionJournal.unregistered.toString()
        if (crash != null) {
            values["crash.exception"] = crash::class.java.name
            values["crash.message"] = crash.message?.lineSequence()?.firstOrNull()?.take(300) ?: ""
            values["crash.thread"] = crashThread?.name ?: "?"
            // Первопричина: стадия-обёртка «воркер упал» ничего не говорит о том, где упало.
            val root = generateSequence(crash) { it.cause }.last()
            if (root !== crash) values["crash.rootCause"] = root::class.java.name
            values["crash.top"] = root.stackTrace.take(TOP_FRAMES)
                .joinToString(" | ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            values["crash.signature"] = crashSignature(root)
        }
        return values
    }

    private fun putImages(values: MutableMap<String, String>, images: List<BugImage>) {
        values["images"] = images.size.toString()
        values["screenshot"] = images.any { it.name == SCREENSHOT_NAME }.toString()
    }

    /**
     * Подпись краша для группировки: класс исключения и пять верхних кадров — без номеров
     * строк, чтобы правка соседнего кода не делила одну ошибку на две группы.
     */
    internal fun crashSignature(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val key = buildString {
            append(root::class.java.name)
            root.stackTrace.take(SIGNATURE_FRAMES).forEach { append('|').append(it.className).append('.').append(it.methodName) }
        }
        return Fnv.hex(Fnv.bytes(Fnv.OFFSET, key.toByteArray(Charsets.UTF_8)))
    }

    /** Описание одной строкой для манифеста (целиком оно лежит в description.txt). */
    private fun descriptionLine(text: String): String =
        text.lineSequence().joinToString(" / ").take(300)

    /**
     * Журнал текстом: одна команда — одна строка. Вложенные (переход, который сделал
     * пункт меню) — с отступом под своей внешней.
     */
    private fun renderActions(journal: File): ByteArray {
        val result = journal.inputStream().use { JournalFormat.read(it) }
        val text = StringBuilder(result.entries.size * 48)
        text.append("# время     эпоха тик     канал источник  команда\n")
        for (entry in result.entries) {
            val seconds = entry.timeMs / 1000
            text.append(String.format(Locale.ROOT, "[%4d:%02d.%03d] ", seconds / 60, seconds % 60, entry.timeMs % 1000))
            text.append('E').append(entry.epoch).append(' ')
            text.append(if (entry.tick >= 0) "T${entry.tick}" else "-").append(' ')
            text.append(if (entry.channel == Channel.SIM) "SIM " else "UI  ")
            if (entry.nested) text.append("  -> ")
            text.append(entry.source.label).append(' ')
            val command = entry.command
            if (command == null) {
                text.append("#").append(entry.typeId)
            } else {
                text.append(command.name)
                if (command.detail.isNotEmpty()) text.append('(').append(command.detail).append(')')
            }
            text.append('\n')
        }
        if (result.truncated) text.append("# журнал оборван здесь\n")
        return text.toString().toByteArray(Charsets.UTF_8)
    }
}
