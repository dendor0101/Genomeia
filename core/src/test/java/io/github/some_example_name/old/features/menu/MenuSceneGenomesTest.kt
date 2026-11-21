package io.github.some_example_name.old.features.menu

import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Геномы фоновой сцены меню есть в ассетах.
 *
 * Отчёт 21.11: из ассетов убрали Leech, а сцена меню ссылалась на него — игра падала на
 * старте с «File not found: genomes\Leech.genome», до первого экрана. Встроенные геномы
 * чистят руками, и такая ссылка должна всплыть здесь, а не у игрока.
 */
class MenuSceneGenomesTest {

    /** Тесты :core запускаются из каталога core. */
    private val assets = File("../assets")

    @Test
    fun `геномы сцены меню лежат в ассетах и читаются`() {
        val listed = File(assets, "assets.txt").readLines().map { it.trim() }.toSet()
        for ((name, count) in MenuViewModel.SCENE_GENOMES) {
            assertTrue(GenomeCatalog.isBundled(name), "$name: сцена меню берёт только встроенные геномы")
            assertTrue(count > 0, "$name: число организмов $count")
            val path = "genomes/${GenomeCatalog.displayName(name)}.genome"
            val file = File(assets, path)
            assertTrue(file.isFile, "$name: нет файла ${file.absolutePath}")
            assertTrue(path in listed, "$name: $path нет в assets.txt")
            assertTrue(decodeGenome(file.readBytes()).stageInstruction.isNotEmpty(), "$name: пустой геном")
        }
    }
}
