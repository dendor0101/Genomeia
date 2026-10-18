package io.github.some_example_name.old.systems.genomics.library

import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.GenomePreview
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.old.systems.genomics.genome.encodeGenome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Импорт файлов и перенос старых JSON — на настоящих файлах во временном каталоге.
 * Запекание здесь подменено: настоящее выращивает геном в мире редактора, а он тянет
 * OpenGL. Его проверяет :lwjgl3:genomeJsonCheck.
 */
class GenomeImportTest {

    private val root: File = Files.createTempDirectory("genome-import-test").toFile()
    private val genomes = FileHandle(File(root, "genomes"))
    private val outside = FileHandle(File(root, "outside")).apply { mkdirs() }

    /** Снимок, который «снимает» поддельное запекание. */
    private val bakedPreview = GenomePreview(
        cellTypeKeys = listOf("leaf"),
        cellType = intArrayOf(0, 0),
        x = floatArrayOf(-0.5f, 0.5f),
        y = floatArrayOf(0f, 0f),
        radius = floatArrayOf(0.5f, 0.5f),
        angleCos = floatArrayOf(1f, 1f),
        angleSin = floatArrayOf(0f, 0f),
        colorBytes = GenomePreview.colorBytesOf(intArrayOf(-1, -1)),
        cellGenomeId = intArrayOf(0, 1)
    )

    /** Поддельное запекание: помечает геном так, чтобы было видно, что его пекли и под каким именем. */
    private val bakedNames = mutableListOf<String>()
    private val baker = Baker { genome, name ->
        bakedNames += name
        genome.copy(name = name, cellsAmount = genome.stageInstruction.size, linksAmount = 7, preview = bakedPreview)
    }

    private val json = """
        { "name": "Внутреннее имя", "genomeStageInstruction": [
            { "cellActions": { "class": "java.util.LinkedHashMap",
                "0": { "divide": { "id": "1", "angle": 0, "cellType": 0, "physicalLink": { "0": { "length": 24 } }, "colorHsv": "ffc0caff" } } } },
            { "cellActions": { "0": { "mutate": { "cellType": 22, "physicalLink": {} } } } }
        ] }
    """.trimIndent()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(name: String, content: String) = outside.child(name).apply { writeString(content, false, "UTF-8") }

    @Test
    fun `JSON переводится, запекается и ложится под именем файла`() {
        val imported = GenomeImport.importFile(write("Hypha.json", json), genomes, baker)

        assertEquals("Hypha.genome", imported.name())
        assertEquals(listOf("Hypha"), bakedNames)
        val genome = decodeGenome(imported.readBytes())
        // Имя внутри — как у сохранённого редактором: имя файла.
        assertEquals("Hypha", genome.name)
        assertEquals(2, genome.cellsAmount)
        assertEquals(7, genome.linksAmount)
        assertEquals(bakedPreview, genome.preview)
        assertEquals(24f / 40f, genome.stageInstruction[0].cellActions.getValue(0).divide!!.physicalLink.getValue(0)!!.length)
    }

    @Test
    fun `повторный импорт того же файла не плодит копий`() {
        val first = GenomeImport.importFile(write("Hypha.json", json), genomes, baker)
        val second = GenomeImport.importFile(write("Hypha.json", json), genomes, baker)

        assertEquals(first.path(), second.path())
        assertEquals(listOf("Hypha.genome"), genomes.list().map { it.name() })
    }

    @Test
    fun `чужой геном с тем же именем не перезаписывается`() {
        val existing = genomes.child("Hypha.genome")
        val existingBytes = encodeGenome(Genome(version = 24, name = "Hypha"))
        existing.writeBytes(existingBytes, false)

        val imported = GenomeImport.importFile(write("Hypha.json", json), genomes, baker)

        assertEquals("Hypha (2).genome", imported.name())
        assertEquals("Hypha (2)", decodeGenome(imported.readBytes()).name)
        assertContentEquals(existingBytes, existing.readBytes())

        // И третий раз — снова в «Hypha (2)», а не в «Hypha (3)».
        assertEquals("Hypha (2).genome", GenomeImport.importFile(write("Hypha.json", json), genomes, baker).name())
    }

    /** Уже запечённый .genome — как его сохраняет редактор. */
    private fun bakedGenome(name: String) =
        LegacyGenomeJson.parse(json, name).copy(cellsAmount = 3, linksAmount = 2, preview = bakedPreview)

    @Test
    fun `запечённый genome кладётся байт в байт и без запекания`() {
        val bytes = encodeGenome(bakedGenome("Кто-то"))
        val source = outside.child("~Кто-то.genome").apply { writeBytes(bytes, false) }

        val imported = GenomeImport.importFile(source, genomes, baker)

        assertEquals("Кто-то.genome", imported.name())
        assertContentEquals(bytes, imported.readBytes())
        assertTrue(bakedNames.isEmpty())
    }

    @Test
    fun `формат узнаётся по содержимому, а не по расширению`() {
        val bytes = encodeGenome(bakedGenome("g"))
        val genomeAsJson = outside.child("g.json").apply { writeBytes(bytes, false) }
        val jsonAsGenome = write("j.genome", json)

        assertContentEquals(bytes, GenomeImport.importFile(genomeAsJson, genomes, baker).readBytes())
        assertEquals("j", decodeGenome(GenomeImport.importFile(jsonAsGenome, genomes, baker).readBytes()).name)
    }

    @Test
    fun `genome без запекания допекается, а остальное в нём не меняется`() {
        val unbaked = LegacyGenomeJson.parse(json, "Внутреннее имя")
        val source = outside.child("Старый.genome").apply { writeBytes(encodeGenome(unbaked), false) }

        val genome = decodeGenome(GenomeImport.importFile(source, genomes, baker).readBytes())

        assertEquals(listOf("Внутреннее имя"), bakedNames)
        assertEquals(2, genome.cellsAmount)
        assertEquals(7, genome.linksAmount)
        assertEquals(unbaked.copy(cellsAmount = 2, linksAmount = 7, preview = bakedPreview), genome)
    }

    @Test
    fun `genome без снимка получает только снимок, раскладка и размеры остаются из файла`() {
        val withoutPreview = bakedGenome("Без превью").copy(preview = null)
        val source = outside.child("Без превью.genome").apply { writeBytes(encodeGenome(withoutPreview), false) }

        val genome = decodeGenome(GenomeImport.importFile(source, genomes, baker).readBytes())

        assertEquals(listOf(withoutPreview.name), bakedNames)
        // Поддельное запекание дало бы cellsAmount = 2 и linksAmount = 7 — их брать нельзя.
        assertEquals(withoutPreview.copy(preview = bakedPreview), genome)
    }

    @Test
    fun `при запуске старые genome запекаются на месте, запечённые и битые не трогаются`() {
        val unbaked = genomes.child("Старый.genome").apply { writeBytes(encodeGenome(LegacyGenomeJson.parse(json, "Старый")), false) }
        val bakedBytes = encodeGenome(bakedGenome("Новый"))
        val baked = genomes.child("Новый.genome").apply { writeBytes(bakedBytes, false) }
        val broken = genomes.child("Битый.genome").apply { writeBytes("genomeia".toByteArray() + byteArrayOf(0, 0, 0, 1, 0x7F), false) }

        val (done, failed) = GenomeLibrary.bakeUnbaked(baker, genomes)

        assertEquals(listOf("Старый.genome"), done.map { it.name() })
        assertEquals(listOf("Битый.genome"), failed.map { it.first.name() })
        assertEquals(2, decodeGenome(unbaked.readBytes()).cellsAmount)
        assertNotNull(decodeGenome(unbaked.readBytes()).preview)
        assertContentEquals(bakedBytes, baked.readBytes())
        assertTrue(broken.exists())

        // Второй запуск: печь уже нечего.
        assertTrue(GenomeLibrary.bakeUnbaked(baker, genomes).first.isEmpty())
    }

    @Test
    fun `битый файл отвергается и ничего не пишет`() {
        val brokenGenome = outside.child("broken.genome").apply {
            writeBytes("genomeia".toByteArray() + byteArrayOf(0, 0, 0, 1, 0x7F, 0x7F, 0x7F), false)
        }
        assertFailsWith<IllegalArgumentException> { GenomeImport.importFile(brokenGenome, genomes, baker) }
        assertFailsWith<IllegalArgumentException> { GenomeImport.importFile(write("picture.json", "PNG..."), genomes, baker) }
        assertFailsWith<IllegalArgumentException> { GenomeImport.importFile(write("other.json", """{ "a": 1 }"""), genomes, baker) }
        assertFalse(genomes.exists() && genomes.list().isNotEmpty())
    }

    @Test
    fun `имя генома из имени выбранного файла`() {
        assertEquals("foo", GenomeImport.fileBaseName("~foo.genome"))
        assertEquals("foo", GenomeImport.fileBaseName("primary:Download/foo.genome"))
        assertEquals("bar", GenomeImport.fileBaseName("C:\\Users\\me\\bar.JSON"))
        assertEquals("Big walker", GenomeImport.fileBaseName("Big walker.json"))
        assertEquals("ab", GenomeImport.fileBaseName("a<b>?.json"))
        assertEquals("imported", GenomeImport.fileBaseName(".json"))
        assertEquals("imported", GenomeImport.fileBaseName(""))
    }

    @Test
    fun `старые JSON переносятся один раз и убираются в converted`() {
        val legacy = FileHandle(File(root, "user_genomes"))
        legacy.child("Glist.json").writeString(json, false, "UTF-8")
        legacy.child("~Maggot.json").writeString(json, false, "UTF-8")
        legacy.child("broken.json").writeString("{ oops", false, "UTF-8")

        val (converted, failed) = GenomeLibrary.convertLegacyJson(baker, listOf(legacy), genomes)

        assertEquals(listOf("Glist.genome", "Maggot.genome"), converted.map { it.second.name() }.sorted())
        assertEquals(listOf("broken.json"), failed.map { it.first.name() })
        assertEquals(listOf("Glist.json", "~Maggot.json"), legacy.child("converted").list().map { it.name() }.sorted())
        // Битый остаётся на месте: игрок его увидит, и в следующий раз будет ещё попытка.
        assertEquals(listOf("broken.json"), legacy.list().filter { !it.isDirectory }.map { it.name() })

        val (again, _) = GenomeLibrary.convertLegacyJson(baker, listOf(legacy), genomes)
        assertTrue(again.isEmpty())
        assertEquals(listOf("Glist.genome", "Maggot.genome"), genomes.list().map { it.name() }.sorted())
    }
}
