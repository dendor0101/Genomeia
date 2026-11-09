package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.SceneFile
import io.github.some_example_name.physics.lab.session.DemoSettings
import java.util.Locale

/**
 * ЦЕНА СВОБОДНЫХ ЧАСТИЦ (субстанций). Мир — тела сцены; к нему: ничего; N частиц через
 * addFree (пул, часть слотов нерождённые); те же N частиц одним телом из клеток без связей.
 * Частицы — сеткой далеко правее сцены с малыми случайными скоростями (зерно фиксировано):
 * с телами они не встречаются (проверка — число разрывов). Варианты идут дважды подряд. Печатается медиана времени тика под гребком после прогрева.
 *
 * Запуск: FreeBench <сцена.scene> [N] [тиков]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val bodies = SceneFile.loadBodies(args.getOrElse(0) { "docs/physics/scenes/showcase-30k.scene" })
    val count = args.getOrNull(1)?.toIntOrNull() ?: 5000
    val ticks = args.getOrNull(2)?.toIntOrNull() ?: 300
    val config = PhysicsConfig(DemoSettings.fromEnvironment().snapshot())
    val scale = WorldScale.of(bodies[0])
    fun base(): PhysicsWorld = PhysicsWorld(config, scale = scale).apply { state.quiet = true; for (b in bodies) addBody(b) }
    // Места частиц: сетка под сценой, шаг — три радиуса.
    val probe = base()
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (i in 0 until probe.particleCount) {
        minX = minOf(minX, probe.x[i]); maxX = maxOf(maxX, probe.x[i]); minY = minOf(minY, probe.y[i]); maxY = maxOf(maxY, probe.y[i])
    }
    // Сетка частиц — правее сцены на её ширину (на полу встречаются все, кто тонет):
    // тела до неё не доплывают, и замер меряет сами частицы, а не разрывы от столкновений.
    val width = maxX - minX
    minX = maxX + width; maxX = minX + width
    val r = 0.5 * probe.meanLinkLength
    val step = 3.0 * r
    val perRow = maxOf(1, ((maxX - minX) / step).toInt())
    val px = DoubleArray(count) { minX + (it % perRow) * step }
    val py = DoubleArray(count) { maxY - (it / perRow) * step }
    val rnd = java.util.Random(7L)
    val vx = DoubleArray(count) { (rnd.nextDouble() - 0.5) * 0.1 }; val vy = DoubleArray(count) { (rnd.nextDouble() - 0.5) * 0.1 }

    val none = base()
    val pool = base().apply { for (k in 0 until count) addFree(px[k], py[k], r, vx[k], vy[k]) }
    val def = base().apply {
        val d = BodyDefinition(FloatArray(count) { px[it].toFloat() }, FloatArray(count) { py[it].toFloat() },
            FloatArray(count) { r.toFloat() }, BooleanArray(count), BooleanArray(count), IntArray(0), IntArray(0),
            IntArray(0), IntArray(0), IntArray(0), FloatArray(0), emptyArray(), emptyArray(), bodies[0].scale)
        val first = bodyFirst(addBody(d))
        for (k in 0 until count) setVelocity(first + k, vx[k], vy[k])
    }
    val runs = listOf("без частиц" to none, "пул addFree" to pool, "тело из клеток" to def)
    for ((label, w) in runs + runs) {
        val t = LongArray(ticks)
        val tornBefore = w.state.tornTotal
        w.state.dbgTimeOn = System.getenv("FREE_STAGES") == "1"
        for (k in 0 until ticks) {
            if (k == ticks / 3) java.util.Arrays.fill(w.state.dbgTimeNs, 0L)
            w.setAllMuscleTargets(if (k % 40 < 20) 1.0 else 0.0)
            val t0 = System.nanoTime(); w.step(); t[k] = System.nanoTime() - t0
        }
        val warm = t.copyOfRange(ticks / 3, ticks).sorted()
        if (w.state.dbgTimeOn) {
            val ns = w.state.dbgTimeNs; val n = ticks - ticks / 3
            println("  стадии, мс/тик: " + ns.indices.filter { ns[it] > 0 }.sortedByDescending { ns[it] }.take(10)
                .joinToString(", ") { "%s %.1f".format(w.state.dbgTimeNames[it], ns[it] / 1e6 / n) })
        }
        println("FREE_BENCH %-15s частиц %6d (организмов %6d): тик медиана %.2f мс, 90%% %.2f мс, разрывов за прогон %d".format(
            label, w.particleCount, w.organismCount, warm[warm.size / 2] / 1e6, warm[warm.size * 9 / 10] / 1e6, w.state.tornTotal - tornBefore))
    }
}
