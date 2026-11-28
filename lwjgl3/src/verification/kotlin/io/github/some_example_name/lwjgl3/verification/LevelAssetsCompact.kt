package io.github.some_example_name.lwjgl3.verification

import io.github.some_example_name.old.features.levels.editor.LevelEditorViewModel
import io.github.some_example_name.old.features.levels.editor.LevelFiles
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel
import io.github.some_example_name.old.features.worldeditor.WorldGenerator
import java.io.File
import kotlin.system.exitProcess

/**
 * Встроенные уровни (assets/levels-builtin): рецепт карты без пустых мазков и проверка.
 *
 * Для каждого файла: карта из исходного рецепта и из сжатого (WorldEditorViewModel.applyEdit
 * не пишет мазки, которые ничего не меняют, и всё, что до очистки) должны совпасть
 * побитово — иначе файл не трогаем и выходим с ошибкой. Затем уровень записывается заново
 * в формате LevelFiles (без убранных полей), и печатается размер файла и записи журнала
 * (команда GoSimulation) до и после: предел записи — LevelEditorViewModel.MAX_LEVEL_BYTES.
 *
 * Запуск: gradlew :lwjgl3:levelAssetsCompact  (-PcheckOnly — только проверить, не писать).
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrNull(0) ?: "assets/levels-builtin")
    val checkOnly = System.getProperty("levelAssets.checkOnly") != null
    val files = dir.listFiles { f -> f.isFile && f.extension == "json" }?.sortedBy { it.name }.orEmpty()
    require(files.isNotEmpty()) { "нет уровней в ${dir.absolutePath}" }

    var failed = false
    var totalBefore = 0L
    var totalAfter = 0L
    for (file in files) {
        val text = file.readText(Charsets.UTF_8)
        val level = LevelFiles.decode(text)
        val compactWorld = WorldEditorViewModel.fromSpec(level.world).toSpec()
        val sameMap = level.world.buildMap().contentDeepEquals(compactWorld.buildMap())
        val compact = level.copy(world = compactWorld)
        val newText = LevelFiles.encode(compact)
        val journalBefore = LevelEditorViewModel.encodedSize(level)
        val journalAfter = LevelEditorViewModel.encodedSize(compact)
        totalBefore += text.length
        totalAfter += newText.length
        println(
            "%-24s мазков %5d → %4d, файл %7d → %6d Б, журнал %7d → %6d Б%s".format(
                file.nameWithoutExtension, level.world.edits.size, compactWorld.edits.size,
                text.length, newText.length, journalBefore, journalAfter,
                if (sameMap) "" else "  КАРТА РАЗОШЛАСЬ"
            )
        )
        // Организм уровня — встроенный геном из ассетов: свой геном игрока есть только у
        // того, кто делал уровень (так и было с Antestor и PrimitiveAlgae в PR 38).
        for (organism in level.scriptedOrganisms) {
            val name = organism.genomeName.removePrefix("bundled/")
            if (!organism.genomeName.startsWith("bundled/") || !File(GENOMES_DIR, "$name.genome").isFile) {
                println("  ${file.nameWithoutExtension}: нет встроенного генома '${organism.genomeName}' в $GENOMES_DIR")
                failed = true
            }
        }
        if (!sameMap || journalAfter > LevelEditorViewModel.MAX_LEVEL_BYTES) {
            failed = true
            continue
        }
        if (!checkOnly) file.writeText(newText, Charsets.UTF_8)
    }
    // Параметры генератора — общие поля, fromSpec их меняет.
    WorldGenerator.GENERATOR_DAY_NIGHT = WorldGenerator.DEFAULT_DAY_NIGHT
    WorldGenerator.GENERATOR_INTERPOLATE = WorldGenerator.DEFAULT_INTERPOLATE
    println("Всего файлов: $totalBefore → $totalAfter Б")
    if (failed) {
        println("FAIL: карта разошлась, уровень больше предела записи журнала или нет генома организма")
        exitProcess(1)
    }
    println(if (checkOnly) "PASS: все уровни в порядке" else "PASS: уровни переписаны")
}

private const val GENOMES_DIR = "assets/genomes"
