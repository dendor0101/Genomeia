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
    // PERF_COPIES=число — сколько копий тела в мире. Нужно, чтобы измерить, как время
    // тика растёт с числом частиц, и оценить потолок при распараллеливании.
    P.boot(path, copies = System.getenv("PERF_COPIES")?.toIntOrNull() ?: 2)
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
    // ВЫГРУЗКА ТОПОЛОГИИ ДЛЯ РАЗБОРА ЛОКАЛЬНОСТИ: PERF_DUMP=путь. Пишет настоящие массивы
    // решателя — связи, треугольники, контур, — чтобы считать промахи кэша не на догадках
    // о порядке, а на том, что решатель и обходит.
    System.getenv("PERF_DUMP")?.let { outPath ->
        val conA: IntArray = P.get("conA"); val conB: IntArray = P.get("conB")
        val conCount: Int = P.get("conCount")
        val triA: IntArray = P.get("triA"); val triB: IntArray = P.get("triB"); val triC: IntArray = P.get("triC")
        val triCount: Int = P.get("triCount")
        val boundA: IntArray = P.get("boundA"); val boundB: IntArray = P.get("boundB")
        val boundCount: Int = P.get("boundCount")
        val bendA: IntArray = P.get("bendA"); val bendB: IntArray = P.get("bendB")
        val bendCount: Int = P.get("bendCount")
        val w = java.io.PrintWriter(java.io.BufferedWriter(java.io.FileWriter(outPath)))
        w.println("n ${P.n}")
        for (c in 0 until conCount) w.println("C ${conA[c]} ${conB[c]}")
        for (t in 0 until triCount) w.println("T ${triA[t]} ${triB[t]} ${triC[t]}")
        for (e in 0 until boundCount) w.println("B ${boundA[e]} ${boundB[e]}")
        for (e in 0 until bendCount) w.println("D ${bendA[e]} ${bendB[e]}")
        w.close()
        println("выгружено в $outPath: клеток ${P.n}, связей $conCount, треугольников $triCount, контур $boundCount, изгибов $bendCount")
        return
    }
    // ПРОДАВЛИВАНИЕ ДВУХ КЛЕТОК: PERF_SQUASH=скорость в клетках за тик.
    //
    // Вопрос игрока: «мягкость не замечаю, две отдельные частицы как абсолютно твёрдые».
    // Берём две клетки из РАЗНЫХ организмов, уносим их в пустое место, сводим лбами с
    // заданной скоростью и смотрим, насколько глубоко они вошли друг в друга. Глубина
    // печатается в долях контактного расстояния: 0 — абсолютно твёрдые, 0.1 — вошли на
    // десятую часть. Так видно саму мягкость, а не её следствия на куче.
    System.getenv("PERF_SQUASH")?.toDoubleOrNull()?.let { speed ->
        P.resetState()
        P.setTearing(false)
        // Берём две клетки и ОТРЕЗАЕМ их от тела: иначе, унеся их в пустое место, мы
        // растянули бы их связи на пять тысяч длин, и стенд мерил бы борьбу предела длины,
        // а не контакт.
        val conA: IntArray = P.get("conA"); val conB: IntArray = P.get("conB")
        val conDead: BooleanArray = P.get("conDead")
        val linkTorn: BooleanArray = P.get("linkTorn")
        val boundA: IntArray = P.get("boundA"); val boundCount: Int = P.get("boundCount")
        var a = if (boundCount > 0) boundA[0] else 0
        var b = -1
        val orgBefore: IntArray = P.get("organismOf")
        for (e in 0 until boundCount) {
            val i = boundA[e]
            if (orgBefore[i] != orgBefore[a]) { b = i; break }
        }
        if (b < 0) { println("ПРОДАВЛИВАНИЕ: не нашлось двух клеток контура в разных организмах"); return }
        run {
            val cc: Int = P.get("conCount")
            for (c in 0 until cc) if (conA[c] == a || conB[c] == a || conA[c] == b || conB[c] == b) {
                conDead[c] = true; linkTorn[c] = true
            }
            P.setField("tearsPending", true)
            P.frame(dt, sub, contract = false)
        }
        val ct0 = P.contactsObj()!!
        if (ct0.contactRadiusOf(a) <= 0.0 || ct0.contactRadiusOf(b) <= 0.0) {
            println("ПРОДАВЛИВАНИЕ: у отрезанных клеток нет контактного радиуса"); return
        }
        val ml = P.body.meanLinkLength
        val rr = ct0.contactRadiusOf(a) + ct0.contactRadiusOf(b)
        // Уносим пару далеко от всех, чтобы мешать было некому, и ставим на расстоянии
        // в три контактных диаметра друг от друга.
        val cx = 5000.0 * ml; val cy = 5000.0 * ml
        val v = speed * ml / dt
        P.px[a] = cx - 1.5 * rr; P.py[a] = cy
        P.px[b] = cx + 1.5 * rr; P.py[b] = cy
        P.prevX[a] = P.px[a]; P.prevY[a] = P.py[a]
        P.prevX[b] = P.px[b]; P.prevY[b] = P.py[b]
        var deepest = 0.0
        var atFrame = -1
        var deepestSub = 0.0
        // Смотрим и ПО ПОДШАГАМ: отрисовка интерполирует по ним, значит игрок видит
        // именно эти положения, а не только состояние на границе тика.
        P.demo.dbgSubstepHook = { _ ->
            val dd = Math.hypot(P.px[b] - P.px[a], P.py[b] - P.py[a])
            val o = 1.0 - dd / rr
            if (o > deepestSub) deepestSub = o
        }
        // Вторую клетку держим неподвижной — это упор, в который давит рука.
        val holdInv = P.invMass[b]
        P.invMass[b] = 0.0
        for (fr in 0 until 240) {
            // ДАВИМ РУКОЙ, а не подменой скорости. Рука прикладывает силу КАЖДЫЙ
            // подшаг (applyDragVelocity), и только так выходит настоящая постоянная
            // нагрузка: подменённая раз в тик скорость гаснет в первом же подшаге,
            // контакт разводит пару, и к границе тика перекрытия не остаётся вовсе.
            P.dragTo(a, P.px[b], P.py[b])
            P.frame(dt, sub, contract = false)
            val dd = Math.hypot(P.px[b] - P.px[a], P.py[b] - P.py[a])
            val overlap = 1.0 - dd / rr
            if (overlap > deepest) { deepest = overlap; atFrame = fr }
        }
        P.dragRelease()
        P.invMass[b] = holdInv
        P.demo.dbgSubstepHook = null
        println("ПРОДАВЛИВАНИЕ на %s: клетки #%d и #%d, упор %.4f связи, скорость сближения %.1f клетки/тик"
            .format(path, a, b, rr / ml, 2.0 * speed))
        println("  вошли друг в друга на %.4f упора на границе тика и на %.4f внутри подшагов; ω·h пары %.2f"
            .format(deepest, deepestSub, ct0.omegaPair))
        return
    }
    // ПРОТАСКИВАНИЕ ЧАСТИЦЫ СКВОЗЬ ТЕЛО: PERF_PUSH=секунд.
    //
    // Жалоба игрока: свободную частицу мышью можно провести через всё тело, хотя она
    // обязана либо упереться, либо порвать ткань. Берём свободную клетку, ставим её
    // снаружи и ведём курсором прямо на центр масс тела. Печатаем, оказалась ли она
    // внутри контура, сколько связей при этом порвалось и как глубоко она залезла.
    System.getenv("PERF_PUSH")?.toDoubleOrNull()?.let { secs ->
        P.resetState()
        P.setTearing(true)
        val ml = P.body.meanLinkLength
        var free = -1
        for (i in 0 until P.n) if (P.isFree(i)) { free = i; break }
        if (free < 0) { println("ПРОТАСКИВАНИЕ: в теле нет свободных частиц"); return }
        // Центр масс САМОГО КРУПНОГО организма — туда и ведём.
        val org: IntArray = P.get("organismOf")
        val sizes = IntArray(P.organismCount)
        for (i in 0 until P.n) sizes[org[i]]++
        var big = 0
        for (o in sizes.indices) if (sizes[o] > sizes[big]) big = o
        var cx = 0.0; var cy = 0.0; var m = 0.0
        for (i in 0 until P.n) {
            if (org[i] != big || P.invMass[i] <= 0.0) continue
            val w = 1.0 / P.invMass[i]; m += w; cx += w * P.px[i]; cy += w * P.py[i]
        }
        cx /= m; cy /= m
        // ВЕДЁМ НАСКВОЗЬ, а не в центр масс: у подковообразного тела центр масс лежит в
        // пустоте, и частица туда долетает, ни во что не упёршись.
        //
        // Берём самую широкую горизонталь тела: по ней и тащим слева направо.
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var bestY = cy; var bestSpan = -1.0
        run {
            val step = 2.0 * ml
            var y = cy - 20.0 * ml
            while (y < cy + 20.0 * ml) {
                var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE; var cnt = 0
                for (i in 0 until P.n) {
                    if (org[i] != big) continue
                    if (Math.abs(P.py[i] - y) > ml) continue
                    if (P.px[i] < lo) lo = P.px[i]
                    if (P.px[i] > hi) hi = P.px[i]
                    cnt++
                }
                if (cnt > 10 && hi - lo > bestSpan) { bestSpan = hi - lo; bestY = y; minX = lo; maxX = hi }
                y += step
            }
        }
        if (bestSpan <= 0.0) { println("ПРОТАСКИВАНИЕ: не нашлось горизонтали через тело"); return }
        val startX = minX - 5.0 * ml
        val endX = maxX + 5.0 * ml
        P.px[free] = startX; P.py[free] = bestY
        P.prevX[free] = P.px[free]; P.prevY[free] = P.py[free]
        P.vx[free] = 0.0; P.vy[free] = 0.0
        val frames = Math.round(secs / dt).toInt()
        var inside = 0
        var deepest = 0.0
        for (fr in 0 until frames) {
            P.dragTo(free, endX, bestY)
            P.frame(dt, sub, contract = false)
            val depth = (P.px[free] - startX) / ml
            if (depth > deepest) deepest = depth
            if (insideAny(P, free)) inside++
        }
        P.dragRelease()
        val total = (endX - startX) / ml
        println("ПРОТАСКИВАНИЕ на %s: свободная клетка #%d, вели %.1f с НАСКВОЗЬ (ширина тела %.1f связи)"
            .format(path, free, secs, bestSpan / ml))
        println("  внутри чужой ткани была %d кадров из %d; прошла %.1f из %.1f связи пути"
            .format(inside, frames, deepest, total))
        println("  порвано связей %d; %s"
            .format(P.killedLinks(),
                if (deepest > total - 1.0) "ПРОШЛА НАСКВОЗЬ" else "застряла"))
        return
    }
    // ОТРЫВ МЫШЬЮ: PERF_PULL=секунд. Хватаем клетку контура и ведём курсор прочь от тела с
    // постоянной скоростью. Вопрос игрока: почему мышью не удаётся отрывать клетки, как в
    // основной симуляции. Печатаем, порвалось ли, за сколько и как далеко клетка ушла от
    // тела — если она просто тащит тело за собой, не порвётся никогда.
    System.getenv("PERF_PULL")?.toDoubleOrNull()?.let { secs ->
        P.resetState()
        P.setTearing(true)
        val ml = P.body.meanLinkLength
        // Берём клетку контура подальше от центра масс: её легче оторвать, чем внутреннюю.
        val boundA: IntArray = P.get("boundA")
        val boundCount: Int = P.get("boundCount")
        var cx = 0.0; var cy = 0.0; var m = 0.0
        for (i in 0 until P.n) {
            if (P.invMass[i] <= 0.0) continue
            val w = 1.0 / P.invMass[i]; m += w; cx += w * P.px[i]; cy += w * P.py[i]
        }
        cx /= m; cy /= m
        var grab = -1; var far = -1.0
        for (e in 0 until boundCount) {
            val i = boundA[e]
            val d = Math.hypot(P.px[i] - cx, P.py[i] - cy)
            if (d > far) { far = d; grab = i }
        }
        val dirX = (P.px[grab] - cx) / far
        val dirY = (P.py[grab] - cy) / far
        val frames = Math.round(secs / dt).toInt()
        // Курсор уходит от тела ровно со скоростью потолка тяги: быстрее тяга всё равно
        // не пойдёт, а медленнее — не проверка.
        val step = P.const("DRAG_SPEED_LIMIT") * P.const("MAX_SPEED_CELLS_PER_TICK") * ml
        var tx = P.px[grab]; var ty = P.py[grab]
        var tornAt = -1
        var peakStretch = 0.0
        val conA: IntArray = P.get("conA"); val conB: IntArray = P.get("conB")
        val conRest: DoubleArray = P.get("conRest"); val conDead: BooleanArray = P.get("conDead")
        for (fr in 0 until frames) {
            tx += dirX * step; ty += dirY * step
            P.dragTo(grab, tx, ty)
            P.frame(dt, sub, contract = false)
            val cc: Int = P.get("conCount")
            for (c in 0 until cc) {
                if (conDead[c]) continue
                if (conA[c] != grab && conB[c] != grab) continue
                val s = Math.hypot(P.px[conA[c]] - P.px[conB[c]], P.py[conA[c]] - P.py[conB[c]]) / conRest[c]
                if (s > peakStretch) peakStretch = s
            }
            if (tornAt < 0 && P.killedLinks() > 0) tornAt = fr
        }
        P.dragRelease()
        val gap = Math.hypot(P.px[grab] - tx, P.py[grab] - ty) / ml
        println("ТЯГА МЫШЬЮ на %s: тянем клетку #%d %.1f с, ускорение %.0f, потолок скорости %.2f"
            .format(path, grab, secs, P.const("DRAG_ACCEL"), P.const("DRAG_SPEED_LIMIT")))
        println("  порвано связей %d%s; пиковое растяжение связи схваченной клетки %.3f (рвёт выше %.2f)"
            .format(P.killedLinks(), if (tornAt < 0) ", не рвалось" else " (первый разрыв на кадре $tornAt)",
                peakStretch, P.const("LINK_MAX_STRETCH") + P.const("LINK_TEAR_STRAIN")))
        println("  клетка отстала от курсора на %.2f связи, организмов %d".format(gap, P.organismCount))
        // Главное число: насколько связь ХОТЕЛА вытянуться сверх предела внутри подшага.
        // Рвётся она ровно по этому, а не по длине в конце тика.
        println("  пик недобора сверх предела %.4f покоя (рвёт выше %.2f)"
            .format(P.demo.dbgMaxOverStrain, P.const("LINK_TEAR_STRAIN")))
        return
    }
    // ЗАПАС СРЕДЫ ПРИ РАЗРЫВЕ: PERF_FLOW=секунд. Разгоняем тело гребком, смотрим запас,
    // затем рвём ОДНУ связь и смотрим, что с запасом стало и за сколько тиков он вернулся.
    // Вопрос игрока: что происходит с flowVX/flowVY, когда тело распадается надвое.
    System.getenv("PERF_FLOW")?.toDoubleOrNull()?.let { secs ->
        P.resetState()
        P.setTearing(true)
        val frames = Math.round(secs / dt).toInt()
        for (fr in 0 until frames) P.frameGait(dt, fr)
        fun comSpeed(): Double {
            var m = 0.0; var vx = 0.0; var vy = 0.0
            for (i in 0 until P.n) {
                if (P.invMass[i] <= 0.0) continue
                val w = 1.0 / P.invMass[i]; m += w; vx += w * P.vx[i]; vy += w * P.vy[i]
            }
            return Math.hypot(vx / m, vy / m) * dt / P.body.meanLinkLength
        }
        val before = P.flowPeak(); val comBefore = comSpeed()
        println("ЗАПАС СРЕДЫ на %s после %.0f с гребка: %.6f, скорость центра масс %.4f клетки/тик, организмов %d"
            .format(path, secs, before, comBefore, P.organismCount))
        // Рвём одну живую связь подальше от края — этого хватает, чтобы пошла пересборка.
        val conDead: BooleanArray = P.get("conDead")
        val linkTorn: BooleanArray = P.get("linkTorn")
        val conCount: Int = P.get("conCount")
        var killed = -1
        for (c in 0 until conCount) if (!conDead[c]) { conDead[c] = true; linkTorn[c] = true; killed = c; break }
        P.setField("tearsPending", true)
        var fr = frames
        P.frameGait(dt, fr); fr++
        val after = P.flowPeak()
        println("  порвали связь #%d — запас стал %.6f (было %.6f), организмов %d"
            .format(killed, after, before, P.organismCount))
        var back = -1
        for (k in 0 until 120) {
            P.frameGait(dt, fr); fr++
            if (back < 0 && P.flowPeak() >= before * 0.9) { back = k + 1; break }
        }
        println("  вернулся к 90%% прежнего за %s тиков (%.2f с)"
            .format(if (back < 0) "более 120" else back.toString(), if (back < 0) 4.0 else back * dt))

        // А теперь НАСТОЯЩИЙ распад: отрезаем клетку от тела целиком и смотрим, с каким
        // запасом среды поехал новый кусок.
        val conA: IntArray = P.get("conA"); val conB: IntArray = P.get("conB")
        val orgBefore: IntArray = P.get("organismOf")
        // Клетка должна быть НЕ костью и иметь живые связи: внутрикостные в conA/conB не
        // попадают, и «отрезав» такую, мы ничего бы не отрезали.
        val boneOf: IntArray = P.get("boneOf")
        val deadCell: BooleanArray = P.get("cellDead")
        var cut = -1
        for (i in 0 until P.n) {
            if (deadCell[i] || boneOf[i] >= 0) continue
            var deg = 0
            for (c in 0 until conCount) if (!conDead[c] && (conA[c] == i || conB[c] == i)) deg++
            if (deg >= 2) { cut = i; break }
        }
        val orgOfCut = orgBefore[cut]
        val flowParent = P.flowOf(orgOfCut)
        var n2 = 0
        for (c in 0 until conCount) if (!conDead[c] && (conA[c] == cut || conB[c] == cut)) {
            conDead[c] = true; linkTorn[c] = true; n2++
        }
        P.setField("tearsPending", true)
        val orgsBefore = P.organismCount
        P.frameGait(dt, fr)
        val orgAfter: IntArray = P.get("organismOf")
        println("  отрезали клетку #%d (%d связей): организмов %d -> %d; запас родителя был %.6f, "
            .format(cut, n2, orgsBefore, P.organismCount, flowParent) +
            "стал у куска %.6f, у остатка %.6f"
                .format(P.flowOf(orgAfter[cut]), P.flowOf(orgAfter[if (cut == 0) P.n - 1 else 0])))
        return
    }
    // ОТПУСКАНИЕ МЫШЦЫ: PERF_RELEASE=секунд[,номер]. Держим одну мышцу сокращённой
    // столько секунд, отпускаем и смотрим, каким толчком это отдаётся. Вопрос от игрока:
    // «слегка коснулся другой медузы при сжатой мышце — сильный толчок». Держать нужно
    // РАЗНОЕ время: если рывок растёт с выдержкой, значит ткань вокруг мышцы успевает
    // потечь (см. PLASTIC_HOLD) и запасает деформацию, а если не растёт — это просто
    // работа мышцы на распрямлении, и тогда вопрос только к её скорости.
    System.getenv("PERF_RELEASE")?.split(',')?.let { spec ->
        val secs = spec[0].trim().toDoubleOrNull() ?: return@let
        val which = spec.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        P.resetState()
        P.setTearing(true)
        val hold = Math.round(secs / dt).toInt()
        fun comSpeed(): Double {
            var m = 0.0; var vx = 0.0; var vy = 0.0
            for (i in 0 until P.n) {
                if (P.invMass[i] <= 0.0) continue
                val w = 1.0 / P.invMass[i]; m += w; vx += w * P.vx[i]; vy += w * P.vy[i]
            }
            return Math.hypot(vx / m, vy / m) * dt / P.body.meanLinkLength
        }
        var peakWho = -1
        fun peakCell(): Double {
            var v = 0.0
            for (i in 0 until P.n) {
                val s = Math.hypot(P.vx[i], P.vy[i]) * dt / P.body.meanLinkLength
                if (s > v) { v = s; peakWho = i }
            }
            return v
        }
        val isFree: BooleanArray = P.get("isFree")
        fun who() = if (peakWho < 0) "" else
            " (#%d%s)".format(peakWho, if (isFree[peakWho]) ", свободная" else "")
        for (f in 0 until hold) P.frameHold(dt, which)
        val comHeld = comSpeed(); val cellHeld = peakCell(); val whoHeld = who()
        // ДРЕБЕЗГ ПОД НАГРУЗКОЙ: PERF_RELEASE_TRACE=1 — по подшагам за два тика, где
        // самая быстрая клетка и что её держит. Тело при этом стоит (центр масс ~0),
        // поэтому всё, что тут видно, — чистое дрожание на месте.
        if (System.getenv("PERF_RELEASE_TRACE") != null) {
            val i = peakWho
            val ct = P.contactsObj()
            val muscleOf: IntArray = P.get("muscleOf")
            val boneOf: IntArray = P.get("boneOf")
            val conA: IntArray = P.get("conA"); val conB: IntArray = P.get("conB")
            val conRest: DoubleArray = P.get("conRest"); val conCount: Int = P.get("conCount")
            val conDead: BooleanArray = P.get("conDead")
            val conMuscle: IntArray = P.get("conMuscle")
            val ml = P.body.meanLinkLength
            println("  клетка #%d: мышца %d, кость %d, свободная %s"
                .format(i, muscleOf[i], boneOf[i], if (isFree[i]) "да" else "нет"))
            for (c in 0 until conCount) {
                if (conDead[c] || (conA[c] != i && conB[c] != i)) continue
                val j = if (conA[c] == i) conB[c] else conA[c]
                val d = Math.hypot(P.px[i] - P.px[j], P.py[i] - P.py[j])
                println("    связь с #%d: длина %.3f покоя (мышца %d, покой %.3f св)"
                    .format(j, d / conRest[c], conMuscle[c], conRest[c] / ml))
            }
            if (ct != null) for (k in 0 until ct.contactCount) {
                val a = ct.contactI(k); val b = ct.contactJ(k)
                if (a != i && b != i) continue
                val j = if (a == i) b else a
                val d = Math.hypot(P.px[i] - P.px[j], P.py[i] - P.py[j])
                println("    контакт с #%d: %.3f упора".format(j, d / ct.contactDistanceOf(i, j)))
            }
            var sub2 = 0
            P.demo.dbgSubstepHook = { st ->
                println("    подшаг %d.%02d: x %+.5f y %+.5f  v %.4f кл/тик"
                    .format(sub2, st, P.px[i] / ml, P.py[i] / ml,
                        Math.hypot(P.vx[i], P.vy[i]) * dt / ml))
            }
            for (f in 0 until 2) { sub2 = f; P.frameHold(dt, which) }
            P.demo.dbgSubstepHook = null
            return
        }
        var comPeak = 0.0; var cellPeak = 0.0; var atCom = 0
        // Отпускаем: цель нулевая, дальше только распрямление и среда.
        for (f in 0 until Math.round(3.0 / dt).toInt()) {
            P.frame(dt, sub, contract = false)
            val c = comSpeed(); if (c > comPeak) { comPeak = c; atCom = f }
            val q = peakCell(); if (q > cellPeak) cellPeak = q
        }
        println("ОТПУСКАНИЕ мышцы %d на %s после %.1f с удержания:".format(which, path, secs))
        println("  под нагрузкой: центр масс %.4f, самая быстрая клетка %.4f клетки/тик%s"
            .format(comHeld, cellHeld, whoHeld))
        println("  после отпускания: центр масс МАКС %.4f (через %d тиков), самая быстрая клетка %.4f%s"
            .format(comPeak, atCom, cellPeak, who()))
        println("  порвано связей %d".format(P.killedLinks()))
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
        // Пик скорости КЛЕТКИ за прогон: с чем сравнивать рывок при отпускании мышцы.
        var vPeak = 0.0; var vWho = -1
        var worstTick = 0.0; var worstTickFr = -1; var worstTickReb = 0; var worstReb = 0
        val marks = intArrayOf(Math.round(1.0 / dt).toInt(), Math.round(5.0 / dt).toInt())
        val at = IntArray(marks.size)
        for (fr in 0 until frames) {
            if (idle) P.frame(dt, sub, contract = false)
            else if (hold >= 0) P.frameHold(dt, hold)
            else P.frameGait(dt, fr)
            if (first < 0 && P.killedLinks() > 0) first = fr
            // Худший тик: именно он и виден как рывок, среднее тут ничего не говорит.
            val ms = P.demo.dbgTickNs / 1e6
            if (ms > worstTick) { worstTick = ms; worstTickFr = fr; worstTickReb = P.demo.dbgRebuildN }
            if (P.demo.dbgRebuildN > worstReb) worstReb = P.demo.dbgRebuildN
            P.demo.dbgRebuildN = 0
            for (i in 0 until P.n) {
                val v = Math.hypot(P.vx[i], P.vy[i]) * dt / P.body.meanLinkLength
                if (v > vPeak) { vPeak = v; vWho = i }
            }
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
        println("  пик скорости клетки за прогон %.4f клетки/тик (#%d)".format(vPeak, vWho))
        run {
            val r = d.dbgRebuildTot
            println("  пересборок %d, всего %.0f мс: списки %.0f, контур %.0f, изгиб+лоскуты %.0f, организмы %.0f, контакты %.0f"
                .format(d.dbgRebuildCount, r.sum() / 1e6, r[0] / 1e6, r[1] / 1e6, r[2] / 1e6, r[3] / 1e6, r[4] / 1e6))
        }
        println("  худший тик %.1f мс (кадр %d, пересборок в нём %d); больше всего пересборок за тик %d"
            .format(worstTick, worstTickFr, worstTickReb, worstReb))
        P.contactsObj()?.let { ct ->
            val c = ct.cfgNs
            println("    из них внутри контактов: смежность %.0f мс, контур %.0f, радиусы %.0f, пары покоя %.0f"
                .format(c[0] / 1e6, c[1] / 1e6, c[2] / 1e6, c[3] / 1e6))
        }
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

/** Внутри ли клетка какого-нибудь ЖИВОГО треугольника чужого организма. */
private fun insideAny(P: Probe, i: Int): Boolean {
    val org: IntArray = P.get("organismOf")
    val dead: BooleanArray = P.get("cellDead")
    val ta: IntArray = P.get("triA"); val tb: IntArray = P.get("triB"); val tc: IntArray = P.get("triC")
    val tn: Int = P.get("triCount")
    val x = P.px[i]; val y = P.py[i]
    for (t in 0 until tn) {
        val a = ta[t]; val b = tb[t]; val c = tc[t]
        if (a == i || b == i || c == i || dead[a] || dead[b] || dead[c]) continue
        if (org[a] == org[i]) continue
        val ax = P.px[a]; val ay = P.py[a]; val bx = P.px[b]; val by = P.py[b]
        val cx = P.px[c]; val cy = P.py[c]
        if (x < minOf(ax, bx, cx) || x > maxOf(ax, bx, cx) || y < minOf(ay, by, cy) || y > maxOf(ay, by, cy)) continue
        val d1 = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        val d2 = (cx - bx) * (y - by) - (cy - by) * (x - bx)
        val d3 = (ax - cx) * (y - cy) - (ay - cy) * (x - cx)
        if ((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0)) continue
        return true
    }
    return false
}
