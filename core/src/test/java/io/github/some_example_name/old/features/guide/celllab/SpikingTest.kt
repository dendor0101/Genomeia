package io.github.some_example_name.old.features.guide.celllab

import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.cells.synapseState
import io.github.some_example_name.old.journal.CellLabInput
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Спайковые формулы и обучение Synapse — на живом мире стенда гайда: тот же CellSystem и
 * та же нейрофаза, что в игре.
 */
class SpikingTest {

    /** Спайки нейрона стенда за [ticks] тиков при ровном входе [signal]; заодно проверяет, что выход только 0 или 1. */
    private fun lifSpikes(signal: Float, ticks: Int = 300): Int {
        val lab = CellLabModel("neuron")
        try {
            lab.apply(CellLabInput("set", "formula", CellLabModel.FORMULAS.indexOf("lif").toFloat()))
            lab.apply(CellLabInput("set", "a", 0.1f))
            lab.apply(CellLabInput("set", "signal", signal))
            var spikes = 0
            var previous = 0f
            repeat(ticks) {
                lab.step()
                val y = lab.c.neuronImpulseOutput[lab.primary]
                assertTrue(y == 0f || y == 1f, "выход lif $y")
                // Пауза после спайка: два спайка подряд Synapse принял бы за один.
                assertTrue(!(y == 1f && previous == 1f), "два спайка подряд")
                if (y == 1f) spikes++
                previous = y
            }
            return spikes
        } finally { lab.dispose() }
    }

    @Test fun `lif - чем сильнее вход, тем чаще спайки, тишина не даёт ни одного`() {
        val none = lifSpikes(0f)
        val weak = lifSpikes(0.3f)
        val strong = lifSpikes(0.6f)
        println("LIF spikes: 0 -> $none, 0.3 -> $weak, 0.6 -> $strong")
        assertEquals(0, none)
        // v = 0.3·(1 − 0.9^n)/0.1 доходит до порога 1 на 4-м тике, плюс тик паузы — период 5.
        assertEquals(60, weak)
        assertTrue(strong > weak, "strong=$strong weak=$weak")
    }

    @Test fun `trace - выход примерно равен частоте спайков и гаснет до нуля`() {
        val lab = CellLabModel("neuron")
        try {
            lab.c.setActivationFuncType(lab.primary, Formulas.requireIndex("trace").toByte())
            lab.apply(CellLabInput("set", "a", 0.1f))
            lab.apply(CellLabInput("set", "b", 0f))
            var sum = 0f
            repeat(600) { tick ->
                // Спайк каждый 5-й тик: частота 0.2.
                lab.apply(CellLabInput("set", "signal", if (tick % 5 == 0) 1f else 0f))
                lab.step()
                if (tick >= 300) sum += lab.c.neuronImpulseOutput[lab.primary]
            }
            val mean = sum / 300
            println("TRACE mean=$mean")
            assertEquals(0.2f, mean, 0.02f)

            lab.apply(CellLabInput("set", "signal", 0f))
            repeat(200) { lab.step() }
            assertEquals(0f, lab.c.neuronImpulseOutput[lab.primary])
        } finally { lab.dispose() }
    }

    private fun synapseLab(delay: Float, teacher: Boolean = true, cycles: Int = 15, block: (CellLabModel) -> Unit = {}): CellLabModel {
        val lab = CellLabModel("synapse")
        lab.apply(CellLabInput("set", "delay", delay))
        lab.apply(CellLabInput("set", "teacher", if (teacher) 1f else 0f))
        block(lab)
        repeat(cycles * 120) { lab.step() }
        return lab
    }

    @Test fun `synapse - вход раньше нейрона усиливает, позже ослабляет`() {
        val up = synapseLab(8f)
        val down = synapseLab(-8f)
        try {
            val wUp = up.world.specialEntity.synapseState(up.primary)!!.weight
            val wDown = down.world.specialEntity.synapseState(down.primary)!!.weight
            println("SYNAPSE delay +8 -> $wUp, -8 -> $wDown")
            assertTrue(wUp > 0.6f, "вес после усиления $wUp")
            assertTrue(wDown < 0.5f, "вес после ослабления $wDown")
        } finally { up.dispose(); down.dispose() }
    }

    @Test fun `synapse - выученный синапс будит нейрон без учителя, неученый нет`() {
        val trained = synapseLab(8f)
        val untrained = synapseLab(8f, teacher = false)
        try {
            val trainedBefore = trained.postSpikes
            trained.apply(CellLabInput("set", "teacher", 0f))
            repeat(5 * 120) { trained.step() }
            println("SYNAPSE без учителя: выученный +${trained.postSpikes - trainedBefore}, неученый ${untrained.postSpikes}")
            assertTrue(trained.postSpikes - trainedBefore >= 5, "выученный синапс должен будить нейрон каждый цикл")
            assertEquals(0, untrained.postSpikes)
        } finally { trained.dispose(); untrained.dispose() }
    }

    @Test fun `synapse - тормозящий отдаёт вход с минусом`() {
        val lab = synapseLab(8f, cycles = 1) { it.apply(CellLabInput("set", "inhibitory", 1f)) }
        try {
            var minOutput = 0f
            repeat(120) {
                lab.step()
                minOutput = minOf(minOutput, lab.c.neuronImpulseOutput[lab.primary])
            }
            assertTrue(minOutput < 0f, "выход тормозящего синапса $minOutput")
        } finally { lab.dispose() }
    }
}
