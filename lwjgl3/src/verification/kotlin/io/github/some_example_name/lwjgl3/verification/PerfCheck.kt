package io.github.some_example_name.lwjgl3.verification

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.github.some_example_name.lwjgl3.DesktopFileProvider
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.PlatformTuning
import io.github.some_example_name.old.features.simulation.SimulationIntent
import io.github.some_example_name.old.features.worldeditor.WorldEdit
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.old.systems.maps.MapSave
import io.github.some_example_name.old.systems.simulation.Phase
import io.github.some_example_name.old.systems.simulation.SimulationSystem
import io.github.some_example_name.physics.describeBlocks
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * ЗАМЕР ПРОИЗВОДИТЕЛЬНОСТИ НАСТОЯЩЕЙ ИГРЫ (05.11): окно, мир 256×256 (с террейном или без), сетка из
 * [COUNT] организмов одного генома, рост [GROW] тиков, затем [MEASURE] тиков на максимальной скорости.
 * Печатает UPS, загрузку процессора процессом (доля всех логических ядер), клеток и частиц, среднее
 * фаз тика (PhaseProfiler, мкс). Под организмы в террейне вычищаются круглые поляны по размеру тела —
 * иначе крупное тело врастало бы в стены; остальной террейн на месте.
 *
 * Запуск: gradlew :lwjgl3:perfCheck -Pgenome="Burdock leaf" -Pcount=4 -Pterrain=1 [-Pgrow=1500 -Pmeasure=600]
 *
 * ЗАМЕР НА СНИМКЕ: -Psnapshot=имя. Файла maps/имя.map нет — мир растёт как обычно, сохраняется и
 * проверка выходит (рост 34 червей — минуты). Файл есть — загрузка, [WARM] тиков прогрева, замер и
 * печать хэша мира на точном тике (PERFHASH): при любом числе воркеров он обязан быть один и тот же.
 * Загрузка начинается с одного и того же состояния, поэтому прогоны сравнимы между собой. Сохранение
 * пересоздавать после смены формата сущностей или мира физики (удалить файл).
 */
fun main() {
    System.getProperty("perf.workers")?.toIntOrNull()?.let { PlatformTuning.performanceCoreCount = it }
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Genomeia — замер производительности")
        setWindowedMode(900, 700)
        useVsync(false)
        setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 3)
    }
    Lwjgl3Application(PerfRun(MyGame(DesktopFileProvider())), config)
    exitProcess(exitCode)
}

private var exitCode = 1

private val GENOME = System.getProperty("perf.genome") ?: "Burdock leaf"
private val COUNT = System.getProperty("perf.count")?.toIntOrNull() ?: 4
private val TERRAIN = System.getProperty("perf.terrain") == "1"
private val GROW = System.getProperty("perf.grow")?.toIntOrNull() ?: 1500
private val MEASURE = System.getProperty("perf.measure")?.toIntOrNull() ?: 600
private val CLEAR = System.getProperty("perf.clear")?.toFloatOrNull() ?: 25f
/** Таймеры стадий физики (dbgTimeNs) за замер: -Pstages=1. Сами таймеры чуть замедляют шаг. */
private val STAGES = System.getProperty("perf.stages") == "1"
/** Сон тел выключен (-Psleep=0): все тела считаются каждый тик — замер «худшего случая» без покоя. */
private val SLEEP = System.getProperty("perf.sleep") != "0"
/** Профиль JFR только за окно замера, без роста (-Pjfr=путь.jfr). */
private val JFR = System.getProperty("perf.jfr")
private val SNAPSHOT = System.getProperty("perf.snapshot")
private val WARM = System.getProperty("perf.warm")?.toIntOrNull() ?: 100
/** Режим отрисовки (-Prender=tissue_map|debug|nostalgic): настройка игрока на замер не влияет. В отладочном идут наклейки и нейросвязи. */
private val RENDER = io.github.some_example_name.render.RenderMode.valueOf((System.getProperty("perf.render") ?: "tissue_map").uppercase())
private const val WORLD = 256
private const val STEP_LOADED = 10
private const val STEP_SAVE = 20
private const val STEP_SAVED = 21

private class PerfRun(private val game: MyGame) : ApplicationListener by game {
    private var frames = 0
    private var step = 0
    private var waitUntilFrame = 0
    private var waitUntilTick = -1
    private val startedAt = System.nanoTime()

    private var tick0 = 0; private var wall0 = 0L; private var cpu0 = 0L
    private var recording: jdk.jfr.Recording? = null
    private var lastWindow = 0
    private val sums = DoubleArray(Phase.COUNT)
    private var windows = 0
    private var spec: WorldSpec? = null
    private var savedSlot: String? = null
    private var hashTick = -1

    override fun render() {
        try {
            game.render()
            frames++
            val data = DISimulationContainer.simulationData
            if (step == 3) collect()
            if (frames < waitUntilFrame) return
            if (waitUntilTick >= 0 && data.tickCounter < waitUntilTick) {
                check(System.nanoTime() - startedAt < 1_200_000_000_000L) { "замер завис на шаге $step" }
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

    private fun collect() {
        val p = DISimulationContainer.simulationSystem.profiler
        if (p.windowsClosed == lastWindow) return
        lastWindow = p.windowsClosed
        for (k in 0 until Phase.COUNT) sums[k] += p.windowMicros[k]
        windows++
    }

    private fun advance() {
        val data = DISimulationContainer.simulationData
        when (step++) {
            0 -> {
                if (SNAPSHOT != null && MapSave.exists(SNAPSHOT)) {
                    // На паузе: первый тик после загрузки — ровно то, что в файле.
                    data.isPlay = false
                    DIGameGlobalContainer.navigationCommandsManager.performCommand(GoSimulation(world = null, genomeName = null, savedWorld = SNAPSHOT))
                    waitUntilFrame = frames + 5
                    step = STEP_LOADED
                    return
                }
                val spots = spots()
                val edits = if (!TERRAIN) listOf(WorldEdit.Clear) else {
                    // Поляна под тело: радиус [CLEAR] клеток мира (лист лопуха, 2970 клеток, — ~17 клеток в радиусе).
                    val radius = CLEAR * WorldSpec.MAP_CELLS_PER_WORLD_CELL
                    spots.map { (x, y) ->
                        WorldEdit.Stroke((x * WorldSpec.MAP_CELLS_PER_WORLD_CELL).toInt(), (y * WorldSpec.MAP_CELLS_PER_WORLD_CELL).toInt(),
                            ceil(radius).toInt(), erase = true, circle = true)
                    }
                }
                val world = WorldSpec(WorldSpec.mapCells(WORLD), WorldSpec.mapCells(WORLD), "31415926", 9, 0, edits)
                spec = world
                DIGameGlobalContainer.navigationCommandsManager.performCommand(GoSimulation(world, null))
                waitUntilFrame = frames + 5
            }
            1 -> {
                DISimulationContainer.renderBufferManager.mode = RENDER
                screen(SimulationIntent.SetMaxSpeed(true))
                // Мир ещё пуст (тел нет — будить некого), так что запись флага из этого потока безопасна.
                if (!SLEEP) DISimulationContainer.worldPhysics.bridge.world.sleepOn = false
                val g = genomeIndex()
                for ((x, y) in spots()) DISimulationContainer.userCommandManager.push(PlayerCommand.Tap(x, y, genomeIndex = g))
                waitUntilTick = data.tickCounter + GROW
            }
            STEP_LOADED -> {
                val c = DISimulationContainer
                check(c.cellEntity.aliveList.size > 0) { "в снимке $SNAPSHOT нет клеток" }
                if (!SLEEP) c.simulationSystem.betweenTicks { c.worldPhysics.bridge.world.sleepOn = false }
                // Тик хэша считается на паузе: он не должен зависеть от того, когда поток рендера посмотрит на счётчик.
                val period = SimulationSystem.STATE_HASH_PERIOD
                hashTick = ((data.tickCounter + WARM + MEASURE) / period + 1) * period
                c.simulationSystem.hashWatchTick = hashTick
                c.renderBufferManager.instanceHashTick = hashTick
                DISimulationContainer.renderBufferManager.mode = RENDER
                screen(SimulationIntent.SetMaxSpeed(true))
                data.isPlay = true
                println("PERFSNAPSHOT загружен $SNAPSHOT: тик ${data.tickCounter}, клеток ${c.cellEntity.aliveList.size}, хэш на тике $hashTick")
                waitUntilTick = data.tickCounter + WARM
                step = 2
            }
            STEP_SAVE -> {
                println("PERFBLOCKS " + with(DISimulationContainer.worldPhysics.bridge.world.state) { describeBlocks() })
                DISimulationContainer.renderSystem.requestPreview(MapSave.PREVIEW_SIZE) { preview ->
                    savedSlot = DISimulationContainer.mapSave.save(SNAPSHOT, spec, preview)
                }
                step = STEP_SAVED
            }
            STEP_SAVED -> {
                val slot = savedSlot
                if (slot == null) { step = STEP_SAVED; return }
                println("PERFSNAPSHOT записан $slot: ${MapSave.mapFile(slot).file().length() / 1024 / 1024} МБ, тик ${data.tickCounter}, клеток ${DISimulationContainer.cellEntity.aliveList.size}")
                exitCode = 0
                Gdx.app.exit()
            }
            2 -> {
                if (SNAPSHOT != null && hashTick < 0) {
                    // Вырос, снимка ещё не было: записать и выйти.
                    data.isPlay = false
                    waitUntilFrame = frames + 5
                    step = STEP_SAVE
                    return
                }
                tick0 = data.tickCounter; wall0 = System.nanoTime(); cpu0 = processCpu()
                threadCpu0 = threadCpu()
                if (STAGES) { val st = DISimulationContainer.worldPhysics.bridge.world.state; st.dbgTimeOn = true; java.util.Arrays.fill(st.dbgTimeNs, 0L) }
                lastWindow = DISimulationContainer.simulationSystem.profiler.windowsClosed
                if (JFR != null) recording = jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).apply { start() }
                waitUntilTick = maxOf(data.tickCounter + MEASURE, hashTick + 1)
            }
            3 -> {
                val wall = (System.nanoTime() - wall0) / 1e9
                recording?.let { it.stop(); it.dump(java.nio.file.Path.of(JFR!!)); it.close() }
                val ticks = data.tickCounter - tick0
                val cores = Runtime.getRuntime().availableProcessors()
                val cpu = (processCpu() - cpu0) / 1e9 / wall / cores
                val c = DISimulationContainer
                val names = arrayOf("LINKS", "NEURAL", "COLLIDE", "CELLS", "PHEROMONE", "ARRANGE(физика)", "WORLD_CMD",
                    "ORGANS", "USER_CMD", "LAST_CMD", "REBUILD", "RENDER", "TICK")
                val phases = (0 until Phase.COUNT).joinToString(" ") { "${names[it]} %.2f".format(sums[it] / maxOf(1, windows) / 1000) }
                println(("PERFCHECK геном=$GENOME организмов=$COUNT террейн=${if (TERRAIN) 1 else 0} сон=${if (SLEEP) 1 else 0} клеток=${c.cellEntity.aliveList.size} " +
                    "частиц=${c.particleEntity.aliveList.size} UPS=%.1f мс/тик=%.2f процессор=%.0f%% из $cores логических; фазы мс: $phases")
                    .format(ticks / wall, wall * 1000 / ticks, cpu * 100))
                // Время процессора по потокам за замер — доля стенного времени (1.00 = поток занят весь замер).
                val now = threadCpu()
                val busy = now.entries.map { (name, t) -> name to (t - (threadCpu0[name] ?: 0L)) / 1e9 / wall }.filter { it.second > 0.01 }.sortedByDescending { it.second }
                run { val w = DISimulationContainer.worldPhysics.bridge.world; val st = w.state; var awake = 0; var awakeT = 0; for (i in 0 until st.n) { if (st.cellDead[i] || st.invMass[i] == 0.0) continue; awake++; if (st.cellGroup[i] != 0) awakeT++ }; println("PERFAWAKE не спят частиц мира физики $awake из ${st.n}, из них террейна $awakeT") }
                println("PERFTHREADS " + busy.joinToString(" ") { "${it.first}=%.2f".format(it.second) })
                println("PERFBLOCKS " + with(DISimulationContainer.worldPhysics.bridge.world.state) { describeBlocks() })
                if (hashTick >= 0) println("PERFHASH тик $hashTick хэш ${c.simulationSystem.hashWatchReport} буфер %016x".format(c.renderBufferManager.instanceHashValue))
                if (STAGES) { val ns = DISimulationContainer.worldPhysics.bridge.world.state.dbgTimeNs; println("PERFSTAGES " + ns.indices.filter { ns[it] > 0 }.joinToString(" ") { "$it:%.2f".format(ns[it] / 1e6 / ticks) }) }
                exitCode = 0
                Gdx.app.exit()
            }
        }
    }

    private fun genomeIndex(): Int {
        val all = GenomeCatalog.all()
        val i = all.indexOfFirst { it == GENOME || it.endsWith("/$GENOME") }
        check(i >= 0) { "нет генома $GENOME среди ${all.take(40)}" }
        return i
    }

    /** Сетка мест посадки по всему миру с полями. */
    private fun spots(): List<Pair<Float, Float>> {
        val n = ceil(sqrt(COUNT.toDouble())).toInt()
        val step = (WORLD - 40f) / n
        return List(COUNT) { i -> (20f + step * (i % n) + step / 2) to (20f + step * (i / n) + step / 2) }
    }

    private fun screen(command: GameCommand) {
        val target = DIGameGlobalContainer.game.screen as? ReplayTarget ?: error("экран не принимает команды")
        check(target.replay(command)) { "экран не принял ${command.name}" }
    }

    private var threadCpu0 = emptyMap<String, Long>()

    /** Время процессора по потокам (имя → нс); потоки с одинаковым именем складываются. */
    private fun threadCpu(): Map<String, Long> {
        val mx = ManagementFactory.getThreadMXBean()
        val out = HashMap<String, Long>()
        for (id in mx.allThreadIds) { val info = mx.getThreadInfo(id) ?: continue; val t = mx.getThreadCpuTime(id); if (t > 0) out[info.threadName] = (out[info.threadName] ?: 0L) + t }
        return out
    }

    private fun processCpu(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime
}
