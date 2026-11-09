package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.WorldScale
import java.util.Locale
import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ТОЛПА ДЛЯ ЦЕЛИ «100k КЛЕТОК В 30 UPS». Тела без мышц, костей и нейросвязей, сон выключен:
 * треть клеток в телах по 300, треть по 1000, треть по 3000. Каждое тело толкает своя сила,
 * направление которой медленно поворачивается, поэтому тела всё время двигаются и бьются
 * друг о друга (в сетке листьев касаний не было вовсе: стадия контактов занимала 0.01 мс).
 *
 * Масштаб и единицы — как в игре (PhysicsUnits): клетка радиуса 0.5, связь 0.6 единиц игры.
 * Толчок и контрольная сумма вне таймера. Сумма мира печатается в конце и должна совпасть при
 * любом числе воркеров: так проверяется, что параллельность не поменяла физику.
 *
 * Запуск: gradlew :physics-lab:crowdBench -Pcells=100000 -Pworkers=8 -Pwarm=150 -Pmeasure=150
 */
private const val PHYSICS_PER_GAME = 0.048
private const val LINK = 0.6 * PHYSICS_PER_GAME
private const val RADIUS = 0.5 * PHYSICS_PER_GAME
private const val MIN_WORLD = 256 * PHYSICS_PER_GAME
private const val SEED = 20261109L
/** Разгон за тик при -Ppush=1, в единицах мира в секунду. Подобран так, чтобы тела не рвались. */
private const val PUSH = 0.012

private val SIZES = intArrayOf(300, 1000, 3000)

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val cells = args.getOrNull(0)?.toIntOrNull() ?: 100_000
    val workers = args.getOrNull(1)?.toIntOrNull() ?: 8
    val warm = args.getOrNull(2)?.toIntOrNull() ?: 150
    val measure = args.getOrNull(3)?.toIntOrNull() ?: 150
    val push = (args.getOrNull(4)?.toDoubleOrNull() ?: 1.0) * PUSH

    val random = Random(SEED)
    val sizes = ArrayList<Int>()
    for (size in SIZES) repeat(max(1, cells / SIZES.size / size)) { sizes.add(size) }
    sizes.shuffle(random)
    val templates = HashMap<Int, BodyDefinition>()
    for (size in SIZES) templates[size] = hexDisc(size)
    val radii = sizes.map { bodyRadius(templates.getValue(it)) + 2 * RADIUS }
    val (side, rows) = shelves(radii)

    val world = PhysicsWorld(PhysicsConfig(), workers = if (workers > 1) PhysicsWorkers.shared(workers) else null,
        scale = WorldScale(LINK.toFloat(), RADIUS * RADIUS))
    world.state.quiet = true
    world.sleepOn = false
    world.setBounds(0.0, 0.0, side, side)
    var y = 0.0
    val freeY = side - rows.sumOf { row -> row.maxOf { 2 * radii[it] } }
    for (row in rows) {
        val height = row.maxOf { 2 * radii[it] }
        y += freeY / (rows.size + 1)
        var x = 0.0
        val freeX = side - row.sumOf { 2 * radii[it] }
        for (k in row) {
            x += freeX / (row.size + 1) + radii[k]
            world.addBody(moved(templates.getValue(sizes[k]), x, y + height / 2))
            x += radii[k]
        }
        y += height
    }

    val bodies = sizes.size
    val phase = DoubleArray(bodies) { random.nextDouble() * 2 * Math.PI }
    val omega = DoubleArray(bodies) { (0.01 + random.nextDouble() * 0.03) * if (random.nextBoolean()) 1 else -1 }
    val state = world.state
    val totalCells = (0 until bodies).sumOf { world.bodyCount(it) }
    val linksBefore = state.conCount

    val times = DoubleArray(measure)
    val torn = IntArray(measure)
    val stageOfTick = Array(measure) { LongArray(state.dbgTimeNs.size) }
    var hash = -3750763034362895579L
    for (tick in 0 until warm + measure) {
        for (b in 0 until bodies) {
            val angle = phase[b] + omega[b] * tick
            val ax = push * StrictMath.cos(angle)
            val ay = push * StrictMath.sin(angle)
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
        if (tick % 10 == 9) hash = worldHash(hash, state.n, state.px, state.py, state.vx, state.vy)
    }
    state.dbgTimeOn = false
    hash = worldHash(hash, state.n, state.px, state.py, state.vx, state.vy)

    val mean = times.average()
    val sorted = times.sorted()
    var speed = 0.0
    var live = 0
    for (i in 0 until state.n) if (!state.cellDead[i]) { speed += sqrt(state.vx[i] * state.vx[i] + state.vy[i] * state.vy[i]); live++ }
    println("CROWD клеток=$totalCells тел=$bodies мир=%.0f подшагов=$SUBSTEPS воркеров=$workers прогрев=$warm замер=$measure".format(side / PHYSICS_PER_GAME))
    println("CROWD мс/тик=%.2f медиана=%.2f p95=%.2f UPS=%.1f мкс_на_клетку=%.2f".format(
        mean, sorted[measure / 2], sorted[((measure - 1) * 0.95).toInt()], 1000 / mean, mean * 1000 / totalCells))
    println("CROWD скорость=%.3f связи/тик живых_клеток=$live порвано_связей=${linksBefore - state.conCount} пересборок_покоя=${state.contacts?.restFullRebuilds ?: 0}"
        .format(speed / live * (1.0 / 30) / LINK))
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

private fun worldHash(seed: Long, n: Int, vararg arrays: DoubleArray): Long {
    var h = seed
    for (a in arrays) for (i in 0 until n) { h = (h xor a[i].toRawBits()) * 1099511628211L; h = h xor (h ushr 29) }
    return h
}

private fun bodyRadius(body: BodyDefinition): Double {
    var r2 = 0f
    for (i in 0 until body.count) r2 = maxOf(r2, body.x[i] * body.x[i] + body.y[i] * body.y[i])
    return sqrt(r2.toDouble())
}

/** Ряды тел: сторона мира (не меньше игрового мира 256) и номера тел по рядам. */
private fun shelves(radii: List<Double>): Pair<Double, List<List<Int>>> {
    var side = MIN_WORLD
    while (true) {
        val rows = ArrayList<List<Int>>()
        var row = ArrayList<Int>()
        var used = 0.0
        for (k in radii.indices) {
            if (used + 2 * radii[k] > side) { rows.add(row); row = ArrayList(); used = 0.0 }
            row.add(k); used += 2 * radii[k]
        }
        if (row.isNotEmpty()) rows.add(row)
        if (rows.sumOf { r -> r.maxOf { 2 * radii[it] } } <= side) return side to rows
        side *= 1.05
    }
}

private fun moved(body: BodyDefinition, x: Double, y: Double) = BodyDefinition(
    FloatArray(body.count) { body.x[it] + x.toFloat() }, FloatArray(body.count) { body.y[it] + y.toFloat() },
    body.radius, body.isBone, body.isMuscle, body.linkA, body.linkB, body.triA, body.triB, body.triC, body.triRestArea2,
    body.boneClusters, body.muscleClusters, body.scale)

/** Круг из треугольной решётки около [cells] клеток; порядок частиц — по рядам, лента ~ стороне. */
private fun hexDisc(cells: Int): BodyDefinition {
    val h = sqrt(3.0) / 2
    val reach = sqrt(cells * h / Math.PI)
    val span = reach.toInt() + 2
    val size = 4 * span + 1
    val slot = IntArray(size * size) { -1 }
    fun key(q: Int, r: Int) = (r + 2 * span) * size + q + 2 * span
    fun inside(q: Int, r: Int): Boolean {
        val px = q + r * 0.5; val py = r * h
        return px * px + py * py <= reach * reach
    }
    val tris = ArrayList<IntArray>()
    val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
    fun cell(q: Int, r: Int): Int {
        if (slot[key(q, r)] < 0) {
            slot[key(q, r)] = xs.size
            xs.add(((q + r * 0.5) * LINK).toFloat()); ys.add((r * h * LINK).toFloat())
        }
        return slot[key(q, r)]
    }
    for (r in -span..span) for (q in -2 * span..2 * span) {
        if (r + 1 > span || q + 1 > 2 * span) continue
        if (inside(q, r) && inside(q + 1, r) && inside(q, r + 1)) tris.add(intArrayOf(cell(q, r), cell(q + 1, r), cell(q, r + 1)))
        if (inside(q, r + 1) && inside(q + 1, r) && inside(q + 1, r + 1)) tris.add(intArrayOf(cell(q, r + 1), cell(q + 1, r), cell(q + 1, r + 1)))
    }
    val n = xs.size
    val edges = sortedSetOf<Long>()
    for (t in tris) for (k in 0 until 3) {
        val a = t[k]; val b = t[(k + 1) % 3]
        edges.add(minOf(a, b).toLong() * n + maxOf(a, b))
    }
    val x = FloatArray(n) { xs[it] }; val y = FloatArray(n) { ys[it] }
    val area = FloatArray(tris.size) { k ->
        val t = tris[k]
        (x[t[1]] - x[t[0]]) * (y[t[2]] - y[t[0]]) - (y[t[1]] - y[t[0]]) * (x[t[2]] - x[t[0]])
    }
    return BodyDefinition(x, y, FloatArray(n) { RADIUS.toFloat() }, BooleanArray(n), BooleanArray(n),
        edges.map { (it / n).toInt() }.toIntArray(), edges.map { (it % n).toInt() }.toIntArray(),
        IntArray(tris.size) { tris[it][0] }, IntArray(tris.size) { tris[it][1] }, IntArray(tris.size) { tris[it][2] },
        area, emptyArray(), emptyArray(), PHYSICS_PER_GAME.toFloat())
}
