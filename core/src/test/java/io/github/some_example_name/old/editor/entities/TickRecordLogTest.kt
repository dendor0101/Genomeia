package io.github.some_example_name.old.editor.entities

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TickRecordLogTest {

    /**
     * Любой тик собирается ровно в тот ряд, что был записан: и при росте, и при сжатии ряда,
     * и при правке старых записей, и при чтении тиков вразнобой (кэш из трёх буферов).
     */
    @Test
    fun everyTickReadsBackExactly() {
        val width = 4
        val random = Random(42)
        val log = TickRecordLog(width)
        val expected = ArrayList<IntArray>()
        var row = IntArray(0)

        repeat(600) { tick ->
            // Ряд то растёт, как тело в росте, то сжимается (смерть клеток), а иногда
            // меняется много записей сразу — чтобы опорные кадры писались по ходу.
            val count = when {
                tick % 97 == 50 -> (row.size / width / 2)
                else -> row.size / width + random.nextInt(0, 4)
            }
            val next = IntArray(count * width)
            System.arraycopy(row, 0, next, 0, minOf(row.size, next.size))
            for (r in row.size / width until count) for (k in 0 until width) next[r * width + k] = random.nextInt()
            val edits = if (tick % 31 == 0) count else random.nextInt(0, 3)
            repeat(edits) {
                if (count > 0) next[random.nextInt(count) * width + random.nextInt(width)] = random.nextInt()
            }
            row = next

            val buffer = log.beginTick(count)
            System.arraycopy(row, 0, buffer, 0, row.size)
            log.endTick()
            expected += row.copyOf()
        }

        assertEquals(expected.size, log.tickCount)
        val order = expected.indices.shuffled(Random(7)) + expected.indices + expected.indices.reversed()
        for (tick in order) {
            val view = log.view(tick)!!
            assertEquals(expected[tick].size / width, log.count(tick), "записей на тике $tick")
            assertContentEquals(expected[tick], view.copyOf(expected[tick].size), "тик $tick")
        }
        assertNull(log.view(-1))
        assertNull(log.view(expected.size))
    }

    /** Чтение другого тика внутри обхода не портит ряд, который обходят. */
    @Test
    fun nestedReadKeepsOuterRow() {
        val log = TickRecordLog(1)
        for (tick in 0 until 10) {
            val buffer = log.beginTick(tick + 1)
            for (r in 0..tick) buffer[r] = tick * 100 + r
            log.endTick()
        }
        val outer = log.view(9)!!
        log.view(3)
        log.view(5)
        for (r in 0..9) assertEquals(900 + r, outer[r])
    }

    /**
     * Память — по изменениям, а не по тикам. Ряд растёт на 2 записи за тик до 5000, как
     * Megalodon (5120 клеток за 2135 тиков): снимками это 2500 тиков × 2500 записей в
     * среднем, а по изменениям — несколько рядов.
     */
    @Test
    fun memoryFollowsChangesNotTicks() {
        val width = 11
        val log = TickRecordLog(width)
        for (tick in 0 until 2500) {
            val count = 2 * (tick + 1)
            val buffer = log.beginTick(count)
            for (r in 0 until count) for (k in 0 until width) buffer[r * width + k] = r * 31 + k
            log.endTick()
        }
        val snapshotBytes = (0 until 2500).sumOf { 2L * (it + 1) } * width * 4
        assertTrue(log.byteSize() * 50 < snapshotBytes, "записано ${log.byteSize()} байт, снимками было бы $snapshotBytes")
    }
}
