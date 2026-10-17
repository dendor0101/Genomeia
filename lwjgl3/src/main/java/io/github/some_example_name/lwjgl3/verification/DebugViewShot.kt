package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import io.github.some_example_name.lwjgl3.DesktopFileProvider
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.editor.system.logic.GoToEndOfTimeLine
import io.github.some_example_name.old.editor.system.logic.SetRenderMode
import io.github.some_example_name.old.features.menu.MenuIntent
import io.github.some_example_name.old.features.simulation.SimulationIntent
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.debug.RenderSceneDump
import java.io.File
import java.util.zip.Deflater
import kotlin.system.exitProcess

/**
 * Снимки отладочного режима на настоящих организмах: gradlew :lwjgl3:debugViewShot
 *
 * Игра в окне проходит короткий сценарий через команды, как игрок: мир, отладочный режим,
 * по организму каждого запечённого генома, рост. Потом камера по очереди наезжает на
 * каждый организм, и кадр пишется в PNG вместе с дампом сцены (его открывает
 * :render:renderLab — шейдеры можно крутить уже без игры). Последний снимок — редактор
 * генома в отладочном режиме: геном с нейросвязями, конец временной шкалы.
 *
 *   -PshotTicks=600     сколько тиков растить (по умолчанию 600)
 *   -PshotZoom=0.012    зум камеры на снимке
 *   -PshotDir=путь      куда писать (по умолчанию lwjgl3/build/debugViewShot)
 */
fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Genomeia — снимки отладочного режима")
        setWindowedMode(1100, 800)
        useVsync(false)
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 3)
    }
    Lwjgl3Application(Shooter(MyGame(DesktopFileProvider())), config)
    exitProcess(shotExitCode)
}

private var shotExitCode = 1

private class Shooter(private val game: MyGame) : ApplicationListener by game {

    private var frames = 0
    private var step = 0
    private var waitUntilFrame = 0
    private var waitUntilTick = -1
    private val startedAt = System.nanoTime()

    private val outDir = File(System.getProperty("debugViewShot.dir", "lwjgl3/build/debugViewShot"))
    private val ticks = System.getProperty("debugViewShot.ticks")?.toIntOrNull() ?: 600
    private val zoom = System.getProperty("debugViewShot.zoom")?.toFloatOrNull() ?: 0.012f

    /** Геном и точка посадки каждого организма. */
    private val spawned = ArrayList<Pair<String, Pair<Float, Float>>>()
    private var shot = 0

    override fun render() {
        try {
            game.render()
            frames++
            if (System.nanoTime() - startedAt > TIMEOUT_NS) error("сценарий завис на шаге $step")
            if (frames < waitUntilFrame) return
            if (waitUntilTick >= 0 && DISimulationContainer.simulationData.tickCounter < waitUntilTick) return
            waitUntilTick = -1
            advance()
        } catch (e: Throwable) {
            e.printStackTrace()
            shotExitCode = 1
            Gdx.app.exit()
        }
    }

    private fun advance() {
        when (step) {
            0 -> pause(10)
            1 -> {
                // Мир 256×256: организмы ниже расставлены до x = 140.
                val world = WorldSpec(WorldSpec.mapCells(256), WorldSpec.mapCells(256), "31415926", 9, 0, emptyList())
                DIGameGlobalContainer.navigationCommandsManager.performCommand(GoSimulation(world, null))
                pause(5)
            }
            2 -> {
                screen(SimulationIntent.SetRenderMode(RenderMode.DEBUG))
                screen(SimulationIntent.SetUiVisible(false))
                val files = DIGameGlobalContainer.genomeJsonReader.getGenomeFileNamesFromFolder()
                val genomes = DISimulationContainer.genomeManager.genomes
                val baked = genomes.indices.filter { genomes[it].cellsAmount > 0 }
                check(baked.isNotEmpty()) { "в папке genomes нет запечённых геномов" }
                screen(SimulationIntent.SelectGenome(files[baked.first()]))
                baked.take(MAX_ORGANISMS).forEachIndexed { i, genome ->
                    val x = 40f + (i % 3) * 50f
                    val y = 50f + (i / 3) * 50f
                    DISimulationContainer.userCommandManager.push(PlayerCommand.Tap(x, y, genomeIndex = genome))
                    spawned += genomes[genome].name to (x to y)
                }
                println("Организмы: " + spawned.joinToString { it.first })
                untilTick(DISimulationContainer.simulationData.tickCounter + ticks)
            }
            3 -> {
                if (shot == spawned.size) {
                    step = 5
                    return
                }
                val (x, y) = organismCenter(spawned[shot].second)
                screen(SimulationIntent.ZoomCamera(zoom, x, y))
                // Кадр, нарисованный уже с новой камерой, — через один.
                pause(3)
                step = 4
                return
            }
            4 -> {
                capture(spawned[shot].first)
                shot++
                step = 3
                return
            }

            // Редактор генома: геном с нейросвязями, если есть, — конец шкалы, отладка.
            5 -> {
                DIGameGlobalContainer.navigationCommandsManager.performCommand(GoReplace(GoMenu))
                pause(5)
            }
            6 -> {
                val files = DIGameGlobalContainer.genomeJsonReader.getGenomeFileNamesFromFolder()
                val genome = files.firstOrNull { it.startsWith(EDITOR_GENOME) } ?: files.first()
                editorGenome = genome
                screen(MenuIntent.OpenGenomeEditor(genome))
                pause(5)
            }
            7 -> {
                screen(SetRenderMode(RenderMode.DEBUG))
                screen(GoToEndOfTimeLine)
                pause(10)
            }
            8 -> {
                captureImage("editor-$editorGenome")
                println("✅ снимки: ${outDir.absolutePath}")
                shotExitCode = 0
                Gdx.app.exit()
                return
            }
        }
        step++
    }

    private var editorGenome = ""

    /**
     * Середина организма, посаженного в [spawn]: среднее клеток в окрестности. Организм
     * за время роста отплывает, поэтому ищем от ближайшей к точке посадки клетки.
     */
    private fun organismCenter(spawn: Pair<Float, Float>): Pair<Float, Float> {
        val cells = DISimulationContainer.cellEntity
        val alive = cells.aliveList
        var nearest = -1
        var nearestDistance = Float.MAX_VALUE
        for (i in 0 until alive.size) {
            val cell = alive.getInt(i)
            val dx = cells.getX(cell) - spawn.first
            val dy = cells.getY(cell) - spawn.second
            val d = dx * dx + dy * dy
            if (d < nearestDistance) {
                nearestDistance = d
                nearest = cell
            }
        }
        if (nearest == -1) return spawn
        val cx = cells.getX(nearest)
        val cy = cells.getY(nearest)
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until alive.size) {
            val cell = alive.getInt(i)
            val dx = cells.getX(cell) - cx
            val dy = cells.getY(cell) - cy
            if (dx * dx + dy * dy < GATHER_RADIUS * GATHER_RADIUS) {
                sx += cells.getX(cell)
                sy += cells.getY(cell)
                n++
            }
        }
        return (sx / n) to (sy / n)
    }

    /** Кадр симуляции: PNG и дамп сцены. */
    private fun capture(name: String) {
        val safe = captureImage("$shot-$name")
        DISimulationContainer.renderSystem.dumpScene(
            Gdx.files.absolute(File(outDir, "$safe.${RenderSceneDump.EXTENSION}").absolutePath)
        )
    }

    /** Кадр как есть — в PNG; возвращает имя файла без расширения. */
    private fun captureImage(name: String): String {
        outDir.mkdirs()
        val safe = name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
        val png = File(outDir, "$safe.png")
        val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        try {
            PixmapIO.writePNG(Gdx.files.absolute(png.absolutePath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
        } finally {
            pixmap.dispose()
        }
        println("снимок: ${png.absolutePath}")
        return safe
    }

    private fun screen(command: GameCommand) {
        val target = DIGameGlobalContainer.game.screen as? ReplayTarget
            ?: error("экран ${DIGameGlobalContainer.game.screen} не принимает команды")
        check(target.replay(command)) { "экран ${target::class.simpleName} не принял ${command.name}" }
    }

    private fun pause(frameCount: Int) {
        waitUntilFrame = frames + frameCount
    }

    private fun untilTick(tick: Int) {
        waitUntilTick = tick
    }

    private companion object {
        const val MAX_ORGANISMS = 6

        /** Геном для снимка редактора — с нейросвязями; нет такого — первый по алфавиту. */
        const val EDITOR_GENOME = "Tested"
        const val GATHER_RADIUS = 12f
        const val TIMEOUT_NS = 300_000_000_000L
    }
}
