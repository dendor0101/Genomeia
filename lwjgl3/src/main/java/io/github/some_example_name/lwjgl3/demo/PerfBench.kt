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
    // ИЗ ЧЕГО СОСТОИТ МЫШЦА: PERF_MUSCLE=1. Сколько связей и треугольников считаются
    // мышечными, а сколько лежат на стыке — одним концом в мышце, другим в кости или в
    // обычной ткани. Именно стык и рвётся, когда мышца тянет.
    System.getenv("PERF_MUSCLE")?.let {
        val muscleOf: IntArray = P.get("muscleOf")
        val boneOf: IntArray = P.get("boneOf")
        val conA: IntArray = P.get("conA"); val conB: IntArray = P.get("conB")
        val conMuscle: IntArray = P.get("conMuscle")
        val conCount: Int = P.get("conCount")
        val triA: IntArray = P.get("triA"); val triB: IntArray = P.get("triB"); val triC: IntArray = P.get("triC")
        val triMuscle: IntArray = P.get("triMuscle")
        val triCount: Int = P.get("triCount")
        var cellsMuscle = 0
        for (i in 0 until P.n) if (muscleOf[i] >= 0) cellsMuscle++
        var linkBoth = 0; var linkOneBone = 0; var linkOneSoft = 0; var linkCross = 0
        for (c in 0 until conCount) {
            val a = muscleOf[conA[c]]; val b = muscleOf[conB[c]]
            if (a >= 0 && a == b) { linkBoth++; continue }
            if (a < 0 && b < 0) continue
            if (a >= 0 && b >= 0) { linkCross++; continue }
            val other = if (a >= 0) conB[c] else conA[c]
            if (boneOf[other] >= 0) linkOneBone++ else linkOneSoft++
        }
        var triAll = 0; var triPart = 0
        for (t in 0 until triCount) {
            val a = muscleOf[triA[t]]; val b = muscleOf[triB[t]]; val c = muscleOf[triC[t]]
            if (a >= 0 && a == b && b == c) triAll++
            else if (a >= 0 || b >= 0 || c >= 0) triPart++
        }
        var triMus = 0
        for (t in 0 until triCount) if (triMuscle[t] >= 0) triMus++
        var conMus = 0
        for (c in 0 until conCount) if (conMuscle[c] >= 0) conMus++
        var core = 0
        for (ids in P.body.muscleClusters) core += ids.size
        println("МЫШЦЫ на %s: клеток в мышцах %d из %d (в выгрузке %d), кластеров %d, захват края %d"
            .format(path, cellsMuscle, P.n, core, P.body.muscleClusters.size, P.get<Int>("muscleTouch")))
        println("  клетки: обоими концами в одном кластере %d связей, стык %d — из них в кость %d, в обычную ткань %d, между разными мышцами %d"
            .format(linkBoth, linkOneBone + linkOneSoft + linkCross, linkOneBone, linkOneSoft, linkCross))
        println("  треугольники: все три вершины в одном кластере %d, задевают мышцу ещё %d"
            .format(triAll, triPart))
        println("  СОКРАЩАЮТСЯ: связей %d, треугольников %d".format(conMus, triMus))
        return
    }
    // ГРЕБОК НА НАСТОЯЩЕЙ ФИЗИКЕ: PERF_GAIT=секунд. Печатает, рвёт ли тело само себя
    // мышцами и с какой скоростью плывёт. Зеркало SwimSolver разрывов не знает вовсе,
    // поэтому «не рвётся» проверяется только здесь.
    System.getenv("PERF_GAIT")?.toDoubleOrNull()?.let { secs ->
        P.resetState()
        P.setTearing(true)
        if (System.getenv("PERF_TEARS") != null) P.demo.dbgTearLog = StringBuilder()
        val frames = Math.round(secs / dt).toInt()
        val x0 = P.pX(); val y0 = P.pY()
        var cx0 = 0.0; var cy0 = 0.0; var m = 0.0
        for (i in 0 until P.n) { if (P.invMass[i] <= 0.0) continue; val w = 1.0 / P.invMass[i]; m += w; cx0 += w * P.px[i]; cy0 += w * P.py[i] }
        cx0 /= m; cy0 /= m
        val idle = System.getenv("PERF_GAIT_IDLE") != null
        // PERF_HOLD=номер — держать одну мышцу сокращённой, как при наведении мышью.
        val hold = System.getenv("PERF_HOLD")?.toIntOrNull() ?: -1
        var first = -1
        val marks = intArrayOf(Math.round(1.0 / dt).toInt(), Math.round(5.0 / dt).toInt())
        val at = IntArray(marks.size)
        for (fr in 0 until frames) {
            if (idle) P.frame(dt, sub, contract = false)
            else if (hold >= 0) P.frameHold(dt, hold)
            else P.frameGait(dt, fr)
            if (first < 0 && P.killedLinks() > 0) first = fr
            for (k in marks.indices) if (fr == marks[k]) at[k] = P.killedLinks()
        }
        // ПЕРЕМЕЩЕНИЕ САМОГО КРУПНОГО КУСКА, а не всех клеток: у рвущегося тела в общий
        // центр масс попадают улетевшие обломки, и «скорость» получается не про плавание.
        val org: IntArray = P.get("organismOf")
        val dead: BooleanArray = P.get("cellDead")
        val sizes = IntArray(P.organismCount)
        for (i in 0 until P.n) if (!dead[i]) sizes[org[i]]++
        var big = 0
        for (o in sizes.indices) if (sizes[o] > sizes[big]) big = o
        var cx1 = 0.0; var cy1 = 0.0; var mBig = 0.0; var cx0b = 0.0; var cy0b = 0.0
        for (i in 0 until P.n) {
            if (dead[i] || org[i] != big || P.invMass[i] <= 0.0) continue
            val w = 1.0 / P.invMass[i]
            mBig += w; cx1 += w * P.px[i]; cy1 += w * P.py[i]
            // Начальная поза тех же клеток — прямо из выгрузки.
            cx0b += w * P.body.x[i]; cy0b += w * P.body.y[i]
        }
        cx1 /= mBig; cy1 /= mBig; cx0b /= mBig; cy0b /= mBig
        val meanLink = P.body.meanLinkLength.toDouble()
        val dist = Math.hypot(cx1 - cx0b, cy1 - cy0b)
        val d = P.demo
        println("ГРЕБОК %.0f с на %s: порвано связей %d (сжатием %d, из них мышечных %d; растяжением %d, из них мышечных %d)"
            .format(secs, path, P.killedLinks(), d.dbgTornCrush, d.dbgTornCrushMuscle, d.dbgTornPull, d.dbgTornPullMuscle))
        println("  первый разрыв на тике %s; порвано к 1 с %d, к 5 с %d%s"
            .format(if (first < 0) "нет" else first.toString(), at[0], at[1],
                if (idle) " (БЕЗ ГРЕБКА)" else if (hold >= 0) " (ДЕРЖИМ МЫШЦУ )" else ""))
        println("  лопнуло клеток: давление %d, раздавленных %d, застрявших %d; запас: сжатие %.3f (рвёт ниже %.2f), растяжение %.3f (рвёт выше %.2f)"
            .format(d.pressureBurstCount, d.crushBurstCount, d.trapKillCount,
                d.dbgWorstCrush, P.const("LINK_CRUSH_RATIO"),
                d.dbgWorstStretch, P.const("LINK_MAX_STRETCH") + P.const("LINK_TEAR_STRAIN")))
        println("  крупнейший кусок: %d клеток из %d, проплыл %.2f связи за %.0f с (%.4f клетки/тик), организмов %d"
            .format(sizes[big], P.n, dist / meanLink, secs, dist / meanLink / frames, P.organismCount))
        d.dbgTearLog?.let { if (it.isNotEmpty()) print(it) }
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
