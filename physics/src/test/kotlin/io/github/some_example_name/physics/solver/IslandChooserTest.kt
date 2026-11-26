package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test

/**
 * Выбор островов по замеру (IslandChooser): на заданных временах тиков — тот режим, что быстрее; прогрев гоняет оба
 * режима; дрейф времени и холодный путь островов (прогрев JIT) выбора не переворачивают; тот же ответ — удержание растёт.
 */
class IslandChooserTest {
    /** Прогнать [ticks] тиков: тик с островами длится [onNs], без — [offNs]; острова находятся, если [islands]. */
    private fun IslandChooser.run(ticks: Int, offNs: Long, onNs: Long, islands: Boolean = true): Int {
        var withIslands = 0
        repeat(ticks) {
            val on = wantIslands()
            if (on) withIslands++
            record(if (on) onNs else offNs, if (on && islands) 3 else 0)
        }
        return withIslands
    }

    @Test fun warmupRunsBothModes() {
        val c = IslandChooser()
        assertEquals("прогрев — половина тиков с островами", WARMUP / 2, c.run(WARMUP, offNs = 10_000_000, onNs = 1_000_000))
        assertFalse("в прогреве выбора нет", c.chosen)
    }

    /**
     * Путь островов холодный, пока не наберёт тиков (JIT компилирует только идущий код): первые 30 тиков с островами
     * вдвое дольше, дальше острова быстрее на 20%. Прогрев гоняет оба пути, и первая же проба выбирает острова. Прогрев
     * только без островов здесь выбрал бы мир (S24 Ultra 26.11: острова быстрее на 12%, а первая проба — без них).
     */
    @Test fun coldIslandsPathDoesNotHideTheWin() {
        val c = IslandChooser()
        var islandTicks = 0
        for (t in 0 until WARMUP + PROBE) {
            val on = c.wantIslands()
            val ns = if (!on) 10_000_000L else if (islandTicks++ < 30) 20_000_000L else 8_000_000L
            c.record(ns, if (on) 3 else 0)
        }
        assertTrue("холодный путь островов спрятал выигрыш в первой пробе", c.chosen)
    }

    @Test fun picksIslandsWhenFasterAndDropsThemWhenSlower() {
        val c = IslandChooser()
        c.run(WARMUP + PROBE, offNs = 10_000_000, onNs = 8_000_000)
        assertTrue("острова быстрее на 20% — должны быть выбраны", c.chosen)
        assertEquals("в удержании — только острова", 300, c.run(300, offNs = 10_000_000, onNs = 8_000_000))
        val d = IslandChooser()
        d.run(WARMUP + PROBE, offNs = 10_000_000, onNs = 12_000_000)
        assertFalse("острова медленнее — не выбраны", d.chosen)
        assertEquals("в удержании островов нет", 0, d.run(300, offNs = 10_000_000, onNs = 12_000_000))
    }

    @Test fun noiseBelowThresholdKeepsWorldMode() {
        val c = IslandChooser()
        c.run(WARMUP + PROBE, offNs = 10_000_000, onNs = 9_800_000)
        assertFalse("выигрыш 2% меньше порога 3% — без островов", c.chosen)
    }

    @Test fun noIslandsFoundMeansWorldMode() {
        val c = IslandChooser()
        c.run(WARMUP + PROBE, offNs = 10_000_000, onNs = 5_000_000, islands = false)
        assertFalse("островов не нашлось — режим мира, как бы ни было быстро", c.chosen)
    }

    /**
     * Тики быстреют на 1% за тик (прогрев JIT), а острова на самом деле медленнее на 5%. Блоки пробы вперемешку делят
     * дрейф поровну, и острова не выбираются. Проба «сначала без, потом с» здесь выбрала бы острова.
     */
    @Test fun warmupDriftDoesNotFlipTheChoice() {
        val c = IslandChooser()
        for (t in 0 until WARMUP + PROBE) {
            val on = c.wantIslands()
            val base = 20_000_000L - maxOf(0, t - WARMUP) * 200_000L
            c.record(if (on) base * 105 / 100 else base, if (on) 3 else 0)
        }
        assertFalse("дрейф прогрева перевернул выбор", c.chosen)
    }

    @Test fun sameAnswerHoldsLonger() {
        val c = IslandChooser()
        // Прогрев, проба и удержание 300; вторая проба с тем же ответом — удержание 600.
        c.run(WARMUP + PROBE + 300 + PROBE, offNs = 10_000_000, onNs = 12_000_000)
        assertEquals("в удвоенном удержании проб нет", 0, c.run(600, offNs = 10_000_000, onNs = 12_000_000))
        // Следующая проба — сразу после 600 тиков: два блока с островами по пять тиков.
        assertEquals(10, c.run(PROBE, offNs = 10_000_000, onNs = 12_000_000))
    }

    private companion object {
        /** IslandChooser.WARMUP и длина пробы (четыре блока по пять тиков). */
        const val WARMUP = 120
        const val PROBE = 20
    }
}
