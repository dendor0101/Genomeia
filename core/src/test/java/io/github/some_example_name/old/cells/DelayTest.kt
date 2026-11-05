package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.SpecialModDataTypes
import io.github.some_example_name.old.systems.genomics.genome.Action
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Буфер Delay без симуляции: точная задержка в обоих режимах, пределы, мутация и сохранение. */
class DelayTest {

    @Test
    fun `обычный режим - выход равен входу ticks тиков назад, сначала нули`() {
        val state = DelayState.of(DelayData(ticks = 3))
        val input = floatArrayOf(0.25f, -1f, 7.5f, 0.125f, 3f, 0f, -2.5f, 1f)
        for (t in input.indices) {
            val expected = if (t < 3) 0f else input[t - 3]
            assertEquals(expected, state.push(input[t]), "t=$t")
        }
    }

    @Test
    fun `ноль тиков - сигнал проходит сразу`() {
        val state = DelayState.of(DelayData(ticks = 0))
        assertEquals(0.7f, state.push(0.7f))
        assertEquals(-3f, state.push(-3f))
        val binary = DelayState.of(DelayData(ticks = 0, binary = true))
        assertEquals(1f, binary.push(0.7f))
        assertEquals(0f, binary.push(0.3f))
    }

    @Test
    fun `двоичный режим - точен при переключении каждый тик и через границу слова`() {
        // 37 тиков: буфер — два int, голова переходит через бит 31.
        val ticks = 37
        val state = DelayState.of(DelayData(ticks = ticks, binary = true))
        assertEquals(2, state.bits.size)
        assertEquals(0, state.values.size)
        val input = FloatArray(200) { t -> if ((t * 7 + t / 3) % 2 == 0) 0.9f else 0.1f }
        for (t in input.indices) {
            val expected = if (t < ticks) 0f else if (input[t - ticks] >= 0.5f) 1f else 0f
            assertEquals(expected, state.push(input[t]), "t=$t")
        }
    }

    @Test
    fun `задержка зажата пределами и памяти ровно столько, сколько тиков`() {
        assertEquals(DelayData.MAX_TICKS, DelayState.of(DelayData(ticks = 1_000_000)).values.size)
        assertEquals(0, DelayState.of(DelayData(ticks = -5)).ticks)
        assertEquals(15, DelayState.of(DelayData(ticks = 15)).values.size)
        assertEquals(1, DelayState.of(DelayData(ticks = 15, binary = true)).bits.size)
        assertEquals(DelayData.MAX_TICKS / 32, DelayState.of(DelayData(ticks = DelayData.MAX_TICKS, binary = true)).bits.size)
    }

    @Test
    fun `мутация - те же параметры оставляют буфер, новые дают новый пустой`() {
        val delay = Delay(0)
        val state = delay.createSpecialData(Action(special = DelayData(ticks = 4))) as DelayState
        repeat(4) { state.push(1f) }

        assertNull(delay.mutateSpecialData(state, Action(special = DelayData(ticks = 4))))
        assertNull(delay.mutateSpecialData(state, Action()), "мутация без своих данных ничего не меняет")

        val longer = delay.mutateSpecialData(state, Action(special = DelayData(ticks = 6))) as DelayState
        assertEquals(6, longer.values.size)
        assertEquals(0f, longer.push(1f))

        val binary = delay.mutateSpecialData(state, Action(special = DelayData(ticks = 4, binary = true))) as DelayState
        assertTrue(binary.binary)
    }

    @Test
    fun `в геном попадают параметры, а не буфер`() {
        val delay = Delay(0)
        val state = DelayState.of(DelayData(ticks = 9, binary = true))
        state.push(1f)
        assertEquals(DelayData(ticks = 9, binary = true), delay.genomeDataOf(state))
    }

    @Test
    fun `состояние переживает сохранение вместе с головой и содержимым`() {
        for (binary in listOf(false, true)) {
            val state = DelayState.of(DelayData(ticks = 40, binary = binary))
            repeat(57) { t -> state.push(if (t % 3 == 0) 1f else 0.25f * t) }
            val loaded = SpecialModDataTypes.decode(SpecialModDataTypes.encode(state)) as DelayState
            assertEquals(state, loaded)
            repeat(100) { t -> assertEquals(state.push(t.toFloat()), loaded.push(t.toFloat()), "binary=$binary t=$t") }
        }
    }
}
