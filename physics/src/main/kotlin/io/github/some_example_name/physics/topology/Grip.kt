package io.github.some_example_name.physics

/*
 * ОПОРА О ДНО: ХВАТКА И ПРУЖИНА К СВОЕМУ МЕСТУ (PhysicsWorld.setGrip, setHome).
 *
 * ХВАТКА g — трение о дно (клиент-игра: присоска-чашка, SuctionCup, импульс 0..1; террейн).
 * Сопротивление среды гасит скорость раз в тик и только скорость: связи и касания внутри тика
 * двигают частицу позиционными поправками, и сильное трение средой не получить — чашку тянуло
 * её же тело. Хватка гасит долю g СМЕЩЕНИЯ частицы за каждый подшаг: скорость умножается на
 * 1 − g, и позиция ставится по ней от начала подшага. При g = 1 частица за подшаг не сдвигается
 * вовсе — сдвинуть её нельзя ничем; при 0.95 от любого толчка остаётся 5% за подшаг.
 *
 * ПРУЖИНА К МЕСТУ (террейн: точка на карте) — мягкое ограничение XPBD частицы к неподвижной
 * точке с податливостью cellHomeCompliance: мягкий террейн проминается и возвращается в форму,
 * а не уплывает. Сначала пружина (она меняет и позицию, и скорость), затем хватка — гасит и её
 * отскок.
 *
 * Стадия стоит сразу после снимка скоростей для учёта импульса (velocityPassBefore): учёт за тик
 * (TickMomentum) видит обе силы внешними, как пол, и импульс, отданный дну, организму не
 * возвращает. Обход — по диапазонам бодрствующих частиц (BodySleep.spanStart): спящий террейн
 * (десятки тысяч частиц) стадию не стоит; замер в игре 04.11 — обход всех частиц с опорой давал
 * 8.3 мс тика на 73 тысячах спящих частиц террейна в одном потоке. Без опоры в мире ([gripN] = 0,
 * пересчёт после смены — [gripsDirty]) стадия — одна проверка.
 */

/** Подшаг [h]: пружины к месту и хватка у бодрствующих частиц. */
internal fun BodyState.applyGrip(h: Double) {
    if (gripsDirty) countGrips()
    if (gripN == 0) return
    prepareAwakeSpans()
    val s = sleep
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        if (cellGrip[i] > 0f || cellHomeCompliance[i] > 0.0) groundOne(i, h)
    }
}

private fun BodyState.groundOne(i: Int, h: Double) {
    val w = invMass[i]
    if (w == 0.0) return
    var nvx = vx[i]; var nvy = vy[i]
    val hc = cellHomeCompliance[i]
    if (hc > 0.0) {
        // Ограничение к точке: поправка доли w / (w + alpha/h²) смещения от места.
        val f = w / (w + hc / (h * h))
        val dx = (cellHomeX[i] - px[i]) * f; val dy = (cellHomeY[i] - py[i]) * f
        nvx += dx / h; nvy += dy / h
    }
    val keep = 1.0 - cellGrip[i]
    nvx *= keep; nvy *= keep
    vx[i] = nvx; vy[i] = nvy
    px[i] = prevX[i] + nvx * h; py[i] = prevY[i] + nvy * h
}

/** Сколько в мире частиц с опорой — пересчёт после смены или перестройки (gripsDirty). */
private fun BodyState.countGrips() {
    var c = 0
    for (i in 0 until n) if (cellGrip[i] > 0f || cellHomeCompliance[i] > 0.0) c++
    gripN = c
    gripsDirty = false
}
