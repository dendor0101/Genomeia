package io.github.some_example_name.old.systems.genomics.library

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.old.systems.genomics.genome.encodeGenome
import java.io.File

/**
 * Папка геномов игрока при запуске игры: перенос старых JSON в .genome и допекание
 * .genome, сохранённых до появления запекания или снимка для превью.
 *
 * Встроенные геномы сюда не копируются — они читаются из ассетов (GenomeCatalog).
 *
 * Вызывается один раз, до первого экрана (MyGame.create), и не при повторе бага: там папка
 * геномов — снимок из .bug, и он уже такой, каким был у игрока после этого шага.
 */
object GenomeLibrary {

    /** Где старые версии (до .genome) хранили геномы игрока, относительно своего каталога. */
    private const val LEGACY_DIR = "user_genomes"

    /** Куда убираются перенесённые JSON: рядом, чтобы игрок их нашёл, но не в обход повторно. */
    private const val CONVERTED_DIR = "converted"

    fun prepare(baker: Baker) {
        val (converted, failed) = convertLegacyJson(baker)
        converted.forEach { (from, to) -> Gdx.app.log(TAG, "перенесён старый JSON: ${from.path()} -> ${to.name()}") }
        failed.forEach { (file, reason) -> Gdx.app.error(TAG, "не удалось перенести ${file.path()}: $reason") }

        val (baked, notBaked) = bakeUnbaked(baker)
        baked.forEach { Gdx.app.log(TAG, "запечён старый геном: ${it.name()}") }
        notBaked.forEach { (file, reason) -> Gdx.app.error(TAG, "не удалось запечь ${file.path()}: $reason") }
    }

    /**
     * Свои .genome, в которых чего-то не хватает, — допечь на месте (GenomeImport.withBaking):
     *  - без запекания — сохранены между переходом на .genome и появлением запекания; такой
     *    геном роняет игру при посадке в мир;
     *  - без снимка для превью — сохранены до его появления. Это один раз выращивает тело
     *    каждого такого генома в мире редактора (см. :lwjgl3:genomeJsonCheck, там же время);
     *  - с границей тела прежнего признака (BakedLayout.interiorVersion) — тоже одно
     *    выращивание, A-11 на 947 клеток ~90 мс.
     * Полностью запечённые файлы не трогаются, битые пропускаются с записью в лог.
     *
     * [dir] — папка игрока; встроенные геномы (assets/genomes) допекаются этим же кодом при
     * сборке, см. :lwjgl3:bakeBundledGenomes.
     */
    fun bakeUnbaked(
        baker: Baker,
        dir: FileHandle = GameFiles.genomesDir()
    ): Pair<List<FileHandle>, List<Pair<FileHandle, String>>> {
        val baked = mutableListOf<FileHandle>()
        val failed = mutableListOf<Pair<FileHandle, String>>()
        if (!dir.isDirectory) return baked to failed
        val files = dir.list()
            .filter { !it.isDirectory && it.extension().equals("genome", ignoreCase = true) }
            .sortedBy { it.name() }
        for (file in files) {
            try {
                val genome = decodeGenome(file.readBytes())
                val result = GenomeImport.withBaking(genome, baker) ?: continue
                file.writeBytes(encodeGenome(result), false)
                baked += file
            } catch (e: Exception) {
                failed += file to (e.message ?: e.toString())
            }
        }
        return baked to failed
    }

    /**
     * Старые JSON игрока → .genome с запеканием. Перенесённый файл уезжает в converted/
     * рядом с собой: при следующем запуске его уже не будет среди кандидатов.
     *
     * Сначала пишется геном, потом убирается JSON. Если убрать не вышло (нет прав), в
     * следующий раз JSON переведётся снова, но GenomeImport найдёт тот же самый файл генома
     * и второй копии не сделает. Битый JSON остаётся на месте и пишется в лог.
     */
    fun convertLegacyJson(
        baker: Baker,
        sources: List<FileHandle> = legacyJsonDirs(),
        target: FileHandle = GameFiles.genomesDir()
    ): Pair<List<Pair<FileHandle, FileHandle>>, List<Pair<FileHandle, String>>> {
        val converted = mutableListOf<Pair<FileHandle, FileHandle>>()
        val failed = mutableListOf<Pair<FileHandle, String>>()
        for (json in sources.flatMap(::jsonFilesIn)) {
            val genome = try {
                GenomeImport.importFile(json, target, baker)
            } catch (e: Exception) {
                failed += json to (e.message ?: e.toString())
                continue
            }
            converted += json to genome
            try {
                json.moveTo(json.parent().child(CONVERTED_DIR).child(json.name()))
            } catch (e: Exception) {
                Gdx.app.error(TAG, "геном перенесён, но ${json.path()} не убрать в $CONVERTED_DIR/", e)
            }
        }
        return converted to failed
    }

    /**
     * Каталоги, где могут лежать JSON прошлых версий. Все, что встречались в истории:
     *  - рядом с классами игры (так считал путь старый GenomeJsonReader на ПК: каталог jar
     *    собранной игры);
     *  - личное хранилище (Gdx.files.local): Android и iOS, а на ПК — рабочий каталог;
     *  - сама папка геномов: туда JSON кладут руками.
     * Одинаковые каталоги — один раз: у собранной игры на ПК первые два обычно совпадают.
     */
    fun legacyJsonDirs(): List<FileHandle> {
        val dirs = mutableListOf<FileHandle>()
        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            runCatching {
                val classes = File(MyGame::class.java.protectionDomain.codeSource.location.toURI())
                dirs += Gdx.files.absolute(classes.parentFile.absolutePath).child(LEGACY_DIR)
            }
            dirs += GameFiles.gameDir.child(LEGACY_DIR)
        }
        dirs += Gdx.files.local(LEGACY_DIR)
        dirs += GameFiles.genomesDir()
        return dirs.distinctBy { runCatching { it.file().canonicalPath }.getOrElse { _ -> it.path() } }
    }

    private fun jsonFilesIn(dir: FileHandle): List<FileHandle> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        // По имени: порядок переноса решает, кому достанется «X», а кому «X (2)».
        return dir.list()
            .filter { !it.isDirectory && it.extension().equals("json", ignoreCase = true) }
            .sortedBy { it.name() }
    }

    private const val TAG = "GenomeLibrary"
}
