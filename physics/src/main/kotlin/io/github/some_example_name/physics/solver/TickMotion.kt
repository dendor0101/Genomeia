package io.github.some_example_name.physics

/**
 * СРЕДНЯЯ СКОРОСТЬ ЗА ТИК ДЛЯ СТАДИЙ, КОТОРЫЕ ИДУТ РАЗ В ТИК.
 *
 * Лёгкая клетка, на которую связи тянут в разные стороны, дрожит с частотой подшагов:
 * обход связей меняет направление каждый подшаг (sweepBackwards), и при одной
 * итерации на подшаг решатель сходится то в одно, то в другое положение. Запись
 * игрока 22.10, клетка #885: скорость +0.129, -0.128, +0.129... — знак меняется
 * каждый подшаг, а за тик клетка сдвигается на 0.0007 клетки при «скорости» 0.07.
 * Сам дребезг внутренний и импульса не несёт.
 *
 * Но сопротивление среды с 22.10 считается раз в тик, на последнем подшаге, а
 * подшагов чётное число: оно ВСЕГДА видит одну и ту же фазу дребезга, со скоростью
 * одного знака. Сопротивление рёбер превращает её в толчок одного направления
 * каждый тик, и тело само по себе ровно ползёт: там же 0.00019 связи за тик
 * центра масс без единого контакта, не затухая. Без чередования обхода тот же
 * дрейф затухал (0.00018 -> 0.00005 за 165 тиков), без коррекции импульса не менялся.
 *
 * Лекарство — дать сопротивлению среднюю скорость за тик: смещение за тик, делённое
 * на тик. Дребезг с периодом в два подшага в ней гасится сам. Делается обёрткой:
 * перед сопротивлением из скорости вычитается дрожь (мгновенная минус средняя),
 * после — возвращается, так что сама стадия не меняется (проверки зовут её с
 * вручную заданной скоростью), а внутреннее колебание остаётся нетронутым.
 *
 * В том же проходе меряется покой для засыпания: удаление клеток от места, где
 * началась серия покоя (SLEEP_DRIFT_CELLS). По мгновенной скорости такое тело не
 * уснуло бы никогда.
 *
 * Цена: снимок положений бодрствующих частиц в начале тика и один проход в конце —
 * 16 байт на частицу, раз в тик.
 */
internal fun BodyState.beginTickMotion() {
    if (tickJitterX.size != n) {
        tickJitterX = FloatArray(n); tickJitterY = FloatArray(n)
    }
    prepareAwakeSpans()
    val s = sleep
    for (k in 0 until s.spanCount) {
        val from = s.spanStart[k]; val len = s.spanEnd[k] - from
        System.arraycopy(px, from, tickJitterX, from, len)
        System.arraycopy(py, from, tickJitterY, from, len)
    }
}

/**
 * Последний подшаг, после восстановления скоростей: скорость становится средней за
 * тик, дрожь откладывается в tickJitter. Заодно — удаление клеток каждого
 * бодрствующего организма от места начала его серии покоя; у организма без серии
 * (счёт ноль) это место ставится здесь.
 */
internal fun BodyState.removeTickJitter() {
    if (orgSleepDrift2.size < n) {
        orgSleepDrift2 = FloatArray(n); sleepAnchorX = FloatArray(n); sleepAnchorY = FloatArray(n)
    }
    // Только организмы текущей области (острова, TickScope.kt): удаление чужой области уже посчитано или посчитается.
    if (sleep.orgScope == null) java.util.Arrays.fill(orgSleepDrift2, 0, organismCount, 0.0f)
    else forScopeOrganisms { o -> orgSleepDrift2[o] = 0.0f }
    prepareAwakeSpans()
    val s = sleep
    val w = activeWorkers
    if (w != null && w.count > 1) parallelPhysics(w).removeJitter()
    else for (k in 0 until s.spanCount) removeJitterRange(s.spanStart[k], s.spanEnd[k])
    // Удаление от места покоя — максимум на организм, общие ячейки: отдельным проходом в одном потоке.
    val calm = s.calmTicks
    if (calm.isEmpty()) return
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        if (invMass[i] == 0.0f) continue
        val x = px[i]; val y = py[i]
        val o = organismOf[i]
        if (calm[o] == 0) { sleepAnchorX[i] = x; sleepAnchorY[i] = y; continue }
        val dx = x - sleepAnchorX[i]; val dy = y - sleepAnchorY[i]
        val d2 = dx * dx + dy * dy
        if (d2 > orgSleepDrift2[o]) orgSleepDrift2[o] = d2
    }
}

/** Частицы [from, to): скорость — средняя за тик, дрожь — в tickJitter. Кусок параллельного прохода. */
internal fun BodyState.removeJitterRange(from: Int, to: Int) {
    val inv = 1.0f / DT
    // Массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop.
    val px = px; val py = py; val vx = vx; val vy = vy; val invMass = invMass
    val tickJitterX = tickJitterX; val tickJitterY = tickJitterY
    lengthsBeforeLoop(px.size or py.size or vx.size or vy.size or invMass.size or tickJitterX.size or tickJitterY.size)
    for (i in from until to) {
        if (invMass[i] == 0.0f) { tickJitterX[i] = 0.0f; tickJitterY[i] = 0.0f; continue }
        val mx = (px[i] - tickJitterX[i]) * inv
        val my = (py[i] - tickJitterY[i]) * inv
        tickJitterX[i] = vx[i] - mx; tickJitterY[i] = vy[i] - my
        vx[i] = mx; vy[i] = my
    }
}

/** После сопротивления: дрожь возвращается в скорость. */
internal fun BodyState.restoreTickJitter() {
    val s = sleep
    val w = activeWorkers
    if (w != null && w.count > 1) { parallelPhysics(w).restoreJitter(); return }
    for (k in 0 until s.spanCount) restoreJitterRange(s.spanStart[k], s.spanEnd[k])
}

internal fun BodyState.restoreJitterRange(from: Int, to: Int) {
    val vx = vx; val vy = vy; val tickJitterX = tickJitterX; val tickJitterY = tickJitterY
    lengthsBeforeLoop(vx.size or vy.size or tickJitterX.size or tickJitterY.size)
    for (i in from until to) { vx[i] += tickJitterX[i]; vy[i] += tickJitterY[i] }
}

/** Начало учёта импульса за тик: группы в одном потоке, импульс частиц — на воркерах. */
internal fun BodyState.momentumBegin() {
    val w = activeWorkers
    if (w != null && w.count > 1) parallelPhysics(w).momentumBegin()
    else tickMomentum.begin(px, py, vx, vy, invMass, organismOf, sleep, cellDead)
}

internal fun BodyState.momentumCancel() {
    prepareAwakeSpans()
    val w = activeWorkers
    if (w != null && w.count > 1) parallelPhysics(w).momentumCancel()
    else tickMomentum.cancel(px, py, vx, vy, invMass, sleep, passChunks())
}
