package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.RustDigest
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.stands.GenomeBodies
import io.github.some_example_name.physics.lab.stands.StandBody
import io.github.some_example_name.physics.lab.stands.StandWorld
import java.util.Locale
import kotlin.math.sqrt

/**
 * KOTLIN ПРОТИВ RUST НА ТЕЛАХ ИГРОКОВ (RUST_PORT.md, веха 7): та же сцена, что у genomeBench способом `one` — толпа
 * настоящих геномов, мышцы «дышат», тела не встречаются, сон выключен, — в настройке игры: Kotlin и Rust с одним
 * числом воркеров, острова у каждого по своему замеру (ISLANDS=auto; выбор у каждого свой, биты от него не зависят).
 *
 * Мир Rust — двойник мира стенда (тень, `-Dgenomeia.physics.rust=shadow`): все входы тика (мышцы, кости) доходят до
 * обоих миров методами PhysicsWorld, а шагают они раздельно (`rustTwinSeparateStep`), каждый под своим таймером. Порядок
 * шагов чередуется по тикам (Kotlin, Rust / Rust, Kotlin — A B B A): дрейф частоты и нагрев ложатся на обоих поровну.
 * В таймер Kotlin входит только шаг мира (StandWorld.lastStepNanos), у Rust — один вызов JNI worldStep.
 *
 * Замер имеет смысл, только если Rust посчитал ту же физику: каждые 30 тиков и в конце отпечатки состояния сверяются
 * (RustDigest; при островах Kotlin — без кэша широкой фазы и списка касаний подшага), расхождение останавливает стенд.
 *
 * Запуск: gradlew :physics-lab:rustGenomeBench -Pcells=20000 -Pworkers=8 -Pwarm=60 -Pmeasure=120 [-PmaxBody=6000]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    // Двойник на Rust заводится каждому миру физики — до первого обращения к RustShadow.
    System.setProperty("genomeia.physics.rust", "shadow")
    val cells = args.getOrNull(0)?.toIntOrNull() ?: 20_000
    val workers = args.getOrNull(1)?.toIntOrNull() ?: 8
    val warm = args.getOrNull(2)?.toIntOrNull() ?: 60
    val measure = (args.getOrNull(3)?.toIntOrNull() ?: 120).coerceAtLeast(2)
    val maxBody = args.getOrNull(4)?.toIntOrNull() ?: 6000
    val minBody = args.getOrNull(5)?.toIntOrNull() ?: 20

    val picked = pickBodies(cells, minBody, maxBody)
    require(picked.isNotEmpty()) { "нет тел от $minBody до $maxBody клеток" }
    val pool = if (workers > 1) PhysicsWorkers.shared(workers) else null
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
        val mx = (x.min() + x.max()) / 2; val my = (y.min() + y.max()) / 2
        for (k in shapes.indices) { x[k] -= mx; y[k] -= my }
    }
    val stand = StandWorld(pool).also { it.noSleep() }
    val bodies = ArrayList<StandBody>()
    for (k in shapes.indices) bodies.add(stand.body(shapes[k], x[k], y[k]))
    val world = stand.world
    val rust = checkNotNull(world.rustTwin) { "у мира нет двойника на Rust: библиотеки нет или тень не включилась" }
    world.rustTwinSeparateStep = true
    val state = world.state

    fun check(label: String) {
        val diff = RustDigest.firstDifference(RustDigest.of(state), rust.digest(), RustDigest.ignoredFor(state))
        check(diff == null) { "Rust разошёлся с Kotlin ($label): $diff" }
    }
    check("после сборки")

    val kt = DoubleArray(measure); val rt = DoubleArray(measure)
    // Острова каждый выбирает по своему замеру (ISLANDS=auto): в скольких тиках замера они были и сколько их за тик.
    var islandTicks = 0; var islandSum = 0L
    var rustIslandTicks = 0; var rustIslandSum = 0L
    for (tick in 0 until warm + measure) {
        for (k in shapes.indices) {
            if (shapes[k].muscleGroups == 0) continue
            val phase = 2 * Math.PI * tick / BREATH_TICKS + 0.7 * k
            stand.muscles(bodies[k], (0.925 + 0.075 * StrictMath.cos(phase)).toFloat())
        }
        var kNs = 0L; var rNs = 0L
        fun stepK() { stand.step(); kNs = stand.lastStepNanos }
        fun stepR() { rust.syncParams(state); val t0 = System.nanoTime(); rust.step(DT); rNs = System.nanoTime() - t0 }
        if (tick % 2 == 0) { stepK(); stepR() } else { stepR(); stepK() }
        if (tick >= warm) {
            kt[tick - warm] = kNs / 1e6; rt[tick - warm] = rNs / 1e6
            if (state.islandsLastTick > 0) { islandTicks++; islandSum += state.islandsLastTick }
            val ri = rust.islandsLastTick
            if (ri > 0) { rustIslandTicks++; rustIslandSum += ri }
        }
        if (tick % 30 == 29) check("тик ${tick + 1}")
    }
    check("в конце")

    fun stats(a: DoubleArray): String {
        val s = a.sorted()
        return "мс/тик=%.2f медиана=%.2f p95=%.2f мин=%.2f".format(a.average(), s[a.size / 2], s[((a.size - 1) * 0.95).toInt()], s[0])
    }
    val total = shapes.sumOf { it.count }
    println("RUST_GENOME тел=${shapes.size} клеток=$total частиц=${state.n} подшагов=$SUBSTEPS воркеров=$workers " +
        "прогрев=$warm замер=$measure тиков_с_островами_Kotlin=$islandTicks/$measure" +
        (if (islandTicks > 0) " (островов за такой тик %.1f)".format(islandSum.toDouble() / islandTicks) else "") +
        " тиков_с_островами_Rust=$rustIslandTicks/$measure" +
        (if (rustIslandTicks > 0) " (%.1f, откатов за всё время ${rust.islandAborts})".format(
            rustIslandSum.toDouble() / rustIslandTicks) else ""))
    println("RUST_GENOME Kotlin ${stats(kt)}")
    println("RUST_GENOME Rust   ${stats(rt)}")
    println("RUST_GENOME Kotlin/Rust по среднему %.2f, по медиане %.2f; отпечатки совпали (каждые 30 тиков и в конце)".format(
        kt.average() / rt.average(), kt.sorted()[measure / 2] / rt.sorted()[measure / 2]))
}

/** Запас между телами, единиц игры (как у genomeBench). */
private const val MARGIN = 30.0

/** Тиков на вдох и выдох (как у genomeBench). */
private const val BREATH_TICKS = 20
