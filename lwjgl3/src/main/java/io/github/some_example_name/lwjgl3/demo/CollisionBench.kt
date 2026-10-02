package io.github.some_example_name.lwjgl3.demo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * СТЕНД СТОЛКНОВЕНИЙ: много разных сцен, и каждая — числом, а не на глаз.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ СТЕНД. Всё, что видно глазом — «дребезжит», «кусок залез внутрь»,
 * «частицу как маятник качает», — субъективно ровно до тех пор, пока не названо
 * величиной. А величина нужна не для красоты: без неё нельзя отличить починку от
 * совпадения. За время работы над контактами четыре правки выглядели улучшением на
 * одном прогоне и оказались ухудшением на шестнадцати.
 *
 * ДВА ПРАВИЛА, БЕЗ КОТОРЫХ ЭТОТ СТЕНД БЕСПОЛЕЗЕН
 * ----------------------------------------------
 *
 * 1. С ВКЛЮЧЁННЫМ РАЗРЫВОМ ОДИНОЧНЫЙ ПРОГОН НЕ ИЗМЕРЯЕТ НИЧЕГО. Траектории хаотичны:
 *    один и тот же код на одной и той же сцене даёт |P| то 0.4, то 240, то 800.
 *    Поэтому каждая сцена гоняется по сетке вариаций, а печатается МЕДИАНА. Среднее
 *    не годится — одна улетевшая щепка задирает его вдесятеро.
 *
 * 2. МЕРИТЬ НАДО В СПОКОЙНОЙ ФАЗЕ, ПОСЛЕ УДАРА. Во время удара контакты обязаны
 *    работать, и большой импульс там ничего не означает. Патология — это работа,
 *    которая НЕ КОНЧАЕТСЯ, когда всё уже разлетелось и успокоилось.
 *
 * ЧТО МЕРИТСЯ И ПОЧЕМУ ИМЕННО ЭТО
 * -------------------------------
 *
 *   дребезг    Суммарный нормальный импульс контактов за спокойную фазу. Работает
 *              только потому, что ГРАВИТАЦИИ НЕТ: покоящийся контакт ничем не
 *              нагружен, значит любой ненулевой импульс — это спор решателя с самим
 *              собой. С гравитацией пришлось бы вычитать вес, и метрика поплыла бы.
 *
 *   выброс     Наибольшая скорость ОДНОЙ клетки относительно центра масс её тела, в
 *              клетках за тик. Это и есть «частица получила слишком большой импульс и
 *              качается маятником»: тело стоит, а клетка внутри него летает.
 *
 *   внутри     Сколько клеток оказалось СТРОГО ВНУТРИ чужого контура и осталось там
 *              после успокоения. Лучевой тест по граничным рёбрам чужого тела.
 *              Отдельно считается «внутри кости» — то, что видно на медузе после двух
 *              толчков.
 *
 *   проник     Наибольшее перекрытие несвязанных граничных пар, в долях порога.
 *
 *   |P|        Импульс сцены. Сцены собраны так, что он стартует РОВНО с нуля
 *              (зеркальные копии со встречными скоростями), а все внутренние стадии
 *              решателя сохраняют импульс до нуля — замерено постадийно. Значит любое
 *              ненулевое значение в конце сделано средой из колебаний границы.
 *
 * ЗАПУСК:  gradlew :lwjgl3:collisionBench
 *          gradlew :lwjgl3:collisionBench -Pbody=body-export-кирпич.txt
 *          gradlew :lwjgl3:collisionBench -Pscene=бассейн
 */
private fun demoField(name: String): Any? =
    RealBodyDemo::class.java.getDeclaredField(name).apply { isAccessible = true }.get(Probe.demo)

private fun demoInt(name: String): Int =
    RealBodyDemo::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(Probe.demo)

private fun setDemo(name: String, v: Any) =
    RealBodyDemo::class.java.getDeclaredField(name).apply { isAccessible = true }.set(Probe.demo, v)

const val chr10 = 10.toChar()

fun main(args: Array<String>) {
    val path = if (args.isNotEmpty() && args[0].isNotEmpty()) args[0] else "body-export.txt"
    val only = if (args.size > 1 && args[1].isNotEmpty()) args[1] else null
    val hunt = args.size > 2 && args[2].isNotEmpty()
    val P = Probe
    P.boot(path)
    val dt = P.const("DT")
    val sub = P.constInt("SUBSTEPS")
    val meanLink = P.body.meanLinkLength.toDouble()
    val vmax = P.const("MAX_SPEED_CELLS_PER_TICK")

    P.setContacts(true)
    P.resetState()

    // ------------------------------------------------------------------
    //  ОХОТА ЗА ХУДШИМ КУСКОМ.
    //
    //  Стенд брал ПЕРВЫЙ попавшийся мягкий кусок сверху, и он оказывался здоровым:
    //  улетал по прямой, путь равен сносу, контакты в его движение не вкладывали
    //  ничего. А руками дребезг виден каждый запуск. Значит виноват не любой кусок,
    //  а какой-то конкретный, и искать его надо перебором.
    //
    //  Момент включения гребка решает, что именно оторвётся, поэтому он и перебирается.
    //  Окно печатается, и по нему кусок воспроизводится ровно: -Phunt=1 находит,
    //  печатает окно, дальше это окно можно зашить в сцену.
    //
    //  Мера — ОТНОШЕНИЕ ПУТИ К СНОСУ. Кусок, летящий по инерции, даёт около единицы:
    //  путь и снос совпадают. Кусок, которого дёргает, наматывает путь, никуда не
    //  уезжая, и отношение растёт. Именно это и есть блуждание, а не скорость.
    // ------------------------------------------------------------------
    val order = (0 until P.organismCount).sortedByDescending { P.organismSize(it) }
    val oa = order[0]
    val ob = if (order.size > 1) order[1] else order[0]

    fun com(o: Int): DoubleArray {
        var x = 0.0; var y = 0.0; var c = 0
        for (i in 0 until P.n) if (P.organismOf[i] == o) { x += P.px[i]; y += P.py[i]; c++ }
        return if (c == 0) doubleArrayOf(0.0, 0.0) else doubleArrayOf(x / c, y / c)
    }
    val ca0 = com(oa); val cb0 = com(ob)
    var axX = cb0[0] - ca0[0]; var axY = cb0[1] - ca0[1]
    val axLen = sqrt(axX * axX + axY * axY)
    require(axLen > 1e-9) { "тела совпали — ось не определена (позиции считаются ПОСЛЕ resetState)" }
    axX /= axLen; axY /= axLen
    val perpX = -axY; val perpY = axX

    // ---------- строительные блоки сцен ----------

    fun rotateAndShift(o: Int, deg: Double, offCells: Double) {
        val c = com(o)
        val a = Math.toRadians(deg); val cs = cos(a); val sn = sin(a)
        for (i in 0 until P.n) {
            if (P.organismOf[i] != o) continue
            val x = P.px[i] - c[0]; val y = P.py[i] - c[1]
            P.px[i] = c[0] + x * cs - y * sn + perpX * offCells * meanLink
            P.py[i] = c[1] + x * sn + y * cs + perpY * offCells * meanLink
            P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i]
        }
    }

    fun launch(o: Int, dirX: Double, dirY: Double, cellsPerTick: Double) {
        val sp = cellsPerTick * meanLink / dt
        for (i in 0 until P.n) if (P.organismOf[i] == o) {
            P.vx[i] = dirX * sp; P.vy[i] = dirY * sp
        }
    }

    /**
     * РАССЫПАТЬ тело на одиночные клетки: все рабочие связи объявляются мёртвыми.
     *
     * Внутрикостных связей в conA/conB нет, поэтому кости уцелеют — и это не изъян
     * сцены, а ровно тот случай, который описан руками: горсть одиночных клеток
     * вперемешку с твёрдыми обломками.
     */
    fun shatter(o: Int) {
        val conDead = demoField("conDead") as BooleanArray
        val conA = demoField("conA") as IntArray
        val cc = demoInt("conCount")
        for (c in 0 until cc) if (P.organismOf[conA[c]] == o) conDead[c] = true
        setDemo("tearsPending", true)
        P.setTearing(true)
        P.frame(dt, sub, contract = false)     // simulate() пересоберёт топологию
    }

    /** Клетки тела [o], сложенные в плотную решётку вокруг точки. */
    fun packInto(o: Int, cx: Double, cy: Double, stepCells: Double) {
        val ids = (0 until P.n).filter { P.organismOf[it] == o }
        val side = Math.ceil(sqrt(ids.size.toDouble())).toInt().coerceAtLeast(1)
        val step = stepCells * meanLink
        for ((k, i) in ids.withIndex()) {
            P.px[i] = cx + (k % side - side / 2.0) * step
            P.py[i] = cy + (k / side - side / 2.0) * step
            P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i]
            P.vx[i] = 0.0; P.vy[i] = 0.0
        }
    }

    /** Граничные рёбра, у которых оба конца в теле [o]. */
    fun boundaryOf(o: Int): Pair<IntArray, IntArray> {
        val ba = demoField("boundA") as IntArray
        val bb = demoField("boundB") as IntArray
        val nb = demoInt("boundCount")
        val a = ArrayList<Int>(); val b = ArrayList<Int>()
        for (e in 0 until nb) if (P.organismOf[ba[e]] == o && P.organismOf[bb[e]] == o) {
            a.add(ba[e]); b.add(bb[e])
        }
        return a.toIntArray() to b.toIntArray()
    }

    /** Лучевой тест: лежит ли точка внутри контура [o]. */
    fun insideOf(o: Int, x: Double, y: Double, ba: IntArray, bb: IntArray): Boolean {
        var crossings = 0
        for (e in ba.indices) {
            val ax = P.px[ba[e]]; val ay = P.py[ba[e]]
            val bx = P.px[bb[e]]; val by = P.py[bb[e]]
            if ((ay > y) == (by > y)) continue
            val t = (y - ay) / (by - ay)
            if (ax + t * (bx - ax) > x) crossings++
        }
        return crossings % 2 == 1
    }

    /** Затолкать [count] клеток тела [src] внутрь контура тела [dst]. */
    fun pushInside(src: Int, dst: Int, count: Int) {
        val (ba, bb) = boundaryOf(dst)
        if (ba.isEmpty()) return
        val c = com(dst)
        val ids = (0 until P.n).filter { P.organismOf[it] == src }.take(count)
        var placed = 0
        var ring = 0.0
        for (i in ids) {
            // По спирали от центра чужого тела, пока точка внутри контура.
            var ok = false
            var guard = 0
            while (!ok && guard < 64) {
                val ang = placed * 2.39996          // золотой угол — точки не липнут в ряд
                val r = ring * meanLink
                val x = c[0] + r * cos(ang); val y = c[1] + r * sin(ang)
                if (insideOf(dst, x, y, ba, bb)) {
                    P.px[i] = x; P.py[i] = y
                    P.prevX[i] = x; P.prevY[i] = y
                    P.vx[i] = 0.0; P.vy[i] = 0.0
                    ok = true
                }
                ring += 0.35
                guard++
            }
            placed++
        }
    }

    // ---------- измерители ----------

    fun comVel(o: Int): DoubleArray {
        var m = 0.0; var sx = 0.0; var sy = 0.0
        for (i in 0 until P.n) {
            if (P.organismOf[i] != o || P.invMass[i] <= 0.0) continue
            val w = 1.0 / P.invMass[i]
            m += w; sx += w * P.vx[i]; sy += w * P.vy[i]
        }
        return if (m <= 0.0) doubleArrayOf(0.0, 0.0) else doubleArrayOf(sx / m, sy / m)
    }

    /** Наибольшая скорость клетки ОТНОСИТЕЛЬНО своего тела, в клетках за тик. */
    fun worstFlail(): Double {
        val vc = HashMap<Int, DoubleArray>()
        var worst = 0.0
        for (i in 0 until P.n) {
            val o = P.organismOf[i]
            val v = vc.getOrPut(o) { comVel(o) }
            val dx = P.vx[i] - v[0]; val dy = P.vy[i] - v[1]
            val s = sqrt(dx * dx + dy * dy) * dt / meanLink
            if (s > worst) worst = s
        }
        return worst
    }

    /**
     * [клеток внутри чужой ткани, из них внутри кости].
     *
     * Проверяются только САМЫЕ КРУПНЫЕ куски: мелкие обломки чужую клетку внутри себя
     * удержать не могут, а перебор всех шестидесяти компонент стоил бы дороже самой
     * симуляции. Перед лучевым тестом стоит отсечение по габаритам — оно снимает
     * подавляющее большинство клеток одной проверкой вместо обхода всего контура.
     */
    fun trapped(): DoubleArray {
        val boneOf = demoField("boneOf") as IntArray
        // Лопнувшая клетка лежит, где умерла, но её уже нет: застрявшей она не считается.
        val dead = demoField("cellDead") as BooleanArray
        val bigs = (0 until P.organismCount)
            .filter { P.organismSize(it) > 20 }
            .sortedByDescending { P.organismSize(it) }
            .take(3)
        var total = 0; var inBone = 0
        for (o in bigs) {
            val (ba, bb) = boundaryOf(o)
            if (ba.size < 3) continue
            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (e in ba.indices) {
                for (v in intArrayOf(ba[e], bb[e])) {
                    if (P.px[v] < minX) minX = P.px[v]
                    if (P.px[v] > maxX) maxX = P.px[v]
                    if (P.py[v] < minY) minY = P.py[v]
                    if (P.py[v] > maxY) maxY = P.py[v]
                }
            }
            for (i in 0 until P.n) {
                if (P.organismOf[i] == o || dead[i]) continue
                val x = P.px[i]; val y = P.py[i]
                if (x < minX || x > maxX || y < minY || y > maxY) continue
                if (!insideOf(o, x, y, ba, bb)) continue
                total++
                // «Внутри кости» — если ближайшая ГРАНИЧНАЯ клетка чужого тела костная.
                var best = Double.MAX_VALUE; var bestJ = -1
                for (e in ba.indices) {
                    val j = ba[e]
                    val dx = x - P.px[j]; val dy = y - P.py[j]
                    val d = dx * dx + dy * dy
                    if (d < best) { best = d; bestJ = j }
                }
                if (bestJ >= 0 && boneOf[bestJ] >= 0) inBone++
            }
        }
        return doubleArrayOf(total.toDouble(), inBone.toDouble())
    }

    // ---------- сцены ----------

    // СЦЕНЫ БЕРУТСЯ ИЗ BenchScenes — ОДНИ И ТЕ ЖЕ, ЧТО В ДЕМО.
    // Иначе числа стенда и картинка в окне отвечали бы за разные постановки.
    var gaitFrame = 0
    val ctx = SceneCtx(
        n = P.n, px = P.px, py = P.py, prevX = P.prevX, prevY = P.prevY,
        vx = P.vx, vy = P.vy,
        organismOfCell = { i -> P.organismOf[i] },
        organismSizeOf = { o -> P.organismSize(o) },
        organismCount = { P.organismCountNow() },
        isFreeOf = { i -> P.isFree(i) },
        invMass = P.invMass,
        contactRadiusOf = { i -> P.contactsObj()?.contactRadiusOf(i) ?: 0.3 * meanLink },
        meanLink = meanLink, dt = dt, vmax = vmax,
        shatter = { o -> shatter(o) },
        step = { P.frame(dt, sub, contract = false) },
        stepContract = { P.frameGait(dt, gaitFrame++) },
        inBoneOf = { i -> P.rigidBones.any { b -> b.contains(i) } },
        focus = { _, _ -> },
    )

    if (hunt) {
        println("=== охота: какой кусок мечется сильнее всех ===")
        println("  окно гребка | кусков | худший: клеток, снос, путь, путь/снос, контактов")
        var bestRatio = 0.0
        var bestStart = -1
        var bestEnd = -1
        var bestPiece = -1
        for (start in intArrayOf(0, 40, 80, 120, 160, 200, 240, 280, 320, 360)) {
            val stop = start + 450          // 15 с гребка
            P.resetState(); P.resetCounters()
            P.setTearing(true)
            val whole = (0 until P.organismCount).maxByOrNull { P.organismSize(it) } ?: 0
            val origin = IntArray(P.n) { P.organismOf[it] }
            for (t in 0 until stop) {
                if (t >= start) P.frameGait(dt, t - start) else P.frame(dt, sub, contract = false)
            }
            P.refreshTopology()
            val frags = BenchScenes.fragments(ctx, whole, origin)
            // Каждый кусок ведётся 10 с ПОСЛЕ гребка, гребок выключен: смотрим, как
            // он живёт сам, а не как его толкает работающая мышца.
            val ids = frags.map { it.id }
            val cellsOf = ids.map { o -> (0 until P.n).filter { P.organismOf[it] == o } }
            val startX = cellsOf.map { c -> c.map { P.px[it] }.average() }
            val startY = cellsOf.map { c -> c.map { P.py[it] }.average() }
            val prevX = startX.toDoubleArray(); val prevY = startY.toDoubleArray()
            val path = DoubleArray(ids.size)
            for (fr in 0 until (10.0 / dt).toInt()) {
                P.frame(dt, sub, contract = false)
                for (k in ids.indices) {
                    val cx = cellsOf[k].map { P.px[it] }.average()
                    val cy = cellsOf[k].map { P.py[it] }.average()
                    path[k] += Math.hypot(cx - prevX[k], cy - prevY[k])
                    prevX[k] = cx; prevY[k] = cy
                }
            }
            var localBest = -1; var localRatio = 0.0
            for (k in ids.indices) {
                if (frags[k].bony || cellsOf[k].size < 4) continue
                val moved = Math.hypot(prevX[k] - startX[k], prevY[k] - startY[k])
                val ratio = if (moved > 1e-9) path[k] / moved else path[k] / meanLink
                if (ratio > localRatio) { localRatio = ratio; localBest = k }
            }
            if (localBest < 0) {
                println("  %4d..%4d | %6d | мягких кусков нет".format(start, stop, ids.size))
                continue
            }
            val k = localBest
            val moved = Math.hypot(prevX[k] - startX[k], prevY[k] - startY[k])
            println("  %4d..%4d | %6d | %d клеток, снос %6.2f, путь %7.2f, отношение %6.2f"
                .format(start, stop, ids.size, cellsOf[k].size,
                    moved / meanLink, path[k] / meanLink, localRatio))
            // Геометрия худшего куска этого окна — по ней рисуется картинка.
            run {
                P.refreshTopology()
                val mine = BooleanArray(P.n)
                for (i in cellsOf[k]) mine[i] = true
                val ct = P.contactsObj()
                val onEdge = BooleanArray(P.n)
                for (e in 0 until P.boundCount) {
                    if (mine[P.boundA[e]]) onEdge[P.boundA[e]] = true
                    if (mine[P.boundB[e]]) onEdge[P.boundB[e]] = true
                }
                val sb = StringBuilder()
                sb.append("meanLink ").append(meanLink).append(chr10)
                for (i in cellsOf[k]) sb.append("c ").append(i).append(' ').append(P.px[i])
                    .append(' ').append(P.py[i]).append(' ')
                    .append(ct?.contactRadiusOf(i) ?: 0.0).append(' ')
                    .append(if (onEdge[i]) 1 else 0).append(chr10)
                for (c in 0 until P.conCount) {
                    val a = P.conA[c]; val b = P.conB[c]
                    if (mine[a] && mine[b]) sb.append("l ").append(a).append(' ').append(b).append(chr10)
                }
                for (e in 0 until P.boundCount) {
                    val a = P.boundA[e]; val b = P.boundB[e]
                    if (mine[a] && mine[b]) sb.append("b ").append(a).append(' ').append(b).append(chr10)
                }
                val f = java.io.File(System.getProperty("java.io.tmpdir"), "hunt_%d.txt".format(start))
                f.writeText(sb.toString())
                println("       DUMP %s".format(f.path))
            }
            if (localRatio > bestRatio) {
                bestRatio = localRatio; bestStart = start; bestEnd = stop; bestPiece = frags[k].id
            }
        }
        println()
        println("  ХУДШЕЕ ОКНО: гребок с тика %d по %d, кусок #%d, отношение пути к сносу %.2f"
            .format(bestStart, bestEnd, bestPiece, bestRatio))
        return
    }
    val scenes = BenchScenes.all()

    println("тело: $path,  клеток ${P.n},  средняя связь %.4f".format(meanLink))
    println()
    println("%-20s %9s %8s %8s %8s %8s %8s %8s".format(
        "сцена", "дребезг", "выброс", "внутри", "в кости", "проник", "|P|", "умерло"))

    for (s in scenes) {
        if (only != null && !s.name.startsWith(only)) continue
        val ch = ArrayList<Double>(); val fl = ArrayList<Double>()
        val tr = ArrayList<Double>(); val bn = ArrayList<Double>()
        val pn = ArrayList<Double>(); val pp = ArrayList<Double>()
        val dd = ArrayList<Double>()

        // 3x3 = 9 вариаций: медиана уже устойчива, а прогон втрое короче.
        // Сцены с засыпкой углом не вращаются (вращать россыпь бессмысленно), им нужен
        // только сдвиг — поэтому углов у них один, а сдвигов три. См. BenchScenes.
        val spin = s.name.startsWith("лобовой") || s.name.startsWith("вскользь") ||
            s.name.startsWith("мягкое") || s.name.startsWith("два толчка")
        val angles = if (spin) doubleArrayOf(0.0, 45.0, 90.0) else doubleArrayOf(0.0)
        val offs = if (s.vary) doubleArrayOf(0.0, 6.0, 12.0) else doubleArrayOf(0.0)
        for (angle in angles) for (off in offs) {
            P.setTearing(true)
            P.resetState(); P.resetCounters()
            s.setup(ctx, angle, off)

            // Удар: контакты обязаны работать, здесь не мерим.
            for (fr in 1..(2.0 / dt).toInt()) P.frame(dt, sub, contract = false)

            // Спокойная фаза: вот тут любая работа контактов — патология.
            val ct = P.contactsObj()!!
            ct.impulseAccum = 0.0
            val dead0 = demoField("deadCount") as Int
            var flail = 0.0
            for (fr in 1..(6.0 / dt).toInt()) {
                P.frame(dt, sub, contract = false)
                if (fr % 10 == 0) { val f = worstFlail(); if (f > flail) flail = f }
            }
            // УДРАЛИ ЛИ ЧАСТИЦЫ ИЗ ЁМКОСТИ. Круг на экране бывает пуст — значит мерили
            // не давление, а утечку. Считаем в конце спокойной фазы.
            if (BenchScenes.lastRadius > 0.0 && System.getenv("CB_SCENE_INFO") != null) {
                var stay = 0
                for (k in BenchScenes.lastInside) {
                    val dx = P.px[k] - BenchScenes.lastCx; val dy = P.py[k] - BenchScenes.lastCy
                    if (dx * dx + dy * dy <= BenchScenes.lastRadius * BenchScenes.lastRadius) stay++
                }
                println("  в ёмкости осталось %d из %d".format(stay, BenchScenes.lastFill))
                BenchScenes.lastRadius = -1.0
            }
            val t = trapped()
            ch.add(P.contactsObj()!!.impulseAccum / meanLink)
            fl.add(flail)
            tr.add(t[0]); bn.add(t[1])
            pn.add(P.contactsObj()!!.maxPenetration(P.px, P.py))
            pp.add(sqrt(P.pX() * P.pX() + P.pY() * P.pY()))
            dd.add(((demoField("deadCount") as Int) - dead0).toDouble())
            if (System.getenv("CB_DIAG") != null) {
                val c2 = P.contactsObj()!!
                val wi = c2.worstI; val wj = c2.worstJ
                val dm = demoField("cellDead") as BooleanArray
                val im = demoField("invMass") as DoubleArray
                println("    [%s угол %.0f сдвиг %.0f] умерло всего %d (давл %d разд %d застр %d), проник %.3f: #%d орг %d m=%s своб %s  #%d орг %d m=%s своб %s, d=%.3f св".format(
                    s.name, angle, off, demoField("deadCount") as Int,
                    demoField("pressureBurstCount") as Int, demoField("crushBurstCount") as Int, demoField("trapKillCount") as Int,
                    pn.last(), wi, if (wi >= 0) P.organismOf[wi] else -1, if (wi >= 0) (if (im[wi] == 0.0) "0" else "1") else "-", if (wi >= 0) P.isFree(wi) else false,
                    wj, if (wj >= 0) P.organismOf[wj] else -1, if (wj >= 0) (if (im[wj] == 0.0) "0" else "1") else "-", if (wj >= 0) P.isFree(wj) else false,
                    if (wi >= 0) Math.hypot(P.px[wi] - P.px[wj], P.py[wi] - P.py[wj]) / meanLink else 0.0))
            }
        }
        fun med(v: List<Double>) = if (v.isEmpty()) 0.0 else v.sorted()[v.size / 2]
        println("%-20s %9.1f %8.2f %8.0f %8.0f %8.3f %8.2f %8.0f".format(
            s.name, med(ch), med(fl), med(tr), med(bn), med(pn), med(pp), med(dd)))
    }

    // ------------------------------------------------------------------
    //  ДЕТЕРМИНИРОВАННЫЙ МИКРО-ЗАМЕР: две одиночные клетки в лоб.
    //
    //  Зачем он нужен отдельно от сцен выше. Сцены с разрывом ХАОТИЧНЫ: медиана по
    //  девяти прогонам гуляет на порядок, и правку размером «в полтора раза» на них
    //  различить нельзя — проверено, три варианта одного и того же изменения дали
    //  |P| 17.8 / 115.0 / 62.9 без всякой закономерности.
    //
    //  Здесь всё повторяемо до бита: две свободные клетки, известная скорость, один
    //  контакт. Мерится УСИЛЕНИЕ — во сколько раз скорость разлёта больше скорости
    //  подлёта. Физика говорит, что это должно быть ровно CONTACT_RESTITUTION.
    //  Всё, что выше, — артефакт позиционного решателя: поправка, делённая на
    //  крошечный шаг подшага, превращается в скорость, которой неоткуда взяться.
    // ------------------------------------------------------------------
    run {
        val free = (0 until P.n).filter { P.isFree(it) }
        if (free.size >= 2) {
            val i = free[0]; val j = free[1]
            val e = P.const("CONTACT_RESTITUTION")
            println()
            println("две одиночные клетки в лоб (детерминировано, разрыва нет)")
            println("  подлёт, кл/тик | разлёт, кл/тик | усиление | должно быть %.2f".format(e))
            for (v in doubleArrayOf(0.1, 0.25, 0.5, 1.0, 2.0, 4.0)) {
                P.setTearing(false)
                P.resetState(); P.resetCounters()
                // Уводим всех прочих далеко, чтобы в кадре была ровно одна пара.
                for (k in 0 until P.n) if (k != i && k != j) {
                    P.px[k] = 1e4 + k * 0.5; P.py[k] = 1e4
                    P.prevX[k] = P.px[k]; P.prevY[k] = P.py[k]
                    P.vx[k] = 0.0; P.vy[k] = 0.0
                }
                val ct0 = P.contactsObj()!!
                val gap = (ct0.contactRadiusOf(i) + ct0.contactRadiusOf(j)) * 3.0
                val sp = v * meanLink / dt
                P.px[i] = -gap; P.py[i] = 0.0; P.vx[i] = sp; P.vy[i] = 0.0
                P.px[j] = gap; P.py[j] = 0.0; P.vx[j] = -sp; P.vy[j] = 0.0
                P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i]
                P.prevX[j] = P.px[j]; P.prevY[j] = P.py[j]

                var worst = 0.0
                for (fr in 1..90) {
                    P.frame(dt, sub, contract = false)
                    val dx = P.px[i] - P.px[j]
                    val rel = (P.vx[i] - P.vx[j]) * (if (dx < 0) 1.0 else -1.0)
                    // rel > 0 означает расхождение
                    val sepCells = -rel * dt / meanLink
                    if (sepCells > worst) worst = sepCells
                }
                // ДЕЛИТЬ НАДО НА ОТНОСИТЕЛЬНУЮ СКОРОСТЬ, А НЕ НА СКОРОСТЬ ОДНОЙ КЛЕТКИ.
                // Клетки летят НАВСТРЕЧУ по v каждая, значит сближение равно 2v, и
                // правильный отскок — e * 2v. Деление на v давало ровное удвоение и
                // выглядело как ошибка решателя; ошибка была в замере.
                println("  %14.2f | %14.3f | %8.2f |".format(v, worst, worst / (2.0 * v)))
            }
        }
    }

    // ------------------------------------------------------------------
    //  ДЕТЕРМИНИРОВАННЫЙ МИКРО-ЗАМЕР: одиночная клетка в КОСТЬ.
    //
    //  Прилипание руками видно именно на кости, а замер двух свободных клеток его не
    //  ловит: там обе стороны одинаково лёгкие. У кости эффективная масса это масса
    //  всего кластера, и если проход отскока считает её как одну клетку, частица
    //  получает лишь часть положенного, а толчок в саму клетку кости проекция на
    //  следующем подшаге стирает.
    //
    //  Мерится то же усиление, что и у пары. Кость тяжёлая и почти не движется,
    //  поэтому сближение равно скорости одной клетки, и отскок обязан выйти около
    //  CONTACT_RESTITUTION.
    // ------------------------------------------------------------------
    run {
        val free = (0 until P.n).filter { P.isFree(it) }
        val bone = P.rigidBones.maxByOrNull { it.size }
        if (free.isNotEmpty() && bone != null) {
            val e = P.const("CONTACT_RESTITUTION")
            val i = free[0]
            println()
            println("клетка в кость (детерминировано, разрыва нет)")
            println("  подлёт, кл/тик | разлёт, кл/тик | усиление | должно быть %.2f".format(e))
            for (v in doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0)) {
                P.setTearing(false)
                P.resetState(); P.resetCounters()
                var bcx = 0.0; var bcy = 0.0
                for (k in bone) { bcx += P.px[k]; bcy += P.py[k] }
                bcx /= bone.size; bcy /= bone.size
                val tgt = bone.maxByOrNull { (P.px[it] - bcx) * (P.px[it] - bcx) + (P.py[it] - bcy) * (P.py[it] - bcy) }!!
                var ox = P.px[tgt] - bcx; var oy = P.py[tgt] - bcy
                val ol = sqrt(ox * ox + oy * oy); if (ol > 1e-12) { ox /= ol; oy /= ol }
                val ct0 = P.contactsObj()!!
                val gap = (ct0.contactRadiusOf(i) + ct0.contactRadiusOf(tgt)) * 3.0
                val sp = v * meanLink / dt
                P.px[i] = P.px[tgt] + ox * gap; P.py[i] = P.py[tgt] + oy * gap
                P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i]
                P.vx[i] = -ox * sp; P.vy[i] = -oy * sp
                var worst = 0.0
                for (fr in 1..90) {
                    P.frame(dt, sub, contract = false)
                    val away = (P.vx[i] * ox + P.vy[i] * oy) * dt / meanLink
                    if (away > worst) worst = away
                }
                println("  %14.2f | %14.3f | %8.2f | BONEHIT".format(v, worst, worst / v))
            }
        }
    }

    // ------------------------------------------------------------------
    //  ДЕТЕРМИНИРОВАННЫЙ МИКРО-ЗАМЕР: клетка, положенная ВНУТРЬ ткани В ПОКОЕ.
    //
    //  Самый чистый замер выброса, какой вообще можно поставить: и клетка, и тело
    //  стартуют с НУЛЕВОЙ скоростью. Значит любая скорость, которую клетка наберёт, —
    //  целиком артефакт расталкивания, и сравнивать её не с чем не нужно.
    //
    //  Именно здесь живёт «маятник»: updateVelocities делает скорость как поправка,
    //  делённая на h, а h это тик на число подшагов. Поправка на потолке
    //  CONTACT_MAX_STEP даёт 0.25 * SUBSTEPS = четыре клетки за тик при потолке
    //  восемь — и это НЕ ЗАВИСИТ от того, с какой скоростью клетка туда попала.
    // ------------------------------------------------------------------
    run {
        val free = (0 until P.n).filter { P.isFree(it) }
        if (free.isNotEmpty()) {
            println()
            println("клетка внутри ткани, всё стартует В ПОКОЕ (детерминировано)")
            println("  глубина, связей | пик скорости, кл/тик | за сколько тиков вышла")
            for (depth in doubleArrayOf(0.0, 0.5, 1.0, 2.0, 4.0)) {
                P.setTearing(false)
                P.resetState(); P.resetCounters()
                val i = free[0]
                // Прочие свободные клетки — подальше, чтобы не мешали.
                for (k in free.drop(1)) {
                    P.px[k] = 1e4 + k; P.py[k] = 1e4
                    P.prevX[k] = P.px[k]; P.prevY[k] = P.py[k]
                    P.vx[k] = 0.0; P.vy[k] = 0.0
                }
                // Сажаем на заданную глубину от центра тела вдоль оси.
                val c = com(oa)
                P.px[i] = c[0] + depth * meanLink; P.py[i] = c[1]
                P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i]
                P.vx[i] = 0.0; P.vy[i] = 0.0

                val (ba, bb) = boundaryOf(oa)
                var peak = 0.0
                var outAt = -1
                for (fr in 1..300) {
                    P.frame(dt, sub, contract = false)
                    val s = sqrt(P.vx[i] * P.vx[i] + P.vy[i] * P.vy[i]) * dt / meanLink
                    if (s > peak) peak = s
                    if (outAt < 0 && ba.size >= 3 && !insideOf(oa, P.px[i], P.py[i], ba, bb)) outAt = fr
                }
                println("  %15.1f | %20.2f | %s".format(
                    depth, peak, if (outAt >= 0) outAt.toString() else "не вышла"))
            }
        }
    }

    // ------------------------------------------------------------------
    //  ДЕТЕРМИНИРОВАННЫЙ МИКРО-ЗАМЕР: одиночная клетка ЛЕТИТ В МЕМБРАНУ.
    //
    //  Главный замер: именно так чужое попадает внутрь. Внутри его уже никто не
    //  вытолкнет — внутренние клетки тела в контактах не участвуют вовсе, там для
    //  гостя пустота, а наружу не пускает мембрана. Поэтому чинить надо ВХОД.
    //
    //  Ключевые числа, с которыми сравнивать: за подшаг клетка проходит
    //  v / SUBSTEPS клеток, а расталкивание за подшаг ограничено CONTACT_MAX_STEP.
    //  Как только первое больше второго, решатель перестаёт успевать.
    // ------------------------------------------------------------------
    run {
        val free = (0 until P.n).filter { P.isFree(it) }
        if (free.isNotEmpty()) {
            val i = free[0]
            println()
            println("одиночная клетка летит в мембрану (детерминировано, разрыва нет)")
            println("  скорость, кл/тик | путь за подшаг | ближе всего | прошла | CCD")
            for (v in doubleArrayOf(0.5, 1.0, 2.0, 4.0, 8.0)) {
                P.setTearing(false)
                P.resetState(); P.resetCounters()
                for (k in free.drop(1)) {
                    P.px[k] = 1e4 + k; P.py[k] = 1e4
                    P.prevX[k] = P.px[k]; P.prevY[k] = P.py[k]
                    P.vx[k] = 0.0; P.vy[k] = 0.0
                }
                // Стартуем слева от тела, летим строго в его центр.
                val c = com(oa)
                var minX = Double.MAX_VALUE
                for (k in 0 until P.n) if (P.organismOf[k] == oa && P.px[k] < minX) minX = P.px[k]
                P.px[i] = minX - 6.0 * meanLink; P.py[i] = c[1]
                P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i]
                val sp = v * meanLink / dt
                P.vx[i] = sp; P.vy[i] = 0.0

                // ОДНОЗНАЧНАЯ МЕТРИКА вместо лучевого теста: ближайший подход к
                // мембране в долях порога контакта. 1.0 — коснулась, 0.0 — центры
                // совпали, отрицательного не бывает. Лучевой тест на самой границе
                // ошибается (луч цепляет вершину), а это число ошибиться не может.
                val (ba, bb) = boundaryOf(oa)
                val ct0 = P.contactsObj()!!
                var closest = Double.MAX_VALUE
                var ccd = 0
                val startX = P.px[i]
                for (fr in 1..120) {
                    P.frame(dt, sub, contract = false)
                    ccd += P.contactsObj()!!.lastToiClamps
                    for (e in ba.indices) for (b in intArrayOf(ba[e], bb[e])) {
                        val dx = P.px[i] - P.px[b]; val dy = P.py[i] - P.py[b]
                        val rr = ct0.contactRadiusOf(i) + ct0.contactRadiusOf(b)
                        if (rr <= 0.0) continue
                        val r = sqrt(dx * dx + dy * dy) / rr
                        if (r < closest) closest = r
                    }
                }
                // Прошла ли насквозь: оказалась заметно правее самой правой клетки тела.
                var maxBx = -Double.MAX_VALUE
                for (k in 0 until P.n) if (P.organismOf[k] == oa && P.px[k] > maxBx) maxBx = P.px[k]
                val through = P.px[i] > maxBx
                println("  %16.1f | %14.2f | %7.3f | %12s | %d".format(
                    v, v / sub, closest, if (through) "НАСКВОЗЬ" else "нет", ccd))
            }
        }
    }

    // ------------------------------------------------------------------
    //  ДЕТЕРМИНИРОВАННЫЙ МИКРО-ЗАМЕР: ПЛОТНАЯ КУЧА ОДИНОЧНЫХ КЛЕТОК В ПОКОЕ.
    //
    //  Вот здесь и живёт дребезг. Один кусок, попавший внутрь чужой ткани, не делает
    //  НИЧЕГО: замер «клетка внутри ткани» даёт скорость 0.00 и полную неподвижность,
    //  потому что внутренние клетки тела в контактах не участвуют и толкать гостя
    //  нечем. А вот КУЧА одиночных клеток дребезжит сама по себе — они сталкиваются
    //  друг с другом, и плотная пачка взаимных перекрытий за один проход
    //  Гаусса-Зейделя не сходится.
    //
    //  Сцена детерминирована полностью: рассыпание задаётся напрямую, укладка в
    //  решётку тоже, все скорости стартуют нулями. Значит ЛЮБОЙ импульс и ЛЮБАЯ
    //  скорость здесь — целиком работа решателя против самого себя.
    // ------------------------------------------------------------------
    run {
        println()
        println("плотная куча одиночных клеток, всё в покое (детерминировано)")
        println("  шаг решётки | перекрытие | дребезг за 5 с |     пик |  в конце | разлёт, связей")
        for (step in doubleArrayOf(0.4, 0.6, 0.8, 1.0)) {
            P.setTearing(false)
            P.resetState(); P.resetCounters()
            shatter(ob)
            // Уводим целое тело далеко: меряем ТОЛЬКО кучу, без чужой мембраны.
            for (k in 0 until P.n) if (P.organismOf[k] == oa) {
                P.px[k] += 500.0 * meanLink
                P.prevX[k] = P.px[k]; P.prevY[k] = P.py[k]
                P.vx[k] = 0.0; P.vy[k] = 0.0
            }
            // В КУЧУ КЛАДЁМ ТОЛЬКО ПО-НАСТОЯЩЕМУ СВОБОДНЫЕ КЛЕТКИ.
            //
            // shatter убивает лишь мягкие связи, внутрикостные остаются — кости
            // переживают рассыпание целыми. Уложив костные клетки на решётку, мы
            // ломаем жёсткую позу кости, и projectBone возвращает её обратно на
            // BONE_MAX_STEP = 0.5 связи за подшаг, то есть 8 клеток за тик. Замер
            // тогда показывает потолок скорости и меряет не кучу, а раздавленную кость.
            val ids = (0 until P.n).filter { P.organismOf[it] == ob && P.isFree(it) }
            val bones = (0 until P.n).filter { P.organismOf[it] == ob && !P.isFree(it) }
            for (k in bones) {
                P.px[k] += 900.0 * meanLink
                P.prevX[k] = P.px[k]; P.prevY[k] = P.py[k]
                P.vx[k] = 0.0; P.vy[k] = 0.0
            }
            run {
                val side = Math.ceil(sqrt(ids.size.toDouble())).toInt().coerceAtLeast(1)
                val st = step * meanLink
                for ((k, i) in ids.withIndex()) {
                    P.px[i] = (k % side - side / 2.0) * st
                    P.py[i] = (k / side - side / 2.0) * st
                    P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i]
                    P.vx[i] = 0.0; P.vy[i] = 0.0
                }
            }

            val ct0 = P.contactsObj()!!
            // Перекрытие пары соседей по решётке в долях порога: 1 — касание, меньше —
            // старт уже внутри друг друга.
            val rr = if (ids.size >= 2)
                ct0.contactRadiusOf(ids[0]) + ct0.contactRadiusOf(ids[1]) else 1.0
            val overlap = step * meanLink / rr

            /** Среднеквадратичный радиус кучи в связях: во что она расплылась. */
            fun spread(): Double {
                var cx = 0.0; var cy = 0.0
                for (k in ids) { cx += P.px[k]; cy += P.py[k] }
                cx /= ids.size; cy /= ids.size
                var s = 0.0
                for (k in ids) {
                    val dx = P.px[k] - cx; val dy = P.py[k] - cy
                    s += dx * dx + dy * dy
                }
                return sqrt(s / ids.size) / meanLink
            }
            val spread0 = spread()

            P.frame(dt, sub, contract = false)
            P.contactsObj()!!.impulseAccum = 0.0
            var peak = 0.0
            var tail = 0.0
            val total = (5.0 / dt).toInt()
            for (fr in 1..total) {
                P.frame(dt, sub, contract = false)
                for (k in ids) {
                    val s = sqrt(P.vx[k] * P.vx[k] + P.vy[k] * P.vy[k]) * dt / meanLink
                    if (s > peak) peak = s
                    // Последняя секунда: успокоилась куча или всё ещё живёт.
                    if (fr > total - (1.0 / dt).toInt() && s > tail) tail = s
                }
            }
            println("  %11.1f | %10.2f | %14.1f | %8.2f | %8.2f | %6.1f -> %.1f".format(
                step, overlap, P.contactsObj()!!.impulseAccum / meanLink,
                peak, tail, spread0, spread()))
        }
    }

    // ------------------------------------------------------------------
    //  РАЗБОР ЁМКОСТИ ПО ТИКАМ: откуда в ней берётся движение.
    //
    //  Сцена детерминирована и стартует из полного покоя, поэтому вопрос ставится
    //  однозначно: частицы уже перекрыты на старте (тогда виновата расстановка) или
    //  движение появляется из ничего (тогда решатель). Печатается геометрия старта и
    //  первые тики.
    // ------------------------------------------------------------------
    run {
        val scene = scenes.firstOrNull { it.name.startsWith("ёмкость, битком") }
        if (scene != null) {
            P.setTearing(true)
            P.resetState(); P.resetCounters()
            scene.setup(ctx, 0.0, 0.0)

            val ct = P.contactsObj()!!
            val free = (0 until P.n).filter { P.isFree(it) }
            val moving = free.filter { P.invMass[it] > 0.0 }
            val pinned = free.filter { P.invMass[it] == 0.0 }
            println()
            println("=== ёмкость: геометрия старта ===")
            println("  подвижных частиц %d, закреплённых в стенке %d".format(moving.size, pinned.size))
            if (moving.size >= 2) {
                val r = ct.contactRadiusOf(moving[0])
                println("  контактный радиус частицы %.3f связи, порог пары %.3f связи"
                    .format(r / meanLink, 2.0 * r / meanLink))
            }
            // Ближайшая пара ПОДВИЖНЫХ частиц на старте.
            var closest = Double.MAX_VALUE
            for (a in moving.indices) for (b in a + 1 until moving.size) {
                val i = moving[a]; val j = moving[b]
                val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
                val d = sqrt(dx * dx + dy * dy)
                if (d < closest) closest = d
            }
            // Насколько плотно набита ёмкость. Радиус кольца берётся из самих
            // закреплённых клеток, поэтому проверка не зависит от того, как сцена его
            // считала: если она ошибётся, это будет видно здесь.
            if (pinned.isNotEmpty()) {
                var wx = 0.0; var wy = 0.0
                for (k in pinned) { wx += P.px[k]; wy += P.py[k] }
                wx /= pinned.size; wy /= pinned.size
                var ring = 0.0
                for (k in pinned) {
                    val dx = P.px[k] - wx; val dy = P.py[k] - wy
                    ring += sqrt(dx * dx + dy * dy)
                }
                ring /= pinned.size
                var insideRing = 0
                for (k in moving) {
                    val dx = P.px[k] - wx; val dy = P.py[k] - wy
                    if (sqrt(dx * dx + dy * dy) <= ring) insideRing++
                }
                val r0 = if (moving.isNotEmpty()) ct.contactRadiusOf(moving[0]) else 0.0
                // Сколько частиц влезло бы в круг при шестиугольной укладке вплотную.
                val capacity = (Math.PI * ring * ring) / (2.0 * r0 * (2.0 * r0) * sqrt(3.0) / 2.0)
                println("  кольцо радиусом %.1f связи, внутри %d частиц (вместимость вплотную ~%.0f, заполнено %.0f%%)"
                    .format(ring / meanLink, insideRing, capacity, 100.0 * insideRing / capacity))
            }
            println("  ближайшая пара подвижных на старте %.3f связи".format(closest / meanLink))
            println("  перекрыты ли они: %s".format(
                if (closest < 2.0 * ct.contactRadiusOf(moving[0])) "ДА" else "нет"))

            // КОНТРОЛЬ: та же сцена с ВЫКЛЮЧЕННЫМИ контактами. Всё стартует из покоя,
            // связей у одиночных клеток нет, гравитации нет — значит без контактов
            // сцена обязана остаться неподвижной. Ненулевая скорость здесь означала бы,
            // что движение делает вовсе не контактный решатель.
            run {
                P.setContacts(false)
                P.resetState(); P.resetCounters()
                scene.setup(ctx, 0.0, 0.0)
                var worst = 0.0
                for (fr in 1..20) {
                    P.frame(dt, sub, contract = false)
                    for (k in (0 until P.n).filter { P.isFree(it) && P.invMass[it] > 0.0 }) {
                        val s = sqrt(P.vx[k] * P.vx[k] + P.vy[k] * P.vy[k]) * dt / meanLink
                        if (s > worst) worst = s
                    }
                }
                println("  КОНТРОЛЬ без контактов: наибольшая скорость за 20 тиков %.6f кл/тик"
                    .format(worst))
                P.setContacts(true)
                P.resetState(); P.resetCounters()
                scene.setup(ctx, 0.0, 0.0)
            }

            println("  тик | контактов | max скорость, кл/тик | проникновение")
            for (fr in 1..20) {
                P.frame(dt, sub, contract = false)
                var vmaxSeen = 0.0
                for (k in moving) {
                    val s = sqrt(P.vx[k] * P.vx[k] + P.vy[k] * P.vy[k]) * dt / meanLink
                    if (s > vmaxSeen) vmaxSeen = s
                }
                if (fr <= 5 || fr % 5 == 0) {
                    // КТО именно разогнался и с чем он соприкасается: без этого видно
                    // только «что-то движется», а надо знать, засыпка это, стенка или
                    // отложенные клетки.
                    var fastest = -1
                    for (k in moving) {
                        val s = sqrt(P.vx[k] * P.vx[k] + P.vy[k] * P.vy[k]) * dt / meanLink
                        if (s >= vmaxSeen - 1e-12) { fastest = k; break }
                    }
                    var nearMoving = Double.MAX_VALUE
                    var nearPinned = Double.MAX_VALUE
                    if (fastest >= 0) {
                        for (k in moving) {
                            if (k == fastest) continue
                            val dx = P.px[fastest] - P.px[k]; val dy = P.py[fastest] - P.py[k]
                            val d = sqrt(dx * dx + dy * dy) / meanLink
                            if (d < nearMoving) nearMoving = d
                        }
                        for (k in pinned) {
                            val dx = P.px[fastest] - P.px[k]; val dy = P.py[fastest] - P.py[k]
                            val d = sqrt(dx * dx + dy * dy) / meanLink
                            if (d < nearPinned) nearPinned = d
                        }
                    }
                    println("  %4d | %9d | %20.4f | %.3f | быстрейшая #%d: до подвижной %.2f, до стенки %.2f"
                        .format(fr, P.liveContacts(), vmaxSeen,
                            P.contactsObj()!!.maxPenetration(P.px, P.py),
                            fastest, nearMoving, nearPinned))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  ОТОРВАВШИЙСЯ КУСОК: ОТКУДА У НЕГО ДРОЖАНИЕ И ТЯГА.
    //
    //  Проверяется одна конкретная догадка, и она про СВЯЗКУ СВЯЗЬ-КОНТАКТ.
    //
    //  Связанные пары не сталкиваются — иначе соседи, стоящие в покое на 0.6 связи
    //  при контактном диаметре около 1.01, были бы в вечном контакте и тело само
    //  себя распирало бы. Но связь МОЖЕТ УМЕРЕТЬ. В ту же секунду пара перестаёт
    //  быть связанной, а расстояние остаётся прежним — и она немедленно попадает в
    //  тот самый вечный контакт, от которого исключение и защищало.
    //
    //  Если догадка верна, у свежеоторванного куска таких пар будет много, и каждая
    //  будет давить наружу, пока уцелевшие связи тянут обратно. Это и есть колебания
    //  пограничных клеток и постоянная тяга.
    //
    //  Кусок берётся НАСТОЯЩИЙ: тем же гребком, что по клавише G, а не нарисованный.
    // ------------------------------------------------------------------
    println()
    println("=== оторвавшийся кусок: связь умерла, контакт остался? ===")
    run {
        P.resetState(); P.resetCounters()
        P.setTearing(true)          // ПОСЛЕ сброса: он возвращает топологию целой
        // Связи ЦЕЛОГО тела: по ним потом отличим бывших соседей от прочих пар.
        val wasLinked = BoundaryContacts.linkedPairs(P.conA, P.conB, P.conCount)
        val whole = (0 until P.organismCount).maxByOrNull { P.organismSize(it) } ?: 0
        val origin = IntArray(P.n) { P.organismOf[it] }
        var t = 0
        while (t < BenchScenes.GAIT_TO) {
            if (t >= BenchScenes.GAIT_FROM) P.frameGait(dt, t - BenchScenes.GAIT_FROM)
            else P.frame(dt, sub, contract = false)
            t++
        }
        val all = BenchScenes.fragments(ctx, whole, origin)
        println("  за %.0f с гребка отделилось кусков: %d".format(BenchScenes.GAIT_TO * dt, all.size))
        for (f in all) println("    кусок #%d: клеток %d, %s, высота %.2f связи"
            .format(f.id, f.size, if (f.bony) "КОСТЯНОЙ" else "мягкий", f.topY / meanLink))
        val piece = BenchScenes.looseFragment(ctx, whole, origin)
        if (piece < 0) {
            println("  за 20 с гребка ничего не оторвалось: порвано %d, убито %d, мягких %d, тел %d, мышц %d, активация %.2f"
                .format(P.tornCount(), P.killedLinks(), P.softLinks(), P.organismCount,
                    P.muscleActivation.size, P.muscleActivation.maxOrNull() ?: 0.0))
        } else {
            P.refreshTopology()   // ДО всего: иначе контур и связи читаются устаревшими
            val cells = (0 until P.n).filter { P.organismOf[it] == piece }
            val mine = BooleanArray(P.n); for (i in cells) mine[i] = true
            println("  оторвалось на %.1f с, клеток %d".format(t * dt, cells.size))

            // Граница КУСКА и его нынешние связи.
            val onEdge = BooleanArray(P.n)
            val onEdge2 = BooleanArray(P.n)
            for (e in 0 until P.boundCount) {
                val a = P.boundA[e]; val b = P.boundB[e]
                onEdge2[a] = true; onEdge2[b] = true
                if (mine[a]) onEdge[a] = true
                if (mine[b]) onEdge[b] = true
            }
            val nowLinked = BoundaryContacts.linkedPairs(P.conA, P.conB, P.conCount)
            val ct = P.contactsObj()!!
            var pairs = 0
            var wereNeighbours = 0
            var worst = 0.0
            val edge = cells.filter { onEdge[it] }
            for (a in edge.indices) for (b in a + 1 until edge.size) {
                val i = edge[a]; val j = edge[b]
                val key = if (i < j) i.toLong() * 1_000_000L + j else j.toLong() * 1_000_000L + i
                if (nowLinked.contains(key)) continue
                val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
                val d = sqrt(dx * dx + dy * dy)
                val rr = ct.contactRadiusOf(i) + ct.contactRadiusOf(j)
                if (d >= rr) continue
                pairs++
                if (wasLinked.contains(key)) wereNeighbours++
                val over = (rr - d) / rr
                if (over > worst) worst = over
            }
            // Через сколько связей пара расходится по сетке. Прямые соседи от контакта
            // освобождены, а вот соседи ЧЕРЕЗ ОДНУ клетку — уже нет, и на целом теле
            // их держит врозь только геометрия, с запасом всего 1.15x.
            val adj = HashMap<Int, MutableSet<Int>>()
            for (c in 0 until P.conCount) {
                adj.getOrPut(P.conA[c]) { HashSet() }.add(P.conB[c])
                adj.getOrPut(P.conB[c]) { HashSet() }.add(P.conA[c])
            }
            var twoHop = 0
            for (a in edge.indices) for (b in a + 1 until edge.size) {
                val i = edge[a]; val j = edge[b]
                val key = if (i < j) i.toLong() * 1_000_000L + j else j.toLong() * 1_000_000L + i
                if (nowLinked.contains(key)) continue
                val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
                if (sqrt(dx * dx + dy * dy) >= ct.contactRadiusOf(i) + ct.contactRadiusOf(j)) continue
                val ni = adj[i] ?: emptySet<Int>()
                if ((adj[j] ?: emptySet<Int>()).any { ni.contains(it) }) twoHop++
            }
            println("  граничных клеток %d, из них НЕСВЯЗАННЫХ перекрытых пар %d".format(edge.size, pairs))
            println("  из них СОСЕДИ ЧЕРЕЗ ОДНУ клетку: %d".format(twoHop))
            println("  из этих пар БЫЛИ СОСЕДЯМИ до разрыва: %d".format(wereNeighbours))
            println("  худшее перекрытие такой пары %.2f от контактного диаметра".format(worst))

            // СХОДИТСЯ ЛИ КОНТУР КУСКА САМ С СОБОЙ.
            //
            // Граница по определению это ребро, входящее ровно в ОДИН треугольник.
            // Пересчитываю её здесь заново, по нынешним связям и треугольникам, и
            // сравниваю с тем, что лежит в boundA/boundB. Если числа разойдутся,
            // значит контур после разрыва не пересобран, и решатель контактов честно
            // отрабатывает НЕВЕРНУЮ границу — чинить надо не его.
            run {
                val use = HashMap<Long, Int>()
                fun k2(a: Int, b: Int): Long =
                    (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
                var tri = 0
                for (t in 0 until P.triCount) {
                    val i0 = P.triA[t]; val i1 = P.triB[t]; val i2 = P.triC[t]
                    if (!mine[i0] || !mine[i1] || !mine[i2]) continue
                    tri++
                    use.merge(k2(i0, i1), 1, Int::plus)
                    use.merge(k2(i1, i2), 1, Int::plus)
                    use.merge(k2(i2, i0), 1, Int::plus)
                }
                var links = 0; var should = 0
                for (c in 0 until P.conCount) {
                    val i = P.conA[c]; val j = P.conB[c]
                    if (!mine[i] || !mine[j]) continue
                    links++
                    if ((use[k2(i, j)] ?: 0) <= 1) should++
                }
                var have = 0
                for (e in 0 until P.boundCount) {
                    if (mine[P.boundA[e]] && mine[P.boundB[e]]) have++
                }
                var inSet = 0
                for (i in cells) if (ct.inContactSet(i)) inSet++
                println("  КОНТУР КУСКА: клеток %d, связей %d, треугольников %d"
                    .format(cells.size, links, tri))
                println("  граничных рёбер: по определению %d, в boundA/boundB %d"
                    .format(should, have))
                var inSetAll = 0
                for (i in 0 until P.n) if (ct.inContactSet(i)) inSetAll++
                println("  клеток в контактном множестве %d из %d (во всём мире %d из %d)"
                    .format(inSet, cells.size, inSetAll, P.n))
            }

            // ПАРЫ ЧЕРЕЗ САМУ ЛИНИЮ РАЗРЫВА. Внутри куска их искать было мало:
            // связь умерла МЕЖДУ куском и телом, значит и пара, потерявшая
            // исключение, лежит поперёк разрыва, а не внутри одной из половин.
            run {
                var pairs = 0; var wereNeighbours = 0; var worst = 0.0
                val other = (0 until P.n).filter { !mine[it] && onEdge2[it] }
                for (i in edge) for (j in other) {
                    val key = if (i < j) i.toLong() * 1_000_000L + j else j.toLong() * 1_000_000L + i
                    if (nowLinked.contains(key)) continue
                    val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
                    val d = sqrt(dx * dx + dy * dy)
                    val rr = ct.contactRadiusOf(i) + ct.contactRadiusOf(j)
                    if (d >= rr) continue
                    pairs++
                    if (wasLinked.contains(key)) wereNeighbours++
                    val over = (rr - d) / rr
                    if (over > worst) worst = over
                }
                println("  ЧЕРЕЗ РАЗРЫВ: перекрытых несвязанных пар %d, из них бывших соседей %d"
                    .format(pairs, wereNeighbours))
                println("  худшее перекрытие через разрыв %.2f от контактного диаметра".format(worst))
            }

            // Геометрия куска на диск: по ней рисуется картинка для глаз.
            run {
                val sb = StringBuilder()
                sb.append("meanLink ").append(meanLink).append(chr10)
                for (i in cells) sb.append("c ").append(i).append(' ').append(P.px[i]).append(' ')
                    .append(P.py[i]).append(' ').append(ct.contactRadiusOf(i)).append(' ')
                    .append(if (onEdge[i]) 1 else 0).append(chr10)
                for (k in 0 until P.conCount) {
                    val a = P.conA[k]; val b = P.conB[k]
                    if (mine[a] && mine[b]) sb.append("l ").append(a).append(' ').append(b).append(chr10)
                }
                for (e in 0 until P.boundCount) {
                    val a = P.boundA[e]; val b = P.boundB[e]
                    if (mine[a] && mine[b]) sb.append("b ").append(a).append(' ').append(b).append(chr10)
                }
                val out = java.io.File(System.getProperty("java.io.tmpdir"), "fragment.txt")
                out.writeText(sb.toString())
                println("  геометрия куска записана в " + out.path)
            }

            // ДВА ЗАМЕРА ПОДРЯД, И РАЗНИЦА МЕЖДУ НИМИ И ЕСТЬ ОТВЕТ.
            //
            // Сначала кусок мерится НА МЕСТЕ, рядом с родным телом: гребок выключен,
            // все скорости обнулены, дальше никто ничего не толкает. Потом всё, кроме
            // куска, уезжает со сцены, и он мерится ОДИН.
            //
            // Если он тих один и дрожит на месте, дело не в нём и не в его контуре, а
            // в том, что рядом. Если дрожит и один — дело в нём самом.
            P.setTearing(false)
            // Снимок ПОСЛЕ разрыва. Без него фазы шли подряд, вторая наследовала
            // и смещение куска, и подсевший запас среды от первой, и сравнивать
            // было нечего: тишина во второй фазе объяснялась порядком, а не физикой.
            val snapX = P.px.copyOf(); val snapY = P.py.copyOf()
            // Скорости тоже: для замера с гребком кусок обязан стартовать ЖИВЫМ,
            // таким, каким он оторвался. Из мёртвого покоя среде толкать его нечем,
            // и замер честно показывает нули, ничего общего не имеющие с картинкой.
            val snapVX = P.vx.copyOf(); val snapVY = P.vy.copyOf()
            fun restoreLive() {
                for (i in 0 until P.n) {
                    P.px[i] = snapX[i]; P.py[i] = snapY[i]
                    P.vx[i] = snapVX[i]; P.vy[i] = snapVY[i]
                    P.prevX[i] = P.px[i] - P.vx[i] * dt / sub
                    P.prevY[i] = P.py[i] - P.vy[i] * dt / sub
                }
                P.resetFlow()
            }
            fun restore() {
                for (i in 0 until P.n) {
                    P.px[i] = snapX[i]; P.py[i] = snapY[i]
                    P.vx[i] = 0.0; P.vy[i] = 0.0; P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i]
                }
                P.resetFlow()
            }
            fun watch(label: String) {
                println("  " + label)
                println("  тик | max скорость | |P| куска | |P| МИРА | энергия мира | ушёл, связей")
                val ax = cells.map { P.px[it] }.average(); val ay = cells.map { P.py[it] }.average()
                for (fr in 1..60) {
                    P.frame(dt, sub, contract = false)
                    if (fr != 1 && fr != 5 && fr != 15 && fr != 30 && fr != 60) continue
                    var vmaxSeen = 0.0
                    var pxs = 0.0; var pys = 0.0
                    for (i in cells) {
                        val s = sqrt(P.vx[i] * P.vx[i] + P.vy[i] * P.vy[i]) * dt / meanLink
                        if (s > vmaxSeen) vmaxSeen = s
                        if (P.invMass[i] > 0.0) { pxs += P.vx[i] / P.invMass[i]; pys += P.vy[i] / P.invMass[i] }
                    }
                    // ИМПУЛЬС И ЭНЕРГИЯ ВСЕГО МИРА. Без них разговор пустой: кусок
                    // может улетать совершенно законно, если тело улетает в другую
                    // сторону на столько же. Энергия из ниоткуда видна только тут.
                    var wpx = 0.0; var wpy = 0.0; var wke = 0.0
                    for (i in 0 until P.n) {
                        if (P.invMass[i] <= 0.0) continue
                        val m = 1.0 / P.invMass[i]
                        wpx += m * P.vx[i]; wpy += m * P.vy[i]
                        wke += 0.5 * m * (P.vx[i] * P.vx[i] + P.vy[i] * P.vy[i])
                    }
                    val bx = cells.map { P.px[it] }.average(); val by = cells.map { P.py[it] }.average()
                    val moved = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay)) / meanLink
                    println("  %4d | %11.4f | %9.3f | %8.3f | %12.4f | %.3f".format(
                        fr, vmaxSeen, sqrt(pxs * pxs + pys * pys),
                        sqrt(wpx * wpx + wpy * wpy), wke, moved))
                }
            }
            restore()
            watch("НА МЕСТЕ рядом с телом, запас среды обнулён:")

            // ГРЕБОК НЕ ВЫКЛЮЧАЯ: ровно то, что видно глазами.
            //
            // Замеры с замороженной сценой отвечают «всё хорошо», а руками дребезг
            // виден. Разница в том, что там гребок выключен, а тут нет: тело
            // продолжает деформироваться и толкать кусок.
            //
            // Четыре прогона отвечают на главный вопрос — КТО толкает. Среда действует
            // только на граничные рёбра и превращает колебание границы в тягу; контакт
            // толкает напрямую. Выключая их по одному, видно вклад каждого.
            run {
                fun swim(label: String, contacts: Boolean, drag: Boolean) {
                    restoreLive()
                    P.setContacts(contacts)
                    P.setDragOff(!drag)
                    val ax = cells.map { P.px[it] }.average(); val ay = cells.map { P.py[it] }.average()
                    var baseBX = 0.0; var baseBY = 0.0; var baseBM = 0.0
                    for (q in 0 until P.n) {
                        if (P.invMass[q] <= 0.0) continue
                        val m = 1.0 / P.invMass[q]
                        baseBX += m * P.px[q]; baseBY += m * P.py[q]; baseBM += m
                    }
                    baseBX /= baseBM; baseBY /= baseBM
                    // Импульс мира НА СТАРТЕ. Без него разговор пустой: мир стартует
                    // не из покоя, тело уже плывёт, и без среды оно законно катится
                    // по инерции. Создание импульса видно только по РОСТУ.
                    var p0x = 0.0; var p0y = 0.0
                    for (q in 0 until P.n) {
                        if (P.invMass[q] <= 0.0) continue
                        val m = 1.0 / P.invMass[q]
                        p0x += m * P.vx[q]; p0y += m * P.vy[q]
                    }
                    val p0 = sqrt(p0x * p0x + p0y * p0y)
                    var prevX = ax; var prevY = ay
                    var path = 0.0
                    val ticks = (20.0 / dt).toInt()
                    for (fr in 0 until ticks) {
                        P.frameGait(dt, fr)
                        val cx = cells.map { P.px[it] }.average(); val cy = cells.map { P.py[it] }.average()
                        path += sqrt((cx - prevX) * (cx - prevX) + (cy - prevY) * (cy - prevY))
                        prevX = cx; prevY = cy
                    }
                    val moved = sqrt((prevX - ax) * (prevX - ax) + (prevY - ay) * (prevY - ay))
                    // ЦЕНТР МАСС ВСЕГО МИРА, честно по массам. Без среды и без контактов
                    // внешних сил нет вовсе, и он обязан стоять на месте: мышцы
                    // внутренние, они центр масс двигать не могут.
                    var bx = 0.0; var by = 0.0; var bm = 0.0; var pvx = 0.0; var pvy = 0.0
                    for (q in 0 until P.n) {
                        if (P.invMass[q] <= 0.0) continue
                        val m = 1.0 / P.invMass[q]
                        bx += m * P.px[q]; by += m * P.py[q]; bm += m
                        pvx += m * P.vx[q]; pvy += m * P.vy[q]
                    }
                    val bodyMoved = sqrt((bx / bm - baseBX) * (bx / bm - baseBX) +
                        (by / bm - baseBY) * (by / bm - baseBY))
                    val pAbs = sqrt(pvx * pvx + pvy * pvy)
                    println("  SWIM %-28s | snos %7.2f | put %7.2f | CM_mira %8.3f | P_mira %8.3f"
                        .format(label, moved / meanLink, path / meanLink, bodyMoved / meanLink, pAbs))
                    println("       P_start %8.3f -> P_end %8.3f".format(p0, pAbs))
                }
                println("  С ГРЕБКОМ, 20 с. Снос — насколько уехал, путь — сколько намотал.")
                swim("всё включено", true, true)
                swim("без контактов", false, true)
                swim("без среды", true, false)
                swim("без контактов и без среды", false, false)
                P.setContacts(true); P.setDragOff(false)
            }

            // ВРАЩЕНИЕ ИЗ НИОТКУДА: сокращается мышца, кусок раскручивается.
            //
            // Кусок один, контактов нет, среды нет. Внутренняя мышца не может создать
            // ни импульса, ни МОМЕНТА импульса: её силы парные и направлены вдоль
            // связи. Если момент растёт, его делает решатель.
            //
            // Главный подозреваемый назван прямо в коде: у projectBone угловая
            // поправка НЕ обнуляется, зачёт её пробовали и сочли вредным. Поэтому
            // второй прогон идёт с мягкими костями: если там момент стоит, виновата
            // проекция кости.
            run {
                var musc = 0
                for (t in 0 until P.triCount) {
                    if (mine[P.triA[t]] && P.triMuscle[t] >= 0) musc++
                }
                println("  мышечных треугольников в куске: %d".format(musc))
                fun spin(label: String, rigid: Boolean) {
                    restoreLive()
                    for (i in 0 until P.n) if (!mine[i]) {
                        P.px[i] += 600.0 * meanLink; P.prevX[i] = P.px[i]
                        P.vx[i] = 0.0; P.vy[i] = 0.0
                    }
                    P.setContacts(false); P.setDragOff(true); P.setBonesRigid(rigid)
                    fun angular(): DoubleArray {
                        var m0 = 0.0; var cx = 0.0; var cy = 0.0
                        for (i in cells) {
                            if (P.invMass[i] <= 0.0) continue
                            val m = 1.0 / P.invMass[i]
                            m0 += m; cx += m * P.px[i]; cy += m * P.py[i]
                        }
                        if (m0 <= 0.0) return doubleArrayOf(0.0, 0.0)
                        cx /= m0; cy /= m0
                        var l = 0.0; var inert = 0.0
                        for (i in cells) {
                            if (P.invMass[i] <= 0.0) continue
                            val m = 1.0 / P.invMass[i]
                            val rx = P.px[i] - cx; val ry = P.py[i] - cy
                            l += m * (rx * P.vy[i] - ry * P.vx[i])
                            inert += m * (rx * rx + ry * ry)
                        }
                        return doubleArrayOf(l, if (inert > 0) l / inert else 0.0)
                    }
                    P.resetCounters()
                    val a0 = angular()
                    for (fr in 0 until 300) P.frameGait(dt, fr)
                    val a1 = angular()
                    // Кто мог: только стадии, правящие частицы ПО ОДНОЙ. Парные
                    // поправки идут вдоль линии между клетками и момента не дают.
                    println("  SPIN %-16s | L %10.4f -> %10.4f | omega %8.4f -> %8.4f | clamp %d | ccd %d"
                        .format(label, a0[0], a1[0], a0[1], a1[1],
                            P.speedCapHits(), P.toiClamps()))
                }
                spin("кости жёсткие", true)
                spin("кости мягкие", false)
                P.setClampOff(true)
                spin("без потолка", true)
                P.setClampOff(false)
                // Контроль: то же самое, но мышцы МОЛЧАТ. Момент обязан стоять.
                run {
                    restoreLive()
                    for (i in 0 until P.n) if (!mine[i]) {
                        P.px[i] += 600.0 * meanLink; P.prevX[i] = P.px[i]
                        P.vx[i] = 0.0; P.vy[i] = 0.0
                    }
                    P.setContacts(false); P.setDragOff(true); P.setBonesRigid(true)
                    P.resetCounters()
                    fun ang(): Double {
                        var m0 = 0.0; var cx = 0.0; var cy = 0.0
                        for (i in cells) { if (P.invMass[i] <= 0.0) continue
                            val m = 1.0 / P.invMass[i]; m0 += m; cx += m * P.px[i]; cy += m * P.py[i] }
                        if (m0 <= 0.0) return 0.0
                        cx /= m0; cy /= m0
                        var l = 0.0
                        for (i in cells) { if (P.invMass[i] <= 0.0) continue
                            val m = 1.0 / P.invMass[i]
                            l += m * ((P.px[i] - cx) * P.vy[i] - (P.py[i] - cy) * P.vx[i]) }
                        return l
                    }
                    val b0 = ang()
                    for (fr in 0 until 300) P.frame(dt, sub, contract = false)
                    println("  SPIN %-16s | L %10.4f -> %10.4f | clamp %d"
                        .format("мышцы молчат", b0, ang(), P.speedCapHits()))
                }
                P.setContacts(true); P.setDragOff(false); P.setBonesRigid(true)
            }

            // ОТКУДА У КУСКА ПАРА ПОЧТИ В ОДНОЙ ТОЧКЕ.
            //
            // Перекрытие 0.99 контактного диаметра это расстояние около сотой доли
            // связи. Радиус тут ни при чём: две клетки просто лежат друг на друге.
            // Вопрос один — схлопнулась ли ткань, и если да, то по чьей вине.
            run {
                var wi = -1; var wj = -1; var wd = Double.MAX_VALUE
                for (a in cells.indices) for (b in a + 1 until cells.size) {
                    val i = cells[a]; val j = cells[b]
                    val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < wd) { wd = d; wi = i; wj = j }
                }
                println("  БЛИЖАЙШАЯ ПАРА куска: #%d и #%d на %.4f связи".format(wi, wj, wd / meanLink))
                // Длины покоя: если пластика их источила, схлопывание объясняется ею.
                var minRest = Double.MAX_VALUE; var maxRest = 0.0; var sum = 0.0; var cnt = 0
                var minStrain = 0.0
                for (c in 0 until P.conCount) {
                    val i = P.conA[c]; val j = P.conB[c]
                    if (!mine[i] || !mine[j]) continue
                    val r = P.conRest[c]
                    if (r < minRest) minRest = r
                    if (r > maxRest) maxRest = r
                    sum += r; cnt++
                    val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
                    val len = sqrt(dx * dx + dy * dy)
                    val s = (len - r) / r
                    if (s < minStrain) minStrain = s
                }
                if (cnt > 0) println("  ДЛИНЫ ПОКОЯ куска, в средних связях: мин %.4f, сред %.4f, макс %.4f"
                    .format(minRest / meanLink, sum / cnt / meanLink, maxRest / meanLink))
                println("  сильнее всего СЖАТАЯ связь куска: %.3f от своей длины покоя".format(1.0 + minStrain))

                // ЧТО ДЕРЖИТ УГОЛ между этой парой. Если общая клетка есть, а
                // треугольника на её связях нет, то угол не держит НИЧТО: тонкий
                // отросток шириной в одну клетку складывается свободно.
                val nb = HashMap<Int, MutableSet<Int>>()
                for (c in 0 until P.conCount) {
                    nb.getOrPut(P.conA[c]) { HashSet() }.add(P.conB[c])
                    nb.getOrPut(P.conB[c]) { HashSet() }.add(P.conA[c])
                }
                val di = nb[wi] ?: emptySet<Int>()
                val dj = nb[wj] ?: emptySet<Int>()
                val common = di.intersect(dj)
                println("  СВЯЗАНЫ ли они: %s; общих соседей: %d"
                    .format(if (di.contains(wj)) "да" else "нет", common.size))
                fun triOn(a: Int, b: Int): Int {
                    var t = 0
                    for (q in 0 until P.triCount) {
                        val s = intArrayOf(P.triA[q], P.triB[q], P.triC[q])
                        if (s.contains(a) && s.contains(b)) t++
                    }
                    return t
                }
                for (k in common) println("  общая клетка #%d: треугольников на связи с #%d: %d, с #%d: %d"
                    .format(k, wi, triOn(wi, k), wj, triOn(wj, k)))
                var triHere = 0
                for (q in 0 until P.triCount) {
                    val s = intArrayOf(P.triA[q], P.triB[q], P.triC[q])
                    if (s.contains(wi) && s.contains(wj)) triHere++
                }
                println("  треугольников, содержащих ОБЕ клетки пары: %d".format(triHere))
                var deg = 0
                for (c in 0 until P.conCount) if (P.conA[c] == wi || P.conB[c] == wi) deg++
                var deg2 = 0
                for (c in 0 until P.conCount) if (P.conA[c] == wj || P.conB[c] == wj) deg2++
                println("  связей у #%d: %d, у #%d: %d".format(wi, deg, wj, deg2))
            }

            // КТО ИМЕННО ДВИГАЕТ ПОЗИЦИИ. Скорость восстанавливается из позиций как
            // (px - prevX) / h, значит каждая стадия вносит в неё ровно своё смещение,
            // делённое на подшаг. Здесь видно, чьё смещение больше.
            run {
                // Мерить надо ИМЕННО С МЫШЦАМИ: без них момент честно затухает, с ними
                // переворачивается и растёт вдесятеро. Контакты и среда выключены,
                // значит всё, что тут появится, сделали внутренние стадии.
                restoreLive()
                for (i in 0 until P.n) if (!mine[i]) {
                    P.px[i] += 600.0 * meanLink; P.prevX[i] = P.px[i]
                    P.vx[i] = 0.0; P.vy[i] = 0.0
                }
                P.setContacts(false); P.setDragOff(true)
                P.setStageProbe(true)
                P.setStageMask(mine)
                java.util.Arrays.fill(P.stageMove(), 0.0)
                java.util.Arrays.fill(P.stageHits(), 0)
                java.util.Arrays.fill(P.stageL(), 0.0)
                for (fr in 0 until 300) P.frameGait(dt, fr)
                P.setStageProbe(false)
                P.setStageMask(null)
                P.setContacts(true); P.setDragOff(false)
                val names = arrayOf("связи", "площади", "изгиб", "кость", "предел длины", "контакты")
                val mv = P.stageMove(); val hi = P.stageHits()
                println("  ЧЬИ ПОПРАВКИ СТАНОВЯТСЯ СКОРОСТЬЮ, за 60 тиков покоя")
                println("  стадия       | пик скорости, кл/тик | сдвинутых клеток")
                val lv = P.stageL()
                for (k in names.indices) println("  STAGE%d %-12s | %10.4f | %d | dL %12.5f"
                    .format(k, names[k], mv[k] * dt / meanLink, hi[k], lv[k]))
            }
            restore()
            for (i in 0 until P.n) if (!mine[i]) {
                P.px[i] += 600.0 * meanLink; P.prevX[i] = P.px[i]
            }
            // ГЛАВНОЕ ЧИСЛО. Кусок стоит один, вокруг пусто. Каждый живой контакт
            // здесь — это пара его СОБСТВЕННЫХ клеток, которые давят друг на друга
            // в покое. У целого тела их быть не должно вовсе.
            println("  ЖИВЫХ КОНТАКТОВ внутри куска в покое: %d".format(P.liveContacts()))
            watch("ОДИН, всё лишнее убрано:")
            // Третья фаза: снова на месте, но запас среды НЕ трогаем. Разница с
            // первой фазой — это ровно вклад среды.
            for (i in 0 until P.n) { P.px[i] = snapX[i]; P.py[i] = snapY[i] }
            for (i in 0 until P.n) {
                P.vx[i] = 0.0; P.vy[i] = 0.0; P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i]
            }
            watch("НА МЕСТЕ, запас среды КАК ЕСТЬ:")
            // И контрольный: то же самое, но КОНТАКТОВ НЕТ ВОВСЕ. Если кусок улетает
            // и так, коллизии к делу не относятся и чинить надо не их.
            restore()
            P.setContacts(false)
            watch("НА МЕСТЕ, КОНТАКТЫ ВЫКЛЮЧЕНЫ:")
            P.setContacts(true)
        }
    }

    println()
    println("дребезг — импульс контактов за 6 спокойных секунд, в средних связях")
    println("выброс  — быстрейшая клетка относительно своего тела, клеток/тик")
    println("внутри  — клеток застряло в чужом контуре; «в кости» — из них у кости")
    println("медиана по 9 вариациям (3 угла x 3 сдвига), кроме контроля")
    // Проходимость мембраны за весь стенд: CB_SEAL=1. См. dbgSeal в RealBodyDemo.
    if (System.getenv("CB_SEAL") != null) println(
        "мембрана: наименьший радиус контакта %.4f связи, проверок %d, коридор шире 2r у %d, худший %.3f от нужного"
            .format(SealStats.minR, SealStats.checks, SealStats.over, SealStats.worst) +
        "; шире 2r у %d, вдвое у %d, впятеро у %d"
            .format(SealStats.over, SealStats.over2, SealStats.over5))
    if (System.getenv("CB_SEAL") != null) println(
        ("  по ЖИВЫМ связям (растяжение ниже порога разрыва): проверок %d, "
            + "проходимых %d, худший коридор %.3f от нужного (#%d-#%d при %.3f)")
            .format(SealStats.liveChecks, SealStats.liveOver, SealStats.liveWorst,
                SealStats.liveI, SealStats.liveJ, SealStats.liveStretch))
    if (System.getenv("CB_SEAL") != null) println(
        ("  порвано по щели %d; проходимых при ВКЛЮЧЁННОМ разрыве %d, при выключенном %d; "
            + "проходов предела длины с разрывом %d, без разрыва %d")
            .format(SealStats.torn, SealStats.overTearing, SealStats.overNoTear,
                SealStats.tearingOnTicks, SealStats.tearingOffTicks))
}
