package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.MAX_SPEED_CELLS_PER_TICK
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.io.SceneFile
import io.github.some_example_name.physics.lab.session.DemoSettings
import java.util.Locale
import kotlin.math.sqrt

/**
 * МИР, КОТОРЫЙ МЕНЯЕТСЯ НА ХОДУ (этап A2.3): тела дописываются и удаляются посреди
 * прогона, между ними гребок и таран. Оракула «загрузка разом» здесь нет — история другая,
 * — поэтому проверяется то, что обязано держаться всегда: один и тот же сценарий в одном
 * потоке и на N воркерах даёт побитово один мир на каждом тике (детерминизм, отсутствие
 * чтения неинициализированного и устаревшего после роста массивов). Полноту списка пар с
 * новыми телами проверяет CT_BPCHECK=1 (полный перебор каждый подшаг).
 *
 * Запуск: RuntimeWorldCheck <сцена.scene> [воркеров]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val bodies = SceneFile.loadBodies(args.getOrElse(0) { "docs/physics/scenes/append-small.scene" })
    val workers = args.getOrNull(1)?.toIntOrNull() ?: 7
    require(bodies.size >= 6) { "нужно хотя бы 6 тел в сцене" }
    val scale = WorldScale.of(bodies[0])
    val base = DemoSettings.fromEnvironment().snapshot().toMutableMap()
    base["RB_PARALLEL_MIN"] = "0"
    val config = PhysicsConfig(base)
    val serial = PhysicsWorld(config, workers = null, scale = scale)
    val parallel = PhysicsWorld(config, workers = PhysicsWorkers.shared(workers), scale = scale)
    val worlds = listOf(serial, parallel)
    for (w in worlds) { w.state.quiet = true; w.sleepOn = true }
    val ids = ArrayList<Int>()
    fun add(k: Int) { var id = -1; for (w in worlds) id = w.addBody(bodies[k]); ids.add(id) }
    var tick = 0
    var ok = true
    fun run(count: Int, gait: Boolean) {
        repeat(count) {
            if (!ok) return
            val on = if (gait && tick % 40 < 20) 1.0 else 0.0
            for (w in worlds) { w.setAllMuscleTargets(on); w.step() }
            tick++
            val h = worlds.map { PlayerLog.stateHash(it.particleCount, it.x, it.y, it.vx, it.vy) }
            if (h[0] != h[1]) { println("RUNTIME diverged at tick $tick (particles ${serial.particleCount})"); ok = false }
        }
    }
    fun slam() {
        for (w in worlds) {
            val st = w.state
            val order = (0 until st.organismCount).sortedByDescending { st.organismSize[it] }
            val a = order[0]; val b = order[1]
            var ax = 0.0; var ay = 0.0; var an = 0; var bx = 0.0; var by = 0.0; var bn = 0
            for (i in 0 until st.n) when (st.organismOf[i]) {
                a -> { ax += st.px[i]; ay += st.py[i]; an++ }
                b -> { bx += st.px[i]; by += st.py[i]; bn++ }
            }
            ax /= an; ay /= an; bx /= bn; by /= bn
            var dx = bx - ax; var dy = by - ay
            val d = sqrt(dx * dx + dy * dy); dx /= d; dy /= d
            val speed = 0.8 * MAX_SPEED_CELLS_PER_TICK * w.meanLinkLength / DT
            var ca = -1; var cb = -1
            for (i in 0 until st.n) when (st.organismOf[i]) {
                a -> { w.setVelocity(i, dx * speed, dy * speed); if (ca < 0) ca = i }
                b -> { w.setVelocity(i, -dx * speed, -dy * speed); if (cb < 0) cb = i }
            }
            w.wake(ca); w.wake(cb)
        }
    }
    for (k in 0 until 3) add(k)
    run(60, gait = true)
    add(3); add(4)
    run(40, gait = true)
    slam()
    run(80, gait = false)
    for (w in worlds) w.removeBody(ids[1])
    run(40, gait = true)
    for (k in 5 until bodies.size) add(k)
    run(60, gait = true)
    slam()
    run(120, gait = true)
    val torn = serial.state.tornTotal
    println(if (ok) "RUNTIME OK: $tick тиков, ${serial.particleCount} частиц, порвано ${torn}, один поток = $workers воркеров побитово"
        else "RUNTIME FAILED")
    if (!ok) kotlin.system.exitProcess(1)
}
