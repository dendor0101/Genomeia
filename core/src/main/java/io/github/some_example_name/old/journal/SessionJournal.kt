package io.github.some_example_name.old.journal

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.logName
import io.github.some_example_name.old.core.log.ActionLog
import java.io.BufferedOutputStream
import java.io.DataOutputStream

/**
 * Журнал сессии: каждая команда игрока за этот запуск игры, в порядке выполнения,
 * в файле session/journal.bin. Из него собирается .bug и по нему идёт повтор (BugReplay).
 *
 * Пишется всегда — отдельной настройки нет, — и при каждом запуске начинается заново.
 * Пишется на лету, а не копится в памяти: часовая сессия — это сотни тысяч команд
 * (перетаскивание идёт на каждое движение пальца), а при краше на диске уже лежит всё,
 * что успело случиться.
 *
 * ПОТОКИ
 * ------
 * UI-команды пишет поток рендера через [execute], SIM-события — поток симуляции через
 * [recordSim], сохранение мира — поток рендера между тиками. Запись под одним локом, так
 * что в файле они идут в том порядке, в каком случились.
 */
object SessionJournal {

    private val lock = Any()

    private var out: DataOutputStream? = null
    private var file: FileHandle? = null
    private var startNanos = 0L
    private var entriesSinceFlush = 0
    private var writeFailed = false

    val isRecording: Boolean get() = synchronized(lock) { out != null }

    /** Число записей и контрольные суммы каналов — для манифеста .bug. */
    var uiCount = 0
        private set
    var simCount = 0
        private set
    var uiHash = Fnv.OFFSET
        private set
    var simHash = Fnv.OFFSET
        private set

    /** Команды, которых нет в CommandRegistry: выполнились, но в журнал не попали. */
    var unregistered = 0
        private set

    /**
     * Текущая эпоха — номер сессии симуляции за этот запуск (см. JournalEntry.epoch).
     * Меняет только поток симуляции в начале сессии; остальные читают.
     */
    @Volatile
    var epoch = 0
        private set

    /**
     * Тик мира, пока открыт экран симуляции, иначе null. Ставит экран симуляции: только он
     * знает, когда мир живой. UI-команды получают по нему отметку «в каком тике нажали».
     */
    @Volatile
    var simTick: (() -> Int)? = null

    /** Глубина вложенности [execute] — только поток рендера, см. JournalEntry.nested. */
    private var depth = 0

    fun startRecording(target: FileHandle) {
        synchronized(lock) {
            close()
            target.parent().mkdirs()
            val stream = DataOutputStream(BufferedOutputStream(target.write(false), BUFFER))
            JournalFormat.writeHeader(stream)
            stream.flush()
            out = stream
            file = target
            writeFailed = false
            unregistered = 0
            startNanos = System.nanoTime()
            uiCount = 0
            simCount = 0
            uiHash = Fnv.OFFSET
            simHash = Fnv.OFFSET
        }
    }

    /** Журнал на диске целиком — перед сборкой .bug. */
    fun flush() {
        synchronized(lock) {
            try {
                out?.flush()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Журнал на момент снимка и его контрольные суммы — согласованные между собой. */
    class Snapshot(val file: java.io.File, val uiCount: Int, val uiHash: Long, val simCount: Int, val simHash: Long, val complete: Boolean)

    /**
     * Копия журнала для .bug. Под локом: поток симуляции дописывает журнал прямо сейчас,
     * и без лока суммы в манифесте разошлись бы с тем, что попало в файл.
     */
    fun snapshot(target: java.io.File): Snapshot? = synchronized(lock) {
        val source = file?.file() ?: return null
        try {
            out?.flush()
            source.copyTo(target, overwrite = true)
            Snapshot(target, uiCount, uiHash, simCount, simHash, !writeFailed && unregistered == 0)
        } catch (e: Exception) {
            Gdx.app?.error(TAG, "не удалось снять журнал", e)
            null
        }
    }

    /**
     * Выполнить команду игрока через журнал. Все обработчики команд — ViewModel, редактор
     * генома, навигация — заворачивают свою работу сюда:
     *
     *     fun handle(intent: X) = SessionJournal.execute(SOURCE, intent) { apply(it) }
     *
     * Здесь команда попадает в ActionLog (для краш-репорта) и в журнал (для повтора).
     *
     * Во время повтора бага живой ввод игнорируется: игрок смотрит, а команды подаёт
     * BugReplay. Отличить их можно только здесь, поэтому и проверка здесь.
     *
     * @param logAction false — команда сама пишет себя в ActionLog подробнее (навигация
     *   дописывает, на каком экране оказались).
     */
    fun <T : GameCommand> execute(
        source: CommandSource,
        command: T,
        logAction: Boolean = true,
        block: (T) -> Unit
    ) {
        if (depth == 0 && BugReplay.isActive && !BugReplay.isInjecting) return

        if (logAction) {
            ActionLog.record(source.label, command, coalesce = source != CommandSource.NAVIGATION)
        }
        val clock = simTick
        writeEntry(
            channel = Channel.UI,
            source = source,
            nested = depth > 0,
            epoch = epoch,
            tick = clock?.invoke() ?: -1,
            command = command
        )

        depth++
        try {
            block(command)
        } finally {
            depth--
        }
    }

    /** SIM-событие: из потока симуляции (или между тиками), с точным тиком. */
    fun recordSim(source: CommandSource, command: GameCommand, epoch: Int, tick: Int) {
        writeEntry(Channel.SIM, source, nested = false, epoch = epoch, tick = tick, command = command)
    }

    /** Поток симуляции: началась новая сессия. Возвращает её эпоху. */
    fun beginSimEpoch(): Int {
        epoch++
        return epoch
    }

    private fun writeEntry(
        channel: Channel,
        source: CommandSource,
        nested: Boolean,
        epoch: Int,
        tick: Int,
        command: GameCommand
    ) {
        if (BugReplay.isActive) return

        val encoded = CommandRegistry.encode(command)
        if (encoded == null) {
            unregistered++
            Gdx.app?.error(TAG, "команды ${command.logName} нет в CommandRegistry — в журнал она не попала")
            return
        }
        val (typeId, payload) = encoded

        synchronized(lock) {
            val stream = out ?: return
            try {
                val timeMs = ((System.nanoTime() - startNanos) / 1_000_000L).toInt()
                JournalFormat.writeEntry(stream, channel, source, nested, epoch, tick, timeMs, typeId, payload)
                when {
                    channel == Channel.SIM -> {
                        simCount++
                        simHash = JournalFormat.hashSim(simHash, epoch, tick, typeId, payload)
                    }
                    !nested -> {
                        uiCount++
                        uiHash = JournalFormat.hashUi(uiHash, typeId, payload)
                    }
                }
                // Сбрасываем на диск пачками: журнал должен пережить убитый процесс, но
                // сброс на каждое движение пальца — это лишняя нагрузка на флеш телефона.
                if (++entriesSinceFlush >= FLUSH_EVERY) {
                    entriesSinceFlush = 0
                    stream.flush()
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Ошибка записи не должна ронять игру: журнал просто выключается. */
    private fun fail(e: Exception) {
        writeFailed = true
        Gdx.app?.error(TAG, "журнал сессии остановлен", e)
        close()
    }

    private fun close() {
        try {
            out?.close()
        } catch (_: Exception) {
        }
        out = null
    }

    private const val TAG = "SessionJournal"
    private const val BUFFER = 64 * 1024
    private const val FLUSH_EVERY = 64
}
