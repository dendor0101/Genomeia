package io.github.some_example_name.physics

/**
 * Один обход в конце тика, после всех поправок позиций. Краткая складка может
 * распрямиться сама; удаляем площадь только на 11-м подряд вывернутом конце тика.
 * Это согласованное правило разрушения, а не исправление позиции или импульса.
 *
 * История — 1 байт/треугольник, ~200 КБ на сетку 100k частиц: больше L1, меньше
 * обычного L2 ПК. Идёт потоком по постоянным triId с пропусками после разрушения.
 * Вершины читаются через уже существующий порядок треугольников; новых объектов
 * и буферов на тик нет. Обход не зависит от 144 проходов предела длины за тик.
 */
internal fun BodyState.removePersistentInversions() {
    val ages = baked.invertedTicks
    if (!tearingOn) {
        // X выключает все разрывы. После включения нужна новая непрерывная серия.
        ages.fill(0)
        return
    }
    val alive = baked.triAlive
    for (t in 0 until triCount) {
        val id = triId[t]
        if (!alive[id]) continue
        val a = triA[t]; val b = triB[t]; val c = triC[t]
        val area2 = (px[b] - px[a]) * (py[c] - py[a]) -
            (py[b] - py[a]) * (px[c] - px[a])
        if (area2 < 0.0) {
            val age = ages[id].toInt() + 1
            if (age > INVERTED_TRIANGLE_HOLD_TICKS) killTriangleNow(id)
            else ages[id] = age.toByte()
        } else {
            ages[id] = 0
        }
    }
}

/** Номер из исходной топологии. Контур меняется по трём рёбрам, без разрыва связей. */
internal fun BodyState.killTriangleNow(id: Int) {
    if (!baked.killTriangle(id)) return
    constraintBlocks?.removeTriangle(id)
    for (k in 0..2) {
        val e = baked.triEdge[3 * id + k]
        if (e >= 0) updateBoundaryLinkLimit(e)
    }
    tearsPending = true
}

/** Упаковка один раз за тик, в прежнем порядке; счётчики инверсии имеют постоянные ID. */
internal fun BodyState.compactTriangles() {
    val alive = baked.triAlive
    var m = 0
    while (m < triCount && alive[triId[m]]) m++
    var k = m
    while (k < triCount) {
        if (alive[triId[k]]) {
            constraintBlocks?.moveTriangle(k, m, triId[k])
            triA[m] = triA[k]; triB[m] = triB[k]; triC[m] = triC[k]
            if (triHold.size > k) triHold[m] = triHold[k]
            triRestArea2[m] = triRestArea2[k]; triMuscle[m] = triMuscle[k]; triId[m] = triId[k]
            m++
        }
        k++
    }
    triCount = m
    constraintBlocks?.areas?.packDirty()
    triInverted.fill(false)
}
