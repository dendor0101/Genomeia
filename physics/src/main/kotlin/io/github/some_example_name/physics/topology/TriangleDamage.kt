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
    // Пока никто не спит — прежний сплошной проход в порядке треугольников. Со спящими —
    // только блоки с бодрствующими частицами и шов: у спящего площади заморожены, а
    // засыпает тело без вывернутых треугольников (10 тиков инверсии рвут раньше, чем
    // 60 тиков покоя усыпят). Порядок гибели треугольников тогда другой, но он
    // детерминирован. См. BodySleep.
    val layout = constraintBlocks
    val w = activeWorkers
    if (w != null && w.count > 1 && layout != null) { parallelPhysics(w).scanInversions(layout); return }
    if (sleep.asleepCount == 0 || layout == null) {
        checkInversionsRange(0, triCount)
        return
    }
    val areas = layout.areas
    for (block in 0 until layout.buckets) {
        if (!layout.active(block)) continue
        checkInversionsOf(areas.items, areas.start[block], areas.start[block] + areas.count[block])
    }
}

/**
 * Счётчик инверсии треугольника [t]: true — пора убить (делает один поток, killTriangleNow). Единственная запись
 * этой арифметики для одного потока и воркеров; `inline` с массивами в параметрах — циклы держат их в локальных
 * переменных (см. lengthsBeforeLoop).
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun inversionExpiredAt(t: Int, ages: ByteArray, triAlive: BooleanArray, triId: IntArray,
                                       triA: IntArray, triB: IntArray, triC: IntArray, px: FloatArray, py: FloatArray): Boolean {
    val id = triId[t]
    if (!triAlive[id]) return false
    val a = triA[t]; val b = triB[t]; val c = triC[t]
    val area2 = (px[b] - px[a]) * (py[c] - py[a]) -
        (py[b] - py[a]) * (px[c] - px[a])
    if (area2 < 0.0f) {
        val age = ages[id].toInt() + 1
        if (age > INVERTED_TRIANGLE_HOLD_TICKS) return true
        ages[id] = age.toByte()
    } else {
        ages[id] = 0
    }
    return false
}

/** Треугольники [t0, t1) по номерам: без сна. Гибель — сразу, как раньше: массивы от неё не пересоздаются. */
private fun BodyState.checkInversionsRange(t0: Int, t1: Int) {
    val ages = baked.invertedTicks; val alive = baked.triAlive; val triId = triId
    val triA = triA; val triB = triB; val triC = triC; val px = px; val py = py
    lengthsBeforeLoop(ages.size or alive.size or triId.size or triA.size or triB.size or triC.size or px.size or py.size)
    for (t in t0 until t1) if (inversionExpiredAt(t, ages, alive, triId, triA, triB, triC, px, py)) killTriangleNow(triId[t])
}

/** Треугольники списка [items] на местах [from, to); отрицательный номер — пустое место списка. */
private fun BodyState.checkInversionsOf(items: IntArray, from: Int, to: Int) {
    val ages = baked.invertedTicks; val alive = baked.triAlive; val triId = triId
    val triA = triA; val triB = triB; val triC = triC; val px = px; val py = py
    lengthsBeforeLoop(ages.size or alive.size or triId.size or triA.size or triB.size or triC.size or px.size or py.size or
        items.size)
    for (slot in from until to) {
        val t = items[slot]
        if (t >= 0 && inversionExpiredAt(t, ages, alive, triId, triA, triB, triC, px, py)) killTriangleNow(triId[t])
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
    // Нерождённый треугольник остаётся в списке: клетка родится, и он оживёт (рост тела).
    val unborn = baked.triUnborn
    fun keep(id: Int) = alive[id] || (id < unborn.size && unborn[id])
    var m = 0
    while (m < triCount && keep(triId[m])) m++
    var k = m
    while (k < triCount) {
        if (keep(triId[k])) {
            constraintBlocks?.moveTriangle(k, m, triId[k])
            triA[m] = triA[k]; triB[m] = triB[k]; triC[m] = triC[k]
            if (triHold.size > k) triHold[m] = triHold[k]
            triRestArea2[m] = triRestArea2[k]; triMuscle[m] = triMuscle[k]; triId[m] = triId[k]
            triCellScale[m] = triCellScale[k]; triSoftness[m] = triSoftness[k]
            m++
        }
        k++
    }
    triCount = m
    constraintBlocks?.areas?.packDirty()
    triInverted.fill(false)
}
