package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.SceneFile
import io.github.some_example_name.physics.lab.session.DemoSettings
import java.util.Locale

/**
 * ВХОДЫ КЛИЕНТА-ИГРЫ (этап B1): мышца по клетке, масса и сопротивление по частице, рамка
 * мира, гибель клетки по причине игры. Каждый — по смыслу, плюс детерминизм там, где
 * вход меняется каждый тик.
 *
 * Запуск: ClientInputsCheck <сцена.scene> [воркеров]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val bodies = SceneFile.loadBodies(args.getOrElse(0) { "docs/physics/scenes/append-small.scene" })
    val workers = args.getOrNull(1)?.toIntOrNull() ?: 7
    val config = PhysicsConfig(DemoSettings.fromEnvironment().snapshot().toMutableMap().also { it["RB_PARALLEL_MIN"] = "0" })
    val scale = WorldScale.of(bodies[0])
    val grid = bodies.first { it.linkCount > 0 && it.muscleClusters.isEmpty() && it.boneClusters.isEmpty() }
    var ok = true
    fun report(name: String, pass: Boolean, detail: String) { println("CLIENT ${if (pass) "ok  " else "FAIL"} $name: $detail"); if (!pass) ok = false }

    // 1. Мышца клетки: в свободном теле сокращение меняет длину, расслабление сохраняет исходную.
    for (s in floatArrayOf(0.5f, 1f, 1.5f)) {
        val w = world(config, scale, null); w.addBody(grid)
        for (i in 0 until w.particleCount) w.setCellScale(i, s)
        repeat(150) { w.step() }
        val ratio = meanLinkRatio(w)
        report("мышца клетки ×$s", Math.abs(ratio - minOf(1f, s)) < 0.05 && w.state.tornTotal == 0,
            "средняя длина связи %.3f длины покоя, разрывов ${w.state.tornTotal}".format(ratio))
    }
    // Одинаковый импульс от центра наружу. Команду меняем до опыта на свободном теле.
    fun pulledRatio(command: Float): Double {
        val w = world(config, scale, null); w.addBody(grid)
        for (i in 0 until grid.count) w.setCellScale(i, command)
        repeat(150) { w.step() }
        val cx = grid.x.average(); val cy = grid.y.average()
        for (i in 0 until grid.count) w.setVelocity(i, (grid.x[i] - cx).toFloat(), (grid.y[i] - cy).toFloat())
        w.step()
        return meanLinkRatio(w)
    }
    val neutral = pulledRatio(1f)
    val relaxed = pulledRatio(1.5f)
    report("одинаковая внешняя тяга", relaxed > neutral && neutral > 1.0 && relaxed <= 1.25,
        "длина к исходной: сигнал 0 %.5f, сигнал +1 %.5f".format(neutral, relaxed))
    // Бегущая волна по клеткам: один поток = N воркеров побитово.
    run {
        val a = world(config, scale, null); val b = world(config, scale, PhysicsWorkers.shared(workers))
        for (w in listOf(a, b)) w.addBody(grid)
        var same = true
        for (t in 0 until 200) {
            for (w in listOf(a, b)) {
                for (i in 0 until w.particleCount) w.setCellScale(i, (1.0 + 0.4 * Math.sin(0.15 * t + 0.3 * w.x[i] / w.meanLinkLength)).toFloat())
                w.step()
            }
            if (hashOf(a) != hashOf(b)) { same = false; break }
        }
        val finite = (0 until a.particleCount).all { a.x[it].isFinite() && a.y[it].isFinite() }
        report("волна мышц клеток", same && finite, "200 тиков, один поток = $workers воркеров: $same, порвано ${a.state.tornTotal}")
    }

    // 2. Масса: в ударе тяжёлой (×4) и лёгкой частицы изменения скоростей обратны массам.
    // Импульс пары в воде не сохраняется и не должен: каждый организм тащит «запас среды»
    // (FLOW_ENTRAIN/FLOW_MASS), который забирает импульс и затухает. Поэтому сравнивается
    // только тик удара — среда за тик снимает доли процента.
    run {
        val w = world(config, scale, null); w.addBody(grid)
        val r = 0.5 * w.meanLinkLength
        val y0 = (0 until w.particleCount).minOf { w.y[it] } - 20 * r
        val heavy = w.freeParticle(w.addFree(0.0f, y0.toFloat(), r.toFloat(), 0.6f, 0.0f, drag = 0.0f))
        val light = w.freeParticle(w.addFree((10 * r).toFloat(), y0.toFloat(), r.toFloat(), -0.6f, 0.0f, drag = 0.0f))
        w.setMass(heavy, (4.0 / w.state.restInvMass[light]).toFloat())
        var ratio = Double.NaN
        repeat(80) {
            val hv = w.vx[heavy]; val lv = w.vx[light]
            w.step()
            val dh = w.vx[heavy] - hv; val dl = w.vx[light] - lv
            if (ratio.isNaN() && dl > 0.05) ratio = (-dl / dh).toDouble()
        }
        report("масса", Math.abs(ratio - 4.0) < 0.2, "в тик удара Δv лёгкой / Δv тяжёлой = %.3f (масса 4)".format(ratio))
    }

    // 3. Сопротивление: 0 не тормозит, 2 тормозит сильнее 1.
    run {
        val w = world(config, scale, null); w.addBody(grid)
        val r = 0.5 * w.meanLinkLength
        val y0 = (0 until w.particleCount).minOf { w.y[it] } - 40 * r
        val ids = doubleArrayOf(0.0, 1.0, 2.0).mapIndexed { k, d -> w.freeParticle(w.addFree((k * 30 * r).toFloat(), y0.toFloat(), r.toFloat(), 0.0f, 0.3f, drag = d.toFloat())) }
        repeat(30) { w.step() }
        // Без изотропной среды частицу тормозит только запас среды; множитель d добавляет
        // (1 − MEDIUM_DRAG·DT·d) за тик.
        val v = ids.map { Math.hypot(w.vx[it].toDouble(), w.vy[it].toDouble()) }
        val keep = 1.0 - io.github.some_example_name.physics.MEDIUM_DRAG * io.github.some_example_name.physics.DT
        val e1 = Math.pow(keep, 30.0); val e2 = Math.pow(1.0 - 2 * (1.0 - keep), 30.0)
        report("сопротивление", Math.abs(v[1] / v[0] / e1 - 1) < 2e-3 && Math.abs(v[2] / v[0] / e2 - 1) < 2e-3,
            "после 30 тиков ×1/×0 = %.4f (ждали %.4f), ×2/×0 = %.4f (ждали %.4f)".format(v[1] / v[0], e1, v[2] / v[0], e2))
    }

    // 4. Рамка: частицы, пущенные в стенки, остаются внутри.
    run {
        val w = world(config, scale, null); w.addBody(grid)
        val r = 0.5 * w.meanLinkLength
        val cx = (0 until w.particleCount).map { w.x[it] }.average(); val cy = (0 until w.particleCount).map { w.y[it] }.average()
        val half = 3.0
        w.setBounds((cx - half).toFloat(), (cy - half).toFloat(), (cx + half).toFloat(), (cy + half).toFloat())
        val shots = (0 until 16).map { k ->
            val a = 2 * Math.PI * k / 16
            w.freeParticle(w.addFree((cx + 2.0 * Math.cos(a)).toFloat(), (cy + 2.0 * Math.sin(a)).toFloat(), r.toFloat(), (3 * Math.cos(a)).toFloat(), (3 * Math.sin(a)).toFloat(), drag = 0.0f))
        }
        var worst = 0.0
        repeat(120) {
            w.step()
            for (i in shots) {
                worst = maxOf(worst, (cx - half + r) - w.x[i], w.x[i] - (cx + half - r), (cy - half + r) - w.y[i], w.y[i] - (cy + half - r))
            }
        }
        // Внутри шага контакт подшага может вытолкнуть частицу за стенку, но к концу шага её
        // возвращает clampToBounds: снаружи — ни на сколько (прежний допуск — половина радиуса,
        // клиент-игра падала на клетке за краем сетки).
        report("рамка мира", worst <= 0.0, "наибольший выход за стенку %.3g при радиусе %.3g".format(worst, r))
    }

    // 5. Гибель клетки по причине игры.
    run {
        val w = world(config, scale, null); w.addBody(grid)
        val v = grid.count / 2
        w.kill(v)
        repeat(5) { w.step() }
        val b = w.state.baked
        var liveEdges = 0
        for (s in b.vertStart[v] until b.vertStart[v + 1]) if (!b.edgeDead[b.vertEdge[s]]) liveEdges++
        report("гибель клетки", w.state.cellDead[v] && liveEdges == 0 && w.state.invMass[v] == 0.0f,
            "клетка мертва ${w.state.cellDead[v]}, живых рёбер $liveEdges")
    }
    // 6. Удар по спящему. Спящий организм заморожен (обратная масса 0); налетевшая частица
    // обязана будить его ДО решения контакта, иначе бьётся как в стену: импульс не передан,
    // а налетевшая отскакивает. Сравнение — тот же удар в мире без сна.
    for (target in listOf("частицу", "тело")) {
        val got = DoubleArray(2)
        for ((k, sleep) in listOf(false, true).withIndex()) {
            val w = world(config, scale, null).apply { sleepOn = sleep }
            w.addBody(grid)
            val r = 0.5 * w.meanLinkLength
            val y0 = (0 until w.particleCount).minOf { w.y[it] } - 30 * r
            val x0 = (0 until w.particleCount).map { w.x[it] }.average()
            // Цель — свободная частица под телом или само тело; пуля — снизу или слева.
            val still = if (target == "частицу") w.freeParticle(w.addFree(x0.toFloat(), y0.toFloat(), r.toFloat(), drag = 0.0f)) else -1
            repeat(120) { w.step() }
            val asleep = if (still >= 0) w.state.sleep.isAsleep(w.state.organismOf[still]) else w.state.sleep.isAsleep(w.state.organismOf[0])
            if (sleep && !asleep) { report("удар по спящему: $target", false, "цель не уснула за 120 тиков"); continue }
            val bullet = if (still >= 0) w.freeParticle(w.addFree((x0 - 12 * r).toFloat(), y0.toFloat(), r.toFloat(), 0.6f, 0.0f, drag = 0.0f))
                else w.freeParticle(w.addFree(x0.toFloat(), y0.toFloat(), r.toFloat(), 0.0f, 0.6f, drag = 0.0f))
            repeat(40) { w.step() }
            got[k] = if (still >= 0) Math.hypot(w.vx[still].toDouble(), w.vy[still].toDouble())
                else (0 until grid.count).map { Math.hypot(w.vx[it].toDouble(), w.vy[it].toDouble()) }.average() * grid.count
            if (bullet < 0) got[k] = Double.NaN
        }
        val ratio = got[1] / got[0]
        report("удар по спящему: $target", ratio > 0.8, "импульс цели со сном / без сна = %.3f".format(ratio))
    }
    println(if (ok) "CLIENT OK" else "CLIENT FAILED")
    if (!ok) kotlin.system.exitProcess(1)
}

private fun world(config: PhysicsConfig, scale: WorldScale, workers: PhysicsWorkers?) =
    PhysicsWorld(config, workers = workers, scale = scale).apply { state.quiet = true; sleepOn = false }

private fun meanLinkRatio(w: PhysicsWorld): Double {
    val st = w.state
    var sum = 0.0; var k = 0
    for (c in 0 until st.conCount) {
        if (st.conDead[c]) continue
        val a = st.conA[c]; val b = st.conB[c]
        sum += Math.hypot((st.px[a] - st.px[b]).toDouble(), (st.py[a] - st.py[b]).toDouble()) / st.conRest[c]; k++
    }
    return sum / k
}

private fun hashOf(w: PhysicsWorld): Long {
    var h = 1125899906842597L
    fun mix(v: Long) { h = (h xor v) * -7046029254386353131L }
    for (i in 0 until w.particleCount) { mix(w.x[i].toRawBits().toLong()); mix(w.y[i].toRawBits().toLong()); mix(w.vx[i].toRawBits().toLong()); mix(w.vy[i].toRawBits().toLong()) }
    return h
}
