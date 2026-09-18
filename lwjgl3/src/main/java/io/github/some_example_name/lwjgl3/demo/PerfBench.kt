package io.github.some_example_name.lwjgl3.demo

/**
 * ЗАМЕР ВРЕМЕНИ ТИКА НА НАСТОЯЩЕМ ТЕЛЕ, без окна и без рендера.
 *
 * Нужен потому, что «вчетверо больше работы решателя» и «вчетверо медленнее» — разные
 * вещи. В тике кроме ограничений есть широкая фаза контактов с сеткой, DDA и CCD,
 * стадии среды, интегрирование и восстановление скоростей. Доля решателя в этом
 * наборе заранее неизвестна, поэтому её надо мерить, а не прикидывать.
 *
 * Прогрев обязателен: без него меряется работа JIT, а не физики.
 *
 *   gradlew :lwjgl3:perfBench
 *   gradlew :lwjgl3:perfBench -Pbody=body-export-кирпич.txt
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "body-export.txt"
    val P = Probe
    P.boot(path)
    val dt = P.const("DT")
    val sub = P.constInt("SUBSTEPS")
    P.setContacts(true)
    if (System.getenv("PERF_NOSPIN") != null) P.demo.cancelSpinOn = false
    P.resetState()

    if (System.getenv("PERF_STAGES") != null) P.demo.dbgTimeOn = true
    // ЗАТУХАНИЕ ВРАЩЕНИЯ: PERF_SPIN=ω0 — кусок раскручивается и живёт свободно, печатается
    // ω по секундам. Нужен, чтобы мерить сопротивление среды вращению отдельно от всего
    // остального: на журнале игрока этого не увидеть, там физика после правки расходится.
    System.getenv("PERF_SPIN")?.toDoubleOrNull()?.let { w0 ->
        P.resetState()
        val org: IntArray = P.get("organismOf")
        val count: Int = P.get("organismCount")
        val cells = IntArray(count)
        for (i in 0 until P.n) cells[org[i]]++
        var big = 0
        for (o in 0 until count) if (cells[o] > cells[big]) big = o
        var m = 0.0; var cx = 0.0; var cy = 0.0
        for (i in 0 until P.n) {
            if (org[i] != big || P.invMass[i] <= 0.0) continue
            val w = 1.0 / P.invMass[i]
            m += w; cx += w * P.px[i]; cy += w * P.py[i]
        }
        cx /= m; cy /= m
        for (i in 0 until P.n) {
            if (org[i] != big) continue
            P.vx[i] = -w0 * (P.py[i] - cy)
            P.vy[i] = w0 * (P.px[i] - cx)
        }
        fun omega(): Double {
            var mm = 0.0; var qx = 0.0; var qy = 0.0; var ux = 0.0; var uy = 0.0
            for (i in 0 until P.n) {
                if (org[i] != big || P.invMass[i] <= 0.0) continue
                val w = 1.0 / P.invMass[i]
                mm += w; qx += w * P.px[i]; qy += w * P.py[i]; ux += w * P.vx[i]; uy += w * P.vy[i]
            }
            qx /= mm; qy /= mm; ux /= mm; uy /= mm
            var l = 0.0; var j = 0.0
            for (i in 0 until P.n) {
                if (org[i] != big || P.invMass[i] <= 0.0) continue
                val w = 1.0 / P.invMass[i]
                val rx = P.px[i] - qx; val ry = P.py[i] - qy
                l += w * (rx * (P.vy[i] - uy) - ry * (P.vx[i] - ux))
                j += w * (rx * rx + ry * ry)
            }
            return if (j > 1e-18) l / j else 0.0
        }
        println("ЗАТУХАНИЕ ВРАЩЕНИЯ: кусок орг %d, клеток %d, касательное трение %.3f от нормального"
            .format(big, cells[big], P.demo.tangentDrag))
        println("  секунда |  ω рад/с | доля от начальной")
        val w1 = omega()
        for (s in 0..10) {
            val w = omega()
            println("   %5d   | %8.3f | %6.3f".format(s, w, if (w1 != 0.0) w / w1 else 0.0))
            for (i in 0 until Math.round(1.0 / dt).toInt()) P.frame(dt, sub, contract = false)
        }
        return
    }
    val warm = 300
    val measured = 600
    for (i in 0 until warm) P.frame(dt, sub, contract = false)
    var best = Double.MAX_VALUE
    repeat(3) {
        P.resetState()
        for (i in 0 until warm / 3) P.frame(dt, sub, contract = false)
        val t0 = System.nanoTime()
        for (i in 0 until measured) P.frame(dt, sub, contract = false)
        val ms = (System.nanoTime() - t0) / 1e6 / measured
        if (ms < best) best = ms
    }
    // Бюджет кадра при 60 в секунду — 16.7 мс, при 30 — 33.3 мс.
    println("PERF %s | cells=%d | %.3f ms/tick | %.1f tick/s | budget60=%.0f%%"
        .format(path, P.n, best, 1000.0 / best, best / 16.7 * 100))
    if (P.demo.dbgTimeOn) {
        // Замер идёт по ВСЕМ прогонам, включая прогрев: доли важнее абсолютных чисел.
        val ns = P.demo.dbgTimeNs
        val total = ns.sum().toDouble()
        println("  по стадиям, доля тика:")
        for (k in ns.indices.sortedByDescending { ns[it] }) {
            if (ns[k] == 0L) continue
            println("    %-22s %5.1f%%".format(P.demo.dbgTimeNames[k], ns[k] / total * 100))
        }
    }
}
