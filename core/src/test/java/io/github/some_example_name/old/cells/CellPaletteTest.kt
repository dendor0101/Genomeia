package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.cells.base.CellTypes
import io.github.some_example_name.old.systems.genomics.genome.LegacyTypeIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Палитра редактора: скрывает из выбора, но не из игры. */
class CellPaletteTest {

    private val cells = CellTypes.createAll()

    @Test
    fun `полная палитра — всё, кроме выведенного из игры`() {
        val palette = CellPalette.FULL
        assertEquals(cells.map { it.key }.filterNot(CellPalette.RETIRED_CELLS::contains), palette.cellChoices(cells, current = null).map { it.key })
        assertFalse(palette.allowsFormula(LegacyTypeIds.IDENTITY_FORMULA))
        assertTrue(palette.formulaChoices(current = null).none { it.key == LegacyTypeIds.IDENTITY_FORMULA })
    }

    @Test
    fun `палитра уровня показывает только разрешённое, в порядке реестра`() {
        val palette = CellPalette.only(cells = setOf("neuron", "leaf"), formulas = setOf("sigmoid"))
        assertEquals(listOf("leaf", "neuron"), palette.cellChoices(cells, current = null).map { it.key })
        assertEquals(listOf("sigmoid"), palette.formulaChoices(current = null).map { it.key })
        // Обычная формула скрыта — новый нейрон получает первую разрешённую.
        assertEquals("sigmoid", palette.defaultFormula().key)
    }

    @Test
    fun `выведенная клетка скрыта и в палитре уровня, но видна у клетки этого типа`() {
        val retired = CellPalette.RETIRED_CELLS.firstOrNull() ?: return
        val palette = CellPalette.only(cells = setOf("leaf", retired), formulas = setOf("linear"))
        assertEquals(listOf("leaf"), palette.cellChoices(cells, current = null).map { it.key })
        assertTrue(palette.cellChoices(cells, current = retired).any { it.key == retired })
    }

    @Test
    fun `тип редактируемой клетки виден, даже если скрыт`() {
        val palette = CellPalette.only(cells = setOf("leaf"), formulas = setOf("linear"))
        assertEquals(listOf("leaf", "eye"), palette.cellChoices(cells, current = "eye").map { it.key })
        assertEquals(listOf("linear", "identity"), palette.formulaChoices(current = "identity").map { it.key })
    }

    @Test
    fun `зиготу нельзя выбрать при делении и мутации, но в справочнике она есть`() {
        val palette = CellPalette.FULL
        assertTrue(palette.cellChoices(cells, current = null).any { it.key == "zygote" })
        assertTrue(palette.actionCellChoices(cells, current = null).none { it.key == "zygote" })
        assertTrue(palette.actionCellChoices(cells, current = "leaf").none { it.key == "zygote" })
        // Мутация самой зиготы-корня: её тип в списке остаётся, иначе диалог подменил бы его.
        assertTrue(palette.actionCellChoices(cells, current = "zygote").any { it.key == "zygote" })
    }

    @Test
    fun `опечатка в палитре уровня находится`() {
        val palette = CellPalette.only(cells = setOf("leaf", "lief"), formulas = setOf("linaer"))
        assertEquals(listOf("lief", "linaer"), palette.unknownKeys(cells))
    }
}
