package io.github.some_example_name.physics

import kotlin.math.sqrt

/**
 * СКЛЕЕННЫЕ ПОШТУЧНЫЕ ПРОХОДЫ ПО СКОРОСТЯМ.
 *
 * После контактов каждый подшаг шли пять отдельных проходов по всем бодрствующим
 * частицам: восстановление скорости, снимок для учёта импульса, отскок от пола,
 * потолок скорости, учёт внешнего импульса (и раз в тик — изотропная среда). Каждый
 * заново читал vx/vy и соседние массивы; на 100k это мегабайты из памяти на проход.
 * Все эти стадии считаются для каждой частицы отдельно, поэтому их можно сделать
 * за два прохода: до редких стадий (отскок контактов, тяга мышью, вязкость и
 * сопротивление раз в тик) и после них. Порядок операций внутри частицы прежний —
 * результат побитово тот же, что у раздельных проходов.
 *
 * Раздельные функции (updateVelocities, applyRestitution, applyMediumDrag,
 * clampSpeed) остаются: их зовут стенды поштучно и вторая коррекция потолка в конце тика.
 */
internal fun BodyState.velocityPassBefore(h: Double) {
    prepareAwakeSpans()
    val s = sleep
    val tm = if (cancelSpinOn) tickMomentum else null
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        if (invMass[i] == 0.0) {
            vx[i] = 0.0; vy[i] = 0.0
        } else {
            val nvx = (px[i] - prevX[i]) / h
            val nvy = (py[i] - prevY[i]) / h
            vx[i] = nvx; vy[i] = nvy
        }
        tm?.beforeAt(i, vx[i], vy[i])
    }
}

/**
 * Отскок от пола, изотропная среда (раз в тик, [lastStep]), потолок скорости и учёт
 * внешнего импульса — в этом порядке, как шли раздельные проходы. [hTick] — то же
 * значение h * SUBSTEPS, что получала applyMediumDrag: иначе округление даст другие биты.
 */
internal fun BodyState.velocityPassAfter(lastStep: Boolean, hTick: Double) {
    prepareAwakeSpans()
    val s = sleep
    val medium = lastStep && !dbgIsoDragOff
    var keep = 1.0 - MEDIUM_DRAG * hTick
    if (keep < 0.0) keep = 0.0
    val clamp = !dbgNoClamp
    val maxV = MAX_SPEED_CELLS_PER_TICK * body.meanLinkLength / DT
    val maxV2 = maxV * maxV
    val tm = if (cancelSpinOn) tickMomentum else null
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        if (inContact[i] && vy[i] > 0.0) vy[i] *= GROUND_RESTITUTION
        if (medium) { vx[i] *= keep; vy[i] *= keep }
        if (clamp) clampOne(i, maxV, maxV2)
        tm?.externalAt(i, px, py, vx, vy, invMass, inContact)
    }
}

/** Потолок одной частицы. Отдельной функцией, чтобы склеенный цикл остался маленьким для JIT. */
private fun BodyState.clampOne(i: Int, maxV: Double, maxV2: Double) {
    val v2 = vx[i] * vx[i] + vy[i] * vy[i]
    if (v2 > peakSpeed2) peakSpeed2 = v2
    if (v2 <= maxV2) return
    val sc = maxV / sqrt(v2)
    vx[i] *= sc; vy[i] *= sc
    speedCapHits++
}
