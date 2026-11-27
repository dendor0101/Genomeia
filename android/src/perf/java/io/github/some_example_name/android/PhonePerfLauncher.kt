package io.github.some_example_name.android

import android.os.Bundle
import android.util.Log
import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.BufferUtils
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
import io.github.some_example_name.old.systems.simulation.Phase
import io.github.some_example_name.render.RenderMode
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * ЗАМЕР НАСТОЯЩЕЙ ИГРЫ НА ТЕЛЕФОНЕ — телефонный PerfCheck (27.11). Только в сборке perf: она
 * ставится рядом с игрой (applicationId …perf) и установленную игру игрока не трогает. Сборка не
 * отлаживаемая: в отлаживаемом приложении ART не встраивает методы, и числа были бы хуже игры.
 *
 * Мир WORLD×WORLD без террейна, сетка из COUNT организмов генома GENOME, рост GROW тиков, затем
 * MEASURE тиков на максимальной скорости. В logcat (тег PERF) — UPS, фазы тика (PhaseProfiler),
 * части фазы физики (WorldPhysics: сверка до шага, шаг, разбор после шага), кадр потока рендера и,
 * с probe=1, цена повторной заливки оверлея и феромонов того же кадра в свой буфер (так видно, во
 * что обходится заливка их каждый кадр).
 *
 *   ./gradlew :android:assemblePerf && adb install -r android/build/outputs/apk/perf/android-perf.apk
 *   adb shell am start -S -n io.github.some_example_name.perf/io.github.some_example_name.android.PhonePerfLauncher \
 *       --es genome Octopod --ei count 16 --ei grow 1500 --ei measure 600 --es render debug [--ei draw 0] [--ei probe 1] [--ei workers N] [--ei hashtick 600] [--ei terrain 1] [--es physics ISLANDS=0]
 *   adb logcat -s PERF
 *
 * Кадр потока рендера (PERFFRAME) включает ожидание свободного буфера экрана у драйвера, то есть
 * при 60 Гц держится около 16 мс — это не цена кадра, а темп экрана.
 *
 * draw=0 — на время замера кадр не рисуется (GL-поток стоит): так видно, сколько тику стоит сама
 * отрисовка рядом с ним (память и нагрев общие).
 */
class PhonePerfLauncher : AndroidLauncher() {

    private lateinit var options: Bundle

    override fun onCreate(savedInstanceState: Bundle?) {
        options = intent?.extras ?: Bundle()
        super.onCreate(savedInstanceState)
    }

    override fun configureSimulationThreads() {
        super.configureSimulationThreads()
        val workers = options.getInt("workers", 0)
        if (workers > 0) PlatformTuning.performanceCoreCount = workers
        // --es physics "ISLANDS=0" — закрепить режим островов: по замеру он двоит время шага.
        options.getString("physics")?.let { keys ->
            PlatformTuning.physicsExecutionKeys = keys.split(',').mapNotNull { kv ->
                val eq = kv.indexOf('=')
                if (eq > 0) kv.substring(0, eq).trim() to kv.substring(eq + 1).trim() else null
            }.toMap()
        }
    }

    override fun wrapGame(game: MyGame): ApplicationListener = PhonePerfRun(game, options)
}

private class PhonePerfRun(private val game: MyGame, o: Bundle) : ApplicationListener by game {
    private val genome = o.getString("genome") ?: "Octopod"
    private val count = o.getInt("count", 4)

    /**
     * Тик сверки (кратный SimulationSystem.STATE_HASH_PERIOD): на нём в лог — хэш мира и хэш
     * снимка (инстансы, оверлей, рамки тел, данные микроскопа). Мир при любом числе воркеров
     * один и тот же, значит и снимок обязан совпасть: прогоны с --ei workers 1 и без него.
     */
    private val hashTick = o.getInt("hashtick", -1)
    private val grow = o.getInt("grow", 1500)
    private val measure = o.getInt("measure", 600)
    private val world = o.getInt("world", 256)
    /** Мир с террейном (--ei terrain 1), поляны радиусом [CLEAR] клеток мира под тела. */
    private val terrain = o.getInt("terrain", 0) != 0
    private val draw = o.getInt("draw", 1) != 0
    private val probe = o.getInt("probe", 0) != 0
    private val mode = RenderMode.valueOf((o.getString("render") ?: "debug").uppercase(Locale.ROOT))

    private var frames = 0
    private var step = 0
    private var waitUntilFrame = 0
    private var waitUntilTick = -1

    private var tick0 = 0
    private var wall0 = 0L
    private var frames0 = 0
    private var lastWindow = 0
    private var windows = 0
    private val sums = DoubleArray(Phase.COUNT)
    private var syncIn0 = 0L
    private var step0 = 0L
    private var syncOut0 = 0L
    private var frameNanos = 0L
    private var measuredFrames = 0

    private var probeVbo = 0
    private var probeCapacity = 0
    private var probeNanos = 0L
    private var probeBytes = 0L
    private var probeFrames = 0

    override fun render() {
        try {
            val measuring = step == 3
            if (!measuring || draw) {
                val t0 = System.nanoTime()
                game.render()
                if (measuring) { frameNanos += System.nanoTime() - t0; measuredFrames++ }
                if (measuring && probe) probeUpload()
            }
            frames++
            val data = DISimulationContainer.simulationData
            if (measuring) collect()
            if (frames < waitUntilFrame) return
            if (waitUntilTick >= 0 && data.tickCounter < waitUntilTick) return
            waitUntilTick = -1
            advance()
        } catch (e: Throwable) {
            Log.e(TAG, "замер упал на шаге $step", e)
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
                // С террейном — как PerfCheck: под каждое тело круглая поляна, остальной террейн на месте.
                val edits = if (!terrain) listOf(WorldEdit.Clear) else {
                    val radius = CLEAR * WorldSpec.MAP_CELLS_PER_WORLD_CELL
                    spots().map { (x, y) ->
                        WorldEdit.Stroke((x * WorldSpec.MAP_CELLS_PER_WORLD_CELL).toInt(), (y * WorldSpec.MAP_CELLS_PER_WORLD_CELL).toInt(),
                            ceil(radius).toInt(), erase = true, circle = true)
                    }
                }
                val spec = WorldSpec(WorldSpec.mapCells(world), WorldSpec.mapCells(world), "31415926", 9, 0, edits)
                DIGameGlobalContainer.navigationCommandsManager.performCommand(GoSimulation(spec, null))
                waitUntilFrame = frames + 5
            }
            1 -> {
                DISimulationContainer.renderBufferManager.mode = mode
                if (hashTick > 0) {
                    DISimulationContainer.simulationSystem.hashWatchTick = hashTick
                    DISimulationContainer.renderBufferManager.instanceHashTick = hashTick
                }
                screen(SimulationIntent.SetMaxSpeed(true))
                val g = genomeIndex()
                for ((x, y) in spots()) DISimulationContainer.userCommandManager.push(PlayerCommand.Tap(x, y, genomeIndex = g))
                Log.i(TAG, "PERFSTART геном=$genome организмов=$count террейн=${if (terrain) 1 else 0} режим=$mode рисовать=${if (draw) 1 else 0} физика=${PlatformTuning.physicsExecutionKeys} " +
                    "воркеров=${DISimulationContainer.threadManager.executor.workerCount} рост $grow тиков")
                waitUntilTick = data.tickCounter + grow
            }
            2 -> {
                val c = DISimulationContainer
                c.simulationSystem.betweenTicks {
                    syncIn0 = c.worldPhysics.syncInNanos; step0 = c.worldPhysics.stepNanos; syncOut0 = c.worldPhysics.syncOutNanos
                    tick0 = data.tickCounter
                }
                wall0 = System.nanoTime(); frames0 = frames
                lastWindow = c.simulationSystem.profiler.windowsClosed
                waitUntilTick = tick0 + measure
            }
            3 -> {
                val c = DISimulationContainer
                var ticks = 0
                var syncIn = 0L; var stepNs = 0L; var syncOut = 0L
                c.simulationSystem.betweenTicks {
                    ticks = data.tickCounter - tick0
                    syncIn = c.worldPhysics.syncInNanos - syncIn0
                    stepNs = c.worldPhysics.stepNanos - step0
                    syncOut = c.worldPhysics.syncOutNanos - syncOut0
                }
                val wall = (System.nanoTime() - wall0) / 1e9
                val names = arrayOf("LINKS", "NEURAL", "COLLIDE", "CELLS", "PHEROMONE", "ARRANGE", "WORLD_CMD",
                    "ORGANS", "USER_CMD", "LAST_CMD", "REBUILD", "RENDER", "TICK")
                val phases = (0 until Phase.COUNT).joinToString(" ") { "${names[it]} %.2f".format(Locale.ROOT, sums[it] / maxOf(1, windows) / 1000) }
                // Числа живых — из снимка (списки живых — fastutil, его нет на пути сборки модуля).
                val shot = c.renderBufferManager.currentSnapshot()
                Log.i(TAG, ("PERFCHECK геном=$genome организмов=$count режим=$mode рисовать=${if (draw) 1 else 0} клеток=${shot.cellCount} " +
                    "частиц=${shot.instanceCount} UPS=%.1f мс/тик=%.2f FPS=%.1f; фазы мс: $phases")
                    .format(Locale.ROOT, ticks / wall, wall * 1000 / ticks, (frames - frames0) / wall))
                Log.i(TAG, "PERFARRANGE мс на тик: сверка до шага %.3f, шаг %.3f, после шага %.3f".format(Locale.ROOT,
                    syncIn / 1e6 / ticks, stepNs / 1e6 / ticks, syncOut / 1e6 / ticks))
                if (measuredFrames > 0) Log.i(TAG, "PERFFRAME кадр потока рендера %.3f мс в среднем, кадров $measuredFrames".format(Locale.ROOT, frameNanos / 1e6 / measuredFrames))
                if (hashTick > 0) Log.i(TAG, "PERFHASH тик $hashTick режим=$mode воркеров=${c.threadManager.executor.workerCount} " +
                    "нейросвязей до ${c.neuralLinkEntity.lastId + 1} мир ${c.simulationSystem.hashWatchReport} снимок %016x".format(c.renderBufferManager.instanceHashValue))
                if (probeFrames > 0) Log.i(TAG, "PERFPROBE повторная заливка оверлея и феромонов кадра: %.3f мс на кадр, %d КБ на кадр".format(Locale.ROOT,
                    probeNanos / 1e6 / probeFrames, probeBytes / probeFrames / 1024))
                Gdx.app.exit()
            }
        }
    }

    /**
     * Оверлей и феромоны взятого кадром снимка — ещё раз, в свой буфер на GPU, тем же
     * glBufferSubData, что и DebugRenderer: столько GL-поток платит за их заливку на каждом кадре.
     */
    private fun probeUpload() {
        val snapshot = DISimulationContainer.renderBufferManager.currentSnapshot()
        if (!snapshot.hasOverlay) return
        val overlay = snapshot.overlay.end()
        val pheromones = snapshot.pheromones.end()
        val bytes = overlay.remaining() + pheromones.remaining()
        val gl = Gdx.gl
        if (probeVbo == 0) probeVbo = gl.glGenBuffer()
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, probeVbo)
        val t0 = System.nanoTime()
        if (bytes > probeCapacity) {
            probeCapacity = bytes * 2
            gl.glBufferData(GL20.GL_ARRAY_BUFFER, probeCapacity, null, GL20.GL_STREAM_DRAW)
        }
        if (overlay.remaining() > 0) gl.glBufferSubData(GL20.GL_ARRAY_BUFFER, 0, overlay.remaining(), overlay)
        if (pheromones.remaining() > 0) gl.glBufferSubData(GL20.GL_ARRAY_BUFFER, overlay.remaining(), pheromones.remaining(), pheromones)
        probeNanos += System.nanoTime() - t0
        gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0)
        probeBytes += bytes
        probeFrames++
    }

    private fun genomeIndex(): Int {
        val all = GenomeCatalog.all()
        val i = all.indexOfFirst { it == genome || it.endsWith("/$genome") }
        check(i >= 0) { "нет генома $genome среди ${all.take(40)}" }
        return i
    }

    /** Сетка мест посадки по всему миру с полями — как у PerfCheck. */
    private fun spots(): List<Pair<Float, Float>> {
        val n = ceil(sqrt(count.toDouble())).toInt()
        val step = (world - 40f) / n
        return List(count) { i -> (20f + step * (i % n) + step / 2) to (20f + step * (i / n) + step / 2) }
    }

    private fun screen(command: GameCommand) {
        val target = DIGameGlobalContainer.game.screen as? ReplayTarget ?: error("экран не принимает команды")
        check(target.replay(command)) { "экран не принял ${command.name}" }
    }

    private companion object {
        const val TAG = "PERF"

        /** Радиус поляны под тело в террейне, клеток мира — как у PerfCheck. */
        const val CLEAR = 25f
    }
}
