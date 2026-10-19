package io.github.some_example_name.old.bug

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.NavigationCommands
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.commands.logName
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.core.GameVersion
import io.github.some_example_name.old.features.simulation.SimulationIntent
import io.github.some_example_name.old.journal.Channel
import io.github.some_example_name.old.journal.ControllerKeys
import io.github.some_example_name.old.journal.Fnv
import io.github.some_example_name.old.journal.JournalEntry
import io.github.some_example_name.old.journal.JournalFormat
import io.github.some_example_name.old.journal.SessionAttachments
import io.github.some_example_name.old.journal.SessionJournal
import io.github.some_example_name.old.journal.SessionStarted
import io.github.some_example_name.old.journal.SimRestarted
import io.github.some_example_name.old.journal.StateHash
import io.github.some_example_name.old.journal.StateHashed
import io.github.some_example_name.old.journal.WorldSaveCommitted
import java.io.File
import java.util.Locale

/**
 * Повтор бага по .bug, присланному игроком.
 *
 * КАК ЗАПУСТИТЬ
 * -------------
 * Положить .bug в папку bug-replay рядом с игрой (при запуске из исходников — в корне
 * проекта) и запустить игру. Если файлов несколько, берётся первый по алфавиту. Пока
 * идёт повтор, игрок только смотрит: живой ввод игнорируется.
 *
 * КАК УСТРОЕН
 * -----------
 * Файлы игрока (геномы, миры, настройки среды) распаковываются в песочницу, и игра на
 * время повтора работает с ними (GameFiles.dataRoot) — у разработчика ничего не
 * перезаписывается.
 *
 * Команды подаются двумя путями, как и записывались:
 *  - UI-команды — отсюда, из потока рендера, по одной и в записанном порядке ([update]).
 *    Команда, нажатая, пока шёл мир, ждёт, пока мир дойдёт до её тика;
 *  - SIM-события — сам поток симуляции забирает в своих тиках: касания мира, клавиши,
 *    сид сессии, рестарт ([forEachCommandAt], [keysAt], [sessionSeed], [restartAt]).
 *
 * Сохранение мира читает мир из потока рендера, поэтому мир держится на тике, где оно
 * случилось у игрока, пока оно не случится и здесь ([mustHold]).
 *
 * ПРОВЕРКА
 * --------
 *  - контрольные суммы журнала из манифеста сверяются с прочитанным файлом — журнал цел;
 *  - те же суммы считаются по командам, которые повтор действительно применил, — всё
 *    применено и в том же порядке;
 *  - хэши состояния мира сверяются в тех же тиках — мир тот же. Первое несовпадение
 *    печатается с эпохой и тиком: дальше этой точки повтору верить нельзя.
 * Итог печатается в консоль и висит в углу экрана.
 */
object BugReplay {

    @Volatile
    var isActive = false
        private set

    /** Сейчас команду подаёт повтор, а не живой ввод. Только поток рендера. */
    var isInjecting = false
        private set

    private var source: File? = null
    private var manifest: Map<String, String> = emptyMap()
    private var started = false

    private var uiEntries: List<JournalEntry> = emptyList()

    /** Следующая UI-команда. Пишет поток рендера, читает и поток симуляции ([mustHold]). */
    @Volatile private var cursor = 0
    private val epochs = HashMap<Int, SimEpoch>()
    private var finalEpoch = 0
    private var finalTick = -1

    private var uiApplied = 0
    private var uiHash = Fnv.OFFSET
    private var expectedUiHash = Fnv.OFFSET
    private var skipped = 0

    private val simLock = Any()
    private var simApplied = 0
    private var simHash = Fnv.OFFSET
    private var simTotal = 0
    private var expectedSimHash = Fnv.OFFSET

    @Volatile private var hashesOk = 0
    @Volatile private var firstMismatch: String? = null

    /**
     * Мир встал на последнем тике записи — тик досчитан целиком. Только по этому повтор
     * и заканчивается: номер тика растёт в НАЧАЛЕ тика, и «дошли до тика» ещё не значит
     * «досчитали его» — краш внутри последнего тика иначе случился бы уже после конца
     * повтора и записался бы новым .bug, как обычный.
     */
    @Volatile private var heldAtEnd = false

    /** Краш, до которого дошёл повтор: где и что. */
    @Volatile private var crash: String? = null

    private val notes = ArrayList<String>()
    private var lastProgressNanos = 0L
    private var lastSeenTick = Int.MIN_VALUE
    @Volatile private var finished = false

    /**
     * Итог повтора: null — ещё идёт (или не начинался), true — всё применено, все суммы
     * и хэши сошлись. Для стендов проверки.
     */
    @Volatile
    var result: Boolean? = null
        private set

    /** Итог для угла экрана; пока идёт повтор — прогресс. */
    @Volatile
    var status: String = ""
        private set

    /** Гнать мир на максимальной скорости — по умолчанию да: повтор нужен, а не просмотр. */
    private val fast = System.getProperty("genomeia.replaySpeed") != "recorded"

    // ==================== Подготовка ====================

    /**
     * Первый по алфавиту .bug в папке повтора, или null — повтора нет.
     *
     * -Dgenomeia.replay=<путь к .bug> — повторить этот файл, -Dgenomeia.replay=off — не
     * повторять ничего (стенды проверок не должны подхватывать чужой .bug из папки).
     */
    fun findInbox(): FileHandle? {
        when (val forced = System.getProperty("genomeia.replay")) {
            null, "" -> Unit
            "off" -> return null
            else -> return Gdx.files.absolute(forced).takeIf { it.exists() }
        }
        val dir = GameFiles.replayInboxDir() ?: return null
        if (!dir.isDirectory) return null
        return dir.list(".${BugFile.EXTENSION}").filter { !it.isDirectory }.minByOrNull { it.name() }
    }

    /**
     * Распаковать .bug и переключить игру на файлы игрока. Звать до того, как кто-либо
     * прочитает геномы или настройки среды, — то есть до контейнеров зависимостей.
     *
     * @return false — файл не читается; игра запускается как обычно.
     */
    fun prepare(bug: FileHandle): Boolean = try {
        val file = bug.file()
        val sandbox = File(file.parentFile, SANDBOX_DIR + File.separator + file.nameWithoutExtension)
        sandbox.deleteRecursively()
        val unpacked = File(sandbox, "bug")
        BugFile.unpack(file, unpacked)

        manifest = File(unpacked, BugFile.MANIFEST).takeIf { it.exists() }
            ?.let { BugFile.parseManifest(it.readBytes()) } ?: emptyMap()

        // Данные игрока: снимки из .bug — в корень данных песочницы.
        val data = File(sandbox, "data")
        val attachments = File(unpacked, BugFile.ATTACHMENTS)
        for (dir in listOf(GameFiles.GENOMES, GameFiles.SETTINGS, GameFiles.MAPS)) {
            File(attachments, dir).takeIf { it.isDirectory }?.copyRecursively(File(data, dir), overwrite = true)
            File(data, dir).mkdirs()
        }
        GameFiles.dataRoot = Gdx.files.absolute(data.absolutePath)
        SessionAttachments.useForReplay(Gdx.files.absolute(attachments.absolutePath))

        loadJournal(File(unpacked, BugFile.JOURNAL))
        source = file
        isActive = true
        printHeader()
        true
    } catch (e: Exception) {
        Gdx.app.error(TAG, "не удалось подготовить повтор ${bug.path()}", e)
        GameFiles.dataRoot = null
        isActive = false
        false
    }

    /** После контейнеров и до первого экрана: окружение игрока, которое влияет на вёрстку. */
    fun applyEnvironment() {
        if (!isActive) return
        manifest["locale"]?.takeIf { it.isNotBlank() }?.let {
            DIGameGlobalContainer.setLanguage(Locale.forLanguageTag(it))
        }
    }

    /** Стартовый экран выставлен — можно подавать команды. */
    fun begin() {
        if (!isActive) return
        started = true
        lastProgressNanos = System.nanoTime()
        status = "REPLAY: старт"
    }

    private fun loadJournal(file: File) {
        val result = file.inputStream().use { JournalFormat.read(it) }
        if (result.truncated) note("журнал оборван (краш посреди записи) — повтор до места обрыва")
        if (result.unknownTypes > 0) note("в журнале ${result.unknownTypes} команд неизвестных типов — .bug из другой версии игры?")

        val ui = ArrayList<JournalEntry>()
        var uiCheck = Fnv.OFFSET
        var simCheck = Fnv.OFFSET
        var simCount = 0
        for (entry in result.entries) {
            if (entry.channel == Channel.UI) {
                if (entry.nested) continue
                ui += entry
                uiCheck = JournalFormat.hashUi(uiCheck, entry.typeId, entry.payload)
            } else {
                simCount++
                simCheck = JournalFormat.hashSim(simCheck, entry.epoch, entry.tick, entry.typeId, entry.payload)
                epochs.getOrPut(entry.epoch) { SimEpoch() }.add(entry)
            }
            if (entry.tick >= 0 && (entry.epoch > finalEpoch || (entry.epoch == finalEpoch && entry.tick > finalTick))) {
                finalEpoch = entry.epoch
                finalTick = entry.tick
            }
        }
        // Где игрок был, когда случился баг: тик из манифеста может быть позже последней
        // записи (мир шёл, а команд не было).
        val reportEpoch = manifest["final.epoch"]?.toIntOrNull()
        val reportTick = manifest["final.tick"]?.toIntOrNull()
        if (reportEpoch != null && reportTick != null && reportTick >= 0 &&
            (reportEpoch > finalEpoch || (reportEpoch == finalEpoch && reportTick > finalTick))
        ) {
            finalEpoch = reportEpoch
            finalTick = reportTick
        }

        uiEntries = ui
        expectedUiHash = uiCheck
        expectedSimHash = simCheck
        simTotal = simCount

        // Журнал против манифеста: файл дошёл целым и это ровно та запись.
        manifest["journal.uiHash"]?.let { if (it != Fnv.hex(uiCheck)) note("контрольная сумма UI-команд не совпала с манифестом — журнал повреждён") }
        manifest["journal.simHash"]?.let { if (it != Fnv.hex(simCheck)) note("контрольная сумма SIM-событий не совпала с манифестом — журнал повреждён") }
    }

    // ==================== Поток рендера: UI-команды ====================

    /** Каждый кадр, до отрисовки экрана. */
    fun update() {
        if (!isActive || !started || finished) return
        if (fast) DISimulationContainer.simulationData.maxSpeed = true

        val deadline = System.nanoTime() + FRAME_BUDGET_NS
        while (cursor < uiEntries.size) {
            val entry = uiEntries[cursor]
            if (!gateOpen(entry)) break
            cursor++
            inject(entry)
            lastProgressNanos = System.nanoTime()
            // После перехода экрану нужен кадр: show/resize и первая отрисовка.
            if (entry.command is NavigationCommands || System.nanoTime() > deadline) break
        }

        val tick = SessionJournal.simTick?.invoke()
        if (tick != null && tick != lastSeenTick) {
            lastSeenTick = tick
            lastProgressNanos = System.nanoTime()
        }

        if (cursor >= uiEntries.size && worldReachedEnd()) {
            finish()
        } else if (System.nanoTime() - lastProgressNanos > STALL_NS) {
            note("повтор застрял на команде ${cursor + 1} из ${uiEntries.size}: ${uiEntries.getOrNull(cursor)?.describe()}")
            finish()
        } else {
            status = progressLine()
        }
    }

    /**
     * Можно ли подать UI-команду. Нажатая без мира — сразу. Нажатая в мире — когда мир
     * дойдёт до её эпохи и тика. Сохранение мира — ровно на тике, где оно случилось у
     * игрока: мир там держится (см. [mustHold]).
     */
    private fun gateOpen(entry: JournalEntry): Boolean {
        if (entry.tick < 0) return true
        val clock = SessionJournal.simTick ?: return true
        val epoch = SessionJournal.epoch
        if (epoch != entry.epoch) return epoch > entry.epoch
        val tick = clock()
        if (entry.command is SimulationIntent.SaveWorld) {
            val barrier = epochs[entry.epoch]?.nextSaveTick()
            if (barrier != null) return tick == barrier
        }
        return tick >= entry.tick
    }

    private fun inject(entry: JournalEntry) {
        val command = entry.command
        if (command == null) {
            skipped++
            note("команда неизвестного типа ${entry.typeId} пропущена")
            return
        }
        isInjecting = true
        try {
            val handled = when (command) {
                is NavigationCommands -> {
                    DIGameGlobalContainer.navigationCommandsManager.performCommand(command)
                    true
                }
                else -> (DIGameGlobalContainer.game.screen as? ReplayTarget)?.replay(command) ?: false
            }
            if (handled) {
                uiApplied++
                uiHash = JournalFormat.hashUi(uiHash, entry.typeId, entry.payload)
            } else {
                skipped++
                val screen = DIGameGlobalContainer.game.screen?.let { it::class.simpleName } ?: "нет экрана"
                note("команду ${command.logName} некому выполнить (экран $screen) — повтор разошёлся с записью")
            }
        } finally {
            isInjecting = false
        }
    }

    private fun worldReachedEnd(): Boolean {
        SessionJournal.simTick ?: return true
        val epoch = SessionJournal.epoch
        if (epoch != finalEpoch) return epoch > finalEpoch
        return finalTick < 0 || heldAtEnd
    }

    private fun finish() {
        finished = true
        val world = SessionJournal.simTick != null
        if (world) DISimulationContainer.simulationData.isPlay = false

        val report = buildString {
            appendLine("=== Повтор бага закончен: ${source?.name} ===")
            appendLine("UI-команды: применено $uiApplied из ${uiEntries.size}, " +
                if (uiApplied == uiEntries.size && uiHash == expectedUiHash) "контрольная сумма совпала" else "контрольная сумма НЕ совпала")
            val (simDone, simSum) = synchronized(simLock) { simApplied to simHash }
            appendLine("SIM-события: применено $simDone из $simTotal, " +
                if (simDone == simTotal && simSum == expectedSimHash) "контрольная сумма совпала" else "контрольная сумма НЕ совпала")
            val mismatch = firstMismatch
            appendLine(
                when {
                    mismatch != null -> "Состояние мира: РАЗОШЛОСЬ — $mismatch (всего сошлось $hashesOk)"
                    hashesOk == 0 && oldHashNoted -> "Состояние мира: не сверялось — хэши в записи старой формулы"
                    else -> "Состояние мира: сверок $hashesOk, все совпали"
                }
            )
            if (skipped > 0) appendLine("Пропущено команд: $skipped")
            notes.forEach { appendLine("  ! $it") }
            crash?.let { appendLine("КРАШ ВОСПРОИЗВЕДЁН: $it") }
            if (crash == null && manifest["kind"] == BugKind.CRASH.label) {
                appendLine("Краш НЕ воспроизвёлся: ${manifest["crash.exception"] ?: "?"}: ${manifest["crash.message"] ?: ""} — исправлен, или повтор разошёлся с записью")
            }
            if (world && crash == null) appendLine("Мир остановлен на тике ${SessionJournal.simTick?.invoke()} (эпоха ${SessionJournal.epoch}).")
        }
        println(report)
        status = report.trim()

        val (simDone, simSum) = synchronized(simLock) { simApplied to simHash }
        val commandsMatch = skipped == 0 && firstMismatch == null &&
            uiApplied == uiEntries.size && uiHash == expectedUiHash
        // Повтор краша удался, если краш случился снова; повтор отчёта — если мир дошёл
        // до конца записи, применив всё. События тика, в котором упало, применены не все.
        result = commandsMatch && if (manifest["kind"] == BugKind.CRASH.label) {
            crash != null
        } else {
            crash == null && simDone == simTotal && simSum == expectedSimHash
        }

        // Дальше — обычная игра поверх состояния игрока, но уже без записи.
        isActive = false
        (DIGameGlobalContainer.game.screen as? ReplayTarget)?.onReplayFinished()
    }

    private fun progressLine(): String {
        val world = SessionJournal.simTick?.let { " · эпоха ${SessionJournal.epoch} тик ${it()}" } ?: ""
        val mismatch = if (firstMismatch != null) " · СОСТОЯНИЕ РАЗОШЛОСЬ" else " · хэшей сошлось $hashesOk"
        return "REPLAY: команда $cursor/${uiEntries.size}$world$mismatch"
    }

    /** Заметка в итог повтора и в консоль. */
    fun note(text: String) {
        notes += text
        println("[$TAG] $text")
    }

    // ==================== Поток симуляции: SIM-события ====================

    /** Сид сессии [epoch] из записи. */
    fun sessionSeed(epoch: Int): Long? {
        val entry = epochs[epoch]?.seed ?: return null
        consume(entry)
        return (entry.command as SessionStarted).seed
    }

    /** Был ли у игрока рестарт в этом тике. */
    fun restartAt(epoch: Int, tick: Int): Boolean {
        val entry = epochs[epoch]?.restart ?: return false
        if (entry.tick != tick) return false
        consume(entry)
        return true
    }

    /** Новая маска клавиш, если в этом тике она у игрока менялась. */
    fun keysAt(epoch: Int, tick: Int): Int? {
        val entry = epochs[epoch]?.keys?.get(tick) ?: return null
        consume(entry)
        return (entry.command as ControllerKeys).mask
    }

    /** Касания мира, применённые у игрока в этом тике, в том же порядке. */
    inline fun forEachCommandAt(epoch: Int, tick: Int, action: (PlayerCommand) -> Unit) {
        val list = commandsAt(epoch, tick) ?: return
        for (entry in list) {
            consumeEntry(entry)
            action(entry.command as PlayerCommand)
        }
    }

    @PublishedApi
    internal fun commandsAt(epoch: Int, tick: Int): List<JournalEntry>? = epochs[epoch]?.commands?.get(tick)

    @PublishedApi
    internal fun consumeEntry(entry: JournalEntry) = consume(entry)

    /**
     * Сверить хэш состояния мира с записанным в этом тике. При расхождении — какие части
     * мира разошлись (StateHash.partNames).
     */
    fun checkStateHash(epoch: Int, tick: Int, actual: StateHash.Result, names: List<String>) {
        val entry = epochs[epoch]?.hashes?.get(tick) ?: return
        consume(entry)
        val expected = entry.command as StateHashed
        if (expected.version != StateHash.VERSION) {
            // Хэш другой формулы: сравнивать нечего — разошлось бы всегда.
            if (!oldHashNoted) {
                oldHashNoted = true
                note("хэши состояния записаны формулой версии ${expected.version}, а игра считает версией ${StateHash.VERSION} — сверка мира пропущена")
            }
            return
        }
        if (expected.hash == actual.hash) {
            hashesOk++
        } else if (firstMismatch == null) {
            val parts = StateHash.diff(names, expected.parts.toLongArray(), actual.parts)
            firstMismatch = "эпоха $epoch, тик $tick: разошлись ${parts.joinToString().ifEmpty { "?" }}"
            println("[$TAG] СОСТОЯНИЕ МИРА РАЗОШЛОСЬ: $firstMismatch")
        }
    }

    @Volatile private var oldHashNoted = false

    /**
     * Держать ли мир на этом тике: впереди сохранение, которого ещё не было, или запись
     * кончилась (мир встаёт там же, где был игрок, когда случился баг).
     */
    fun mustHold(epoch: Int, tick: Int): Boolean {
        if (!started) return true
        if (uiCommandPending(epoch, tick)) return true
        val save = epochs[epoch]?.nextSaveTick()
        if (save != null && tick >= save) return true
        if (epoch == finalEpoch && finalTick >= 0 && tick >= finalTick) {
            heldAtEnd = true
            return true
        }
        return false
    }

    /**
     * Поток рендера ещё не подал UI-команду, нажатую у игрока на этом тике или раньше.
     *
     * Кнопки игрок жмёт в потоке рендера, и у него они ложатся между тиками того мира,
     * который он видит. При повторе поток рендера подаёт команды по кадрам (переход
     * экрана — целый кадр), и без этой остановки мир успевал уйти вперёд. Пока игрок
     * был, например, в редакторе генома, его мир стоял на паузе на одном тике, а при
     * повторе за десятки кадров правок генома мир убегал бы на десятки тиков, и выбранный
     * в конце геном доставался бы уже не тому касанию.
     */
    private fun uiCommandPending(epoch: Int, tick: Int): Boolean {
        if (finished) return false
        val entry = uiEntries.getOrNull(cursor) ?: return false
        // Сохранение мира подаётся не «с тика», а ровно на тике, где оно случилось
        // (gateOpen), и мир до него довозит свой барьер. Держать мир здесь — значит не
        // дать ему дойти до этого тика: повтор встал бы навсегда.
        if (entry.command is SimulationIntent.SaveWorld) return false
        return entry.tick >= 0 && entry.epoch == epoch && entry.tick <= tick
    }

    /** Сохранение мира случилось (или упало) — отпустить мир. Зовётся между тиками. */
    fun onWorldSaveFinished() {
        val entry = epochs[SessionJournal.epoch]?.takeSave() ?: return
        consume(entry)
    }

    /** Повтор дошёл до краша. */
    fun onCrash(thread: Thread, error: Throwable) {
        val where = SessionJournal.simTick?.let { " (эпоха ${SessionJournal.epoch}, тик ${it()})" } ?: ""
        crash = "поток ${thread.name}$where: $error"
        println("[$TAG] КРАШ ВОСПРОИЗВЕДЁН в потоке ${thread.name}$where: $error")
        // Мир дальше не пойдёт — подводим итог сразу, а не по таймауту «застрял».
        Gdx.app?.postRunnable { if (isActive && !finished) finish() }
    }

    private fun consume(entry: JournalEntry) {
        synchronized(simLock) {
            simApplied++
            simHash = JournalFormat.hashSim(simHash, entry.epoch, entry.tick, entry.typeId, entry.payload)
        }
    }

    private fun printHeader() {
        val recorded = manifest["game"]
        println(buildString {
            appendLine("=== Повтор бага: ${source?.name} ===")
            appendLine("Игра ${recorded ?: "?"}, устройство ${manifest["deviceId"] ?: "?"}, ${manifest["platform"] ?: "?"}, " +
                "экран ${manifest["screen"] ?: "?"}, язык ${manifest["locale"] ?: "?"}")
            appendLine("UI-команд ${uiEntries.size}, SIM-событий $simTotal, сессий мира ${epochs.size}, " +
                "конец: эпоха $finalEpoch тик $finalTick")
            manifest["description"]?.takeIf { it.isNotBlank() }?.let { appendLine("Описание: $it") }
        })
        if (recorded != null && recorded != GameVersion.NAME) {
            note("баг записан в версии $recorded, а это ${GameVersion.NAME} — повтор может разойтись")
        }
    }

    private fun JournalEntry.describe(): String =
        "${command?.logName ?: "#$typeId"} (эпоха $epoch, тик $tick)"

    /** SIM-события одной сессии мира, разложенные по тикам. */
    private class SimEpoch {
        var seed: JournalEntry? = null
        var restart: JournalEntry? = null
        val keys = HashMap<Int, JournalEntry>()
        val commands = HashMap<Int, MutableList<JournalEntry>>()
        val hashes = HashMap<Int, JournalEntry>()
        private val saves = ArrayDeque<JournalEntry>()

        fun add(entry: JournalEntry) {
            when (entry.command) {
                is SessionStarted -> seed = entry
                is SimRestarted -> restart = entry
                is ControllerKeys -> keys[entry.tick] = entry
                is StateHashed -> hashes[entry.tick] = entry
                is WorldSaveCommitted -> synchronized(saves) { saves.addLast(entry) }
                is PlayerCommand -> commands.getOrPut(entry.tick) { ArrayList() } += entry
                else -> Unit
            }
        }

        fun nextSaveTick(): Int? = synchronized(saves) { saves.firstOrNull()?.tick }

        fun takeSave(): JournalEntry? = synchronized(saves) { saves.removeFirstOrNull() }
    }

    private const val TAG = "BugReplay"
    private const val SANDBOX_DIR = ".sandbox"
    private const val FRAME_BUDGET_NS = 8_000_000L
    private const val STALL_NS = 15_000_000_000L
}
