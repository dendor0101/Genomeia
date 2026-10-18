package io.github.some_example_name.old.features.genomes

import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.old.systems.genomics.genome.encodeGenome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Удаление, переименование и проверка имени на экране «Геномы» — на настоящих файлах во
 * временной папке игрока (GameFiles.dataRoot, как песочница повтора бага).
 */
class GenomesViewModelTest {

    private val root: File = Files.createTempDirectory("genomes-screen-test").toFile()
    private val genomes get() = GameFiles.genomesDir()
    private val viewModel = GenomesViewModel()

    @BeforeTest
    fun setUp() {
        GameFiles.dataRoot = FileHandle(root)
        genomes.mkdirs()
        put("Glist")
        put("Leech")
    }

    @AfterTest
    fun cleanUp() {
        GameFiles.dataRoot = null
        root.deleteRecursively()
    }

    private fun put(name: String) =
        genomes.child("$name.genome").writeBytes(encodeGenome(Genome(version = 24, name = name, cellsAmount = 3)), false)

    private fun files() = genomes.list().map { it.name() }.sorted()

    @Test
    fun `переименование меняет и файл, и имя внутри генома`() {
        viewModel.handle(GenomesIntent.Rename("Glist", "Мой глист"))

        assertEquals(listOf("Leech.genome", "Мой глист.genome"), files())
        val renamed = decodeGenome(genomes.child("Мой глист.genome").readBytes())
        assertEquals("Мой глист", renamed.name)
        assertEquals(3, renamed.cellsAmount)
    }

    @Test
    fun `смена одного регистра не теряет геном`() {
        viewModel.handle(GenomesIntent.Rename("Glist", "GLIST"))

        assertEquals(listOf("GLIST.genome", "Leech.genome"), files())
        assertEquals("GLIST", decodeGenome(genomes.child("GLIST.genome").readBytes()).name)
    }

    @Test
    fun `удаление убирает только свой геном, встроенный удалить нельзя`() {
        viewModel.handle(GenomesIntent.Delete("Leech"))
        assertEquals(listOf("Glist.genome"), files())

        assertFailsWith<IllegalStateException> { viewModel.handle(GenomesIntent.Delete("bundled/Glist")) }
        assertEquals(listOf("Glist.genome"), files())
    }

    @Test
    fun `имя проверяется до команды`() {
        assertEquals(RenameProblem.EMPTY, viewModel.renameProblem("Glist", "   "))
        assertEquals(RenameProblem.INVALID, viewModel.renameProblem("Glist", "a/b"))
        assertEquals(RenameProblem.INVALID, viewModel.renameProblem("Glist", "что?"))
        assertEquals(RenameProblem.TAKEN, viewModel.renameProblem("Glist", "leech"))
        assertNull(viewModel.renameProblem("Glist", "glist"))
        assertNull(viewModel.renameProblem("Glist", " Новый "))
    }

    @Test
    fun `список и уведомление после изменений`() {
        var changed = 0
        viewModel.onGenomesChanged = { changed++ }

        viewModel.handle(GenomesIntent.Delete("Glist"))
        viewModel.handle(GenomesIntent.Rename("Leech", "Пиявка"))

        assertEquals(2, changed)
        assertEquals(listOf("Пиявка"), viewModel.genomeNames())
        assertFalse(genomes.child("Leech.genome").exists())
        assertTrue(genomes.child("Пиявка.genome").exists())
    }
}
