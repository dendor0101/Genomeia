package io.github.some_example_name.physics

/*
 * ХВАТКА — ТРЕНИЕ О ДНО (клиент-игра: присоска-чашка, SuctionCup, импульс 0..1 — PhysicsWorld.setGrip).
 *
 * Сопротивление среды гасит скорость раз в тик и только скорость: связи и касания внутри тика
 * двигают частицу позиционными поправками, и сильное трение средой не получить — чашку тянуло её
 * же тело. Хватка g гасит долю g СМЕЩЕНИЯ частицы за каждый подшаг: после восстановления
 * скоростей скорость умножается на 1 − g, и позиция ставится по ней от начала подшага. При g = 1
 * частица за подшаг не сдвигается вовсе — сдвинуть её нельзя ничем; при 0.95 от любого толчка
 * остаётся 5% за подшаг, то есть сдвигается она очень тяжело; трение растёт с g линейно.
 *
 * Стадия стоит сразу после снимка скоростей для учёта импульса (velocityPassBefore): учёт за тик
 * (TickMomentum) видит её внешней силой, как пол, и импульс, отданный дну, организму не
 * возвращает. Список частиц с хваткой пересобирается проходом по миру только после смены
 * хватки или перестройки ([gripsDirty]); без хватки стадия — одна проверка.
 */

/** Подшаг [h]: частицы с хваткой теряют её долю смещения за подшаг. */
internal fun BodyState.applyGrip(h: Double) {
    if (gripsDirty) rebuildGripList()
    if (gripN == 0) return
    for (k in 0 until gripN) {
        val i = gripIds[k]
        if (invMass[i] == 0.0) continue
        val keep = 1.0 - cellGrip[i]
        val nvx = vx[i] * keep; val nvy = vy[i] * keep
        vx[i] = nvx; vy[i] = nvy
        px[i] = prevX[i] + nvx * h; py[i] = prevY[i] + nvy * h
    }
}

private fun BodyState.rebuildGripList() {
    var c = 0
    for (i in 0 until n) if (cellGrip[i] > 0f) c++
    if (gripIds.size < c) gripIds = IntArray(c)
    var k = 0
    for (i in 0 until n) if (cellGrip[i] > 0f) gripIds[k++] = i
    gripN = k
    gripsDirty = false
}
