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
import io.github.some_example_name.old.systems.genomics.library.GenomeImport
import java.io.File
import kotlin.system.exitProcess

/**
 * Перевод старых JSON-геномов в .genome на настоящей игре: запекание — в мире редактора,
 * тем же кодом, что у кнопки «Сохранить» (GenomeBaker).
 *
 * Для каждого *.json из каталога:
 *  1. GenomeImport.importFile — тот же путь, что импорт в меню и перенос при запуске игры;
 *  2. файл читается обратно и обязан быть запечён: размеры тела есть, раскладка есть, и
 *     клеток в раскладке ровно cellsAmount;
 *  3. повторное запекание прочитанного генома — как если бы игрок открыл его в редакторе и
 *     нажал «Сохранить» — обязано дать те же байты. Это и есть определение «перевод с
 *     запеканием из редактора»;
 *  4. печатается время запекания: столько игра стоит при первом запуске на каждом JSON.
 *
 * Запуск: gradlew :lwjgl3:genomeJsonCheck [-Pfrom=каталог] [-Pto=каталог]
 *   -Pfrom — откуда брать JSON, по умолчанию core/src/test/resources/legacy-genomes;
 *   -Pto   — куда класть .genome, по умолчанию lwjgl3/build/genomeJsonCheck (чистится).
 * Встроенные геномы (assets/genomes) сделаны этой же программой: -Pto=assets/genomes.
 * Ненулевой код — хоть один файл не перевёлся или перевод не совпал с редактором.
 */
fun main(args: Array<String>) {
    val from = File(args.getOrNull(0) ?: "core/src/test/resources/legacy-genomes")
    val to = File(args.getOrNull(1) ?: DEFAULT_OUTPUT)
    if (to.path == DEFAULT_OUTPUT) to.deleteRecursively()

    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Genomeia — перевод старых JSON-геномов")
        setWindowedMode(640, 480)
        useVsync(false)
        // Как в Lwjgl3Launcher: контейнеры игры поднимают рендер, а ему нужен GL30.
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 3)
    }
    Lwjgl3Application(GenomeJsonCheck(MyGame(DesktopFileProvider()), from, to), config)
    exitProcess(exitCode)
}

private const val DEFAULT_OUTPUT = "lwjgl3/build/genomeJsonCheck"

private var exitCode = 1

private class GenomeJsonCheck(
    private val game: MyGame,
    private val from: File,
    private val to: File
) : ApplicationListener by game {

    private var frames = 0

    override fun render() {
        game.render()
        // Пара кадров: игра доходит до меню, как у игрока, и только потом импорт.
        if (++frames < 3) return
        exitCode = try {
            if (check()) 0 else 1
        } catch (e: Throwable) {
            e.printStackTrace()
            1
        }
        Gdx.app.exit()
    }

    private fun check(): Boolean {
        val jsons = from.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            ?.sortedBy { it.name }.orEmpty()
        require(jsons.isNotEmpty()) { "в ${from.absolutePath} нет *.json" }
        val target = Gdx.files.absolute(to.absolutePath)
        val baker = DIGenomeEditorContainer.genomeBaker

        println("Перевод ${jsons.size} JSON из ${from.path} в ${to.path}")
        println(String.format("%-28s %8s %7s %7s %7s %7s %7s  %s", "файл", "мс", "стадий", "тиков", "клеток", "связей", "кБ", "итог"))
        var failures = 0
        for (json in jsons) {
            val started = System.nanoTime()
            val result = try {
                GenomeImport.importFile(Gdx.files.absolute(json.absolutePath), target, baker)
            } catch (e: Exception) {
                println(String.format("%-28s  НЕ ПЕРЕВЁЛСЯ: %s", json.name, e.message))
                e.stackTrace.take(8).forEach { println("        at $it") }
                failures++
                continue
            }
            val ms = (System.nanoTime() - started) / 1_000_000.0
            // Сколько тиков редактор растил тело: на стадию уходит несколько тиков, и это
            // мерило для предела застрявшего роста (EditorSimulationSystem.STUCK_STAGE_TICKS).
            val ticks = DIGenomeEditorContainer.cellReplay.getTickCount()

            val bytes = result.readBytes()
            val genome = decodeGenome(bytes)
            val problems = mutableListOf<String>()
            val layout = genome.sortedGraph
            if (genome.cellsAmount <= 0) problems += "не запечён: cellsAmount = ${genome.cellsAmount}"
            if (layout == null) problems += "нет раскладки"
            else if (layout.cellGenomeIdsInSlotOrder.size != genome.cellsAmount) {
                problems += "клеток в раскладке ${layout.cellGenomeIdsInSlotOrder.size}, а cellsAmount ${genome.cellsAmount}"
            }
            if (genome.name != result.nameWithoutExtension()) problems += "имя внутри '${genome.name}' не равно имени файла"

            // «Открыть в редакторе и сохранить» — те же байты.
            val rebaked = encodeGenome(baker.bake(genome, genome.name))
            if (!rebaked.contentEquals(bytes)) problems += "повторное запекание дало другой файл (${rebaked.size} байт против ${bytes.size})"

            println(String.format(
                "%-28s %8.1f %7d %7d %7d %7d %7.1f  %s",
                json.name, ms, genome.stageInstruction.size, ticks, genome.cellsAmount, genome.linksAmount,
                bytes.size / 1024.0, if (problems.isEmpty()) "ok -> ${result.name()}" else problems.joinToString("; ")
            ))
            if (problems.isNotEmpty()) failures++
        }

        println(if (failures == 0) "Все ${jsons.size} переведены и совпали с редактором." else "НЕ ПРОШЛИ: $failures из ${jsons.size}.")
        return failures == 0
    }
}
