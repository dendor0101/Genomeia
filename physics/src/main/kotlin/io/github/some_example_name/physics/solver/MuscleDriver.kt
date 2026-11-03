package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

fun BodyState.muscleOfPair(i: Int, j: Int): Int {
    val a = muscleOf[i]; val b = muscleOf[j]
    if (a == b) return a
    if (muscleTouch == 0) return -1
    // ШОВ МЕЖДУ СОСЕДНИМИ МЫШЦАМИ ТОЖЕ СОКРАЩАЕТСЯ. Пока между кластерами лежала
    // обычная ткань, шва не было; после роста они сходятся вплотную, и связь на стыке
    // осталась бы единственной, кто держит длину, пока обе стороны от неё уходят. На
    // body-export таких связей 92, и все они рвались растяжением на нулевом же тике.
    if (a >= 0 && b >= 0) return if (a < b) a else b
    if (muscleTouch != 2) return -1
    return if (a < 0) b else a
}

fun BodyState.muscleOfTri(i0: Int, i1: Int, i2: Int): Int {
    // ПЛОЩАДЬ У КОСТИ НЕ СОКРАЩАЕТСЯ. Кость жёсткая, её сторона треугольника длину не
    // меняет, а площадь просят уменьшить в s^2 раз — выполнить это можно только
    // уронив вершину на кость. Связи при этом тянут её же на s, и стадии дерутся:
    // на body-export это рвало 52 связи на нулевом тике, худшая связь сжималась до
    // 0.111 длины покоя. Тянет кость СВЯЗЬ, ей для этого площадь не нужна.
    if (boneOf[i0] >= 0 || boneOf[i1] >= 0 || boneOf[i2] >= 0) return -1
    val a = muscleOf[i0]; val b = muscleOf[i1]; val c = muscleOf[i2]
    if (a >= 0 && a == b && a == c) return a
    if (muscleTouch == 0) return -1
    // Все три вершины мышечные, но кластеры разные — см. шов у muscleOfPair.
    if (muscleTouch != 2 && (a < 0 || b < 0 || c < 0)) return -1
    var best = -1
    var votes = 0
    if (a >= 0) { val v = 1 + (if (b == a) 1 else 0) + (if (c == a) 1 else 0)
        if (v > votes || (v == votes && a < best)) { best = a; votes = v } }
    if (b >= 0) { val v = 1 + (if (a == b) 1 else 0) + (if (c == b) 1 else 0)
        if (v > votes || (v == votes && b < best)) { best = b; votes = v } }
    if (c >= 0) { val v = 1 + (if (a == c) 1 else 0) + (if (b == c) 1 else 0)
        if (v > votes || (v == votes && c < best)) { best = c; votes = v } }
    return best
}

/**
 * КОЛЬЦО ВОКРУГ МЫШЦЫ УХОДИТ В МЫШЦУ. Режим 1, см. MUSCLE_TOUCH.
 *
 * Треугольник с хотя бы одной мышечной вершиной отдаёт кластеру все три свои клетки.
 * Спорную клетку — ту, что граничит сразу с двумя мышцами, — забирает кластер с
 * меньшим номером: правило должно быть одинаковым при любом порядке треугольников.
 * Смотрим на ИСХОДНЫЙ список клеток, а не на растущий, иначе мышца расползлась бы по
 * телу на столько колец, сколько треугольников попадётся по дороге.
 */
/** [fromTri] — первый треугольник нового тела, когда мир дописывает тело (appendBody). */
fun BodyState.growMuscles(fromTri: Int = 0) {
    if (muscleTouch != 1) return
    val core = muscleOf
    val grown = core.copyOf()
    for (t in fromTri until body.triCount) {
        val i0 = body.triA[t]; val i1 = body.triB[t]; val i2 = body.triC[t]
        val a = core[i0]; val b = core[i1]; val c = core[i2]
        var m = -1
        if (a >= 0) m = a
        if (b >= 0 && (m < 0 || b < m)) m = b
        if (c >= 0 && (m < 0 || c < m)) m = c
        if (m < 0) continue
        if (a < 0 && (grown[i0] < 0 || m < grown[i0])) grown[i0] = m
        if (b < 0 && (grown[i1] < 0 || m < grown[i1])) grown[i1] = m
        if (c < 0 && (grown[i2] < 0 || m < grown[i2])) grown[i2] = m
    }
    muscleOf = grown
}

fun BodyState.muscleScale(m: Int) = if (m < 0) 1.0 else muscleScaleOf[m]

/** Сильнейшая мышца из двух: множитель дальше от 1, при равном удалении — меньший. */
internal fun strongerScale(a: Float, b: Float): Float {
    val da = kotlin.math.abs(a - 1f); val db = kotlin.math.abs(b - 1f)
    return if (da > db || (da == db && a <= b)) a else b
}

/** Слабейшая мышца из двух: множитель ближе к 1, при равном удалении — больший. */
internal fun weakerScale(a: Float, b: Float): Float = if (strongerScale(a, b) == a && a != b) b else a

private fun BodyState.linkCellScale(c: Int): Float = weakerScale(cellScaleEff[conA[c]], cellScaleEff[conB[c]])

private fun BodyState.triangleCellScale(t: Int): Float {
    val i0 = triA[t]; val i1 = triB[t]; val i2 = triC[t]
    if (boneOf[i0] >= 0 || boneOf[i1] >= 0 || boneOf[i2] >= 0) return 1f
    val e = cellScaleEff
    return weakerScale(weakerScale(e[i0], e[i1]), e[i2])
}

/** Мягкость связи — среднее концов (BodyState.cellSoftness): половинки пружины подряд. */
private fun BodyState.linkSoftness(c: Int): Float = (cellSoftness[conA[c]] + cellSoftness[conB[c]]) * 0.5f

/** Мягкость треугольника — среднее трёх вершин. */
private fun BodyState.triangleSoftness(t: Int): Float =
    (cellSoftness[triA[t]] + cellSoftness[triB[t]] + cellSoftness[triC[t]]) * (1f / 3f)

/** Действующий множитель клетки [v]: сильнейший из своего и соседей по живому треугольнику. */
private fun BodyState.effectiveScale(v: Int): Float {
    val b = baked
    var best = cellScale[v]
    for (q in b.vertStart[v] until b.vertStart[v + 1]) {
        val e = b.vertEdge[q]
        if (b.edgeDead[e] || b.triUse[e] == 0) continue
        best = strongerScale(best, cellScale[b.vertOther[q]])
    }
    return best
}

/**
 * Множители всех клеток, связей и треугольников (см. BodyState.cellScale) — после перестройки
 * структуры (дописывание, уплотнение, снимок, сброс). Тик за тиком их ведут advanceCellScales
 * и refreshCellScaleRing по месту.
 */
internal fun BodyState.recomputeCellScales() {
    if (cellScaleEff.size != n) cellScaleEff = FloatArray(n)
    for (v in 0 until n) cellScaleEff[v] = effectiveScale(v)
    for (c in 0 until conCount) conCellScale[c] = linkCellScale(c)
    for (t in 0 until triCount) triCellScale[t] = triangleCellScale(t)
    if (softnessOn) {
        for (c in 0 until conCount) conSoftness[c] = linkSoftness(c)
        for (t in 0 until triCount) triSoftness[t] = triangleSoftness(t)
    }
    cellScalesDirty = false
}

/**
 * Множитель клетки [v] или её кольцо изменились (шаг мышцы, рождение, гибель ребра): заново
 * действующие множители её самой и соседей по треугольнику, у изменившихся — связи и
 * треугольники. Порядок клеток на результат не влияет: значения выбираются, а не считаются.
 */
internal fun BodyState.refreshCellScaleRing(v: Int) {
    // Мягкость ведётся тем же кольцом (см. BodyState.cellSoftness).
    if (!cellScalesOn && !softnessOn) return
    if (cellScaleEff.size != n || constraintBlocks == null) { cellScalesDirty = true; return }
    val b = baked
    refreshEffective(v, always = true)
    for (q in b.vertStart[v] until b.vertStart[v + 1]) refreshEffective(b.vertOther[q], always = false)
}

private fun BodyState.refreshEffective(u: Int, always: Boolean) {
    val e = effectiveScale(u)
    if (e == cellScaleEff[u] && !always) return
    cellScaleEff[u] = e
    refreshCellScaleAround(u)
}

/**
 * Множители клеток — шаг к цели с темпом мышцы стенда (как активация в updateMuscles):
 * удаление от 1 — muscleRateContract, возвращение к 1 — muscleRateRelax. Проход по клеткам
 * только пока хоть одна в пути (cellScalesMoving); у изменившейся клетки связи и
 * треугольники пересчитываются по кольцу (refreshCellScaleRing), без прохода по миру.
 * Значение связи — один из множителей клеток, без арифметики: порядок клеток в проходе на
 * результат не влияет.
 */
internal fun BodyState.advanceCellScales(dt: Double) {
    val s = cellScale; val target = cellScaleTarget
    val byEdges = constraintBlocks != null && !cellScalesDirty && cellScaleEff.size == n
    if (byEdges) beginCellScaleLerp()
    var moving = false
    for (v in 0 until n) {
        val t = target[v]; val cur = s[v]
        if (cur == t) continue
        val away = kotlin.math.abs(t - 1f) > kotlin.math.abs(cur - 1f)
        val k = minOf(1.0, (if (away) muscleRateContract else muscleRateRelax) * dt)
        var next = (cur + (t - cur) * k).toFloat()
        if (kotlin.math.abs(t - next) < CELL_SCALE_SNAP) next = t else moving = true
        s[v] = next
        sleep.requestWake(v)
        if (byEdges) refreshCellScaleRing(v)
    }
    cellScalesMoving = moving
    if (byEdges) { endCellScaleLerp(); return }
    // Полный пересчёт (после перестройки структуры, первый шаг тела) — тоже плавно: иначе в этот
    // тик мышца прыгала бы, и мир, прошедший сюда, расходился бы с прошедшим по кольцам. Путь
    // редкий, поэтому захват всех связей и треугольников здесь допустим.
    val lerp = captureAllForLerp()
    recomputeCellScales()
    if (lerp) endCellScaleLerp()
}

/** Захватить «было» у всех связей и треугольников; false — нечем вести (структура не собрана). */
private fun BodyState.captureAllForLerp(): Boolean {
    val blocks = constraintBlocks ?: return false
    if (conCellScale.size < conCount || triCellScale.size < triCount) return false
    beginCellScaleLerp()
    for (e in 0 until baked.edgeCount) if (!baked.edgeDead[e]) captureEdgeLerp(e)
    val slot = blocks.triangleSlot
    for (id in 0 until minOf(body.triCount, slot.size)) {
        val w = slot[id]
        if (w in 0 until triCount) captureTriLerp(id, w)
    }
    return true
}

/** Множители связей и треугольников у клетки [v] — по её рёбрам. */
private fun BodyState.refreshCellScaleAround(v: Int) {
    val b = baked
    val slot = constraintBlocks!!.triangleSlot
    for (q in b.vertStart[v] until b.vertStart[v + 1]) {
        val e = b.vertEdge[q]
        if (lerpCapturing) captureEdgeLerp(e)
        var c = conOfEdge[e]
        while (c >= 0) {
            conCellScale[c] = linkCellScale(c)
            if (softnessOn) conSoftness[c] = linkSoftness(c)
            c = conNextForEdge[c]
        }
        for (r in b.edgeTriStart[e] until b.edgeTriStart[e + 1]) {
            val id = b.edgeTris[r]
            val w = slot[id]
            if (w in 0 until triCount) {
                if (lerpCapturing) captureTriLerp(id, w)
                triCellScale[w] = triangleCellScale(w)
                if (softnessOn) triSoftness[w] = triangleSoftness(w)
            }
        }
    }
}

// --- плавность мышцы клетки по подшагам ------------------------------------------------
//
// СКАЧОК ДЛИНЫ ПОКОЯ РАЗ В ТИК РВАЛ ТКАНЬ (27.10, standParityCheck A-11). Кластер стенда ведёт
// длину покоя через подшаги (updateMuscleScale(f)), а мышца клетки меняла её в начале тика целиком:
// при темпе 12/с это −20% в первом же подшаге — решатель выполнял скачок за пару подшагов, ткань
// дёргало, смятия и гибели от глубокого касания (гребок 240 тиков: 15 разрывов / 5 гибелей у стенда,
// 21/6 у игры; шаг на каждом подшаге — 0/0). Теперь шаг мышцы, как прежде, раз в тик (кольца
// пересчитываются раз), а задетые связи и треугольники идут от «было» к «станет» линейно по
// подшагам. Цена — проход по задетым (≈12 на движущуюся мышечную клетку) в каждом подшаге; в ядрах
// решателя лишних чтений нет.

private fun BodyState.beginCellScaleLerp() {
    lerpCapturing = true
    lerpEdgeN = 0; lerpTriN = 0
    lerpStampNow++
    if (lerpEdgeStamp.size < baked.edgeCount) lerpEdgeStamp = IntArray(baked.edgeCount)
    if (lerpTriStamp.size < body.triCount) lerpTriStamp = IntArray(body.triCount)
}

private fun BodyState.captureEdgeLerp(e: Int) {
    if (lerpEdgeStamp[e] == lerpStampNow) return
    val c = conOfEdge[e]
    if (c < 0) return
    lerpEdgeStamp[e] = lerpStampNow
    if (lerpEdgeN == lerpEdgeIds.size) {
        lerpEdgeIds = lerpEdgeIds.copyOf(lerpEdgeN * 2)
        lerpEdgeFrom = lerpEdgeFrom.copyOf(lerpEdgeN * 2); lerpEdgeTo = lerpEdgeTo.copyOf(lerpEdgeN * 2)
    }
    lerpEdgeIds[lerpEdgeN] = e; lerpEdgeFrom[lerpEdgeN] = conCellScale[c]; lerpEdgeN++
}

private fun BodyState.captureTriLerp(id: Int, w: Int) {
    if (lerpTriStamp[id] == lerpStampNow) return
    lerpTriStamp[id] = lerpStampNow
    if (lerpTriN == lerpTriIds.size) {
        lerpTriIds = lerpTriIds.copyOf(lerpTriN * 2)
        lerpTriFrom = lerpTriFrom.copyOf(lerpTriN * 2); lerpTriTo = lerpTriTo.copyOf(lerpTriN * 2)
    }
    lerpTriIds[lerpTriN] = id; lerpTriFrom[lerpTriN] = triCellScale[w]; lerpTriN++
}

/** Конец сбора: «станет» — нынешнее значение, а действующее возвращается к «было». */
private fun BodyState.endCellScaleLerp() {
    lerpCapturing = false
    for (k in 0 until lerpEdgeN) {
        val e = lerpEdgeIds[k]
        lerpEdgeTo[k] = conCellScale[conOfEdge[e]]
        setEdgeCellScale(e, lerpEdgeFrom[k])
    }
    val slot = constraintBlocks!!.triangleSlot
    for (k in 0 until lerpTriN) {
        val w = slot[lerpTriIds[k]]
        lerpTriTo[k] = triCellScale[w]
        triCellScale[w] = lerpTriFrom[k]
    }
}

private fun BodyState.setEdgeCellScale(e: Int, value: Float) {
    var c = conOfEdge[e]
    while (c >= 0) { conCellScale[c] = value; c = conNextForEdge[c] }
}

/**
 * Подшаг [step] из [count]: задетые шагом мышцы связи и треугольники — на долю (step+1)/count
 * пути от «было» к «станет»; на последнем — ровно «станет», и списки пустеют. Рёбра и постоянные
 * номера треугольников переживают перепаковку рабочих списков посреди тика; мёртвое пропускается.
 */
internal fun BodyState.applyCellScaleLerp(step: Int, count: Int) {
    if (lerpEdgeN == 0 && lerpTriN == 0) return
    val last = step == count - 1
    val f = (step + 1).toFloat() / count
    for (k in 0 until lerpEdgeN) {
        val from = lerpEdgeFrom[k]; val to = lerpEdgeTo[k]
        setEdgeCellScale(lerpEdgeIds[k], if (last) to else from + (to - from) * f)
    }
    val slot = constraintBlocks?.triangleSlot
    if (slot != null) for (k in 0 until lerpTriN) {
        val w = slot[lerpTriIds[k]]
        if (w !in 0 until triCount) continue
        val from = lerpTriFrom[k]; val to = lerpTriTo[k]
        triCellScale[w] = if (last) to else from + (to - from) * f
    }
    if (last) { lerpEdgeN = 0; lerpTriN = 0 }
}

/** Ближе этого к цели множитель клетки встаёт точно на неё: путь кончается за конечное число тиков. */
private const val CELL_SCALE_SNAP = 1e-4f

fun BodyState.updateMuscleScale(f: Double) {
    if (muscleScaleOf.size != muscleActivation.size) muscleScaleOf = DoubleArray(muscleActivation.size)
    if (muscleActivationPrev.size != muscleActivation.size) muscleActivationPrev = DoubleArray(muscleActivation.size)
    for (m in muscleActivation.indices) {
        val a = muscleActivationPrev[m] + (muscleActivation[m] - muscleActivationPrev[m]) * f
        muscleScaleOf[m] = 1.0 - a * (1.0 - muscleContraction)
    }
}

fun BodyState.updateMuscles(dt: Double) {
    if (muscleActivationPrev.size != muscleActivation.size) muscleActivationPrev = DoubleArray(muscleActivation.size)
    System.arraycopy(muscleActivation, 0, muscleActivationPrev, 0, muscleActivation.size)
    for (m in muscleActivation.indices) {
        val target = muscleTarget[m]
        // Сокращение и распрямление идут с РАЗНОЙ скоростью — см. константы.
        val rate = if (target > muscleActivation[m]) muscleRateContract else muscleRateRelax
        var k = rate * dt
        if (k > 1.0) k = 1.0
        muscleActivation[m] += (target - muscleActivation[m]) * k
    }
}
