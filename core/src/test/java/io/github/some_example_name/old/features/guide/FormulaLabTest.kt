package io.github.some_example_name.old.features.guide

import io.github.some_example_name.old.cells.base.CellPalette
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Стенд формул в гайде. Сам нейрон стенда (NeuronLab) здесь не создаётся: ему нужны
 * сущности DIGenomeEditorContainer, а их инициализация тянет OpenGL.
 */
class FormulaLabTest {

    @Test
    fun `вход собирается как в симуляции - сумма с нуля, произведение с единицы`() {
        val s = NeuronLabSettings()
        s.inputCount = 3
        s.inputs[0] = 2f; s.inputs[1] = -3f; s.inputs[2] = 0.5f
        s.isSum = true
        assertEquals(-0.5f, s.x(), 1e-6f)
        s.isSum = false
        assertEquals(-3f, s.x(), 1e-6f)

        // Невидимые входы (за inputCount) в x не попадают.
        s.inputs[3] = 100f
        assertEquals(-3f, s.x(), 1e-6f)
    }

    @Test
    fun `у каждой формулы из списка редактора есть описание во всех переводах`() {
        val keys = CellPalette.FULL.formulaChoices(current = null).map { "formula.${it.key}.description" } +
            listOf("guide.tab.formulas", "guide.cell.formulasLink", "guide.formulas.text", "guide.formulas.overTime")
        val dir = File("../assets/ui/i18n")
        for (name in listOf("MyBundle", "MyBundle_ru", "MyBundle_es", "MyBundle_id")) {
            val props = Properties().apply { File(dir, "$name.properties").reader(Charsets.UTF_8).use(::load) }
            val missing = keys.filterNot(props::containsKey)
            assertTrue(missing.isEmpty(), "$name: нет ${missing.joinToString()}")
        }
    }
}
