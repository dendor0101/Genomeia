package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsJob
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.SUBSTEPS
import java.util.Locale

/**
 * ОПЫТ «ОСТРОВА» (23.11.2026, находка 39): сколько даст тик, в котором каждое тело считает ВСЕ свои подшаги подряд
 * на одном ядре, а потоки сходятся раз в тик, а не десять раз за подшаг.
 *
 * ПОВОД. Автор: «воркеры долго находятся в ожидании, может как-то можно повысить локальность кэша». Сейчас подшаг — это
 * около десятка общих стадий (блоки двух чётностей, швы, подготовка и решение касаний, проходы по скоростям), и на каждой
 * все воркеры сходятся на барьере; при 32 подшагах — больше трёхсот барьеров за тик. Между барьерами данные тела успевают
 * уйти из L1/L2: за подшаг проходится весь мир.
 *
 * ЧТО МЕРИТ. Та же сцена толпы (CrowdScene) двумя способами:
 *   one   — один мир со всеми телами и воркерами физики, как в игре;
 *   split — каждое тело в СВОЁМ мире без воркеров; миры шагают по воркерам, раздача общим курсором от тяжёлых к лёгким,
 *           один барьер на тик. Это не правка решателя, а оценка сверху: чего можно ждать от тика «по островам».
 * Чтобы тела не встречались, мир берётся шире: восьмой параметр — сторона мира в единицах игры. Работа у способов
 * одна и та же, но биты совпадают не у всех тел: в замере 23.11 (5 тысяч клеток, 90 тиков) шесть тел из семи дали в
 * своём мире те же биты, что в общем, а одно (995 клеток) разошлось между 2-м и 20-м тиком — что-то в общем мире
 * связывает тела и без касаний, причина не разобрана. Поэтому ISLAND_HASH способов может различаться; девятый
 * параметр `bodies` печатает сумму каждого тела — видно, у каких.
 *
 * ЧЕГО ОЦЕНКА НЕ ВИДИТ: у острова из нескольких касающихся тел подшаги общие; в давке остров один на весь мир, и там
 * остаётся нынешняя схема. У мира одного тела своя сетка касаний и свой учёт импульса — накладные расходы на тик, которых
 * у настоящей правки могло бы и не быть.
 *
 * Запуск: islandBench клеток воркеров прогрев замер толчок one|split [game|patches|crowd] [мир] [bodies]
 * (`bodies` — сумма каждого тела отдельно).
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val cells = args.getOrNull(0)?.toIntOrNull() ?: 20_000
    val workers = args.getOrNull(1)?.toIntOrNull() ?: 8
    val warm = args.getOrNull(2)?.toIntOrNull() ?: 100
    val measure = args.getOrNull(3)?.toIntOrNull() ?: 200
    val push = (args.getOrNull(4)?.toDoubleOrNull() ?: 1.0) * CrowdScene.PUSH
    val split = args.getOrNull(5) == "split"
    // `game` — мышцы и мягкость вперемешку, `patches` — сплошными участками (CrowdScene.gameLike).
    val patches = args.getOrNull(6) == "patches"
    val game = args.getOrNull(6) == "game" || patches
    val minWorld = args.getOrNull(7)?.toDoubleOrNull() ?: 1024.0

    val scene = CrowdScene(cells, minWorld)
    val side = scene.side.toFloat()
    val bodies = scene.bodies.size
    val pool = if (workers > 1) PhysicsWorkers.shared(workers) else null

    fun newWorld(w: PhysicsWorkers?) = PhysicsWorld(PhysicsConfig(), workers = w, scale = CrowdScene.SCALE).also {
        it.state.quiet = true; it.sleepOn = false; it.setBounds(0.0f, 0.0f, side, side)
    }

    // Один мир на все тела или мир на тело; first[b] — номер первой частицы тела в общем счёте.
    val worlds = ArrayList<PhysicsWorld>()
    val first = IntArray(bodies + 1)
    if (split) {
        for (b in 0 until bodies) {
            val w = newWorld(null)
            w.addBody(scene.bodies[b])
            first[b + 1] = first[b] + w.bodyCount(0)
            if (game) CrowdScene.gameLike(w, first[b], patches)
            worlds.add(w)
        }
    } else {
        val w = newWorld(pool)
        for (body in scene.bodies) w.addBody(body)
        for (b in 0 until bodies) first[b + 1] = first[b] + w.bodyCount(b)
        // Тела в мире стоят подряд, без зазоров: иначе сумма по первым частицам мира считала бы не то.
        for (b in 0 until bodies) check(w.bodyFirst(b) == first[b]) { "тело $b начинается не подряд: ${w.bodyFirst(b)} вместо ${first[b]}" }
        check(w.state.n == first[bodies]) { "в мире ${w.state.n} частиц, а в телах ${first[bodies]}" }
        if (game) CrowdScene.gameLike(w, patches = patches)
        worlds.add(w)
    }
    val total = first[bodies]
    // Раздача миров — от тяжёлых к лёгким: последний кусок у барьера — самый короткий.
    val order = (0 until bodies).sortedByDescending { first[it + 1] - first[it] }.toIntArray()
    val stepJob = PhysicsJob { k, _ -> worlds[order[k]].step() }

    // Сумма мира — по тем же массивам и в том же порядке частиц, что у одного мира.
    val px = FloatArray(total); val py = FloatArray(total); val vx = FloatArray(total); val vy = FloatArray(total)
    fun gather() {
        if (!split) {
            val s = worlds[0].state
            System.arraycopy(s.px, 0, px, 0, total); System.arraycopy(s.py, 0, py, 0, total)
            System.arraycopy(s.vx, 0, vx, 0, total); System.arraycopy(s.vy, 0, vy, 0, total)
            return
        }
        for (b in 0 until bodies) {
            val s = worlds[b].state; val n = first[b + 1] - first[b]
            System.arraycopy(s.px, 0, px, first[b], n); System.arraycopy(s.py, 0, py, first[b], n)
            System.arraycopy(s.vx, 0, vx, first[b], n); System.arraycopy(s.vy, 0, vy, first[b], n)
        }
    }

    val times = DoubleArray(measure)
    var hash = CrowdScene.HASH_SEED
    for (tick in 0 until warm + measure) {
        for (b in 0 until bodies) {
            val ax = scene.pushX(b, tick, push).toFloat()
            val ay = scene.pushY(b, tick, push).toFloat()
            val s = (if (split) worlds[b] else worlds[0]).state
            val from = if (split) 0 else first[b]
            val to = from + first[b + 1] - first[b]
            val svx = s.vx; val svy = s.vy
            for (i in from until to) { svx[i] += ax; svy[i] += ay }
        }
        val start = System.nanoTime()
        if (!split) worlds[0].step()
        else if (pool != null) pool.run(bodies, stepJob)
        else for (k in 0 until bodies) worlds[order[k]].step()
        if (tick >= warm) times[tick - warm] = (System.nanoTime() - start) / 1e6
        if (tick % 10 == 9) { gather(); hash = CrowdScene.hash(hash, total, px, py, vx, vy) }
    }
    gather(); hash = CrowdScene.hash(hash, total, px, py, vx, vy)

    val mean = times.average()
    val sorted = times.sorted()
    println("ISLAND способ=${if (split) "split" else "one"} клеток=$total тел=$bodies мир=%.0f подшагов=$SUBSTEPS воркеров=$workers прогрев=$warm замер=$measure".format(side / CrowdScene.PHYSICS_PER_GAME) +
        if (patches) " режим=как_в_игре_участками" else if (game) " режим=как_в_игре" else "")
    println("ISLAND мс/тик=%.2f медиана=%.2f p95=%.2f мин=%.2f UPS=%.1f".format(
        mean, sorted[measure / 2], sorted[((measure - 1) * 0.95).toInt()], sorted[0], 1000 / mean))
    println("ISLAND_HASH %016x".format(hash))
    // Девятый параметр `bodies` — сумма каждого тела отдельно (конец прогона): у какого тела способы разошлись.
    if (args.getOrNull(8) == "bodies") for (b in 0 until bodies) {
        val n = first[b + 1] - first[b]
        var h = CrowdScene.HASH_SEED
        for (a in arrayOf(px, py, vx, vy)) for (i in first[b] until first[b + 1]) { h = (h xor a[i].toRawBits().toLong()) * 1099511628211L; h = h xor (h ushr 29) }
        println("ISLAND_BODY %3d клеток %5d %016x px0=%.9g vx0=%.9g".format(b, n, h, px[first[b]], vx[first[b]]))
    }
}
