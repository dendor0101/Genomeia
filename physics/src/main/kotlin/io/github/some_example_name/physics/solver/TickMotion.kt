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
        tickJitterX = DoubleArray(n); tickJitterY = DoubleArray(n)
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
        orgSleepDrift2 = DoubleArray(n); sleepAnchorX = DoubleArray(n); sleepAnchorY = DoubleArray(n)
    }
    java.util.Arrays.fill(orgSleepDrift2, 0, organismCount, 0.0)
    prepareAwakeSpans()
    val s = sleep
    val calm = s.calmTicks
    val inv = 1.0 / DT
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        if (invMass[i] == 0.0) { tickJitterX[i] = 0.0; tickJitterY[i] = 0.0; continue }
        val x = px[i]; val y = py[i]
        val mx = (x - tickJitterX[i]) * inv
        val my = (y - tickJitterY[i]) * inv
        tickJitterX[i] = vx[i] - mx; tickJitterY[i] = vy[i] - my
        vx[i] = mx; vy[i] = my
        if (calm.isEmpty()) continue
        val o = organismOf[i]
        if (calm[o] == 0) { sleepAnchorX[i] = x; sleepAnchorY[i] = y; continue }
        val dx = x - sleepAnchorX[i]; val dy = y - sleepAnchorY[i]
        val d2 = dx * dx + dy * dy
        if (d2 > orgSleepDrift2[o]) orgSleepDrift2[o] = d2
    }
}

/** После сопротивления: дрожь возвращается в скорость. */
internal fun BodyState.restoreTickJitter() {
    val s = sleep
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        vx[i] += tickJitterX[i]; vy[i] += tickJitterY[i]
    }
}
