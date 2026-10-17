package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

/** Текущая податливость изгиба. Отдельным методом — его читает сверка в SwimTuner. */
fun BodyState.bendCompliance(): Double = BEND_LEVELS[bendLevel]

fun BodyState.solveConstraints(h: Double) {
    val alpha = SOFT_COMPLIANCE / (h * h)
    // ВСЁ В ЛОКАЛЬНЫЕ. Стадия идёт 64 раза за тик по пяти тысячам связей, и на таком
    // числе проходов заметно даже обращение к полю: у lateinit оно с проверкой.
    val px = px; val py = py; val invMass = invMass
    val conA = conA; val conB = conB; val conRest = conRest; val conMuscle = conMuscle
    val conDead = conDead; val ms = muscleScaleOf
    // Направление обхода ЧЕРЕДУЕТСЯ. См. sweepBackwards. Индексом, а не диапазоном:
    // диапазон с переменным направлением — объект с итератором на каждый шаг.
    val step = if (sweepBackwards) -1 else 1
    var c = if (sweepBackwards) conCount - 1 else 0
    var left = conCount
    while (left > 0) {
        left--
        val cc = c
        c += step
        // Порванная связь не тянет ни мгновения — даже до пересборки топологии.
        if (conDead[cc]) continue
        val i = conA[cc]; val j = conB[cc]
        val wi = invMass[i]; val wj = invMass[j]
        val w = wi + wj
        if (w == 0.0) continue
        var dx = px[i] - px[j]
        var dy = py[i] - py[j]
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-12) continue
        dx /= len; dy /= len
        val m = conMuscle[cc]
        val rest = conRest[cc] * (if (m < 0) 1.0 else ms[m])
        val dL = -(len - rest) / (w + alpha)
        px[i] += dx * dL * wi; py[i] += dy * dL * wi
        px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
    }
}

fun BodyState.solveLinkMaxLength() {
    if (tearingOn) SealStats.tearingOnTicks++ else SealStats.tearingOffTicks++
    // Всё в локальные: при MAXLEN_EARLY стадия идёт 144 раза за тик.
    val px = px; val py = py; val invMass = invMass
    val conA = conA; val conB = conB; val conRest = conRest; val conMuscle = conMuscle
    val conDead = conDead; val ms = muscleScaleOf
    for (c in 0 until conCount) {
        if (conDead[c]) continue
        val i = conA[c]; val j = conB[c]
        val wi = invMass[i]; val wj = invMass[j]
        val w = wi + wj
        if (w == 0.0) continue
        val dx = px[i] - px[j]
        val dy = py[i] - py[j]
        // КОРЕНЬ БЕРЁТСЯ ТОЛЬКО КОГДА НУЖЕН.
        //
        // Стадия идёт 144 раза за тик по пяти тысячам связей, и у подавляющего
        // большинства из них всё в порядке: ни сжатия до порога, ни растяжения сверх
        // предела. А корень считался ВСЕГДА и стоит 13–15 тактов латентности в
        // зависимой цепочке. Все пороги сравниваются с длиной, то есть с квадратом
        // длины сравниваются их квадраты — ровно то же самое, без корня.
        val len2 = dx * dx + dy * dy
        val mus = conMuscle[c]
        val scale = if (mus < 0) 1.0 else ms[mus]
        val rest = conRest[c]
        val restScale = rest * scale
        val crushLim = LINK_CRUSH_RATIO * restScale
        val max = LINK_MAX_STRETCH * rest
        val crushed = tearingOn && len2 < crushLim * crushLim
        val over = len2 > max * max
        // Запас до разрыва за прогон — только когда он кому-то интересен: у худших
        // значений корень всё равно придётся взять, но это единицы связей за тик.
        // Запас до порогов копится В КВАДРАТАХ, корень берётся один раз при чтении:
        // сравнение «ближе ли этот запас прежнего» от возведения в квадрат не меняется.
        if (DEBUG_CHECKS && dbgLinkExtremaOn) {
            val cr2 = len2 / (restScale * restScale)
            if (cr2 < dbgWorstCrush2) dbgWorstCrush2 = cr2
            val st2 = len2 / (rest * rest)
            if (st2 > dbgWorstStretch2) dbgWorstStretch2 = st2
        }
        if (!crushed && !over) continue
        solveLinkLimitViolation(c, len2, crushed, scale)
    }
}

/** Редкие разрывы отдельно от цикла: меньше байткода и живых локальных в обычном проходе. */
fun BodyState.solveLinkLimitViolation(c: Int, len2: Double, crushed: Boolean, scale: Double) {
    val i = conA[c]; val j = conB[c]
    val wi = invMass[i]; val wj = invMass[j]; val w = wi + wj
    var dx = px[i] - px[j]; var dy = py[i] - py[j]
    val mus = conMuscle[c]
    val max = LINK_MAX_STRETCH * conRest[c]
    val meanLink = body.meanLinkLength
    val len = sqrt(len2)
    // РАЗДАВЛЕННАЯ СВЯЗЬ РВЁТСЯ ТАК ЖЕ, КАК ПЕРЕРАСТЯНУТАЯ. См. LINK_CRUSH_RATIO.
    if (crushed) {
        linkTorn[c] = true; conDead[c] = true; tearsPending = true; killEdgeNow(conEdge[c])
        crushedCell[i] = true; crushedCell[j] = true
        dbgTornCrush++; if (mus >= 0) dbgTornCrushMuscle++
        dbgTear(c, i, j, "сжатие", len / (conRest[c] * scale))
        return
    }
    if (dbgSealOn && conBoundary[c]) dbgSeal(c, i, j, len)
    @Suppress("UNUSED_EXPRESSION")
    // ЩЕЛЬ СТАЛА ПРОХОДИМОЙ — РВЁМ. Проверка идёт ДО выхода по пределу длины:
    // щель открывается раньше, чем связь дотягивается до своего потолка.
    // См. membranePassTear.
    if (tearingOn && membranePassTear && conBoundary[c] &&
        len > (contacts?.contactRadiusOf(i) ?: 0.0) +
            (contacts?.contactRadiusOf(j) ?: 0.0) + 2.0 * dbgSealMinR) {
        linkTorn[c] = true; conDead[c] = true; tearsPending = true; killEdgeNow(conEdge[c])
        membranePassCount++; SealStats.torn++
        dbgTear(c, i, j, "щель", len / conRest[c])
        return
    }
    if (len < 1e-12) return
    val overStrain = (len - max) / conRest[c]
    if (overStrain > dbgMaxOverStrain) dbgMaxOverStrain = overStrain
    // Пик недобора за всё время: сколько связь хотела сверх предела.
    val tear = if (conTendon[c]) linkTearStrain * tendonTear else linkTearStrain
    if (len - max > tear * conRest[c]) {
        linkTorn[c] = true
        if (tearingOn) { conDead[c] = true; tearsPending = true; killEdgeNow(conEdge[c]) }
        dbgTornPull++; if (mus >= 0) dbgTornPullMuscle++
        dbgTear(c, i, j, "растяжение", len / conRest[c])
    }
    // МЕМБРАНА ХРУПЧЕ ТКАНИ. См. MEMBRANE_TEAR_STRAIN.
    if (tearingOn && membraneTearStrain >= 0.0 && conBoundary[c] && !conDead[c] &&
        len - max > membraneTearStrain * conRest[c]) {
        linkTorn[c] = true; conDead[c] = true; tearsPending = true; killEdgeNow(conEdge[c])
        membraneTearCount++
    }
    dx /= len; dy /= len
    // Тот же потолок поправки за подшаг, что и у контакта, и по той же
    // причине: стадия ЖЁСТКАЯ, перерастяжение она снимает целиком за один
    // подшаг, а updateVelocities делит поправку на крошечное h. При загибе
    // связи растягиваются сильно, и отсюда шёл остаток выброса скорости.
    // Делится по паре в тех же долях, поэтому импульс пары сохраняется.
    var dL = -(len - max) / w
    val capL = LINK_MAX_STEP * meanLink / w
    if (dL < -capL) dL = -capL
    px[i] += dx * dL * wi; py[i] += dy * dL * wi
    px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
    linkCapHits++
}

/** Односторонняя: только не даёт сойтись ближе позы покоя. */
fun BodyState.solveFlaps(h: Double) {
    if (flapCount == 0) return
    val alpha = FLAP_COMPLIANCE / (h * h)
    for (c in 0 until flapCount) {
        val i = flapA[c]; val j = flapB[c]
        val wi = invMass[i]; val wj = invMass[j]
        val w = wi + wj
        if (w == 0.0) continue
        var dx = px[i] - px[j]
        var dy = py[i] - py[j]
        val len = sqrt(dx * dx + dy * dy)
        val rest = flapRest[c]
        if (len >= rest || len < 1e-12) continue
        dx /= len; dy /= len
        val dL = -(len - rest) / (w + alpha)
        px[i] += dx * dL * wi; py[i] += dy * dL * wi
        px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
    }
}

fun BodyState.solveBend(h: Double) {
    val compliance = bendCompliance()
    if (compliance < 0.0) return
    val alpha = compliance / (h * h)
    for (c in 0 until bendCount) {
        val i = bendA[c]; val j = bendB[c]
        val wi = invMass[i]; val wj = invMass[j]
        val w = wi + wj
        if (w == 0.0) continue
        var dx = px[i] - px[j]
        var dy = py[i] - py[j]
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-12) continue
        dx /= len; dy /= len
        val dL = -(len - bendRest[c]) / (w + alpha)
        px[i] += dx * dL * wi; py[i] += dy * dL * wi
        px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
    }
}

fun BodyState.solveAreas(h: Double) {
    val alpha = AREA_COMPLIANCE / (h * h)
    // Вывернутым треугольникам своя, нулевая — см. AREA_COMPLIANCE_INVERTED.
    val alphaInverted = AREA_COMPLIANCE_INVERTED / (h * h)
    val maxStep = AREA_MAX_STEP * body.meanLinkLength
    // Всё в локальные: стадия идёт 64 раза за тик по трём тысячам треугольников.
    val px = px; val py = py; val invMass = invMass
    val ta = triA; val tb = triB; val tc = triC
    val rest2 = triRestArea2; val tm = triMuscle; val ms = muscleScaleOf
    val alive = baked.triAlive; val tid = triId
    for (t in 0 until triCount) {
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
        if (denom < 1e-18) continue

        // Площадь покоя едет вместе с длинами: иначе мышца тянет треугольник вниз,
        // а несжимаемая площадь держит его на месте, и ткань запирает.
        val m = tm[t]
        val s = if (m < 0) 1.0 else ms[m]
        val restArea2 = rest2[t].toDouble() * s * s
        val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
        // Плавный переход вместо ступеньки — см. AREA_SMOOTH_RAMP.
        val a = if (AREA_SMOOTH_RAMP) {
            val q = (area2 / restArea2).coerceIn(0.0, 1.0)
            alphaInverted + (alpha - alphaInverted) * q * q
        } else if (area2 < 0.0) alphaInverted else alpha
        var dL = -(area2 - restArea2) / (denom + a)
        // Потолок на длину поправки — см. AREA_MAX_STEP.
        if (maxStep > 0.0) {
            val corr = abs(dL) * sqrt(denom)
            if (corr > maxStep) dL *= maxStep / corr
        }

        px[i0] += w0 * dL * g0x; py[i0] += w0 * dL * g0y
        px[i1] += w1 * dL * g1x; py[i1] += w1 * dL * g1y
        px[i2] += w2 * dL * g2x; py[i2] += w2 * dL * g2y
    }
}
