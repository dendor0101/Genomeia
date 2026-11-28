package io.github.some_example_name.old.systems.genomics.library

import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.core.GameFilePaths
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.old.systems.genomics.genome.loadGenome

/**
 * Какие геномы есть у игрока и как к ним обращаться.
 *
 * Два источника:
 *  - свои — файлы в папке геномов (GameFiles.genomesDir): сохранённые в редакторе,
 *    импортированные, перенесённые из старых JSON;
 *  - встроенные — assets/genomes, только для чтения. В папку игрока они не копируются:
 *    в список своих встроенный геном попадает, только когда игрок сохранит его из редактора.
 *
 * ИМЯ ГЕНОМА В КОМАНДАХ
 * ---------------------
 * OpenGenomeEditor, SelectGenome, GoSimulation несут имя строкой. У своего генома это имя
 * файла без расширения, у встроенного — то же с приставкой [BUNDLED_PREFIX]. В имени файла
 * не бывает '/', поэтому имена не пересекаются, даже когда игрок сохранил встроенный
 * «Glist» под тем же именем. Формат команд не меняется: имя без приставки — как и в старых
 * .bug — свой геном.
 *
 * ПОВТОР БАГА
 * -----------
 * Свои геномы при повторе берутся из снимка в .bug, встроенные — из сборки разработчика,
 * как и всё остальное в ассетах: они часть версии игры, а не данные игрока. В .bug их не
 * кладём: это ~1 МБ к каждому отчёту.
 */
object GenomeCatalog {

    /** Приставка имени встроенного генома. */
    const val BUNDLED_PREFIX = "bundled/"

    /** Встроенные геномы в ассетах. */
    private const val BUNDLED_DIR = "genomes"

    /** Список встроенных — из сборки, за запуск не меняется. */
    private val bundled: List<String> by lazy { readBundledNames() }

    /**
     * Свои геномы, по имени.
     *
     * Сортировка обязательна: порядок list() у каждой файловой системы свой (NTFS — по
     * алфавиту, ext4 на Android — по хэшу), а номер в списке [all] — номер генома в мире
     * (GenomeManager.genomes, PlayerCommand.Tap.genomeIndex). Без сортировки .bug с
     * телефона повторялся бы на ПК с другими геномами, а игрок видел бы список вразнобой.
     */
    fun playerNames(): List<String> {
        val folder = GameFiles.genomesDir()
        if (!folder.isDirectory) return emptyList()
        return folder.list()
            .filter { !it.isDirectory && it.extension().equals("genome", ignoreCase = true) }
            .map { it.nameWithoutExtension() }
            .sortedWith(NAME_ORDER)
    }

    /** Встроенные геномы — имена уже с приставкой [BUNDLED_PREFIX]. */
    fun bundledNames(): List<String> = bundled

    /**
     * Все геномы мира: сначала свои, потом встроенные. Этот порядок — номера геномов в
     * GenomeManager.genomes; список выбора в симуляции показывает его же, с разделителем.
     */
    fun all(): List<String> = playerNames() + bundledNames()

    fun isBundled(name: String): Boolean = name.startsWith(BUNDLED_PREFIX)

    /** Что показать игроку: имя без приставки. */
    fun displayName(name: String): String = name.removePrefix(BUNDLED_PREFIX)

    /** Геном по имени из списка — свой или встроенный. */
    fun load(name: String): Genome =
        if (isBundled(name)) {
            decodeGenome(Gdx.files.internal("$BUNDLED_DIR/${GameFilePaths.fileName(displayName(name))}.genome").readBytes())
        } else {
            loadGenome(name)
        }

    /**
     * Имена встроенных — по assets.txt, его пишет сборка (generateAssetList).
     *
     * Не internal("genomes").list() в первую очередь: на ПК внутренний файл ищется сначала в
     * рабочем каталоге, а у собранной игры там лежит папка геномов ИГРОКА с тем же именем.
     * list() вернул бы его геномы вместо встроенных, а папку внутри jar он не перечисляет
     * вовсе. list() — только запасной путь, если assets.txt в сборку не попал.
     */
    private fun readBundledNames(): List<String> {
        val prefix = "$BUNDLED_DIR/"
        val assetsList = Gdx.files.internal("assets.txt")
        val names = if (assetsList.exists()) {
            assetsList.readString("UTF-8").lineSequence()
                .map { it.trim() }
                .filter { it.startsWith(prefix) && '/' !in it.substring(prefix.length) && it.endsWith(GenomeImport.EXTENSION) }
                .map { it.substring(prefix.length).removeSuffix(GenomeImport.EXTENSION) }
                .toList()
        } else {
            Gdx.files.internal(BUNDLED_DIR).list()
                .filter { !it.isDirectory && it.extension().equals("genome", ignoreCase = true) }
                .map { it.nameWithoutExtension() }
        }
        return names.sortedWith(NAME_ORDER).map { BUNDLED_PREFIX + it }
    }

    private val NAME_ORDER: Comparator<String> = String.CASE_INSENSITIVE_ORDER.thenBy { it }
}
