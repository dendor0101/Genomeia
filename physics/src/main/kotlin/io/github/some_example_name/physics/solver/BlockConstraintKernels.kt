package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * СТВОРКИ (лоскуты): у клетки с двумя голыми связями (ни в одном живом треугольнике) её соседи не сходятся ближе,
 * чем во взрослом теле, — угол между голыми связями не складывается. Длина створки — взрослое расстояние соседей.
 *
 * У НОВОРОЖДЁННОЙ — ВЗРОСЛЫЙ УГОЛ ПРИ НЫНЕШНИХ ДЛИНАХ (20.11.2026, рост из зиготы, reference.md, находка 30). Растущее
 * тело почти всё из голых связей: треугольник оживает, когда родятся все три вершины. Дочь рождается почти в центре
 * родителя, её связи дорастают за BIRTH_GROW_TICKS, а створка «сосед родителя — дочь» сразу требовала взрослого
 * РАССТОЯНИЯ и в первом же подшаге выталкивала дочь по прямой от соседа — вбок, по продолжению связи «сосед —
 * родитель», а не к её точке позы. Следующие клетки подгоняются уже по сдвинутой и рождаются туда же: у Crayfish
 * боковые колонны тела вырастали поперёк средней, и сшивающие их стадии рождались со связями в 5–10 раз длиннее
 * покоя. Теперь, если на конце створки или в её вершине новорождённая (contactRamp < 1, BodyGrowth.isGrowingNewborn),
 * длина створки — сторона треугольника со взрослым углом при вершине и нынешними расстояниями от вершины до концов, но
 * не больше взрослой: дочь, поставленная под своим углом, не толкается, а угол держится в полную силу. Без потолка
 * створка разгоняла сама себя, где связь у вершины растянута (у Huesos — в 26–88 раз): длина по углу росла вместе с
 * расстояниями, створка расталкивала концы ещё дальше, и тело улетало в бесконечность. Ослаблять створку нельзя: мягкий
 * старт по доле возраста (как у касаний) ломал рост цепочек — у Earthworm (стадия за тик) голая цепочка складывалась
 * сама в себя, и гибло 968 клеток из 2967. Без новорождённых ядро идёт прежним путём побитово (ramp = null).
 */
internal fun BodyState.solveBlockFlaps(h: Float, block: Int, layout: ConstraintBlocks) {
    if (flapCount == 0) return
    val alpha = FLAP_COMPLIANCE / (h * h)
    val list = layout.flaps
    // Массивы — в локальные, длины — до цикла; мягкий старт новорождённых — массив и признак, а не null
    // (см. lengthsBeforeLoop).
    val rampOn = rampN > 0; val ramp = contactRamp
    val px = px; val py = py; val invMass = invMass
    val flapA = flapA; val flapB = flapB; val flapRest = flapRest; val flapV = flapV; val next = list.next
    lengthsBeforeLoop(px.size or py.size or invMass.size or flapA.size or flapB.size or flapRest.size or flapV.size or
        ramp.size or next.size)
    var cursor = list.head[block]
    while (cursor >= 0) {
        val c = cursor; cursor = next[cursor]
        val i = flapA[c]; val j = flapB[c]
        val wi = invMass[i]; val wj = invMass[j]
        val w = wi + wj
        if (w == 0.0f) continue
        var dx = px[i] - px[j]
        var dy = py[i] - py[j]
        val len = sqrt(dx * dx + dy * dy)
        var rest = flapRest[c]
        if (rampOn && (ramp[i] < 1f || ramp[j] < 1f || ramp[flapV[c]] < 1f)) rest = minOf(rest, flapAngleRest(flapV[c], i, j))
        if (len >= rest || len < 1e-12f) continue
        dx /= len; dy /= len
        val dL = -(len - rest) / (w + alpha)
        px[i] += dx * dL * wi; py[i] += dy * dL * wi
        px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
    }
}

internal fun BodyState.solveBlockBend(h: Float, block: Int, layout: ConstraintBlocks) {
    val compliance = bendCompliance()
    if (compliance < 0.0f) return
    val alpha = compliance / (h * h)
    val list = layout.bends
    // Массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop.
    val px = px; val py = py; val invMass = invMass
    val bendA = bendA; val bendB = bendB; val bendRest = bendRest; val next = list.next
    lengthsBeforeLoop(px.size or py.size or invMass.size or bendA.size or bendB.size or bendRest.size or next.size)
    var cursor = list.head[block]
    while (cursor >= 0) {
        val c = cursor; cursor = next[cursor]
        val i = bendA[c]; val j = bendB[c]
        val wi = invMass[i]; val wj = invMass[j]
        val w = wi + wj
        if (w == 0.0f) continue
        var dx = px[i] - px[j]
        var dy = py[i] - py[j]
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-12f) continue
        dx /= len; dy /= len
        val dL = -(len - bendRest[c]) / (w + alpha)
        px[i] += dx * dL * wi; py[i] += dy * dL * wi
        px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
    }
}

internal fun BodyState.solveBlockAreas(h: Float, block: Int, layout: ConstraintBlocks) {
    val alpha = AREA_COMPLIANCE / (h * h)
    // Вывернутым треугольникам своя, нулевая — см. AREA_COMPLIANCE_INVERTED.
    val alphaInverted = AREA_COMPLIANCE_INVERTED / (h * h)
    val maxStep = AREA_MAX_STEP * linkScale
    // Всё в локальные: стадия идёт 32 раза за тик по трём тысячам треугольников.
    val px = px; val py = py; val invMass = invMass
    val ta = triA; val tb = triB; val tc = triC
    // Множители клеток и мягкость — массив и признак, а не null: проверка на null в цикле возвращает чтение длины
    // массива в цикл. Длины — до цикла. См. lengthsBeforeLoop.
    val rest2 = triRestArea2; val tm = triMuscle; val ms = muscleScaleOf; val tsOn = cellScalesOn; val ts = triCellScale
    val ksOn = softnessOn; val ks = triSoftness
    val alive = baked.triAlive; val tid = triId; val fib = triFiberById
    val fibersOn = fibersOn; val fiberK = fiberThicken // волокно мышцы (MuscleFiber.kt)
    val list = layout.areas
    val from = list.start[block]; val to = from + list.count[block]
    val items = list.items
    lengthsBeforeLoop(px.size or py.size or invMass.size or ta.size or tb.size or tc.size or rest2.size or tm.size or
        ms.size or ts.size or ks.size or alive.size or tid.size or fib.size or items.size)
    for (slot in from until to) {
        val t = items[slot]
        if (t < 0) continue
        // Треугольник умирает вместе с любым своим ребром И СРАЗУ, ещё до пересборки:
        // держать площадь ткани, которой уже нет, он не должен. См. tearQueue.
        if (!alive[tid[t]]) continue
        val i0 = ta[t]; val i1 = tb[t]; val i2 = tc[t]
        val x0 = px[i0]; val y0 = py[i0]
        val x1 = px[i1]; val y1 = py[i1]
        val x2 = px[i2]; val y2 = py[i2]

        val g0x = y1 - y2; val g0y = x2 - x1
        val g1x = y2 - y0; val g1y = x0 - x2
        val g2x = y0 - y1; val g2y = x1 - x0

        val w0 = invMass[i0]; val w1 = invMass[i1]; val w2 = invMass[i2]
        val denom = w0 * (g0x * g0x + g0y * g0y) +
            w1 * (g1x * g1x + g1y * g1y) +
            w2 * (g2x * g2x + g2y * g2y)
        if (denom < 1e-18f) continue

        // Площадь покоя едет вместе с длинами: иначе мышца тянет треугольник вниз,
        // а несжимаемая площадь держит его на месте, и ткань запирает.
        val m = tm[t]
        val s0 = if (m < 0) 1.0f else ms[m]
        val s = if (!tsOn) s0 else s0 * ts[t]
        val active = minOf(1.0f, s)
        // Мышца с волокном (MuscleFiber.kt): площадь × s^(1−k).
        val fiberArea = s < 1.0f && fibersOn && tid[t] < fib.size && fib[tid[t]]
        val restArea2 = if (fiberArea) rest2[t].toFloat() * fiberAreaFactor(s, fiberK) else rest2[t].toFloat() * active * active
        val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
        // Мягкость ткани (BodyState.cellSoftness) — множитель податливости; вывернутым своя, нулевая.
        val material = if (!ksOn) alpha else alpha * ks[t]
        val alphaT = if (s > 1.0f && area2 > restArea2) tensileCompliance(material, s) else material
        // Плавный переход вместо ступеньки — см. AREA_SMOOTH_RAMP.
        val a = if (AREA_SMOOTH_RAMP) {
            val q = (area2 / restArea2).coerceIn(0.0f, 1.0f)
            alphaInverted + (alphaT - alphaInverted) * q * q
        } else if (area2 < 0.0f) alphaInverted else alphaT
        var excess = area2 - restArea2
        // Площадь мышцы тянет не сильнее удвоенной доли площади покоя — см. MUSCLE_MAX_PULL.
        if (if (fiberArea) fiberAreaFactor(s, fiberK) < 1.0f else active < 1.0f) { val cap = 2.0f * MUSCLE_MAX_PULL * rest2[t]; if (excess > cap) excess = cap }
        var dL = -excess / (denom + a)
        // Потолок на длину поправки — см. AREA_MAX_STEP.
        if (maxStep > 0.0f) {
            val corr = abs(dL) * sqrt(denom)
            if (corr > maxStep) dL *= maxStep / corr
        }

        px[i0] += w0 * dL * g0x; py[i0] += w0 * dL * g0y
        px[i1] += w1 * dL * g1x; py[i1] += w1 * dL * g1y
        px[i2] += w2 * dL * g2x; py[i2] += w2 * dL * g2y
    }
}

internal fun BodyState.solveBlockLimits(block: Int, layout: ConstraintBlocks, from: Int = 0) {
    // Локальный проход контура. Общая проверка после швов вызывается отдельно.
    val px = px; val py = py; val invMass = invMass
    val conA = conA; val conB = conB; val conRest = conRest; val conMuscle = conMuscle
    // Множители клеток — массив и признак, а не null; длины — до цикла (см. lengthsBeforeLoop).
    val conDead = conDead; val ms = muscleScaleOf; val csOn = cellScalesOn; val cs = conCellScale
    // Список дополняется killEdgeNow прямо в этом проходе. Внутренние связи
    // по-прежнему участвуют в solveConstraints, но здесь их координаты не читаем.
    val limits = layout
    limits.beginLimits(block)
    // Список у порога собирается заново; доработка после остановки воркера его продолжает.
    // Критерий записан так же, как у воркера (solveBlockLimitsParallel). См. ConstraintBlocks.nearItems.
    if (from == 0) layout.nearCount[block] = 0
    lengthsBeforeLoop(px.size or py.size or invMass.size or conA.size or conB.size or conRest.size or conMuscle.size or
        conDead.size or ms.size or cs.size)
    var cursor = from
    while (cursor < limits.limitCount()) {
        val c = limits.limitAt(cursor++)
        if (c < 0) continue
        if (conDead[c]) continue
        val i = conA[c]; val j = conB[c]
        val dx = px[i] - px[j]
        val dy = py[i] - py[j]
        // КОРЕНЬ БЕРЁТСЯ ТОЛЬКО КОГДА НУЖЕН.
        //
        // Стадия многократно идёт по связям текущего контура, и у подавляющего
        // большинства из них всё в порядке: ни сжатия до порога, ни растяжения сверх
        // предела. А корень считался ВСЕГДА и стоит 13–15 тактов латентности в
        // зависимой цепочке. Все пороги сравниваются с длиной, то есть с квадратом
        // длины сравниваются их квадраты — ровно то же самое, без корня.
        val len2 = dx * dx + dy * dy
        val mus = conMuscle[c]
        val base = if (mus < 0) 1.0f else ms[mus]
        val scale = if (!csOn) base else base * cs[c]
        val rest = conRest[c]
        val restScale = rest * limitShrink(c, scale)
        val crushLim = LINK_CRUSH_RATIO * restScale
        val max = linkMaxLength(limitRest(c, rest, scale), scale)
        val hi2 = max * max; val lo2 = crushLim * crushLim
        val isNear = len2 > hi2 * LIMIT_NEAR_HI2 || (tearingOn && len2 < lo2 * LIMIT_NEAR_LO2)
        if (isNear) layout.addNear(block, c)
        val crushed = tearingOn && len2 < lo2
        val over = len2 > hi2
        // Запас до разрыва за прогон — только когда он кому-то интересен: у худших
        // значений корень всё равно придётся взять, но это единицы связей за тик.
        // Запас до порогов копится В КВАДРАТАХ, корень берётся один раз при чтении:
        // сравнение «ближе ли этот запас прежнего» от возведения в квадрат не меняется.
        if (DEBUG_CHECKS && dbgLinkExtremaOn) {
            if (invMass[i] + invMass[j] == 0.0f) continue
            val cr2 = len2 / (restScale * restScale)
            if (cr2 < dbgWorstCrush2) dbgWorstCrush2 = cr2
            val st2 = len2 / (rest * rest)
            if (st2 > dbgWorstStretch2) dbgWorstStretch2 = st2
        }
        if (!crushed && !over) continue
        // Массы нужны только если есть нарушение. Пустой проход не читает
        // ещё два значения по индексам концов; порядок поправок остаётся прежним.
        if (invMass[i] + invMass[j] == 0.0f) continue
        solveLinkLimitViolation(c, len2, crushed, scale)
    }
    limits.endLimits()
}

/**
 * Поздний предел блока — только по связям у порога, собранным ранним проходом (ConstraintBlocks.nearItems).
 * Событие может дописать в список новый край контура (addLimit), поэтому счётчик читается на каждом шаге.
 */
internal fun BodyState.solveBlockNearLimits(block: Int, layout: ConstraintBlocks, from: Int) {
    var x = from
    while (x < layout.nearCount[block]) solveLimitItemSerial(layout.nearItems[block][x++])
}

/**
 * Длина створки с вершиной [v] и концами [i], [j] по взрослому углу при вершине (поза покоя тела) и нынешним
 * расстояниям от вершины до концов: d1² + d2² − 2·d1·d2·cos θ. См. solveBlockFlaps.
 */
internal fun BodyState.flapAngleRest(v: Int, i: Int, j: Int): Float {
    val ax = (body.x[i] - body.x[v]).toFloat(); val ay = (body.y[i] - body.y[v]).toFloat()
    val bx = (body.x[j] - body.x[v]).toFloat(); val by = (body.y[j] - body.y[v]).toFloat()
    val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
    if (la < 1e-12f || lb < 1e-12f) return 0.0f
    val cos = (ax * bx + ay * by) / (la * lb)
    val dix = px[i] - px[v]; val diy = py[i] - py[v]
    val djx = px[j] - px[v]; val djy = py[j] - py[v]
    val d1 = sqrt(dix * dix + diy * diy); val d2 = sqrt(djx * djx + djy * djy)
    val r2 = d1 * d1 + d2 * d2 - 2.0f * d1 * d2 * cos
    return if (r2 > 0.0f) sqrt(r2) else 0.0f
}
