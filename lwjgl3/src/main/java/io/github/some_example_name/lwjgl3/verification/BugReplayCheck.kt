package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.lwjgl3.DesktopFileProvider
import io.github.some_example_name.old.bug.BugImage
import io.github.some_example_name.old.bug.BugKind
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.bug.BugReports
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoGenomeEditor
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.GameFiles
import io.github.some_example_name.old.core.PlatformTuning
import io.github.some_example_name.old.editor.system.Axial
import io.github.some_example_name.old.editor.system.logic.GoToEndOfTimeLine
import io.github.some_example_name.old.editor.system.logic.NextTickButtonTap
import io.github.some_example_name.old.editor.system.logic.SetNeuralLinkColor
import io.github.some_example_name.old.editor.system.logic.SetSymmetry
import io.github.some_example_name.old.features.genomes.GenomesIntent
import io.github.some_example_name.old.features.genomes.GenomesScreen
import io.github.some_example_name.old.features.genomes.GenomesTab
import io.github.some_example_name.old.features.menu.MenuIntent
import io.github.some_example_name.old.features.simulation.SimulationIntent
import io.github.some_example_name.old.features.worldeditor.WorldEdit
import io.github.some_example_name.old.features.worldeditor.WorldEditorIntent
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.old.cells.ControllerData
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.systems.genomics.genome.Action
import io.github.some_example_name.old.systems.genomics.genome.CellAction
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.GenomeStage
import io.github.some_example_name.old.systems.genomics.genome.LinkData
import io.github.some_example_name.old.systems.genomics.genome.encodeGenome
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.old.systems.maps.MapSave
import io.github.some_example_name.render.DebugFilter
import io.github.some_example_name.render.RenderMode
import java.io.File
import kotlin.system.exitProcess

/**
 * Сквозная проверка записи и повтора бага: настоящая игра в окне, два запуска.
 *
 *  record — игра проходит сценарий ТОЛЬКО через обработчики команд, как игрок: импорт
 *           старого JSON-генома перетаскиванием в окно, меню, редактор генома, редактор
 *           мира, мир с организмами (импортированный — первым), клавиши контроллера,
 *           сохранение мира, рестарт. В конце — отчёт кнопкой-жуком (без окна).
 *  replay — новый процесс проигрывает этот .bug и сверяет: все команды применены,
 *           контрольные суммы журнала сошлись, хэши состояния мира совпали.
 *
 * Запуск: gradlew :lwjgl3:bugReplayCheck (record выполняется сам, перед ним).
 * Ненулевой код — регрессия: повтор разошёлся с записью.
 */
fun main(args: Array<String>) {
    val phase = args.firstOrNull() ?: "record"
    val pathFile = File(System.getProperty("bugReplayCheck.dir", "lwjgl3/build/bugReplayCheck"), "last-bug.txt")

    // Число воркеров симуляции. С одним порядок обработки чанков фиксирован — так проверяется
    // сам повтор, отдельно от недетерминизма параллельных фаз (см. итог проверки).
    System.getProperty("bugReplayCheck.workers")?.toIntOrNull()?.let { PlatformTuning.performanceCoreCount = it }

    // До создания игры: BugSession читает это свойство в самом начале create().
    val listener: (MyGame) -> ApplicationListener
    when (phase) {
        "record" -> {
            System.setProperty("genomeia.replay", "off")
            // Остаток прошлого прогона: попал бы в снимок папки геномов при запуске, и повтор
            // прошёл бы, даже не восстановив импорт из .bug.
            File("${GameFiles.GENOMES}/$IMPORT_NAME.genome").delete()
            File("${GameFiles.GENOMES}/$CONTROLLER_NAME.genome").delete()
            File("${GameFiles.GENOMES}/$PRODUCER_NAME.genome").delete()
            listener = { game -> Recorder(game, pathFile) }
        }
        "replay" -> {
            val bug = pathFile.readText().trim()
            println("Повтор: $bug")
            System.setProperty("genomeia.replay", bug)
            listener = { game -> Replayer(game) }
        }
        else -> error("фаза $phase: record или replay")
    }

    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Genomeia — проверка повтора бага ($phase)")
        setWindowedMode(900, 700)
        useVsync(false)
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 3)
    }
    Lwjgl3Application(listener(MyGame(DesktopFileProvider())), config)
    exitProcess(exitCode)
}

private var exitCode = 1

/** Старый JSON, который сценарий перетаскивает в окно, и имя, под которым он импортируется. */
private const val IMPORT_SOURCE = "core/src/test/resources/legacy-genomes/Leech.json"
private const val IMPORT_NAME = "bugReplayCheck-import"

/**
 * Геном с клетками Controller — его перетаскивают вместе с JSON. Своих данных клеток
 * (SpecialModData) нет ни в одном встроенном геноме, а без них хэши не проверяли бы ни
 * деление со своими данными (ADD_CELL), ни мутацию в Controller и из него
 * (ADD/DELETE_SPECIAL_MOD_DATA), ни замену данных на месте. Клавиши W и SPACE сценарий
 * зажимает — контроллеры их слышат. Геном не запечён: импорт допечёт его сам.
 */
private const val CONTROLLER_NAME = "bugReplayCheck-controller"

/**
 * Геном с продюсером, который рожает потомка, пока зажата W: контроллер (W) ведёт
 * нейросвязью в продюсер, а сценарий держит W 120 тиков. Без него рождение нового
 * организма в мире (OrganismSpawner, SPAWN_ORGANISM) в хэшах не проверялось бы: продюсеры
 * встроенных геномов за время сценария не срабатывают. Окно клавиши ограничивает потомство:
 * потомки за 120 тиков не вырастают и сами не рожают.
 */
private const val PRODUCER_NAME = "bugReplayCheck-producer"

/** Геном, который на экране «Геномы» переименовывают и удаляют. */
private const val TEMPORARY_NAME = "bugReplayCheck-temporary"

private fun producerGenome(): Genome {
    val toZygote = mapOf(0 to LinkData(length = 0.6f))
    return Genome(
        version = 24,
        name = PRODUCER_NAME,
        stageInstruction = listOf(
            GenomeStage(mapOf(
                0 to CellAction(divide = Action(id = 1, angle = 1.57f, cellType = "leaf", physicalLink = toZygote, color = Color.GREEN))
            )),
            GenomeStage(mapOf(
                0 to CellAction(divide = Action(
                    id = 2, angle = 0f, cellType = "controller", physicalLink = toZygote, color = Color.PURPLE,
                    funActivation = "linear", a = 1f, b = 0f, c = 0f, isSum = true, special = ControllerData("W")
                ))
            )),
            GenomeStage(mapOf(
                0 to CellAction(divide = Action(
                    id = 3, angle = 3.14f, cellType = "producer", physicalLink = toZygote, color = Color.RED,
                    funActivation = "linear", a = 1f, b = 0f, c = 0f, isSum = true
                ))
            )),
            // Нейросвязь контроллер -> продюсер: сигнал идёт к клетке 3 (directedNeuronLink).
            GenomeStage(mapOf(
                3 to CellAction(mutate = Action(physicalLink = mapOf(2 to LinkData(isNeuronal = true, directedNeuronLink = 3))))
            ))
        )
    )
}

private fun controllerGenome(): Genome {
    val link = mapOf(0 to LinkData(length = 0.6f))
    return Genome(
        version = 24,
        name = CONTROLLER_NAME,
        stageInstruction = listOf(
            // Деление в Controller: данные (клавиша W) едут к применению вместе с ADD_CELL.
            GenomeStage(mapOf(
                0 to CellAction(divide = Action(
                    id = 1, angle = 0f, cellType = "controller", physicalLink = link, color = Color.PURPLE,
                    funActivation = "linear", a = 1f, b = 0f, c = 0f, isSum = true, special = ControllerData("W")
                ))
            )),
            // Контроллер меняет клавишу на месте: тот же тип данных, новый объект в своём слоте.
            GenomeStage(mapOf(
                0 to CellAction(divide = Action(id = 2, angle = 3.14f, cellType = "leaf", physicalLink = link, color = Color.GREEN)),
                1 to CellAction(mutate = Action(special = ControllerData("SPACE")))
            )),
            // Лист становится контроллером, контроллер — листом: слоты данных заводятся и
            // удаляются командами.
            GenomeStage(mapOf(
                2 to CellAction(mutate = Action(
                    cellType = "controller", funActivation = "spike", a = 0.5f, b = 0f, c = 0f, isSum = true,
                    special = ControllerData("D")
                )),
                1 to CellAction(mutate = Action(cellType = "leaf"))
            ))
        )
    )
}

/** Сценарий игрока. Каждый шаг — команда через экран (ReplayTarget) или навигацию. */
private class Recorder(private val game: MyGame, private val pathFile: File) : ApplicationListener by game {

    private var frames = 0
    private var step = 0
    private var waitUntilFrame = 0
    private var waitUntilTick = -1
    private var importedMapSlot: String? = null
    private var savedSlot: String? = null
    private var startedAt = System.nanoTime()

    override fun render() {
        try {
            game.render()
            frames++
            if (frames < waitUntilFrame) return
            if (waitUntilTick >= 0 && DISimulationContainer.simulationData.tickCounter < waitUntilTick) {
                check(System.nanoTime() - startedAt < TIMEOUT_NS) { "сценарий завис на шаге $step" }
                return
            }
            waitUntilTick = -1
            advance()
        } catch (e: Throwable) {
            e.printStackTrace()
            exitCode = 1
            Gdx.app.exit()
        }
    }

    private fun advance() {
        val data = DISimulationContainer.simulationData
        val navigation = DIGameGlobalContainer.navigationCommandsManager
        when (step++) {
            // Старый JSON перетаскивают в окно меню — как игрок на ПК. Импорт переводит и
            // запекает его, в журнал идёт GenomeImported со снимком .genome: повтор обязан
            // взять геном оттуда (в снимке папки при запуске его нет — см. main).
            0 -> {
                val dropped = File(pathFile.parentFile, "$IMPORT_NAME.json")
                dropped.parentFile.mkdirs()
                File(IMPORT_SOURCE).copyTo(dropped, overwrite = true)
                val controllers = File(pathFile.parentFile, "$CONTROLLER_NAME.genome")
                controllers.writeBytes(encodeGenome(controllerGenome()))
                val producers = File(pathFile.parentFile, "$PRODUCER_NAME.genome")
                producers.writeBytes(encodeGenome(producerGenome()))
                // Ещё один — его на экране «Геномы» переименуют и удалят (шаг 1).
                val temporary = File(pathFile.parentFile, "$TEMPORARY_NAME.genome")
                temporary.writeBytes(encodeGenome(producerGenome().copy(name = TEMPORARY_NAME)))
                game.filesDropped(listOf(dropped.absolutePath, controllers.absolutePath, producers.absolutePath, temporary.absolutePath))
                check(IMPORT_NAME in GenomeCatalog.playerNames()) {
                    "перетащенный $IMPORT_SOURCE не появился среди геномов"
                }
                check(CONTROLLER_NAME in GenomeCatalog.playerNames()) { "геном с контроллерами не импортировался" }
                check(PRODUCER_NAME in GenomeCatalog.playerNames()) { "геном с продюсером не импортировался" }
                pause(10)
            }

            // Меню отдало перетащенные файлы экрану «Геномы» — он и открыт. Здесь же
            // переименование и удаление: повтор обязан проделать их с файлами песочницы.
            // Потом вкладка встроенных и редактор генома: первый встроенный геном (он читается
            // из ассетов, а не из папки), листаем стадии, симметрия, цвет связей.
            1 -> {
                check(DIGameGlobalContainer.game.screen is GenomesScreen) {
                    "после перетаскивания геномов открыт ${DIGameGlobalContainer.game.screen}, а не экран «Геномы»"
                }
                screen(GenomesIntent.Rename(TEMPORARY_NAME, "$TEMPORARY_NAME-renamed"))
                screen(GenomesIntent.Delete("$TEMPORARY_NAME-renamed"))
                check(GenomeCatalog.playerNames().none { it.startsWith(TEMPORARY_NAME) }) {
                    "переименованный и удалённый геном остался в папке"
                }
                screen(GenomesIntent.SelectTab(GenomesTab.BUNDLED))
                screen(GenomesIntent.OpenEditor(GenomeCatalog.bundledNames().first()))
                pause(5)
            }
            2 -> {
                repeat(3) { screen(NextTickButtonTap) }
                screen(SetSymmetry(Axial))
                screen(SetNeuralLinkColor(0x33cc99ff))
                screen(GoToEndOfTimeLine)
                navigation.performCommand(GoReplace(GoMenu))
                pause(5)
            }

            // Редактор мира: свой сид, кисть, пара мазков.
            3 -> {
                screen(MenuIntent.OpenWorldEditor("31415926"))
                pause(5)
            }
            4 -> {
                // Мир 256×256: на него рассчитаны точки посадки SPAWN.
                screen(WorldEditorIntent.SetWorldWidth(256))
                screen(WorldEditorIntent.SetWorldHeight(256))
                screen(WorldEditorIntent.SetDayNight(9))
                screen(WorldEditorIntent.SetBrushSize(4))
                screen(WorldEditorIntent.Paint(40, 40))
                screen(WorldEditorIntent.Paint(41, 41))
                screen(WorldEditorIntent.SetErasing(true))
                screen(WorldEditorIntent.Paint(90, 90))
                // Кнопка «Создать мир» — сама навигация, рецепт — то, что сейчас в редакторе.
                val world = WorldSpec(WorldSpec.mapCells(256), WorldSpec.mapCells(256), "31415926", 9, 0, listOf(
                    WorldEdit.Stroke(40, 40, 4, erase = false, circle = true),
                    WorldEdit.Stroke(41, 41, 4, erase = false, circle = true),
                    WorldEdit.Stroke(90, 90, 4, erase = true, circle = true),
                ))
                navigation.performCommand(GoSimulation(world, null))
                pause(5)
            }

            // Мир: максимальная скорость, организмы, клавиши, камера.
            5 -> {
                screen(SimulationIntent.SetMaxSpeed(true))
                screen(SimulationIntent.SetRenderMode(RenderMode.DEBUG))
                // Фильтр отладки — тоже команда игрока: повтор обязан её подать.
                screen(SimulationIntent.SetDebugFilter(DebugFilter.ENERGY))
                val genomes = DISimulationContainer.genomeManager.genomes
                // Импортированный — первым: геномов в папке больше, чем мест посадки, а он
                // должен попасть в мир, чтобы хэши проверили, что повтор взял тот же геном.
                val imported = GenomeCatalog.all().indexOf(IMPORT_NAME)
                check(imported >= 0 && genomes[imported].cellsAmount > 0) { "импортированный JSON не в мире или не запечён" }
                val controllers = GenomeCatalog.all().indexOf(CONTROLLER_NAME)
                check(controllers >= 0 && genomes[controllers].cellsAmount > 0) { "геном с контроллерами не в мире или не запечён" }
                val producers = GenomeCatalog.all().indexOf(PRODUCER_NAME)
                check(producers >= 0 && genomes[producers].cellsAmount > 0) { "геном с продюсером не в мире или не запечён" }
                val generated = listOf(imported, controllers, producers)
                val baked = generated + genomes.indices.filter { it !in generated && genomes[it].cellsAmount > 0 }
                screen(SimulationIntent.SelectGenome(GenomeCatalog.all()[baked.first()]))
                // Все запечённые геномы по кругу: нейросвязи, феромоны, глаза — всё, что
                // есть в папке, должно попасть в мир и в хэш состояния.
                println("Запечённых геномов: ${baked.size} из ${genomes.size}")
                val spawnOnly = SPAWN_GENOME?.let { name ->
                    GenomeCatalog.all().indexOf(name).also { check(it >= 0) { "нет генома $name" } }
                }
                SPAWN.forEachIndexed { i, (x, y) ->
                    DISimulationContainer.userCommandManager.push(PlayerCommand.Tap(x, y, genomeIndex = spawnOnly ?: baked[i % baked.size]))
                }
                if (KILL_EVERY > 0 || FLING_EVERY > 0 || SPAWN_EVERY > 0) {
                    DISimulationContainer.userCommandManager.push(PlayerCommand.SetStress(KILL_EVERY, FLING_EVERY, SPAWN_EVERY))
                }
                screen(SimulationIntent.ZoomCamera(0.05f, 90f, 90f))
                untilTick(data.tickCounter + GROW_TICKS)
            }
            6 -> {
                screen(SimulationIntent.SetControllerKey(10, pressed = true))
                screen(SimulationIntent.SetControllerKey(14, pressed = true))
                untilTick(data.tickCounter + 120)
            }
            7 -> {
                screen(SimulationIntent.SetControllerKey(10, pressed = false))
                screen(SimulationIntent.SetControllerKey(14, pressed = false))
                // Перетаскивание частицы и вещество правой кнопкой.
                DISimulationContainer.userCommandManager.push(PlayerCommand.TouchDown(60f, 60f, true))
                DISimulationContainer.userCommandManager.push(PlayerCommand.Drag(61f, 61f, 1f, 1f))
                DISimulationContainer.userCommandManager.push(PlayerCommand.StopDrag)
                DISimulationContainer.userCommandManager.push(PlayerCommand.Tap(120f, 120f, isLeftButton = false))
                untilTick(data.tickCounter + 200)
            }

            // Отладочный стресс-тест (только при DEBUG_CHECKS): D разгоняет клетки, A убивает.
            // Тела рвутся, внутренние клетки становятся краевыми — то, на чём падала сетка.
            // -PstressTicks=N, по умолчанию выключен.
            8 -> {
                if (STRESS_TICKS > 0) {
                    screen(SimulationIntent.SetControllerKey(KEY_D, pressed = true))
                    untilTick(data.tickCounter + STRESS_TICKS)
                }
            }
            9 -> {
                if (STRESS_TICKS > 0) {
                    screen(SimulationIntent.SetControllerKey(KEY_D, pressed = false))
                    screen(SimulationIntent.SetControllerKey(KEY_A, pressed = true))
                    untilTick(data.tickCounter + STRESS_TICKS)
                }
            }
            10 -> {
                if (STRESS_TICKS > 0) screen(SimulationIntent.SetControllerKey(KEY_A, pressed = false))
                pause(2)
            }

            // Сохранение мира — повтор должен сделать его на том же тике.
            11 -> {
                // Что вообще есть в мире: части хэша без объектов ничего не проверяют.
                println("В мире: " + DISimulationContainer.entityList.joinToString { "${it::class.simpleName}=${it.aliveList.size}" })
                println("Геномы: " + DISimulationContainer.genomeManager.genomes.filter { it.cellsAmount > 0 }.joinToString { it.name })
                val slot = DISimulationContainer.mapSave.nextSlot()
                savedSlot = slot
                screen(SimulationIntent.SaveWorld(slot))
                untilTick(data.tickCounter + 240)
            }

            // Рестарт: новая сессия мира со своим сидом.
            12 -> {
                screen(SimulationIntent.Restart)
                pause(10)
            }
            13 -> {
                SPAWN.take(2).forEach { (x, y) -> DISimulationContainer.userCommandManager.push(PlayerCommand.Tap(x + 5f, y + 5f)) }
                untilTick(data.tickCounter + GROW_TICKS)
            }

            // Внешняя .map: мир под именем файла (оно занято — «имя (2)») и снимок во
            // вложении, затем продолжение мира. Проверяет именно путь лаунчера и команду
            // MapImported, а не только ZIP-кодек.
            14 -> {
                importedMapSlot = MapSave.uniqueSlot(savedSlot!!)
                game.openFiles(listOf(MapSave.mapFile(savedSlot!!).file().absolutePath))
                check(MapSave.mapFile(importedMapSlot!!).exists()) { "карта не импортирована" }
                screen(SimulationIntent.SetPaused(false))
                screen(SimulationIntent.SetMaxSpeed(true))
                untilTick(data.tickCounter + 240)
            }

            // Отчёт кнопкой-жуком (без окна) — и конец.
            15 -> {
                val bug = BugReports.create(BugKind.REPORT, "bugReplayCheck: сквозная проверка")
                // Как в окне отчёта: игрок дописал пояснение и приложил картинку после
                // первой кнопки — перепаковка не должна задеть журнал (повтор это проверит).
                BugReports.rewrite(bug, "bugReplayCheck: перепаковано", listOf(BugImage("1-test.png", TEST_PNG)))
                pathFile.parentFile.mkdirs()
                pathFile.writeText(bug.file().absolutePath)
                println("Записан ${bug.file().absolutePath} (${bug.length() / 1024} КБ) на тике ${data.tickCounter}")
                savedSlot?.let { MapSave.mapFile(it).delete() }
                importedMapSlot?.let { MapSave.mapFile(it).delete() }
                // Импортированный геном уже в .bug (приложения сессии) — из папки разработчика долой.
                GameFiles.genomesDir().child("$IMPORT_NAME.genome").delete()
                GameFiles.genomesDir().child("$CONTROLLER_NAME.genome").delete()
                GameFiles.genomesDir().child("$PRODUCER_NAME.genome").delete()
                exitCode = 0
                Gdx.app.exit()
            }
        }
    }

    /** Команда текущему экрану — тем же путём, что нажатие. */
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

    companion object {
        /**
         * Организмы стоят плотно: столкновения тел — то место, где порядок потоков мог бы
         * просочиться в мир. -PspawnGrid=N ставит их сеткой N×N.
         */
        /** Картинка 1×1 PNG для проверки перепаковки. */
        val TEST_PNG: ByteArray = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

        val SPAWN: List<Pair<Float, Float>> = run {
            val n = System.getProperty("bugReplayCheck.spawnGrid")?.toIntOrNull() ?: 2
            val step = 150f / n
            List(n * n) { i -> (25f + step * (i % n) + step / 2) to (25f + step * (i / n) + step / 2) }
        }

        /** Сколько тиков держать каждую клавишу стресс-теста (D, потом A): -PstressTicks=300. */
        val STRESS_TICKS = System.getProperty("bugReplayCheck.stressTicks")?.toIntOrNull() ?: 0
        const val KEY_A = 11
        const val KEY_D = 13

        /** Сколько тиков мир растёт после посадки: -PgrowTicks=3000 — длинный прогон. */
        val GROW_TICKS = System.getProperty("bugReplayCheck.growTicks")?.toIntOrNull() ?: 300

        /**
         * Сажать во все точки один геном: -PspawnGenome=bundled/Megalodon. Для замеров на
         * крупных телах; без параметра — все запечённые по кругу.
         */
        val SPAWN_GENOME: String? = System.getProperty("bugReplayCheck.spawnGenome")

        /**
         * Стресс жизненного цикла на всё время роста (PlayerCommand.SetStress): раз во сколько
         * тиков убить случайную клетку, отбросить клетку, посадить организм случайного генома.
         * -PkillEvery=7 -PflingEvery=11 -PspawnEvery=40 — смерти и рождения вперемешку, на
         * них проверяются возврат и повторная выдача арен.
         */
        val KILL_EVERY = System.getProperty("bugReplayCheck.killEvery")?.toIntOrNull() ?: 0
        val FLING_EVERY = System.getProperty("bugReplayCheck.flingEvery")?.toIntOrNull() ?: 0
        val SPAWN_EVERY = System.getProperty("bugReplayCheck.spawnEvery")?.toIntOrNull() ?: 0

        const val TIMEOUT_NS = 300_000_000_000L
    }
}

/** Повтор: ждём итог BugReplay и выходим с его кодом. */
private class Replayer(private val game: MyGame) : ApplicationListener by game {

    private val startedAt = System.nanoTime()

    override fun render() {
        game.render()
        val result = BugReplay.result
        when {
            result != null -> {
                println(if (result) "PASS: повтор совпал с записью" else "FAIL: повтор не дал ожидаемого — см. итог выше")
                exitCode = if (result) 0 else 1
                Gdx.app.exit()
            }
            System.nanoTime() - startedAt > TIMEOUT_NS -> {
                println("FAIL: повтор не закончился за ${TIMEOUT_NS / 1_000_000_000} с")
                exitCode = 1
                Gdx.app.exit()
            }
        }
    }

    private companion object {
        const val TIMEOUT_NS = 300_000_000_000L
    }
}
