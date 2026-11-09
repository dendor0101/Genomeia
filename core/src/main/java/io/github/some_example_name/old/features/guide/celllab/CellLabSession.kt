package io.github.some_example_name.old.features.guide.celllab

import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.journal.*

/** Единственный владелец стенда. Тики и ввод исполняются последовательно в потоке экрана. */
internal class CellLabSession : Disposable {
    var model: CellLabModel? = null
        private set
    var revision = 0L
        private set
    private var accumulator = 0f

    fun open(key: String) {
        if (key !in CellLabCatalog.keys) return
        close()
        model = CellLabModel(key)
        revision++
    }

    fun close() {
        model?.dispose()
        model = null
        accumulator = 0f
    }

    fun input(command: CellLabInput) {
        if (BugReplay.isActive && !BugReplay.isInjecting) return
        val current = model ?: return
        // Уже на границе тика и в его единственном потоке; никаких записей из слушателя в сущности.
        SessionJournal.recordSim(CommandSource.GUIDE, command, SessionJournal.epoch, current.tick)
        apply(command)
    }

    fun update(delta: Float) {
        val current = model ?: return
        if (BugReplay.isActive || current.paused) { accumulator = 0f; return }
        accumulator = (accumulator + delta).coerceAtMost(4f / 60f)
        val ticks = (accumulator * 60f).toInt()
        if (ticks == 0) return
        accumulator -= ticks / 60f
        val event = CellLabAdvanced(ticks)
        SessionJournal.recordSim(CommandSource.GUIDE, event, SessionJournal.epoch, current.tick)
        val before = current.tick / 60
        apply(event)
        if (current.tick / 60 != before) {
            SessionJournal.recordSim(CommandSource.GUIDE, CellLabChecked(current.stateHash()),
                SessionJournal.epoch, current.tick)
        }
    }

    fun apply(record: CellLabRecord) {
        val current = model ?: error("cell lab event without an open lab")
        revision++
        when (record) {
            is CellLabInput -> if (record.action == "reset") restart(current) else current.apply(record)
            is CellLabAdvanced -> repeat(record.ticks.coerceIn(0, 4)) { current.step() }
            is CellLabChecked -> {
                val hash = current.stateHash()
                if (BugReplay.isActive) BugReplay.checkCellLabHash(current.key, current.tick, record.hash, hash, record.version)
                else check(record.version == StateHash.VERSION && hash == record.hash) {
                    "cell lab ${current.key}: state mismatch at tick ${current.tick}"
                }
            }
        }
    }

    private fun restart(current: CellLabModel) {
        val settings = current.controls.map { CellLabInput("set", it.key, current.value(it.key)) }
        val paused = current.paused
        open(current.key)
        settings.forEach { model!!.apply(it) }
        model!!.apply(CellLabInput("pause", value = if (paused) 1f else 0f))
    }

    override fun dispose() = close()
}
