package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.PhysicsJob
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.stands.GenomeBodies
import io.github.some_example_name.physics.lab.stands.GenomeEntry
import io.github.some_example_name.physics.lab.stands.StandBody
import io.github.some_example_name.physics.lab.stands.StandWorld
import java.util.Locale
import java.util.Random
import kotlin.math.sqrt

/**
 * МИР ИЗ НАСТОЯЩИХ ГЕНОМОВ (24.11.2026, находка 40): цена тика на телах игроков, а не на кругах толпы.
 *
 * ПОВОД. Автор: «По поводу волн и островов и их целесообразности проанализируй список геномов который был в стендах,
 * это по сути реальные геномы от реальных игроков из них и таких же схожих геномов и будет состоять мир». У кругов толпы
 * (CrowdScene) связи идут подряд по номерам частиц, костей нет, а мышцы расставлены рукой; у настоящих тел порядок связей
 * — какой дал редактор, мышцы и кости — какие нарисовал игрок.
 *
 * СЦЕНА. Тела из библиотеки стендов (GenomeBodies, 158 тел от 3 до 10 498 клеток), взятые в случайном порядке с постоянным
 * зерном, пока не наберётся нужное число клеток; каждое стоит взрослым, как его ставит игра (StandWorld: кости включены,
 * мышцам задано волокно). Тела расставлены рядами с запасом и не встречаются: мышцы всех тел сжимаются и отпускаются
 * разом («дыхание», команда 0.85…1.0, у каждого тела своя фаза) — работа у мышечного пути ядра настоящая, а тело никуда
 * не плывёт. Сон выключен.
 *
 * ДВА СПОСОБА, как у islandBench: `one` — один мир с воркерами физики; `split` — мир на тело, миры шагают по воркерам
 * (оценка тика «по островам»).
 *
 * Запуск: genomeBench клеток воркеров прогрев замер one|split [наибольшее тело=6000] [наименьшее=20] [одно тело: имя]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val cells = args.getOrNull(0)?.toIntOrNull() ?: 20_000
    val workers = args.getOrNull(1)?.toIntOrNull() ?: 8
    val warm = args.getOrNull(2)?.toIntOrNull() ?: 60
    val measure = (args.getOrNull(3)?.toIntOrNull() ?: 120).coerceAtLeast(1)
    val split = args.getOrNull(4) == "split"
    val maxBody = args.getOrNull(5)?.toIntOrNull() ?: 6000
    val minBody = args.getOrNull(6)?.toIntOrNull() ?: 20
    val only = args.getOrNull(7)?.takeIf { it.isNotBlank() && it != "-" }

    val picked = pickBodies(cells, minBody, maxBody, only)
    require(picked.isNotEmpty()) { "нет тел от $minBody до $maxBody клеток" + (only?.let { " с именем $it" } ?: "") }
    val pool = if (workers > 1) PhysicsWorkers.shared(workers) else null

    // Ряды с запасом между телами: полуразмеры тела — по его клеткам в позе покоя.
    val shapes = picked.map { GenomeBodies.shape(it.slug) }
    val halfW = DoubleArray(shapes.size) { k -> shapes[k].lx.maxOf { kotlin.math.abs(it) } + MARGIN / 2 }
    val halfH = DoubleArray(shapes.size) { k -> shapes[k].ly.maxOf { kotlin.math.abs(it) } + MARGIN / 2 }
    val rowWidth = sqrt(shapes.indices.sumOf { 4 * halfW[it] * halfH[it] })
    val x = DoubleArray(shapes.size); val y = DoubleArray(shapes.size)
    run {
        var cx = 0.0; var cy = 0.0; var rowH = 0.0
        for (k in shapes.indices) {
            if (cx > 0.0 && cx + 2 * halfW[k] > rowWidth) { cx = 0.0; cy += rowH; rowH = 0.0 }
            x[k] = cx + halfW[k]; y[k] = cy + halfH[k]
            cx += 2 * halfW[k]; if (2 * halfH[k] > rowH) rowH = 2 * halfH[k]
        }
        // Середина раскладки — в начало координат: float точнее всего у нуля.
        val mx = (x.min() + x.max()) / 2; val my = (y.min() + y.max()) / 2
        for (k in shapes.indices) { x[k] -= mx; y[k] -= my }
    }

    val worlds = ArrayList<StandWorld>()
    val bodies = ArrayList<StandBody>()
    val worldOf = IntArray(shapes.size)
    if (split) {
        for (k in shapes.indices) {
            val w = StandWorld(null).also { it.noSleep() }
            bodies.add(w.body(shapes[k], x[k], y[k])); worldOf[k] = worlds.size; worlds.add(w)
        }
    } else {
        val w = StandWorld(pool).also { it.noSleep() }
        for (k in shapes.indices) bodies.add(w.body(shapes[k], x[k], y[k]))
        worlds.add(w)
    }
    // Раздача миров — от тяжёлых к лёгким: последний кусок у барьера — самый короткий.
    val order = shapes.indices.sortedByDescending { shapes[it].count }.toIntArray()
    val stepJob = PhysicsJob { k, _ -> worlds[worldOf[order[k]]].step() }

    val total = shapes.sumOf { it.count }
    val times = DoubleArray(measure)
    var hash = CrowdScene.HASH_SEED
    val state0 = worlds[0].world.state
    for (tick in 0 until warm + measure) {
        // «Дыхание»: все мышцы тела сжимаются и отпускаются разом, у каждого тела своя фаза.
        for (k in shapes.indices) {
            if (shapes[k].muscleGroups == 0) continue
            val phase = 2 * Math.PI * tick / BREATH_TICKS + 0.7 * k
            worlds[worldOf[k]].muscles(bodies[k], (0.925 + 0.075 * StrictMath.cos(phase)).toFloat())
        }
        if (tick == warm && !split) { state0.dbgTimeOn = true; state0.dbgTimeNs.fill(0) }
        val start = System.nanoTime()
        if (!split) worlds[0].step()
        else if (pool != null) pool.run(shapes.size, stepJob)
        else for (k in order) worlds[worldOf[k]].step()
        if (tick >= warm) times[tick - warm] = (System.nanoTime() - start) / 1e6
        if (tick % 10 == 9) for (w in worlds) { val s = w.world.state; hash = CrowdScene.hash(hash, s.n, s.px, s.py, s.vx, s.vy) }
    }
    state0.dbgTimeOn = false

    var live = 0; var dead = 0; var links = 0
    for (w in worlds) { val s = w.world.state; for (i in 0 until s.n) if (s.cellDead[i]) dead++ else live++; links += s.conCount }
    val mean = times.average(); val sorted = times.sorted()
    println("GENOME способ=${if (split) "split" else "one"} тел=${shapes.size} клеток=$total мышечных=${shapes.sumOf { it.muscleCount }} " +
        "костных=${shapes.sumOf { it.boneCount }} самое_большое=${shapes.maxOf { it.count }} подшагов=$SUBSTEPS воркеров=$workers прогрев=$warm замер=$measure")
    println("GENOME мс/тик=%.2f медиана=%.2f p95=%.2f мин=%.2f UPS=%.1f мкс_на_клетку=%.2f".format(
        mean, sorted[measure / 2], sorted[((measure - 1) * 0.95).toInt()], sorted[0], 1000 / mean, mean * 1000 / total))
    // «Неживых мест» — не гибель: у каждого мира стенда свой запас свободных мест (256), у способа split миров много.
    // Гибель видна по «живых клеток» против «клеток» в первой строке.
    println("GENOME живых_клеток=$live неживых_мест=$dead связей_решателя=$links")
    println("GENOME_HASH %016x".format(hash))
    if (!split) {
        var accounted = 0.0
        for (k in state0.dbgTimeNs.indices) if (state0.dbgTimeNs[k] != 0L) {
            val ms = state0.dbgTimeNs[k] / 1e6 / measure
            accounted += ms
            if (ms >= 0.005) println("GENOME_STAGE %-26s %8.3f мс  %5.1f%%".format(state0.dbgTimeNames[k], ms, ms / mean * 100))
        }
        println("GENOME_STAGE %-26s %8.3f мс  %5.1f%%".format("(вне таймеров)", mean - accounted, (mean - accounted) / mean * 100))
    }
}

/** Запас между телами, единиц игры: тела «дышат» на месте и не встречаются. */
private const val MARGIN = 30.0

/** Тиков на вдох и выдох: 1.5 раза в секунду, как гребок организмов игры. */
private const val BREATH_TICKS = 20

/**
 * Тела мира: библиотека стендов в случайном порядке с постоянным зерном, по кругу, пока не наберётся [cells] клеток.
 * [only] — одно тело по имени файла (столько копий, сколько нужно клеток).
 */
internal fun pickBodies(cells: Int, minBody: Int, maxBody: Int, only: String? = null): List<GenomeEntry> {
    val pool = GenomeBodies.entries.filter { if (only != null) it.slug == only else it.cells in minBody..maxBody }.toMutableList()
    if (pool.isEmpty()) return emptyList()
    pool.shuffle(Random(20261124L))
    val picked = ArrayList<GenomeEntry>()
    var sum = 0; var k = 0
    while (sum < cells) { val e = pool[k % pool.size]; picked.add(e); sum += e.cells; k++ }
    return picked
}
