package io.github.some_example_name.old.bug

import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.core.DevStartup
import io.github.some_example_name.old.core.GameFilePaths
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.journal.SessionAttachments
import io.github.some_example_name.old.journal.SessionJournal

/**
 * Запуск всего, что нужно для отчётов о багах, — в правильном порядке относительно
 * MyGame.create. Порядок здесь и есть смысл этого объекта.
 */
object BugSession {

    /**
     * Самое начало: ДО контейнеров зависимостей. Они при создании читают настройки среды
     * и геномы, а при повторе бага это должны быть файлы игрока из песочницы.
     */
    fun start() {
        val slot = claimSessionSlot()
        // Прошлая сессия этого номера больше не нужна: всё ценное из неё уже в .bug, если он был.
        val session = GameFiles.sessionDir(slot)
        session.deleteDirectory()
        session.mkdirs()

        SessionLog.install(session)
        CrashHandler.install()
        BugReports.startSession()
        // Недособранные .bug другой живой копии — не остатки: она их ещё дописывает.
        if (aloneOnDevice(slot)) BugReports.removeLeftovers()

        createReplayInbox()

        val inbox = BugReplay.findInbox()
        if (inbox != null && BugReplay.prepare(inbox)) return

        SessionAttachments.startSession(session.child(ATTACHMENTS))
        SessionJournal.startRecording(session.child(JOURNAL))
        Gdx.app.log(TAG, "сессия пишется в ${session.file().absolutePath}")
    }

    /** Замок своей папки сессии: держится до конца процесса, систему он и снимет. */
    private var sessionLock: AutoCloseable? = null

    /**
     * Первый свободный номер сессии. Зачем: отчёты 13.11 (8 крашей на старте, Windows,
     * AccessDeniedException в GameFilePaths.writeAtomically) — четыре копии игры, запущенные
     * в одну секунду, делили папку "session": каждая при старте стирала её и писала туда
     * свои снимки, и переименование временного файла натыкалось на файл соседа. Теперь у
     * каждой копии своя папка, занятая замком (GameFilePaths.tryLock); после выхода копии
     * её номер свободен снова.
     */
    private fun claimSessionSlot(): Int {
        for (slot in 0 until MAX_INSTANCES) {
            val lock = GameFilePaths.tryLock(GameFiles.sessionLockFile(slot)) ?: continue
            sessionLock = lock
            return slot
        }
        throw IllegalStateException("Запущено больше $MAX_INSTANCES копий игры: закройте лишние")
    }

    /** Других живых копий нет: ни один замок чужих номеров не занят. */
    private fun aloneOnDevice(slot: Int): Boolean {
        for (other in 0 until MAX_INSTANCES) {
            if (other == slot) continue
            val file = GameFiles.sessionLockFile(other)
            if (!file.exists()) continue
            val lock = GameFilePaths.tryLock(file) ?: return false
            lock.close()
        }
        return true
    }

    /** Контейнеры и шрифты готовы, первого экрана ещё нет. */
    fun afterContainers() {
        BugReplay.applyEnvironment()
        BugOverlay.create()
    }

    /** Стартовый экран выставлен. */
    fun afterFirstScreen() {
        if (BugReplay.isActive) {
            BugReplay.begin()
            return
        }
        DevStartup.applyStartCommand()
        // Прошлый запуск упал — теперь, когда игра снова жива, можно показать окно отчёта.
        BugReports.pendingCrash()?.let {
            CrashHandler.logPreviousCrash(it)
            BugOverlay.showCrash(it, previousLaunch = true) {}
        }
    }

    /**
     * Папка для повтора — сразу, а не когда понадобится: её надо видеть, чтобы знать, куда
     * класть присланный .bug. Только на ПК: повторяет баги разработчик.
     */
    private fun createReplayInbox() {
        val inbox = GameFiles.replayInboxDir() ?: return
        runCatching {
            inbox.mkdirs()
            val readme = inbox.child(README)
            if (!readme.exists()) readme.writeString(README_TEXT, false, "UTF-8")
        }
    }

    private const val MAX_INSTANCES = 16
    private const val README = "README.txt"
    private val README_TEXT = """
        Повтор бага
        ===========
        Положите сюда .bug, присланный игроком, и запустите игру: она повторит его сессию
        команда за командой (первый файл по алфавиту, если их несколько). Пока идёт повтор,
        ввод игнорируется; ход и итог сверки — в углу экрана и в консоли.

        Файлы игрока (геномы, миры, настройки) распаковываются в .sandbox рядом с .bug —
        ваши не трогаются. Уберите .bug отсюда, чтобы снова играть обычным образом.

        -Dgenomeia.replay=off              не повторять, даже если файл здесь лежит
        -Dgenomeia.replay=путь/к/файлу.bug повторить конкретный файл
        -Dgenomeia.replaySpeed=recorded    со скоростью игрока, а не на максимальной
    """.trimIndent() + "\n"

    private const val TAG = "BugSession"
    private const val ATTACHMENTS = "attachments"
    private const val JOURNAL = "journal.bin"
}
