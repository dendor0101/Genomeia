package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.github.some_example_name.lwjgl3.DesktopFileProvider
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.systems.genomics.genome.decodeGenome
import io.github.some_example_name.old.systems.genomics.genome.encodeGenome
import io.github.some_example_name.old.systems.genomics.library.GenomeLibrary
import java.io.File
import kotlin.system.exitProcess

/**
 * Допекание встроенных геномов (assets/genomes) на настоящей игре.
 *
 * Свои геномы игра допекает сама при запуске (GenomeLibrary.bakeUnbaked), а встроенные
 * лежат в ассетах только для чтения — их допекает разработчик этой программой, тем же
 * кодом: bakeUnbaked, натравленный на папку ассетов. Берётся только недостающее (см.
 * GenomeImport.withBaking): уже лежащие раскладки и стадии не меняются, поэтому повтор
 * старых .bug со встроенными геномами не расходится — симуляция снимок не читает.
 *
 * Затем для каждого файла:
 *  1. снимок для превью обязан быть, и клеток в нём ровно cellsAmount;
 *  2. повторное выращивание обязано дать тот же снимок: превью не должно зависеть от
 *     того, в какой раз и на каком запуске его сняли;
 *  3. печатается время выращивания (столько игра стоит при первом запуске на каждом
 *     своём геноме без снимка) и сколько снимок добавил к файлу.
 *
 * Запуск: gradlew :lwjgl3:bakeBundledGenomes [-Pdir=каталог], по умолчанию assets/genomes.
 * Ненулевой код — хоть один геном не допёкся или снимок не повторился.
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrNull(0) ?: "assets/genomes")
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Genomeia — допекание встроенных геномов")
        setWindowedMode(640, 480)
        useVsync(false)
        // Как в Lwjgl3Launcher: контейнеры игры поднимают рендер, а ему нужен GL30.
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 3)
    }
    Lwjgl3Application(BakeBundledGenomes(MyGame(DesktopFileProvider()), dir), config)
    exitProcess(exitCode)
}

private var exitCode = 1

private class BakeBundledGenomes(
    private val game: MyGame,
    private val dir: File
) : ApplicationListener by game {

    private var frames = 0

    override fun render() {
        game.render()
        // Пара кадров: игра доходит до меню, как у игрока, и только потом запекание.
        if (++frames < 3) return
        exitCode = try {
            if (bake()) 0 else 1
        } catch (e: Throwable) {
            e.printStackTrace()
            1
        }
        Gdx.app.exit()
    }

    private fun bake(): Boolean {
        require(dir.isDirectory) { "нет каталога ${dir.absolutePath}" }
        val baker = DIGenomeEditorContainer.genomeBaker
        val folder = Gdx.files.absolute(dir.absolutePath)

        val started = System.nanoTime()
        val (baked, failed) = GenomeLibrary.bakeUnbaked(baker, folder)
        val totalMs = (System.nanoTime() - started) / 1_000_000.0
        println("Допечено ${baked.size} за ${"%.0f".format(totalMs)} мс: ${baked.joinToString { it.name() }}")
        failed.forEach { (file, reason) -> println("НЕ ДОПЁКСЯ ${file.name()}: $reason") }

        println(String.format("%-28s %8s %7s %9s %9s  %s", "файл", "мс", "клеток", "файл кБ", "снимок кБ", "итог"))
        var failures = failed.size
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("genome", ignoreCase = true) }
            ?.sortedBy { it.name }.orEmpty()
        for (file in files) {
            val bytes = file.readBytes()
            val genome = decodeGenome(bytes)
            val preview = genome.preview
            val problems = mutableListOf<String>()
            if (preview == null) problems += "нет снимка"
            else if (preview.cellCount != genome.cellsAmount) {
                problems += "клеток в снимке ${preview.cellCount}, а cellsAmount ${genome.cellsAmount}"
            }

            // Выращивание заново — как при открытии в редакторе. Снимок обязан совпасть.
            val growStarted = System.nanoTime()
            val rebaked = baker.bake(genome, genome.name)
            val ms = (System.nanoTime() - growStarted) / 1_000_000.0
            if (rebaked.preview != preview) problems += "повторное выращивание дало другой снимок"
            // Граница тела (BakedLayout.interiorLinks) влияет на симуляцию — она обязана
            // повторяться так же, как снимок.
            val layout = genome.sortedGraph
            if (layout == null || !layout.hasInteriorLinks) problems += "нет границы тела"
            else if (layout.withInteriorFrom(rebaked.sortedGraph).interiorLinks != layout.interiorLinks) {
                problems += "повторное выращивание дало другую границу"
            }

            val previewBytes = bytes.size - encodeGenome(genome.copy(preview = null)).size
            println(String.format(
                "%-28s %8.1f %7d %9.1f %9.1f  %s",
                file.name, ms, preview?.cellCount ?: 0, bytes.size / 1024.0, previewBytes / 1024.0,
                if (problems.isEmpty()) "ok" else problems.joinToString("; ")
            ))
            if (problems.isNotEmpty()) failures++
        }

        println(if (failures == 0) "Все ${files.size} со снимком, снимки повторяются." else "НЕ ПРОШЛИ: $failures из ${files.size}.")
        return failures == 0
    }
}
