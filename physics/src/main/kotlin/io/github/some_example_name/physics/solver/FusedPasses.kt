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
internal fun BodyState.velocityPassBefore(h: Float) {
    prepareAwakeSpans()
    val w = activeWorkers
    if (w != null && w.count > 1) { parallelPhysics(w).velocityBefore(h); return }
    val s = sleep
    for (k in 0 until s.spanCount) velocityBeforeRange(s.spanStart[k], s.spanEnd[k], h)
}

/** Восстановление скоростей частиц [from, to). Кусок параллельного прохода — см. ParallelPhysics. */
internal fun BodyState.velocityBeforeRange(from: Int, to: Int, h: Float) {
    // Массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop. Снимок учёта импульса пишется
    // прямо в его массивы (тот же TickMomentum.beforeAt); без учёта они пустые и не трогаются.
    val px = px; val py = py; val vx = vx; val vy = vy; val prevX = prevX; val prevY = prevY; val invMass = invMass
    val tm = if (cancelSpinOn) tickMomentum else null
    val tmOn = tm != null
    val beforeX = tm?.beforeX ?: NO_FLOATS; val beforeY = tm?.beforeY ?: NO_FLOATS
    lengthsBeforeLoop(px.size or py.size or vx.size or vy.size or prevX.size or prevY.size or invMass.size or
        beforeX.size or beforeY.size)
    // Умножение на 1/h вместо двух делений на частицу (находка 41): закон тот же, биты другие. См. solveDistanceConstraint.
    val invH = 1.0f / h
    for (i in from until to) {
        if (invMass[i] == 0.0f) {
            vx[i] = 0.0f; vy[i] = 0.0f
        } else {
            val nvx = (px[i] - prevX[i]) * invH
            val nvy = (py[i] - prevY[i]) * invH
            vx[i] = nvx; vy[i] = nvy
        }
        if (tmOn) { beforeX[i] = vx[i]; beforeY[i] = vy[i] }
    }
}

/**
 * Отскок от пола, изотропная среда (раз в тик, [lastStep]), потолок скорости и учёт
 * внешнего импульса — в этом порядке, как шли раздельные проходы. [hTick] — то же
 * значение h * SUBSTEPS, что получала applyMediumDrag: иначе округление даст другие биты.
 */
internal fun BodyState.velocityPassAfter(lastStep: Boolean, hTick: Float) {
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
internal fun BodyState.velocityAfterRange(from: Int, to: Int, lastStep: Boolean, hTick: Float,
                                          acc: WorkerScratch?) {
    val medium = lastStep && !dbgIsoDragOff
    var keep = 1.0f - environment.mediumDrag * hTick
    if (keep < 0.0f) keep = 0.0f
    val clamp = !dbgNoClamp
    val maxV = MAX_SPEED_CELLS_PER_TICK * linkScale / DT
    val maxV2 = maxV * maxV
    // Массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop.
    val px = px; val py = py; val vx = vx; val vy = vy; val invMass = invMass; val inContact = inContact
    val dragScale = dragScale
    val tm = if (cancelSpinOn) tickMomentum else null
    val tmOn = tm != null
    val beforeX = tm?.beforeX ?: NO_FLOATS; val beforeY = tm?.beforeY ?: NO_FLOATS
    val targetX = tm?.targetX ?: NO_DOUBLES; val targetY = tm?.targetY ?: NO_DOUBLES; val targetL = tm?.targetL ?: NO_DOUBLES
    val blocked = tm?.blocked ?: NO_BOOLEANS
    lengthsBeforeLoop(px.size or py.size or vx.size or vy.size or invMass.size or inContact.size or dragScale.size or
        beforeX.size or beforeY.size or targetX.size or targetY.size or targetL.size or blocked.size)
    for (i in from until to) {
        if (inContact[i] && vy[i] > 0.0f) vy[i] *= GROUND_RESTITUTION
        if (medium) {
            val d = dragScale[i]
            val k = if (d == 1.0f) keep else mediumKeep(hTick, d)
            vx[i] *= k; vy[i] *= k
        }
        // Потолок — после учёта внешнего импульса: срез скорости одной частицы — не сила среды,
        // и учёт возвращает срезанный импульс организму целиком. Раньше срез шёл во внешнее, и
        // упор новорождённой в потолок оставлял организму отдачу остальной ткани (BirthKickProbe
        // 03.11: Crayfish разгонялся с 0.014 до 0.26 клетки за тик без единого разрыва).
        if (tmOn) momentumExternalAt(i, px, py, vx, vy, invMass, inContact[i], beforeX, beforeY, targetX, targetY, targetL, blocked)
        if (clamp) { if (acc == null) clampOne(i, maxV, maxV2, vx, vy) else clampOneInto(i, maxV, maxV2, acc, vx, vy) }
    }
}

/**
 * ЛЁГКИЙ ПРОХОД ПО СКОРОСТЯМ на подшагах, кроме последнего в тике. У частицы без контакта между
 * восстановлением скорости и потолком ничего не происходит: внешнего импульса нет, учёт ничего
 * не складывает, и снимок для него не нужен. Поэтому один проход, а частицы контактов
 * (и кости, которых они касаются) доделываются поштучно после отскока. Хватка и тяга мышью
 * меняют скорость вне контактов — при них работает полный путь; гравитация мира (applyGravity) — тоже.
 * Цена: 4 байта на частицу и 4 на кость; потоково читается один лишний массив.
 */
internal fun BodyState.velocityFastAllowed(): Boolean = dragId < 0 && gripCountNow() == 0 && environment.gravityY == 0.0f

/** Пометить частицы, чью скорость изменит отскок этого подшага: контакты и целиком их кости. */
internal fun BodyState.markVelocityTouched() {
    if (velTouchStamp.size < n) { velTouchStamp = IntArray(n); velTouchList = IntArray(n) }
    if (velBoneStamp.size < rigidBones.size) velBoneStamp = IntArray(rigidBones.size)
    velTouchPass++
    velTouchN = 0
    val ct = contacts
    if (ct == null || !contactsOn || contactsStale) return
    for (k in 0 until ct.contactCount) { markTouched(ct.contactI(k)); markTouched(ct.contactJ(k)) }
}

private fun BodyState.markTouched(i: Int) {
    if (velTouchStamp[i] != velTouchPass) { velTouchStamp[i] = velTouchPass; velTouchList[velTouchN++] = i }
    if (!bonesRigid) return
    val b = boneOf[i]
    if (b < 0 || velBoneStamp[b] == velTouchPass) return
    velBoneStamp[b] = velTouchPass
    for (k in rigidBones[b]) if (velTouchStamp[k] != velTouchPass) { velTouchStamp[k] = velTouchPass; velTouchList[velTouchN++] = k }
}

internal fun BodyState.velocityPassFast(h: Float) {
    prepareAwakeSpans()
    val w = activeWorkers
    if (w != null && w.count > 1) { parallelPhysics(w).velocityFast(h); return }
    val s = sleep
    for (k in 0 until s.spanCount) velocityFastRange(s.spanStart[k], s.spanEnd[k], h, null)
}

internal fun BodyState.velocityFastRange(from: Int, to: Int, h: Float, acc: WorkerScratch?) {
    val clamp = !dbgNoClamp
    val maxV = MAX_SPEED_CELLS_PER_TICK * linkScale / DT
    val maxV2 = maxV * maxV
    val stamp = velTouchStamp; val pass = velTouchPass
    // Массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop.
    val px = px; val py = py; val vx = vx; val vy = vy; val prevX = prevX; val prevY = prevY
    val invMass = invMass; val inContact = inContact
    val tm = if (cancelSpinOn) tickMomentum else null
    val tmOn = tm != null
    val beforeX = tm?.beforeX ?: NO_FLOATS; val beforeY = tm?.beforeY ?: NO_FLOATS
    val targetX = tm?.targetX ?: NO_DOUBLES; val targetY = tm?.targetY ?: NO_DOUBLES; val targetL = tm?.targetL ?: NO_DOUBLES
    val blocked = tm?.blocked ?: NO_BOOLEANS
    lengthsBeforeLoop(px.size or py.size or vx.size or vy.size or prevX.size or prevY.size or invMass.size or
        inContact.size or stamp.size or beforeX.size or beforeY.size or targetX.size or targetY.size or targetL.size or
        blocked.size)
    val invH = 1.0f / h // см. velocityBeforeRange
    for (i in from until to) {
        if (invMass[i] == 0.0f) {
            vx[i] = 0.0f; vy[i] = 0.0f
        } else {
            vx[i] = (px[i] - prevX[i]) * invH
            vy[i] = (py[i] - prevY[i]) * invH
        }
        if (stamp[i] == pass) { if (tmOn) { beforeX[i] = vx[i]; beforeY[i] = vy[i] }; continue }
        if (inContact[i]) {
            if (tmOn) { beforeX[i] = vx[i]; beforeY[i] = vy[i] }
            if (vy[i] > 0.0f) vy[i] *= GROUND_RESTITUTION
            if (tmOn) momentumExternalAt(i, px, py, vx, vy, invMass, inContact[i], beforeX, beforeY, targetX, targetY, targetL, blocked)
        }
        if (clamp) { if (acc == null) clampOne(i, maxV, maxV2, vx, vy) else clampOneInto(i, maxV, maxV2, acc, vx, vy) }
    }
}

/** Частицы контактов после отскока: то же, что velocityAfterRange на подшаге без среды. */
internal fun BodyState.finishVelocityTouched() {
    // Кости, которых касаются контакты, помечаются целиком: список бывает в тысячи частиц (замер 10.11: 8% тика).
    val w = activeWorkers
    if (w != null && w.count > 1 && velTouchN >= 1024) { parallelPhysics(w).finishTouched(); return }
    finishVelocityTouchedRange(0, velTouchN, null)
}

internal fun BodyState.finishVelocityTouchedRange(from: Int, to: Int, acc: WorkerScratch?) {
    val clamp = !dbgNoClamp
    val maxV = MAX_SPEED_CELLS_PER_TICK * linkScale / DT
    val maxV2 = maxV * maxV
    // Массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop.
    val px = px; val py = py; val vx = vx; val vy = vy; val invMass = invMass; val inContact = inContact
    val list = velTouchList
    val tm = if (cancelSpinOn) tickMomentum else null
    val tmOn = tm != null
    val beforeX = tm?.beforeX ?: NO_FLOATS; val beforeY = tm?.beforeY ?: NO_FLOATS
    val targetX = tm?.targetX ?: NO_DOUBLES; val targetY = tm?.targetY ?: NO_DOUBLES; val targetL = tm?.targetL ?: NO_DOUBLES
    val blocked = tm?.blocked ?: NO_BOOLEANS
    lengthsBeforeLoop(px.size or py.size or vx.size or vy.size or invMass.size or inContact.size or list.size or
        beforeX.size or beforeY.size or targetX.size or targetY.size or targetL.size or blocked.size)
    for (k in from until to) {
        val i = list[k]
        if (inContact[i] && vy[i] > 0.0f) vy[i] *= GROUND_RESTITUTION
        if (tmOn) momentumExternalAt(i, px, py, vx, vy, invMass, inContact[i], beforeX, beforeY, targetX, targetY, targetL, blocked)
        if (clamp) { if (acc == null) clampOne(i, maxV, maxV2, vx, vy) else clampOneInto(i, maxV, maxV2, acc, vx, vy) }
    }
}

/** Слияние групп импульса по контактам подшага: по воркерам, если контактов много. */
internal fun BodyState.noteContactsMomentum(ct: BoundaryContacts) {
    val w = activeWorkers
    if (w != null && w.count > 1 && ct.contactCount >= 1024) parallelPhysics(w).noteContacts(ct)
    else tickMomentum.noteContacts(ct, invMass)
}

/** Доля скорости, что остаётся за тик у частицы со своим множителем сопротивления [d]. */
internal fun BodyState.mediumKeep(hTick: Float, d: Float): Float {
    val k = 1.0f - environment.mediumDrag * hTick * d
    return if (k < 0.0f) 0.0f else k
}

/** clampOne со счётчиками воркера: та же арифметика. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun clampOneInto(i: Int, maxV: Float, maxV2: Float, acc: WorkerScratch, vx: FloatArray, vy: FloatArray) {
    val v2 = vx[i] * vx[i] + vy[i] * vy[i]
    if (v2 > acc.peakSpeed2) acc.peakSpeed2 = v2
    if (v2 <= maxV2) return
    val sc = maxV / sqrt(v2)
    vx[i] *= sc; vy[i] *= sc
    acc.speedCapHits++
}

/**
 * Потолок одной частицы. Скорости — параметрами и `inline`: вызов на каждую частицу читал бы заголовки
 * массивов заново (см. lengthsBeforeLoop). Раньше была отдельной функцией ради маленького цикла для JIT;
 * тело — девять строк, цикл остаётся небольшим.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun BodyState.clampOne(i: Int, maxV: Float, maxV2: Float, vx: FloatArray, vy: FloatArray) {
    val v2 = vx[i] * vx[i] + vy[i] * vy[i]
    if (v2 > peakSpeed2) peakSpeed2 = v2
    if (v2 <= maxV2) return
    val sc = maxV / sqrt(v2)
    vx[i] *= sc; vy[i] *= sc
    speedCapHits++
}
