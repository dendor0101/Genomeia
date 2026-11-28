package io.github.some_example_name.old.systems.genomics.genome

import io.github.some_example_name.old.cells.base.CellTypes
import io.github.some_example_name.old.cells.base.Formulas
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Встроенные геномы (assets/genomes) записаны до ключей — номерами. Проверка, что все они
 * читаются и что каждый номер перевёлся в существующую клетку и формулу: удалили клетку из
 * реестра, а она есть во встроенном геноме — здесь это и всплывёт.
 */
class BundledGenomesTest {

    /** Тесты :core запускаются из каталога core. */
    private val dir = File("../assets/genomes")

    @Test
    fun `встроенные геномы читаются и ссылаются на известные клетки и формулы`() {
        val cells = CellTypes.createAll()
        val files = dir.listFiles { file -> file.extension == "genome" }.orEmpty()
        assertTrue(files.isNotEmpty(), "нет встроенных геномов в ${dir.absolutePath}")

        for (file in files) {
            val genome = decodeGenome(file.readBytes())
            val stages = genome.stageInstruction + genome.subGenomes.values.flatMap { it.genomeStageInstruction }
            val actions = stages.flatMap { it.cellActions.values }.flatMap { listOfNotNull(it.divide, it.mutate) }

            for (action in actions) {
                action.cellType?.let { assertTrue(cells.containsKey(it), "${file.name}: неизвестная клетка '$it'") }
                action.funActivation?.let {
                    assertNotEquals(-1, Formulas.indexOfKey(it), "${file.name}: неизвестная формула '$it'")
                }
            }
        }
    }

    /**
     * Встроенные геномы лежат в ассетах только для чтения, и игра их не допекает. Снимок для
     * превью им ставит :lwjgl3:bakeBundledGenomes — забыли прогнать после добавления встроенного генома,
     * и это всплывёт здесь, а не пустой карточкой у игрока.
     */
    @Test
    fun `у встроенных геномов есть снимок для превью`() {
        val cells = CellTypes.createAll()
        val files = dir.listFiles { file -> file.extension == "genome" }.orEmpty()
        assertTrue(files.isNotEmpty(), "нет встроенных геномов в ${dir.absolutePath}")

        for (file in files) {
            val genome = decodeGenome(file.readBytes())
            val preview = assertNotNull(genome.preview, "${file.name}: нет снимка, прогоните :lwjgl3:bakeBundledGenomes")
            assertEquals(genome.cellsAmount, preview.cellCount, "${file.name}: клеток в снимке")
            preview.cellTypeKeys.forEach { assertTrue(cells.containsKey(it), "${file.name}: неизвестная клетка '$it' в снимке") }
        }
    }
}
