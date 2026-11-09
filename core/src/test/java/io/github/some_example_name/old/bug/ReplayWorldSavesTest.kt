package io.github.some_example_name.old.bug

import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.features.simulation.SimulationIntent
import io.github.some_example_name.old.journal.Channel
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.JournalEntry
import io.github.some_example_name.old.journal.WorldSaveCommitted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayWorldSavesTest {
    @Test fun repeatedRequestsForOneSlotKeepTheirOwnOutcome() {
        fun entry(command: GameCommand, tick: Int): JournalEntry {
            val sim = command is WorldSaveCommitted
            return JournalEntry(if (sim) Channel.SIM else Channel.UI,
                if (sim) CommandSource.SYSTEM else CommandSource.SIMULATION,
                false, 1, tick, 0, 0, byteArrayOf(), command)
        }
        val cancelled = entry(SimulationIntent.SaveWorld("мир"), 10)
        val failed = entry(SimulationIntent.SaveWorld("мир"), 10)
        val succeeded = entry(SimulationIntent.SaveWorld("мир"), 12)
        val saves = ReplayWorldSaves(listOf(cancelled, failed, entry(WorldSaveCommitted("мир"), 11),
            entry(SimulationIntent.SaveWorldFailed("мир"), 12), succeeded, entry(WorldSaveCommitted("мир"), 15)))
        assertNull(saves[cancelled]!!.tick)
        assertFalse(saves[cancelled]!!.failed)
        assertEquals(11, saves[failed]!!.tick)
        assertTrue(saves[failed]!!.failed)
        assertEquals(15, saves[succeeded]!!.tick)
        assertFalse(saves[succeeded]!!.failed)
    }
}
