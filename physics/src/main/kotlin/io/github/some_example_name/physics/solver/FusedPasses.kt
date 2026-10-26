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
    val w = activeWorkers
    if (w != null && w.count > 1) { parallelPhysics(w).velocityBefore(h); return }
    val s = sleep
    for (k in 0 until s.spanCount) velocityBeforeRange(s.spanStart[k], s.spanEnd[k], h)
}

/** Восстановление скоростей частиц [from, to). Кусок параллельного прохода — см. ParallelPhysics. */
internal fun BodyState.velocityBeforeRange(from: Int, to: Int, h: Double) {
    val tm = if (cancelSpinOn) tickMomentum else null
    for (i in from until to) {
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
    val w = activeWorkers
    if (w != null && w.count > 1) { parallelPhysics(w).velocityAfter(lastStep, hTick); return }
    val s = sleep
    for (k in 0 until s.spanCount) velocityAfterRange(s.spanStart[k], s.spanEnd[k], lastStep, hTick, null)
}

/**
 * Частицы [from, to) прохода после редких стадий. [acc] — счётчики воркера в
 * параллельном проходе; null — счётчики самого состояния.
 */
internal fun BodyState.velocityAfterRange(from: Int, to: Int, lastStep: Boolean, hTick: Double,
                                          acc: WorkerScratch?) {
    val medium = lastStep && !dbgIsoDragOff
    var keep = 1.0 - MEDIUM_DRAG * hTick
    if (keep < 0.0) keep = 0.0
    val clamp = !dbgNoClamp
    val maxV = MAX_SPEED_CELLS_PER_TICK * linkScale / DT
    val maxV2 = maxV * maxV
    val tm = if (cancelSpinOn) tickMomentum else null
    for (i in from until to) {
        if (inContact[i] && vy[i] > 0.0) vy[i] *= GROUND_RESTITUTION
        if (medium) {
            val d = dragScale[i]
            val k = if (d == 1.0) keep else mediumKeep(hTick, d)
            vx[i] *= k; vy[i] *= k
        }
        if (clamp) { if (acc == null) clampOne(i, maxV, maxV2) else clampOneInto(i, maxV, maxV2, acc) }
        tm?.externalAt(i, px, py, vx, vy, invMass, inContact)
    }
}

/** Доля скорости, что остаётся за тик у частицы со своим множителем сопротивления [d]. */
internal fun mediumKeep(hTick: Double, d: Double): Double {
    val k = 1.0 - MEDIUM_DRAG * hTick * d
    return if (k < 0.0) 0.0 else k
}

/** clampOne со счётчиками воркера: та же арифметика. */
private fun BodyState.clampOneInto(i: Int, maxV: Double, maxV2: Double, acc: WorkerScratch) {
    val v2 = vx[i] * vx[i] + vy[i] * vy[i]
    if (v2 > acc.peakSpeed2) acc.peakSpeed2 = v2
    if (v2 <= maxV2) return
    val sc = maxV / sqrt(v2)
    vx[i] *= sc; vy[i] *= sc
    acc.speedCapHits++
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
