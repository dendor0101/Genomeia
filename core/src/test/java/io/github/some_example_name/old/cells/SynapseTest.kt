package io.github.some_example_name.old.cells

import io.github.some_example_name.old.systems.genomics.genome.Action

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Чтение параметров Synapse из полей a, b, c без симуляции. Само обучение — на живом мире
 * в CellLabModelTest (стенд синапса).
 */
class SynapseTest {
    @Test
    fun `новый синапс наследует выученный вес с сохранением полярности`() {
        val synapse = Synapse(0)
        val learned = SynapseState(weight = .74f, preTrace = .5f, inhibitory = true)
        val inherited = synapse.genomeDataOf(learned)
        val child = synapse.createSpecialData(Action(special = inherited)) as SynapseState
        assertEquals(SynapseState(weight = .74f, inhibitory = true), child)
        assertEquals(Synapse.NOT_STARTED, (synapse.createSpecialData(Action()) as SynapseState).weight)
    }


    @Test
    fun `learningRate и depression - ноль и меньше значат значение по умолчанию`() {
        // Старые геномы: синапс получал b = c = 0 от обычного нейрона.
        assertEquals(Synapse.DEFAULT_LEARNING_RATE, Synapse.learningRate(0f))
        assertEquals(Synapse.DEFAULT_LEARNING_RATE, Synapse.learningRate(-0.5f))
        assertEquals(Synapse.DEFAULT_LEARNING_RATE, Synapse.learningRate(Float.NaN))
        assertEquals(Synapse.DEFAULT_DEPRESSION, Synapse.depression(0f))
        assertEquals(Synapse.DEFAULT_DEPRESSION, Synapse.depression(Float.NaN))

        assertEquals(0.05f, Synapse.learningRate(0.05f))
        assertEquals(0.02f, Synapse.depression(0.02f))
    }

    @Test
    fun `learningRate и depression - зажаты сверху единицей`() {
        assertEquals(1f, Synapse.learningRate(Float.POSITIVE_INFINITY))
        assertEquals(1f, Synapse.depression(7f))
    }

    @Test
    fun `затухание следа - в пределах от самого медленного до единицы`() {
        assertEquals(Synapse.MIN_DECAY_RATE, Synapse.traceDecay(0f))
        assertEquals(Synapse.MIN_DECAY_RATE, Synapse.traceDecay(-1f))
        assertEquals(0.3f, Synapse.traceDecay(0.3f))
        assertEquals(1f, Synapse.traceDecay(7f))
        assertEquals(1f, Synapse.traceDecay(Float.POSITIVE_INFINITY))
        assertEquals(Synapse.DEFAULT_DECAY_RATE, Synapse.traceDecay(Float.NaN))
    }

    @Test
    fun `память следа - около 3 делить на затухание`() {
        assertEquals(120, Synapse.memoryTicks(Synapse.DEFAULT_DECAY_RATE))
        // Самое медленное — ~500 тиков, как спайк без затухания у прежнего синапса.
        assertEquals(500, Synapse.memoryTicks(0f))
        assertEquals(3, Synapse.memoryTicks(1f))
    }
}
