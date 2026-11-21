package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.github.some_example_name.lwjgl3.DesktopFileProvider
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.GenomeStage
import io.github.some_example_name.old.systems.genomics.genome.getEmptyGenome
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import kotlin.system.exitProcess

/**
 * Память записи роста в редакторе генома на всех встроенных геномах.
 *
 * Зачем: отчёт игрока 11.11 (Samsung SM-S948U, куча 256 МБ) — открытие Megalodon в
 * редакторе падало с OutOfMemoryError в CellReplay.ensureCapacity: запись хранила снимок
 * всех клеток на каждом тике роста. Программа выращивает каждый встроенный геном так же,
 * как его открывает игрок (reinitGenome + simulate), и печатает, сколько байт занимает
 * каждая запись (EditorReplay.byteSize).
 *
 * Ненулевой код — хоть одна запись больше [LIMIT_BYTES]. До правки 17.11 Megalodon занимал
 * 273.4 МБ в записи клеток (277.6 МБ всего), после — 2.4 МБ (3.0 МБ); самая маленькая куча
 * из отчётов — 192 МБ (SM-A042F). Предел 16 МБ — с запасом на геномы крупнее Megalodon,
 * но рост «снимок на каждый тик» он поймает сразу.
 *
 * Заодно — правило записи, на котором стоит весь редактор: кадров ровно на один больше, чем
 * стадий (кадр k — тело до стадии k). Отчёт 21.11: в геноме с пустыми первыми стадиями
 * («Parasite») кадров было столько же, сколько стадий, шкала съехала на кадр, и правый клик
 * на последнем кадре ронял игру (EditorSimulationSystem.simulate). Встроенные геномы пустых
 * стадий не имеют, поэтому к ним добавлен пустой геном с двумя пустыми стадиями впереди:
 * до правки он давал 3 кадра на 3 стадии.
 *
 * Запуск: gradlew :lwjgl3:editorMemoryCheck
 */
fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setPreferencesConfig(java.nio.file.Files.createTempDirectory("genomeia-verification-").toString(), com.badlogic.gdx.Files.FileType.Absolute)
        setTitle("Genomeia — память записи роста в редакторе")
        setWindowedMode(640, 480)
        useVsync(false)
        // Как в Lwjgl3Launcher: контейнеры игры поднимают рендер, а ему нужен GL30.
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 3)
    }
    Lwjgl3Application(EditorMemoryCheck(MyGame(DesktopFileProvider())), config)
    exitProcess(exitCode)
}

private const val LIMIT_BYTES = 16L * 1024 * 1024

private var exitCode = 1

private class EditorMemoryCheck(private val game: MyGame) : ApplicationListener by game {

    private var frames = 0

    override fun render() {
        game.render()
        // Пара кадров: игра доходит до меню, как у игрока.
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
        val sim = DIGenomeEditorContainer.editorSimulationSystem
        val replays = listOf(
            "клетки" to DIGenomeEditorContainer.cellReplay,
            "нейросвязи" to DIGenomeEditorContainer.neuralLinkReplay,
            "нейроны" to DIGenomeEditorContainer.neuralReplay,
            "данные клеток" to DIGenomeEditorContainer.specialModDataReplay
        )
        val names = GenomeCatalog.bundledNames()
        require(names.isNotEmpty()) { "встроенных геномов нет" }

        println(String.format("%-28s %7s %7s %8s  %s", "геном", "тиков", "клеток", "мс", replays.joinToString("  ") { "%12s".format(it.first) } + "      всего, МБ"))
        var failures = 0
        var shifted = 0
        val genomes = names.map { GenomeCatalog.displayName(it) to { sim.reinitGenome(it) } } +
            ("пустые стадии впереди" to { sim.useGenome(leadingEmptyStages()) })
        for ((title, load) in genomes) {
            load()
            val started = System.nanoTime()
            sim.simulate()
            val ms = (System.nanoTime() - started) / 1_000_000.0
            val ticks = DIGenomeEditorContainer.cellReplay.getTickCount()
            val stages = sim.genomeStageInstruction.size
            val cells = DIGenomeEditorContainer.cellEntity.aliveList.size
            val bytes = replays.map { it.second.byteSize() }
            val total = bytes.sum()
            val over = bytes.any { it > LIMIT_BYTES }
            if (over) failures++
            val badTimeline = ticks != stages + 1
            if (badTimeline) shifted++
            println(String.format(
                "%-28s %7d %7d %8.1f  %s  %10.1f%s%s",
                title, ticks, cells, ms,
                bytes.joinToString("  ") { "%12.1f".format(it / 1048576.0) },
                total / 1048576.0, if (over) "  БОЛЬШЕ ПРЕДЕЛА" else "",
                if (badTimeline) "  КАДРОВ $ticks НА $stages СТАДИЙ" else ""
            ))
        }
        println(if (failures == 0) "Все ${genomes.size} в пределе ${LIMIT_BYTES / 1048576} МБ на запись."
        else "БОЛЬШЕ ПРЕДЕЛА: $failures из ${genomes.size}.")
        println(if (shifted == 0) "У всех ${genomes.size} кадров на один больше, чем стадий."
        else "ШКАЛА СЪЕХАЛА: $shifted из ${genomes.size}.")
        return failures == 0 && shifted == 0
    }

    /** Пустой геном редактора (одно деление зиготы), перед которым две пустые стадии. */
    private fun leadingEmptyStages(): Genome {
        val base = getEmptyGenome()
        return base.copy(stageInstruction = listOf(GenomeStage(), GenomeStage()) + base.stageInstruction)
    }
}
