package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.github.some_example_name.lwjgl3.DesktopFileProvider
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.old.systems.maps.MapSave
import io.github.some_example_name.old.systems.maps.encodeState
import java.io.DataInputStream
import java.io.File
import kotlin.system.exitProcess

/**
 * Регрессия сохранения мира: настоящая игра в окне, без подмен.
 *
 *  1. мир из редактора, несколько организмов, растут до взрослого тела;
 *  2. пауза, снимок всего состояния, сохранение тем же путём, что кнопка «Сохранить»;
 *  3. загрузка этого сохранения новым экраном симуляции, снимок ещё раз;
 *  4. снимки обязаны совпасть побайтно — и сами сущности, и всё, что пересобирается
 *     после загрузки (сетка, индексы, действия клеток, раскладки арен, геномы);
 *  5. загруженный мир живёт дальше: тики идут, отладочные инварианты (DEBUG_CHECKS) молчат.
 *
 * Запуск: gradlew :lwjgl3:saveRoundTrip. Ненулевой код — регрессия.
 *
 * gradlew :lwjgl3:saveRoundTrip -PloadSlot=<слот> — мир, сохранённый ДРУГОЙ версией игры
 * (maps/<слот>.map): только загрузка, узнаны ли геномы организмов (без дублей) и
 * RUN_AFTER_LOAD_TICKS тиков без нарушений инвариантов. Сравнивать побайтно тут не с чем;
 * файл не перезаписывается.
 */
fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Genomeia — проверка сохранения мира")
        setWindowedMode(900, 700)
        useVsync(false)
        // Как в Lwjgl3Launcher: рендер клеток требует GL30 (массив текстур).
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 3)
    }
    Lwjgl3Application(SaveRoundTrip(MyGame(DesktopFileProvider())), config)
    exitProcess(SaveRoundTrip.exitCode)
}

private class SaveRoundTrip(private val game: MyGame) : ApplicationListener by game {

    private enum class Step { START, SPAWN, GROW, SAVE, WAIT_SAVE, MENU, LOAD, VERIFY, RUN_AFTER, OVERWRITE, DONE }

    /** Чужое сохранение для режима -PloadSlot, см. описание проверки. */
    private val foreignSlot: String? = System.getProperty("saveRoundTrip.load")

    private var step = Step.START
    private var frames = 0
    private var stepStartedAt = 0L

    private val world = WorldEditorViewModel(initialSeed = "12345678").toSpec()
    private var slot: String? = null
    private lateinit var before: Snapshot
    private var tickAfterLoad = 0

    @Volatile
    private var simulationFailure: Throwable? = null

    override fun create() {
        game.create()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            simulationFailure = error
            previous?.uncaughtException(thread, error)
        }
    }

    override fun render() {
        try {
            // Внутри try: колбэк превью (а с ним и сохранение) выполняется из game.render().
            game.render()
            frames++
            simulationFailure?.let { throw IllegalStateException("поток симуляции упал", it) }
            advance()
        } catch (e: Throwable) {
            fail(e.stackTraceToString())
        }
    }

    private fun advance() {
        val simulation = DISimulationContainer.simulationSystem
        val data = DISimulationContainer.simulationData
        when (step) {
            // Тем же маршрутом, что игрок: из меню в мир, из мира в меню, из меню в сохранённый мир.
            Step.START -> if (frames > 5 && foreignSlot != null) {
                slot = foreignSlot
                // На паузе, как в обычном сценарии: снимок после загрузки — ровно то, что в
                // файле, а не на тик позже.
                data.isPlay = false
                DIGameGlobalContainer.navigationCommandsManager.performCommand(
                    GoSimulation(world = null, genomeName = null, savedWorld = foreignSlot)
                )
                next(Step.LOAD)
            } else if (frames > 5) {
                DIGameGlobalContainer.navigationCommandsManager.performCommand(GoSimulation(world, null))
                data.isPlay = true
                data.maxSpeed = true
                next(Step.SPAWN)
            }

            Step.SPAWN -> if (frames > 3) {
                val genomes = DISimulationContainer.genomeManager.genomes
                // Только запечённые: у незапечённого генома арена нулевой ёмкости, и первая
                // же клетка падает в addCell — это не про сохранение.
                val baked = genomes.indices.filter { genomes[it].cellsAmount > 0 }
                check(baked.isNotEmpty()) { "в папке genomes нет запечённых геномов" }
                println("Геномов в папке: ${genomes.size}, запечённых: ${baked.size}")
                SPAWN_POINTS.forEachIndexed { i, (x, y) ->
                    DISimulationContainer.userCommandManager.push(
                        PlayerCommand.Tap(x, y, isLeftButton = true, genomeIndex = baked[i % baked.size])
                    )
                }
                next(Step.GROW)
            }

            Step.GROW -> if (data.tickCounter >= GROW_TICKS) {
                data.isPlay = false
                next(Step.SAVE)
            } else timeout(120)

            Step.SAVE -> if (frames > 3) {
                before = simulation.betweenTicks { Snapshot.take() }
                println("Перед сохранением: ${before.summary}")
                check(before.cells > 0) { "организмы не выросли — проверять нечего" }

                DISimulationContainer.renderSystem.requestPreview(MapSave.PREVIEW_SIZE) { preview ->
                    val started = System.nanoTime()
                    slot = DISimulationContainer.mapSave.save(null, world, preview)
                    println("Сохранено в слот $slot за ${(System.nanoTime() - started) / 1_000_000} мс")
                }
                next(Step.WAIT_SAVE)
            }

            Step.WAIT_SAVE -> if (slot != null) {
                val file = MapSave.mapFile(slot!!).file()
                println("Файл: ${file.path}, ${file.length() / 1024} КБ")

                val preview = MapSave.readPreview(slot!!) ?: throw IllegalStateException("в сохранении нет превью")
                try {
                    check(preview.width == MapSave.PREVIEW_SIZE && preview.height == MapSave.PREVIEW_SIZE) {
                        "превью ${preview.width}x${preview.height}, а должно быть ${MapSave.PREVIEW_SIZE}"
                    }
                } finally {
                    preview.dispose()
                }

                DIGameGlobalContainer.navigationCommandsManager.performCommand(GoReplace(GoMenu))
                next(Step.MENU)
            } else timeout(10)

            Step.MENU -> if (frames > 5) {
                check(MapSave.listMaps().any { it.slot == slot }) { "сохранённого мира нет в списке" }
                DIGameGlobalContainer.navigationCommandsManager.performCommand(
                    GoSimulation(world = null, genomeName = null, savedWorld = slot)
                )
                next(Step.LOAD)
            }

            Step.LOAD -> if (frames > 3) {
                val after = simulation.betweenTicks { Snapshot.take() }
                println("После загрузки: ${after.summary}")
                if (foreignSlot == null) before.compare(after) else check(after.cells > 0) { "в загруженном мире нет клеток" }

                // Свои из папки и встроенные: организмы любых из них обязаны найти свой геном.
                val folderGenomes = GenomeCatalog.all().size
                check(DISimulationContainer.genomeManager.genomes.size == folderGenomes) {
                    "геномы из папки не узнаны: в списке ${DISimulationContainer.genomeManager.genomes.size}, " +
                        "в папке и встроенных $folderGenomes — организмы получили дубликаты"
                }
                println(if (foreignSlot == null) "Состояние совпало побайтно, геномы сопоставлены с папкой" else "Геномы сопоставлены с папкой")

                tickAfterLoad = data.tickCounter
                data.isPlay = true
                next(Step.VERIFY)
            }

            Step.VERIFY -> if (data.tickCounter >= tickAfterLoad + RUN_AFTER_LOAD_TICKS) {
                println("После загрузки прошло ${data.tickCounter - tickAfterLoad} тиков без нарушений инвариантов")
                data.isPlay = false
                // Чужое сохранение не перезаписывается: его могут прогнать ещё раз.
                next(if (foreignSlot == null) Step.RUN_AFTER else Step.OVERWRITE)
            } else timeout(120)

            // Повторное сохранение в тот же слот: файл заменяется, а не плодится новый.
            Step.RUN_AFTER -> if (frames > 3) {
                val count = MapSave.listMaps().size
                DISimulationContainer.renderSystem.requestPreview(MapSave.PREVIEW_SIZE) { preview ->
                    val again = DISimulationContainer.mapSave.save(slot, world, preview)
                    check(again == slot) { "пересохранение ушло в слот $again вместо $slot" }
                    check(MapSave.listMaps().size == count) { "пересохранение создало новый файл" }
                    println("Пересохранение в тот же слот: ок")
                    next(Step.OVERWRITE)
                }
            }

            Step.OVERWRITE -> {
                if (System.getProperty("keep") == null && foreignSlot == null) MapSave.mapFile(slot!!).file().delete()
                println("PASS")
                exitCode = 0
                next(Step.DONE)
                Gdx.app.exit()
            }

            Step.DONE -> Unit
        }
    }

    private fun next(step: Step) {
        this.step = step
        frames = 0
        stepStartedAt = System.nanoTime()
    }

    private fun timeout(seconds: Long) {
        check(System.nanoTime() - stepStartedAt < seconds * 1_000_000_000L) {
            "шаг $step не завершился за $seconds с (тик ${DISimulationContainer.simulationData.tickCounter})"
        }
    }

    private fun fail(message: String) {
        if (step == Step.DONE) return
        System.err.println("FAIL на шаге $step:\n$message")
        exitCode = 1
        step = Step.DONE
        Gdx.app.exit()
    }

    companion object {
        var exitCode = 1

        const val GROW_TICKS = 1500
        const val RUN_AFTER_LOAD_TICKS = 600

        val SPAWN_POINTS = listOf(40f to 40f, 90f to 45f, 45f to 90f, 90f to 90f, 65f to 65f, 110f to 70f)
    }
}

/** Всё состояние мира, которое обязано пережить сохранение и загрузку. */
private class Snapshot(
    val entities: Map<String, ByteArray>,
    val derived: Map<String, String>,
    val cellActions: List<Any?>,
    val arenaLayouts: List<Any?>,
    val cells: Int,
    val summary: String
) {
    fun compare(after: Snapshot) {
        val mismatched = entities.keys.filter { !entities[it]!!.contentEquals(after.entities[it]) }
        check(mismatched.isEmpty()) {
            "сущности разошлись после загрузки: " + mismatched.joinToString { entity ->
                val a = fields(entities[entity]!!)
                val b = fields(after.entities[entity]!!)
                val fields = (a.keys + b.keys).filter { !(a[it] contentEquals b[it]) }
                "$entity $fields"
            }
        }

        derived.forEach { (name, value) ->
            check(value == after.derived[name]) { "после загрузки разошлось: $name" }
        }
        check(cellActions == after.cellActions) { "после загрузки разошлись действия клеток" }
        check(arenaLayouts == after.arenaLayouts) { "после загрузки разошлись раскладки арен" }
    }

    companion object {
        /** Поля закодированной сущности по именам — тот же формат, что пишет EntityStateCodec. */
        private fun fields(bytes: ByteArray): Map<String, ByteArray> {
            val input = DataInputStream(bytes.inputStream())
            val result = LinkedHashMap<String, ByteArray>()
            while (input.available() > 0) {
                val name = input.readUTF()
                val type = input.readByte().toInt()
                val count = input.readInt()
                val width = if (type == 1 || type == 2) 4 else 1
                result[name] = ByteArray(count * width).also { input.readFully(it) }
            }
            return result
        }

        /** Звать между тиками. */
        fun take(): Snapshot = with(DISimulationContainer) {
            val entities = entityList.associate { it::class.java.simpleName to it.encodeState() }

            val grid = StringBuilder()
            for (cell in 0 until gridManager.gridSize) {
                val from = gridManager.cellFrom(cell)
                val to = gridManager.cellTo(cell)
                if (to > from) {
                    grid.append(cell).append(':')
                    (from until to).map { gridManager.particleIdx[it] }.sorted().joinTo(grid, ",")
                    grid.append(';')
                }
            }

            val aliveCells = cellEntity.aliveList.toIntArray().sorted()
            val idMap = aliveCells.joinToString(";") { cell ->
                "${cellEntity.organIndex[cell]},${cellEntity.cellGenomeId[cell]}=" +
                    cellEntity.organToIdToIndex.get(cellEntity.organIndex[cell], cellEntity.cellGenomeId[cell])
            }

            val neuralLinks = neuralLinkEntity.aliveList.toIntArray().sorted().joinToString(";") { link ->
                val a = neuralLinkEntity.links1[link]
                val b = neuralLinkEntity.links2[link]
                "$link:${neuralLinkEntity.linkIndexMap.get(a, b)},${neuralLinkEntity.linkEditorIndexMap.get(a, b)}"
            }

            val pheromones = listOf(pheromoneEntity.pheromoneMapGrid, pheromoneEntity.emitterMap).joinToString("|") { map ->
                map.keys.sorted().joinToString(";") { type ->
                    val byKey = map.get(type)
                    "$type{" + byKey.keys.sorted().joinToString(",") { key -> "$key=" + byKey.get(key).toIntArray().sorted() } + "}"
                }
            }

            val organs = organEntity.aliveList.toIntArray().sorted()
            val genomes = organs.joinToString(";") { organ ->
                val genome = genomeManager.genomes[organEntity.genomeIndex[organ]]
                // Структурный хэш data-класса: тот же геном из папки или сохранённая копия
                "$organ:" + genome.hashCode()
            }

            Snapshot(
                entities = entities,
                derived = mapOf(
                    "сетка" to grid.toString(),
                    "organToIdToIndex" to idMap,
                    "карты нейросвязей" to neuralLinks,
                    "карты феромонов" to pheromones,
                    "геномы организмов" to genomes,
                    "время" to "${simulationData.tickCounter} ${simulationData.timeSimulation}"
                ),
                cellActions = aliveCells.map { cellEntity.cellActions[it] },
                arenaLayouts = organs.map { organEntity.arenaLayout[it] },
                cells = aliveCells.size,
                summary = "тик ${simulationData.tickCounter}, организмов ${organs.size}, " +
                    "клеток ${aliveCells.size}, частиц ${particleEntity.aliveList.size}, " +
                    "связей ${linkEntity.aliveList.size}, нейросвязей ${neuralLinkEntity.aliveList.size}, " +
                    "феромонов ${pheromoneEntity.aliveList.size}, " +
                    "клеток с действием ${aliveCells.count { cellEntity.cellActions[it] != null }}"
            )
        }
    }
}
