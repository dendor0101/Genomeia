package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.SUBSTEPS
import java.util.Locale
import kotlin.math.sqrt

/**
 * ЦЕНА ЗАКОНА ФИЗИКИ: одна сцена толпы в нескольких мирах с разными настройками, шаг в шаг в одном процессе.
 * Работает и на ПК, и на телефоне (по adb, см. android-bench.gradle).
 *
 * ЗАЧЕМ. Цену правки (любой ключ настроек мира; так мерились гашение скоростей и клин, убранные 24.11) нельзя мерить двумя запусками: на ПК
 * замеры разных часов расходятся на 5–15%, на телефоне Kotlin под ART гуляет между запусками на 15–25% (JIT
 * каждый раз компилирует по-своему; RUST_PORT.md ветки alpha-0.2.4-rust-physics). Здесь миры-варианты шагают
 * блоками по `block` тиков вперемешку: блок одного мира, затем те же тики у следующего; от блока к блоку
 * очередь меняется (для двух и трёх миров — схема Уильямса: каждый идёт первым и следом за каждым другим
 * одинаково часто). Дрейф частоты процессора, нагрев и соседние программы ложатся на всех поровну, а
 * скомпилированный код у всех миров один и тот же.
 *
 * ВАРИАНТЫ — `имя:КЛЮЧ=значение,КЛЮЧ=значение`, через «+»; с первым сравнивают остальные. Ключи — настройки
 * мира (PhysicsConfig). Ключи-переменные процесса так сравнить нельзя: их значение — то, что выставил последний
 * созданный мир (такими были ключи опыта мышц MX_*, убраны 25.11).
 *
 * МИРЫ РАСХОДЯТСЯ: закон другой — и движение другое (где клеток гибнет больше, там их уже не надо считать). Поэтому рядом со временем печатаются живые клетки, порванные связи, скорость и число касаний.
 *
 * В таймере только шаг мира; толчок тел, контрольная сумма и вытеснение кэшей — вне его, цикл замера не
 * выделяет память. stages=1 — ещё и таймеры стадий (сами стоят времени: скорость брать из прогона без них).
 * Сумма мира первого варианта без ключей при тех же cells, push и warm + measure равна CROWD_HASH у
 * crowdBench; на телефоне и на ПК суммы обязаны совпадать — это проверка детерминизма float между платформами.
 *
 * ПК:      gradlew :physics-lab:lawCostBench -Pcells=20000 -Pworkers=8
 * Телефон: gradlew :physics-lab:lawCostBenchAndroid -Pcells=5000 -Pworkers=1   (несколько телефонов: -Pdevice=…)
 * Общие ключи: -Pwarm=150 -Pmeasure=180 -Pblock=10 -Ppush=1 -Pworld=256 -Pstages=0
 *              -Pvariants=base+soft:CT_SOFT=1e-7      (по умолчанию — один мир без ключей)
 * world — наименьшая сторона мира в единицах игры: 256 — тела почти не встречаются, меньше — давка
 * (CrowdScene). measure стоит брать кратным полному циклу очереди: 2 блока для двух миров, 6 для трёх.
 */
private const val DEFAULT_VARIANTS = "base"

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val cells = args.getOrNull(0)?.toIntOrNull() ?: 5000
    val workers = args.getOrNull(1)?.toIntOrNull() ?: 1
    val warm = args.getOrNull(2)?.toIntOrNull() ?: 150
    val measure = (args.getOrNull(3)?.toIntOrNull() ?: 180).coerceAtLeast(1)
    val block = (args.getOrNull(4)?.toIntOrNull() ?: 10).coerceAtLeast(1)
    val pushScale = args.getOrNull(5)?.toDoubleOrNull() ?: 1.0
    val minWorld = args.getOrNull(6)?.toDoubleOrNull() ?: 256.0
    val stages = (args.getOrNull(7)?.toIntOrNull() ?: 0) != 0
    val variants = parseVariants(args.getOrNull(8)?.takeIf { it.isNotBlank() } ?: DEFAULT_VARIANTS)
    // Режим «как в игре» (-Pgame=1): мышцы множителями клеток, мягкость, волокна — см. CrowdScene.gameLike.
    val game = args.getOrNull(9) == "game"

    val scene = CrowdScene(cells, minWorld)
    val pool = if (workers > 1) PhysicsWorkers.shared(workers) else null
    val worlds = variants.map { LawWorld(it, scene, pool, game) }
    val run = LawLockstep(scene, worlds, pushScale * CrowdScene.PUSH, warm, measure, block, stages)
    run.run()

    val first = worlds[0]
    println("LAWCOST клеток=${first.state.n} тел=${scene.bodies.size} мир=%.0f подшагов=$SUBSTEPS воркеров=$workers прогрев=$warm замер=$measure блок=$block толчок=%.2f"
        .format(scene.side / CrowdScene.PHYSICS_PER_GAME, pushScale) + if (game) " режим=как_в_игре" else "")
    val width = worlds.maxOf { it.variant.name.length }
    for ((e, w) in worlds.withIndex()) run.report(w, run.ms[e], width)
    for (e in 1 until worlds.size) {
        val ratios = run.blockRatio(0, e).sorted()
        val byMean = run.ms[0].average() / run.ms[e].average()
        println(("LAWCOST %s дороже, чем %s: по среднему ×%.3f (%+.1f%%), по медиане ×%.3f; по блокам: медиана ×%.3f, " +
            "от ×%.3f до ×%.3f (блоков ${ratios.size})").format(first.variant.name, worlds[e].variant.name, byMean, (byMean - 1) * 100,
            median(run.ms[0]) / median(run.ms[e]), ratios[ratios.size / 2], ratios.first(), ratios.last()))
    }
    // По порядку блоков: видно, догрелся ли JIT к замеру и не уплыла ли частота процессора.
    println("LAWCOST по блокам, мс/тик ${worlds.joinToString("/") { it.variant.name }}: " +
        run.blockMs.joinToString(" ") { p -> p.joinToString("/") { "%.1f".format(it) } })
    if (stages) run.reportStages()
    for (w in worlds) println("LAWCOST_HASH %s %016x".format(w.variant.name.padEnd(width), w.hash))
    println("LAWCOST JVM ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}, " +
        "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ядер ${Runtime.getRuntime().availableProcessors()}")
}

private class LawVariant(val name: String, val keys: Map<String, String>)

private fun parseVariants(spec: String): List<LawVariant> = spec.split('+').filter { it.isNotBlank() }.map { part ->
    val name = part.substringBefore(':').trim()
    val keys = LinkedHashMap<String, String>()
    if (':' in part) for (kv in part.substringAfter(':').split(',')) {
        val k = kv.substringBefore('=').trim()
        require(k.isNotEmpty() && '=' in kv) { "вариант «$name»: ожидалось КЛЮЧ=значение, а не «$kv»" }
        keys[k] = kv.substringAfter('=').trim()
    }
    LawVariant(name, keys)
}.also { require(it.isNotEmpty()) { "нет ни одного варианта" } }

private fun median(a: DoubleArray): Double = a.sorted()[a.size / 2]

/** Мир одного варианта: та же сцена, свои настройки. */
private class LawWorld(val variant: LawVariant, scene: CrowdScene, workers: PhysicsWorkers?, game: Boolean) {
    val world = PhysicsWorld(PhysicsConfig(variant.keys), workers = workers, scale = CrowdScene.SCALE)
    val state = world.state
    val linksBefore: Int
    var hash = CrowdScene.HASH_SEED
    /** Касаний после шага, сумма по тикам замера. */
    var contacts = 0L

    init {
        state.quiet = true
        world.sleepOn = false
        world.setBounds(0.0f, 0.0f, scene.side.toFloat(), scene.side.toFloat())
        for (body in scene.bodies) world.addBody(body)
        if (game) CrowdScene.gameLike(world)
        linksBefore = state.conCount
    }

}

/** Миры одной сцены, шагающие шаг в шаг; времена тиков замера по мирам. */
private class LawLockstep(
    private val scene: CrowdScene,
    private val worlds: List<LawWorld>,
    private val push: Double,
    private val warm: Int,
    measure: Int,
    private val block: Int,
    private val stages: Boolean,
) {
    private val bodies = scene.bodies.size
    private val engines = worlds.size

    /**
     * ОЧЕРЕДЬ МИРОВ В БЛОКЕ — схема Уильямса: каждый мир идёт первым и следом за каждым другим одинаково
     * часто. Простой сдвиг по кругу для трёх миров так не умеет (один идёт сразу после первого в двух блоках
     * из трёх, другой — ни разу), а на телефоне сразу после шага фоновые потоки ART (JIT, сборка мусора)
     * ещё отнимают память — замечено в ветке alpha-0.2.4-rust-physics на двух одинаковых мирах.
     */
    private val orders: List<IntArray> = when (engines) {
        2 -> listOf(intArrayOf(0, 1), intArrayOf(1, 0))
        3 -> listOf(intArrayOf(0, 1, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1),
            intArrayOf(0, 2, 1), intArrayOf(2, 1, 0), intArrayOf(1, 0, 2))
        else -> List(engines) { s -> IntArray(engines) { (s + it) % engines } }
    }

    /** мс на тик замера по мирам. */
    val ms = Array(engines) { DoubleArray(measure) }
    /** Среднее мс/тик каждого мира по блокам замера, по порядку блоков. */
    val blockMs = ArrayList<DoubleArray>()

    fun run() {
        evictCaches()
        val total = warm + ms[0].size
        var tick = 0
        var round = 0
        while (tick < total) {
            if (stages && tick == warm) for (w in worlds) { w.state.dbgTimeNs.fill(0); w.state.dbgTimeOn = true }
            // Блок не пересекает границу прогрева и замера.
            val end = minOf(tick + block, if (tick < warm) warm else total)
            for (e in orders[round % orders.size]) step(worlds[e], ms[e], tick, end)
            if (tick >= warm) blockMs.add(DoubleArray(engines) { e -> sum(ms[e], tick - warm, end - warm) / (end - tick) })
            evictCaches()
            tick = end
            round++
        }
        for (w in worlds) {
            w.state.dbgTimeOn = false
            w.hash = CrowdScene.hash(w.hash, w.state.n, w.state.px, w.state.py, w.state.vx, w.state.vy)
        }
    }

    /** Тики [from, to) одного мира: толчок, как в crowdBench, затем шаг под таймером. */
    private fun step(w: LawWorld, times: DoubleArray, from: Int, to: Int) {
        val st = w.state
        val world = w.world
        for (tick in from until to) {
            for (b in 0 until bodies) {
                val ax = scene.pushX(b, tick, push).toFloat()
                val ay = scene.pushY(b, tick, push).toFloat()
                val first = world.bodyFirst(b)
                for (i in first until first + world.bodyCount(b)) { st.vx[i] += ax; st.vy[i] += ay }
            }
            val start = System.nanoTime()
            world.step()
            val ns = System.nanoTime() - start
            if (tick >= warm) { times[tick - warm] = ns / 1e6; w.contacts += st.contacts?.contactCount ?: 0 }
            if (tick % 10 == 9) w.hash = CrowdScene.hash(w.hash, st.n, st.px, st.py, st.vx, st.vy)
        }
    }

    /**
     * Вытеснение кэшей между блоками, вне таймера: проход по массиву больше любого кэша — и каждый блок
     * начинает одинаково холодным, какой бы мир ни шёл перед ним.
     */
    private fun evictCaches() {
        var s = 0L
        var i = 0
        while (i < sweep.size) { s += sweep[i]; i += 8 } // одна загрузка на линию 64 байта
        sweepSink += s
    }
    private val sweep = LongArray(4 shl 20) // 32 МБ: больше L3 ПК (16 МБ) и кэшей телефона
    /** Сумма прохода — чтобы JIT не выбросил его как бесполезный. */
    var sweepSink = 0L
        private set

    /** Отношение времени мира [slow] к миру [fast] по блокам замера. */
    fun blockRatio(slow: Int, fast: Int): List<Double> = blockMs.map { it[slow] / it[fast] }

    fun report(w: LawWorld, ms: DoubleArray, width: Int) {
        val st = w.state
        val sorted = ms.sorted()
        val mean = ms.average()
        var speed = 0.0
        var live = 0
        for (i in 0 until st.n) if (!st.cellDead[i]) { speed += sqrt(st.vx[i] * st.vx[i] + st.vy[i] * st.vy[i]); live++ }
        println(("LAWCOST %s мс/тик=%.2f медиана=%.2f p95=%.2f мин=%.2f макс=%.2f UPS=%.1f | живых=$live порвано=${w.linksBefore - st.conCount} " +
            "скорость=%.3f связи/тик касаний/тик=%.1f | ключи: %s").format(w.variant.name.padEnd(width), mean, sorted[sorted.size / 2],
            sorted[((sorted.size - 1) * 0.95).toInt()], sorted.first(), sorted.last(), 1000 / mean,
            if (live > 0) speed / live * (1.0 / 30) / CrowdScene.LINK else 0.0, w.contacts.toDouble() / ms.size,
            if (w.variant.keys.isEmpty()) "нет (закон игры)" else w.variant.keys.entries.joinToString(",") { "${it.key}=${it.value}" }))
    }

    /** Таблица стадий: мс за тик у каждого мира (только при stages=1). */
    fun reportStages() {
        val names = worlds[0].state.dbgTimeNames
        val ticks = ms[0].size
        val sums = DoubleArray(engines)
        println("LAWCOST_STAGE %-26s".format("стадия") + worlds.joinToString("") { " %10s".format(it.variant.name.take(10)) })
        for (k in names.indices) {
            val row = DoubleArray(engines) { e -> worlds[e].state.dbgTimeNs[k] / 1e6 / ticks }
            if (row.all { it == 0.0 }) continue
            for (e in 0 until engines) sums[e] += row[e]
            println("LAWCOST_STAGE %-26s".format(names[k]) + row.joinToString("") { " %10.3f".format(it) })
        }
        println("LAWCOST_STAGE %-26s".format("(вне таймеров)") +
            (0 until engines).joinToString("") { e -> " %10.3f".format(ms[e].average() - sums[e]) })
    }

    private fun sum(a: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i]
        return s
    }
}
