package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.CellTypes
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.systems.genomics.genome.LegacyTypeIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Реестры клеток и формул: на них держится чтение всех старых файлов. */
class CellTypesTest {

    private val cells = CellTypes.createAll()

    @Test
    fun `номер типа — место в реестре, ключ находит тот же тип`() {
        cells.forEachIndexed { index, cell ->
            assertEquals(index, cell.cellTypeId)
            assertEquals(index, cells.indexOfKey(cell.key))
        }
        assertEquals(-1, cells.indexOfKey("mod:unknown"))
    }

    @Test
    fun `каждый старый номер клетки ведёт в существующую клетку`() {
        for (key in LegacyTypeIds.CELL_TYPES) {
            assertTrue(cells.containsKey(key), "старый тип '$key' пропал из реестра — старые геномы не откроются")
        }
    }

    @Test
    fun `каждый старый номер формулы ведёт в существующую формулу`() {
        for (key in LegacyTypeIds.FORMULAS + LegacyTypeIds.IDENTITY_FORMULA) {
            assertNotEquals(-1, Formulas.indexOfKey(key), "старая формула '$key' пропала из реестра")
        }
    }

    /**
     * Имя из ключа совпадает с прежним именем класса: по нему лежат настройки клеток в
     * файле игрока (GlobalSimulationSettings.cellsSettings).
     */
    @Test
    fun `имя типа — прежнее имя класса`() {
        for (cell in cells) assertEquals(cell::class.simpleName, cell.name)
    }

    @Test
    fun `ключ мода — по правилам id мода Fabric`() {
        for (key in listOf("biomod:glowworm", "my-mod:cell_2", "bio_mod:x")) assertTrue(CellTypes.MOD_KEY.matches(key), key)
        // Без мода, id из одной буквы (Fabric требует 2+), заглавные, имя с дефисом.
        for (key in listOf("glowworm", "b:glowworm", "BioMod:glowworm", "biomod:glow-worm", "1mod:x")) {
            assertFalse(CellTypes.MOD_KEY.matches(key), key)
        }
    }

    @Test
    fun `данные новой клетки — у тех, кому они нужны`() {
        val withData = cells.filter { it.createSpecialData(Cell.NO_GENOME_ACTION) != null }.map { it.key }.toSet()
        assertEquals(setOf("eye", "tail", "producer", "pheromone_emitter", "controller", "chameleon"), withData)
    }

    @Test
    fun `изменяемое состояние у каждой клетки своё`() {
        val tail = cells.byKey("tail")
        assertNotSame(tail.createSpecialData(Cell.NO_GENOME_ACTION), tail.createSpecialData(Cell.NO_GENOME_ACTION))
    }
}
