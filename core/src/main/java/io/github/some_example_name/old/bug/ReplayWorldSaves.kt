package io.github.some_example_name.old.bug

import io.github.some_example_name.old.features.simulation.SimulationIntent
import io.github.some_example_name.old.journal.Channel
import io.github.some_example_name.old.journal.JournalEntry
import io.github.some_example_name.old.journal.WorldSaveCommitted

/** Превью выполняется в потоке UI позже кнопки. Даже у двух сохранений одного слота
 * разные снимки и исходы. Связываем их по порядку журнала до первого тика повтора. */
internal class ReplayWorldSaves(entries: List<JournalEntry>) {
    class Attempt(val slot: String, val epoch: Int) {
        var tick: Int? = null
        var failed = false
    }

    private val requests = HashMap<JournalEntry, Attempt>()

    init {
        var pending: Attempt? = null
        for (entry in entries) {
            when (val command = entry.command) {
                is SimulationIntent.SaveWorld -> if (entry.channel == Channel.UI && !entry.nested) {
                    pending = Attempt(command.slot, entry.epoch)
                    requests[entry] = pending
                }
                is WorldSaveCommitted -> if (entry.channel == Channel.SIM &&
                    pending?.slot == command.slot && pending.epoch == entry.epoch) {
                    pending.tick = entry.tick
                }
                is SimulationIntent.SaveWorldFailed -> if (entry.channel == Channel.UI &&
                    pending?.slot == command.slot && pending.epoch == entry.epoch) {
                    pending.failed = true
                    pending = null
                }
                else -> Unit
            }
        }
    }

    operator fun get(entry: JournalEntry) = requests[entry]
}
