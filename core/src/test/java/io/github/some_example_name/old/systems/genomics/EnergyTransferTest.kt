package io.github.some_example_name.old.systems.genomics

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Перенос энергии по связи (energyTransferAmount): вдали от равенства — прежняя порция,
 * у равенства — без перелёта, поэтому энергия не дрожит и ядро не моргает.
 */
class EnergyTransferTest {

    /**
     * Как в игре: rateOfEnergyTransferInLinks 0.03 × 8 (DISimulationContainer). Это в шесть
     * раз больше шага байта энергии, по которому рисуется ядро (10 / 255 ≈ 0.039), — отсюда
     * и было видно дрожь.
     */
    private val rate = 0.24f

    @Test
    fun `вдали от равенства порция прежняя и в сторону менее заполненной`() {
        assertEquals(rate, energyTransferAmount(1f, 4f, 5f, 5f, rate))
        assertEquals(-rate, energyTransferAmount(4f, 1f, 5f, 5f, rate))
        // Заполненность, а не энергия: у жира запас больше, и при равной энергии он «пустее».
        assertEquals(-rate, energyTransferAmount(3f, 3f, 5f, 20f, rate))
    }

    @Test
    fun `у равенства пара не перескакивает через него`() {
        // Прежняя полная порция здесь перебрасывала бы разницу на другую сторону.
        val e1 = 2.5f
        val e2 = 2.5f + rate * 0.5f
        val amount = energyTransferAmount(e1, e2, 5f, 5f, rate)
        assertTrue(amount > 0f && amount < rate, "порция $amount")
        val after1 = e1 + amount
        val after2 = e2 - amount
        assertTrue(after1 <= after2, "перелёт: $after1 > $after2")
    }

    @Test
    fun `равные клетки не обмениваются`() {
        assertEquals(0f, energyTransferAmount(2f, 2f, 5f, 5f, rate))
        assertEquals(0f, energyTransferAmount(2f, 8f, 5f, 20f, rate))
    }

    @Test
    fun `цепочка клеток сходится без дрожи`() {
        // Пять клеток в ряд, связи обходятся по порядку, как в стадии связей. Прежняя
        // формула оставляла энергию скакать на ±порцию каждый тик; теперь после
        // выравнивания изменения за тик затухают.
        val energy = floatArrayOf(4f, 1f, 3f, 0.5f, 2f)
        val maxEnergy = 5f
        var lastChange = Float.MAX_VALUE
        repeat(5000) {
            val before = energy.copyOf()
            for (i in 0 until energy.size - 1) {
                val amount = energyTransferAmount(energy[i], energy[i + 1], maxEnergy, maxEnergy, rate)
                energy[i] += amount
                energy[i + 1] -= amount
            }
            lastChange = energy.indices.maxOf { abs(energy[it] - before[it]) }
        }
        assertTrue(lastChange < rate * 0.01f, "энергия всё ещё дрожит на $lastChange за тик")
        val mean = 10.5f / 5f
        for (e in energy) assertEquals(mean, e, 1e-3f)
        assertEquals(10.5f, energy.sum(), 1e-4f, "энергия не берётся из ниоткуда")
    }
}
