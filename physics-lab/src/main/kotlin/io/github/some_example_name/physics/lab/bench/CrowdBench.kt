package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.SUBSTEPS
import java.util.Locale
import kotlin.math.sqrt

/**
 * ТОЛПА ДЛЯ ЦЕЛИ «100k КЛЕТОК В 30 UPS». Тела без мышц, костей и нейросвязей, сон выключен:
 * треть клеток в телах по 300, треть по 1000, треть по 3000. Каждое тело толкает своя сила,
 * направление которой медленно поворачивается, поэтому тела всё время двигаются и бьются
 * друг о друга (в сетке листьев касаний не было вовсе: стадия контактов занимала 0.01 мс).
 * Сама сцена — CrowdScene (её же строит lawCostBench).
 *
 * Масштаб и единицы — как в игре (PhysicsUnits): клетка радиуса 0.5, связь 0.6 единиц игры.
 * Толчок и контрольная сумма вне таймера. Сумма мира печатается в конце и должна совпасть при
 * любом числе воркеров: так проверяется, что параллельность не поменяла физику.
 *
 * Запуск: gradlew :physics-lab:crowdBench -Pcells=100000 -Pworkers=8 -Pwarm=150 -Pmeasure=150
 * Шестой параметр `game` (-Pgame=1) — режим «как в игре»: мышцы множителями клеток, мягкость, волокна
 * (CrowdScene.gameLike); сумма мира у него своя.
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val cells = args.getOrNull(0)?.toIntOrNull() ?: 100_000
    val workers = args.getOrNull(1)?.toIntOrNull() ?: 8
    val warm = args.getOrNull(2)?.toIntOrNull() ?: 150
    val measure = args.getOrNull(3)?.toIntOrNull() ?: 150
    val push = (args.getOrNull(4)?.toDoubleOrNull() ?: 1.0) * CrowdScene.PUSH
    // `game` — мышцы и мягкость вперемешку, `patches` — сплошными участками (CrowdScene.gameLike).
    val patches = args.getOrNull(5) == "patches"
    val game = args.getOrNull(5) == "game" || patches

    val scene = CrowdScene(cells)
    val side = scene.side
    val world = PhysicsWorld(PhysicsConfig(), workers = if (workers > 1) PhysicsWorkers.shared(workers) else null,
        scale = CrowdScene.SCALE)
    world.state.quiet = true
    world.sleepOn = false
    world.setBounds(0.0f, 0.0f, side.toFloat(), side.toFloat())
    for (body in scene.bodies) world.addBody(body)
    if (game) CrowdScene.gameLike(world, patches = patches)

    val bodies = scene.bodies.size
    val state = world.state
    val totalCells = (0 until bodies).sumOf { world.bodyCount(it) }
    val linksBefore = state.conCount

    val times = DoubleArray(measure)
    val torn = IntArray(measure)
    val stageOfTick = Array(measure) { LongArray(state.dbgTimeNs.size) }
    var hash = CrowdScene.HASH_SEED
    for (tick in 0 until warm + measure) {
        for (b in 0 until bodies) {
            val ax = scene.pushX(b, tick, push).toFloat()
            val ay = scene.pushY(b, tick, push).toFloat()
            val first = world.bodyFirst(b)
            for (i in first until first + world.bodyCount(b)) { state.vx[i] += ax; state.vy[i] += ay }
        }
        if (tick == warm) { state.dbgTimeOn = true; state.dbgTimeNs.fill(0) }
        val linksAtStart = state.conCount
        val stagesBefore = state.dbgTimeNs.copyOf()
        val start = System.nanoTime()
        world.step()
        if (tick >= warm) {
            times[tick - warm] = (System.nanoTime() - start) / 1e6; torn[tick - warm] = linksAtStart - state.conCount
            for (k in stagesBefore.indices) stageOfTick[tick - warm][k] = state.dbgTimeNs[k] - stagesBefore[k]
        }
        if (tick % 10 == 9) hash = CrowdScene.hash(hash, state.n, state.px, state.py, state.vx, state.vy)
    }
    state.dbgTimeOn = false
    hash = CrowdScene.hash(hash, state.n, state.px, state.py, state.vx, state.vy)

    val mean = times.average()
    val sorted = times.sorted()
    var speed = 0.0
    var live = 0
    for (i in 0 until state.n) if (!state.cellDead[i]) { speed += sqrt(state.vx[i] * state.vx[i] + state.vy[i] * state.vy[i]); live++ }
    println("CROWD клеток=$totalCells тел=$bodies мир=%.0f подшагов=$SUBSTEPS воркеров=$workers прогрев=$warm замер=$measure".format(side / CrowdScene.PHYSICS_PER_GAME) +
        if (patches) " режим=как_в_игре_участками" else if (game) " режим=как_в_игре" else "")
    println("CROWD мс/тик=%.2f медиана=%.2f p95=%.2f UPS=%.1f мкс_на_клетку=%.2f".format(
        mean, sorted[measure / 2], sorted[((measure - 1) * 0.95).toInt()], 1000 / mean, mean * 1000 / totalCells))
    println("CROWD скорость=%.3f связи/тик живых_клеток=$live порвано_связей=${linksBefore - state.conCount} пересборок_покоя=${state.contacts?.restFullRebuilds ?: 0}"
        .format(speed / live * (1.0 / 30) / CrowdScene.LINK))
    println("CROWD_HASH %016x".format(hash))
    println("CROWD_SLOW " + times.indices.sortedByDescending { times[it] }.take(10).joinToString(" ") { "тик%d:%.0fмс/порвано%d".format(it, times[it], torn[it]) })
    println("CROWD_TORN тиков с разрывами %d, среднее в них %.1f мс, без разрывов %.1f мс".format(torn.count { it > 0 },
        times.indices.filter { torn[it] > 0 }.map { times[it] }.average(), times.indices.filter { torn[it] == 0 }.map { times[it] }.average()))
    for (t in times.indices.sortedByDescending { times[it] }.take(3)) println("CROWD_SLOWTICK $t %.0f мс: ".format(times[t]) +
        stageOfTick[t].indices.filter { stageOfTick[t][it] > 3_000_000 }.joinToString(", ") { "${state.dbgTimeNames[it]} %.0f".format(stageOfTick[t][it] / 1e6) })
    var accounted = 0.0
    for (k in state.dbgTimeNs.indices) if (state.dbgTimeNs[k] != 0L) {
        val ms = state.dbgTimeNs[k] / 1e6 / measure
        accounted += ms
        println("CROWD_STAGE %-26s %8.3f мс  %5.1f%%".format(state.dbgTimeNames[k], ms, ms / mean * 100))
    }
    println("CROWD_STAGE %-26s %8.3f мс  %5.1f%%".format("(вне таймеров)", mean - accounted, (mean - accounted) / mean * 100))
}
