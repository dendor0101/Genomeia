package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

fun BodyState.dbgSeal(c: Int, i: Int, j: Int, len: Double) {
    val ct = contacts ?: return
    val ra = ct.contactRadiusOf(i); val rb = ct.contactRadiusOf(j)
    if (ra <= 0.0 || rb <= 0.0) return
    val need = 2.0 * dbgSealMinR
    if (need <= 0.0) return
    val q = (len - ra - rb) / need
    SealStats.checks++
    SealStats.minR = dbgSealMinR / body.meanLinkLength
    if (q > SealStats.worst) {
        SealStats.worst = q; SealStats.worstI = i; SealStats.worstJ = j
        SealStats.worstStretch = len / conRest[c]
    }
    if (q > 1.0) {
        SealStats.over++
        if (tearingOn) SealStats.overTearing++ else SealStats.overNoTear++
    }
    if (q > 2.0) SealStats.over2++
    if (q > 5.0) SealStats.over5++
    // Отдельно — ЖИВАЯ мембрана: связь, которая ещё не должна была порваться. Стенд
    // ставит сцены, перенося клетки, и связь через такой перенос растянута в сотни
    // раз — по ней о проходимости судить нельзя.
    val st = len / conRest[c]
    if (st < LINK_MAX_STRETCH + LINK_TEAR_STRAIN) {
        SealStats.liveChecks++
        if (q > SealStats.liveWorst) {
            SealStats.liveWorst = q; SealStats.liveI = i; SealStats.liveJ = j; SealStats.liveStretch = st
        }
        if (q > 1.0) SealStats.liveOver++
    }
}

fun BodyState.dbgTear(c: Int, i: Int, j: Int, why: String, ratio: Double) {
    val log = dbgTearLog ?: return
    if (log.length > 20000) return
    log.append("    тик %d: связь #%d-#%d %s %.3f, связь мышцы %d, клетки: мышцы %d/%d, кости %d/%d, сухожилие %s".format(
        playTick, i, j, why, ratio, conMuscle[c], muscleOf[i], muscleOf[j], boneOf[i], boneOf[j],
        if (conTendon[c]) "да" else "нет"))
    log.append(10.toChar())
}

fun BodyState.tick0() { if (DEBUG_CHECKS && dbgTimeOn) {
    dbgTimeSurface0 = dbgTimeNs[12]
    dbgTimeT0 = System.nanoTime()
} }

fun BodyState.tick1(k: Int) { if (DEBUG_CHECKS && dbgTimeOn) {
    dbgTimeNs[k] += System.nanoTime() - dbgTimeT0 - (dbgTimeNs[12] - dbgTimeSurface0)
} }

fun BodyState.dbgMark() {
    // Снимок нужен и наблюдению за одной клеткой: инициализатор поля в Kotlin не
    // проходит через сеттер, так что dbgStages по знаку среды сам не включится.
    if (!dbgStages && watchCell < 0) return
    if (dbgSnapX.size < n) { dbgSnapX = DoubleArray(n); dbgSnapY = DoubleArray(n) }
    System.arraycopy(px, 0, dbgSnapX, 0, n)
    System.arraycopy(py, 0, dbgSnapY, 0, n)
}

/** То же для проходов по СКОРОСТЯМ: импульс берётся прямо из vx, vy. */
fun BodyState.dbgMarkV() {
    if (!dbgStages) return
    if (dbgSnapVx.size < n) { dbgSnapVx = DoubleArray(n); dbgSnapVy = DoubleArray(n) }
    System.arraycopy(vx, 0, dbgSnapVx, 0, n)
    System.arraycopy(vy, 0, dbgSnapVy, 0, n)
}

/** Путь, который стадия [k] дала наблюдаемой клетке. См. watchCell. */
fun BodyState.watchNote(k: Int) {
    val i = watchCell
    if (i < 0 || i >= n || dbgSnapX.size < n) return
    val dx = px[i] - dbgSnapX[i]; val dy = py[i] - dbgSnapY[i]
    if (k in watchStage.indices) watchStage[k] += sqrt(dx * dx + dy * dy)
}

/** Печать разбивки по стадиям за тик и сброс. См. watchCell. */
fun BodyState.watchReport() {
    val i = watchCell
    if (i < 0 || i >= n) return
    var sum = 0.0
    for (v in watchStage) sum += v
    val ml = body.meanLinkLength.toDouble()
    if (sum > 1e-12) {
        val sb = StringBuilder()
        sb.append("[стадии] клетка #").append(i).append(" тик ").append(playTick)
        sb.append(": всего ").append(String.format(java.util.Locale.ROOT, "%.3f", sum / ml)).append(" св")
        for (k in watchStage.indices) {
            if (watchStage[k] <= 1e-12) continue
            sb.append("  ").append(watchNames[k]).append(' ')
                .append(String.format(java.util.Locale.ROOT, "%.3f", watchStage[k] / ml))
        }
        println(sb.toString())
    }
    java.util.Arrays.fill(watchStage, 0.0)
}

fun BodyState.dbgMeasureV(k: Int) {
    if (!dbgStages) return
    val only = dbgOnly
    var m0 = 0.0; var cx = 0.0; var cy = 0.0
    for (i in 0 until n) {
        if (invMass[i] <= 0.0) continue
        if (only != null && !only[i]) continue
        val m = 1.0 / invMass[i]
        m0 += m; cx += m * px[i]; cy += m * py[i]
    }
    if (m0 > 0.0) { cx /= m0; cy /= m0 }
    var dpx = 0.0; var dpy = 0.0; var dl = 0.0; var hits = 0
    for (i in 0 until n) {
        if (invMass[i] <= 0.0) continue
        if (only != null && !only[i]) continue
        val m = 1.0 / invMass[i]
        val ddx = vx[i] - dbgSnapVx[i]; val ddy = vy[i] - dbgSnapVy[i]
        if (ddx != 0.0 || ddy != 0.0) hits++
        dpx += m * ddx; dpy += m * ddy
        dl += m * ((px[i] - cx) * ddy - (py[i] - cy) * ddx)
    }
    dbgStageHits[k] += hits
    dbgStagePx[k] += dpx; dbgStagePy[k] += dpy
    dbgStageL[k] += dl
}

fun BodyState.dbgMeasure(k: Int, h: Double) {
    watchNote(k)
    if (!dbgStages) return
    var worst = 0.0
    var hits = 0
    val only = dbgOnly
    for (i in 0 until n) {
        if (invMass[i] <= 0.0) continue
        if (only != null && !only[i]) continue
        val dx = px[i] - dbgSnapX[i]; val dy = py[i] - dbgSnapY[i]
        val d = Math.sqrt(dx * dx + dy * dy)
        if (d > 1e-12) hits++
        if (d > worst) worst = d
        dbgStageAbs[k] += d
    }
    if (worst / h > dbgStageMove[k]) dbgStageMove[k] = worst / h
    dbgStageHits[k] += hits
    // Момент считается от общего центра масс замеряемых клеток. Начало отсчёта
    // роли не играет, пока суммарный импульс стадии нулевой, но так надёжнее.
    var m0 = 0.0; var cx = 0.0; var cy = 0.0
    for (i in 0 until n) {
        if (invMass[i] <= 0.0) continue
        if (only != null && !only[i]) continue
        val m = 1.0 / invMass[i]
        m0 += m; cx += m * dbgSnapX[i]; cy += m * dbgSnapY[i]
    }
    if (m0 <= 0.0) return
    cx /= m0; cy /= m0
    var dl = 0.0
    var dpx = 0.0; var dpy = 0.0
    for (i in 0 until n) {
        if (invMass[i] <= 0.0) continue
        if (only != null && !only[i]) continue
        val m = 1.0 / invMass[i]
        val rx = dbgSnapX[i] - cx; val ry = dbgSnapY[i] - cy
        val ddx = px[i] - dbgSnapX[i]; val ddy = py[i] - dbgSnapY[i]
        dl += m * (rx * ddy - ry * ddx)
        dpx += m * ddx; dpy += m * ddy
    }
    dbgStageL[k] += dl / h
    dbgStagePx[k] += dpx / h; dbgStagePy[k] += dpy / h
}

fun BodyState.dbgTrapBin(k: Int): Int { var b = 0; while (k > dbgTrapEdges[b]) b++; return b }

/**
 * Кто сидит в чужой ткани. Раз в тик.
 *
 * ДЁШЕВО, ПОТОМУ ЧТО СМОТРЯТСЯ ТОЛЬКО КЛЕТКИ С КОНТАКТОМ — граница и мелкие куски.
 * Внутренняя клетка тела в чужую ткань без своей границы не попадёт, а целиком
 * проглоченный кусок теряет границу слой за слоем: после каждой смерти пересборка
 * делает граничными следующих. Треугольники раскладываются по плотной сетке массивами
 * (без хешей), клетка проверяет только треугольники своей ячейки.
 */
fun BodyState.scanTrapped() {
    val ct = contacts ?: return
    val t0 = System.nanoTime()
    val n = n
    if (trapTicks.size != n) trapTicks = IntArray(n)
    if (trapOrg.size != n) trapOrg = IntArray(n) { -1 }
    if (foldTicks.size != n) foldTicks = IntArray(n)
    // Всё в локальные: поле lateinit проверяется на каждом обращении.
    val px = px; val py = py; val dead = cellDead; val org = organismOf; val ticks = trapTicks
    val ta = triA; val tb = triB; val tc = triC; val tn = triCount
    dbgTrapNow = 0; dbgTrapDeep = 0; dbgTrapBone = 0; dbgTrapFold = 0
    // ХЕШ ЯЧЕЕК, А НЕ ПЛОТНАЯ СЕТКА НА РАМКУ. Осколки разлетаются, рамка всех клеток
    // растягивается до миллиона ячеек, и почти каждое обращение к такой сетке — промах
    // кэша: 200 мкс на обход. Таблица на ~2 треугольника на корзину целиком лежит в
    // кэше. Совпадение корзин у разных ячеек даёт лишь лишних кандидатов — сама
    // проверка «центр в треугольнике» точная.
    var h = 1024
    while (h < tn * 2) h = h shl 1
    val mask = h - 1
    if (trapGridStart.size < h + 1) { trapGridStart = IntArray(h + 1); trapGridCount = IntArray(h + 1) }
    val start = trapGridStart; val cur = trapGridCount
    java.util.Arrays.fill(start, 0, h + 1, 0)
    val inv = 1.0 / (2.0 * body.meanLinkLength)
    val off = 1048576.0         // сдвиг, чтобы отбросить дробь без floor у отрицательных
    if (trapTriBox.size < tn * 4) trapTriBox = IntArray(tn * 8)
    val box = trapTriBox
    // 1. Ячейки каждого треугольника и сколько записей в корзине.
    for (t in 0 until tn) {
        if (!baked.triAlive[triId[t]]) {
            val o = t * 4
            box[o] = 0; box[o + 1] = -1; box[o + 2] = 0; box[o + 3] = -1
            continue
        }
        val a = ta[t]; val b = tb[t]; val c = tc[t]
        val ax = px[a]; val bx = px[b]; val cx = px[c]
        val ay = py[a]; val by = py[b]; val cy = py[c]
        val x0 = (minOf(ax, bx, cx) * inv + off).toInt(); var x1 = (maxOf(ax, bx, cx) * inv + off).toInt()
        val y0 = (minOf(ay, by, cy) * inv + off).toInt(); val y1 = (maxOf(ay, by, cy) * inv + off).toInt()
        // Вытянутый в нитку треугольник — уже не ткань, а раскладывать его дорого.
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > 16) x1 = x0 - 1
        val o = t * 4
        box[o] = x0; box[o + 1] = x1; box[o + 2] = y0; box[o + 3] = y1
        for (yy in y0..y1) for (xx in x0..x1) start[((xx * 73856093) xor (yy * 19349663)) and mask]++
    }
    // 2. Начала корзин и раскладка.
    var run = 0
    for (k in 0..h) { val c = start[k]; start[k] = run; cur[k] = run; run += c }
    if (trapGridItems.size < run) trapGridItems = IntArray(run * 2)
    val items = trapGridItems
    for (t in 0 until tn) {
        val o = t * 4
        for (yy in box[o + 2]..box[o + 3]) for (xx in box[o]..box[o + 1])
            items[cur[((xx * 73856093) xor (yy * 19349663)) and mask]++] = t
    }
    // 3. Клетки с контактом: центр в чужом треугольнике своей корзины.
    for (i in 0 until n) {
        if (dead[i] || ct.contactRadiusOf(i) <= 0.0) { ticks[i] = 0; foldTicks[i] = 0; continue }
        val x = px[i]; val y = py[i]
        val xx = (x * inv + off).toInt(); val yy = (y * inv + off).toInt()
        val k = ((xx * 73856093) xor (yy * 19349663)) and mask
        val oi = org[i]
        var foreign = -1; var fold = false
        for (s in start[k] until cur[k]) {
            val t = items[s]
            val a = ta[t]; val b = tb[t]; val c = tc[t]
            if (a == i || b == i || c == i) continue
            val ax = px[a]; val ay = py[a]; val bx = px[b]; val by = py[b]; val cx = px[c]; val cy = py[c]
            val d1 = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val d2 = (cx - bx) * (y - by) - (cy - by) * (x - bx)
            val d3 = (ax - cx) * (y - cy) - (ay - cy) * (x - cx)
            if ((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0)) continue
            if (org[a] == oi) { fold = true; continue }
            foreign = t
            break
        }
        // Складка — центр в СВОЁМ треугольнике, где клетка не вершина: ткань налезла на
        // себя. Считается отдельно, см. trapKillFolds.
        if (fold && foreign < 0) {
            foldTicks[i]++; dbgTrapFold++
        } else {
            if (foldTicks[i] > 0) dbgFoldExit[dbgTrapBin(foldTicks[i])]++
            foldTicks[i] = 0
        }
        // ПЕРВЫЙ НАРУШИТЕЛЬ ЗАПОМИНАЕТСЯ ЗДЕСЬ, а бросается после обхода: кидать из
        // середины обхода значит оставить сетку треугольников недосчитанной, а её
        // потом читает разбор места.
        if (trapCrash > 0 && trapCrashCell < 0) {
            if (foreign >= 0) {
                trapCrashCell = i; trapCrashTri = foreign; trapCrashFold = false
            } else if (fold && trapCrash >= 2) {
                trapCrashCell = i; trapCrashTri = -1; trapCrashFold = true
            }
        }
        if (foreign < 0) {
            if (ticks[i] > 0) dbgTrapExit[dbgTrapBin(ticks[i])]++
            ticks[i] = 0
            continue
        }
        ticks[i]++
        // В ЧЬЁМ ТЕЛЕ СИДИТ — нужно возврату: контур берётся того организма, внутри
        // которого клетка оказалась, а не ближайшего вообще.
        if (trapOrg.size == n) trapOrg[i] = org[ta[foreign]]
        dbgTrapNow++
        if (boneOf[i] >= 0) dbgTrapBone++
        if (ct.contactRadiusOf(ta[foreign]) <= 0.0 && ct.contactRadiusOf(tb[foreign]) <= 0.0 &&
            ct.contactRadiusOf(tc[foreign]) <= 0.0) dbgTrapDeep++
    }
    dbgTrapNs += System.nanoTime() - t0
}

fun BodyState.countInverted() {
    var c = 0
    for (t in 0 until triCount) {
        val i0 = triA[t]; val i1 = triB[t]; val i2 = triC[t]
        val area2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) - (py[i1] - py[i0]) * (px[i2] - px[i0])
        val inv = area2 < 0f
        triInverted[t] = inv
        if (inv) c++
    }
    invertedNow = c
    if (c > invertedPeak) invertedPeak = c
}
