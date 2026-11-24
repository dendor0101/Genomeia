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

fun BodyState.muscleScale(m: Int) = if (m < 0) 1.0f else muscleScaleOf[m]

/** Сильнейшая мышца из двух: множитель дальше от 1, при равном удалении — меньший. */
internal fun strongerScale(a: Float, b: Float): Float {
    val da = kotlin.math.abs(a - 1f); val db = kotlin.math.abs(b - 1f)
    return if (da > db || (da == db && a <= b)) a else b
}

/** Слабейшая мышца из двух: множитель ближе к 1, при равном удалении — больший. */
internal fun weakerScale(a: Float, b: Float): Float = if (strongerScale(a, b) == a && a != b) b else a

/** Волокно множителю связи не мешает: и сжатие, и мягкость расслабления по волокну ведут ядра (MuscleFiber.kt, conFiberC2). */
private fun BodyState.linkCellScale(c: Int): Float = weakerScale(cellScaleEff[conA[c]], cellScaleEff[conB[c]])

private fun BodyState.triangleCellScale(t: Int): Float {
    val i0 = triA[t]; val i1 = triB[t]; val i2 = triC[t]
    if (boneOf[i0] >= 0 || boneOf[i1] >= 0 || boneOf[i2] >= 0) return 1f
    val e = cellScaleEff
    // Волокно треугольнику множитель не меняет: сжатие по волокну ведут ядра (triFiberById), а расслабление площади —
    // целиком: растянуть треугольник может только связь вдоль волокна, поперечные мягче не становятся (MuscleFiber.kt).
    return weakerScale(weakerScale(e[i0], e[i1]), e[i2])
}

/** Мягкость связи — среднее концов (BodyState.cellSoftness): половинки пружины подряд. */
private fun BodyState.linkSoftness(c: Int): Float = (cellSoftness[conA[c]] + cellSoftness[conB[c]]) * 0.5f

/** Мягкость треугольника — среднее трёх вершин. */
private fun BodyState.triangleSoftness(t: Int): Float =
    (cellSoftness[triA[t]] + cellSoftness[triB[t]] + cellSoftness[triC[t]]) * (1f / 3f)

/**
 * Чей множитель действует на клетку [v] (cellScaleEff = cellScale[источника]): сильнейший из
 * своего и соседей по живому треугольнику. При равных значениях остаётся прежний — сама клетка
 * или первый по кольцу; кольцо постоянно, поэтому от порядка обхода клеток выбор не зависит.
 * Волокно источника — волокно ткани вокруг (MuscleFiber.kt).
 */
private fun BodyState.effectiveSource(v: Int): Int {
    val b = baked
    var src = v
    var best = cellScale[v]
    for (q in b.vertStart[v] until b.vertStart[v + 1]) {
        val e = b.vertEdge[q]
        if (b.edgeDead[e] || b.triUse[e] == 0) continue
        val o = b.vertOther[q]
        val s = cellScale[o]
        if (strongerScale(best, s) != best) { best = s; src = o }
    }
    return src
}

/**
 * Множители всех клеток, связей и треугольников (см. BodyState.cellScale) — после перестройки
 * структуры (дописывание, уплотнение, снимок, сброс). Тик за тиком их ведут advanceCellScales
 * и refreshCellScaleRing по месту.
 */
internal fun BodyState.recomputeCellScales() {
    if (cellScaleEff.size != n) cellScaleEff = FloatArray(n)
    if (cellScaleSrc.size != n) cellScaleSrc = IntArray(n)
    for (v in 0 until n) {
        val src = effectiveSource(v)
        cellScaleSrc[v] = src
        cellScaleEff[v] = cellScale[src]
    }
    // Волокно (MuscleFiber.kt) — вместе с множителями связей и треугольников.
    for (c in 0 until conCount) { conCellScale[c] = linkCellScale(c); refreshLinkFiber(c) }
    for (t in 0 until triCount) { triCellScale[t] = triangleCellScale(t); refreshTriangleFiber(t) }
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
    if (cellScaleEff.size != n || cellScaleSrc.size != n || constraintBlocks == null) { cellScalesDirty = true; return }
    val b = baked
    refreshEffective(v, always = true)
    for (q in b.vertStart[v] until b.vertStart[v + 1]) refreshEffective(b.vertOther[q], always = false)
}

private fun BodyState.refreshEffective(u: Int, always: Boolean) {
    val src = effectiveSource(u)
    val e = cellScale[src]
    // Источник важен только с волокнами: множитель тот же, а волокно — другой мышцы (MuscleFiber.kt).
    if (e == cellScaleEff[u] && (!fibersOn || src == cellScaleSrc[u]) && !always) return
    cellScaleEff[u] = e
    cellScaleSrc[u] = src
    refreshCellScaleAround(u)
}

/*
 * ШАГ МЫШЦ: КОЛЬЦА ВСЕХ СДВИНУВШИХСЯ КЛЕТОК — В ДВА ПРОХОДА, КАЖДОЕ МЕСТО ОДИН РАЗ (24.11, reference.md, находка 40).
 *
 * Прежде шаг мышц звал refreshCellScaleRing на каждую сдвинувшуюся клетку. У мышцы сдвигаются все клетки разом, и
 * одно и то же считалось по многу раз: источник множителя клетки — из её кольца и из кольца каждого соседа (7 раз),
 * связь — с обоих концов и ещё раз при повторе (до 4 раз), треугольник — с трёх вершин по двум рёбрам каждой (до 12).
 * На настоящих геномах игроков (половина клеток — мышцы, все в движении) это было 21–25% тика на S24 Ultra и
 * 31–43% на A21s при 16 подшагах, хотя работа эта — раз в тик, а не раз в подшаг.
 *
 * Теперь два прохода по всему шагу. Первый — действующие множители: сдвинувшимся клеткам всегда, их соседям — если
 * множитель (с волокнами — и источник) сменился; каждая клетка считается один раз (метка lerpCellStamp; сдвинувшимся
 * её ставит сам шаг множителей, поэтому как соседей их никто не трогает). Второй — связи и треугольники вокруг
 * изменившихся клеток, каждое ребро и каждый треугольник один раз (метки плавности они же). Второй проход идёт
 * только после ВСЕГО первого: значение связи — выбор из действующих множителей её концов, и считать его можно, когда
 * оба уже окончательные. Прежний путь считал сразу и пересчитывал, когда доходил до второго конца.
 *
 * Биты те же: действующий множитель клетки — функция множителей её кольца (они к этому времени окончательные), значение
 * связи и треугольника — функция действующих множителей; захват «было» — до первой записи в шаге, как и раньше.
 * Меняется только порядок записей в списках плавности, а записи друг от друга не зависят (каждая про своё ребро
 * или треугольник). Сверено суммой мира на настоящих геномах и отпечатками каталога стендов.
 */

/** Первый проход: действующие множители сдвинувшихся клеток [from]..[to] и их соседей; изменившиеся — в [changed]. */
private inline fun BodyState.refreshEffectiveOf(moved: IntArray, from: Int, to: Int, changed: (Int) -> Unit) {
    val b = baked
    val stamp = lerpCellStamp; val now = lerpStampNow
    val eff = cellScaleEff; val srcOf = cellScaleSrc; val scale = cellScale
    val fibers = fibersOn
    // Сдвинувшиеся — всегда, как refreshEffective(always = true): связи вокруг пересчитываются, даже если действующий
    // множитель остался прежним.
    for (q in from until to) {
        val v = moved[q]
        val src = effectiveSource(v)
        eff[v] = scale[src]; srcOf[v] = src
        changed(v)
    }
    for (q in from until to) {
        val v = moved[q]
        for (r in b.vertStart[v] until b.vertStart[v + 1]) {
            val u = b.vertOther[r]
            if (stamp[u] == now) continue
            stamp[u] = now
            val src = effectiveSource(u)
            val e = scale[src]
            // Источник важен только с волокнами: множитель тот же, а волокно — другой мышцы (MuscleFiber.kt).
            if (e == eff[u] && (!fibers || src == srcOf[u])) continue
            eff[u] = e; srcOf[u] = src
            changed(u)
        }
    }
}

/** Первый проход одного куска воркера (ParallelMuscles): изменившиеся клетки — в его буфер. */
internal fun BodyState.refreshEffectiveChunk(moved: IntArray, from: Int, to: Int, out: CellScaleLerpBuffer) {
    out.changedN = 0
    refreshEffectiveOf(moved, from, to) { u -> out.addChanged(u) }
}

/**
 * Второй проход: связи и треугольники вокруг изменившихся клеток — множитель, волокно, мягкость и захват «было»
 * для плавности ([out] — буфер куска воркера, null — общие списки). Ребро и треугольник — по одному разу на шаг.
 */
internal fun BodyState.refreshAroundChanged(changed: IntArray, count: Int, out: CellScaleLerpBuffer?) {
    val b = baked
    val slot = constraintBlocks!!.triangleSlot
    val now = lerpStampNow
    val edgeStamp = lerpEdgeStamp; val triStamp = lerpTriStamp
    val vertStart = b.vertStart; val vertEdge = b.vertEdge
    val edgeTriStart = b.edgeTriStart; val edgeTris = b.edgeTris
    val soft = softnessOn
    for (k in 0 until count) {
        val v = changed[k]
        for (q in vertStart[v] until vertStart[v + 1]) {
            val e = vertEdge[q]
            if (edgeStamp[e] == now) continue // ребро уже пересчитано с другого конца
            edgeStamp[e] = now
            var c = conOfEdge[e]
            if (c >= 0) {
                noteEdgeLerp(e, conCellScale[c], out)
                while (c >= 0) {
                    conCellScale[c] = linkCellScale(c)
                    refreshLinkFiber(c) // волокно мышцы (MuscleFiber.kt)
                    if (soft) conSoftness[c] = linkSoftness(c)
                    c = conNextForEdge[c]
                }
            }
            for (r in edgeTriStart[e] until edgeTriStart[e + 1]) {
                val id = edgeTris[r]
                if (triStamp[id] == now) continue // треугольник уже пересчитан с другого ребра
                val w = slot[id]
                if (w !in 0 until triCount) continue
                triStamp[id] = now
                noteTriLerp(id, triCellScale[w], out)
                triCellScale[w] = triangleCellScale(w)
                refreshTriangleFiber(w) // волокно мышцы (MuscleFiber.kt)
                if (soft) triSoftness[w] = triangleSoftness(w)
            }
        }
    }
}

/** Оба прохода в одном потоке — сдвинувшиеся клетки [moved] (первые [count]). */
internal fun BodyState.refreshMovedRings(moved: IntArray, count: Int) {
    var changedN = 0
    refreshEffectiveOf(moved, 0, count) { u ->
        if (changedN == cellScaleChanged.size) cellScaleChanged = cellScaleChanged.copyOf(changedN * 2)
        cellScaleChanged[changedN++] = u
    }
    refreshAroundChanged(cellScaleChanged, changedN, null)
}

/**
 * Волокно клетки [v] сменилось (PhysicsWorld.setCellFiber): множители связей и треугольников,
 * чьим источником может быть [v], — вокруг неё и вокруг соседей по кольцу. Действующие
 * множители и источники от волокна не зависят и не меняются.
 */
internal fun BodyState.refreshCellFiberRing(v: Int) {
    if (!cellScalesOn && !softnessOn) return
    if (cellScaleEff.size != n || cellScaleSrc.size != n || constraintBlocks == null) { cellScalesDirty = true; return }
    val b = baked
    refreshCellScaleAround(v)
    for (q in b.vertStart[v] until b.vertStart[v + 1]) refreshCellScaleAround(b.vertOther[q])
}

/**
 * Множители клеток — шаг к цели с темпом мышцы стенда (как активация в updateMuscles):
 * удаление от 1 — muscleRateContract, возвращение к 1 — muscleRateRelax. Проход по клеткам
 * только пока хоть одна в пути (cellScalesMoving); у изменившейся клетки связи и
 * треугольники пересчитываются по кольцу (refreshCellScaleRing), без прохода по миру.
 * Значение связи — один из множителей клеток, без арифметики: порядок клеток в проходе на
 * результат не влияет.
 */
internal fun BodyState.advanceCellScales(dt: Float) {
    ensureFiberSize() // волокно мышцы (MuscleFiber.kt): массивы — до пересчёта колец на воркерах
    val s = cellScale; val target = cellScaleTarget
    val byEdges = constraintBlocks != null && !cellScalesDirty && cellScaleEff.size == n
    if (byEdges) beginCellScaleLerp()
    // КОЛЬЦА — ПОСЛЕ ШАГА ВСЕХ КЛЕТОК, и на воркерах (ParallelMuscles), и в одном потоке. Кольцо, пересчитанное
    // сразу за шагом клетки, видит промежуточные множители соседей, и решение «действующий множитель не
    // изменился — связи не трогать» выходило другим, чем по окончательным: bugReplayCheck 11.11 (мягкая ткань,
    // 6 подшагов) — запись на 8 воркерах и повтор на одном разошлись на тике 643 в одной связи (1.461 против
    // 1.384 — её множитель уже устарел, и один путь его пересчитал, а другой нет).
    val w = if (byEdges) activeWorkers else null
    val muscles = if (w != null && w.count > 1) parallelPhysics(w).muscles.also { it.begin() } else null
    var movedN = 0
    var moving = false
    for (v in 0 until n) {
        val t = target[v]; val cur = s[v]
        if (cur == t) continue
        val away = kotlin.math.abs(t - 1f) > kotlin.math.abs(cur - 1f)
        val k = minOf(1.0f, (if (away) muscleRateContract else muscleRateRelax) * dt)
        var next = openingRateLimit(cur, (cur + (t - cur) * k).toFloat()) // ВРЕМЕННО (MX_OPEN = 2, MuscleExperiment.kt)
        if (kotlin.math.abs(t - next) < CELL_SCALE_SNAP) next = t else moving = true
        s[v] = next
        sleep.requestWake(v)
        if (!byEdges) continue
        // Метка «сдвинулась»: действующий множитель ей считает её же кусок, а не тот, кому она соседка (refreshEffectiveOf).
        lerpCellStamp[v] = lerpStampNow
        if (muscles != null) muscles.note(v)
        else {
            if (movedN == cellScaleMoved.size) cellScaleMoved = cellScaleMoved.copyOf(movedN * 2)
            cellScaleMoved[movedN++] = v
        }
    }
    cellScalesMoving = moving
    if (byEdges) {
        if (muscles != null) muscles.refreshRings(w!!) else refreshMovedRings(cellScaleMoved, movedN)
        endCellScaleLerp()
        return
    }
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

/**
 * Множители связей и треугольников у клетки [v] — по её рёбрам. Одиночный путь (рождение, гибель ребра, мягкость,
 * волокно): без захвата для плавности — она только у шага мышц, а тот идёт своим путём (refreshAroundChanged).
 */
private fun BodyState.refreshCellScaleAround(v: Int) {
    val b = baked
    val slot = constraintBlocks!!.triangleSlot
    for (q in b.vertStart[v] until b.vertStart[v + 1]) {
        val e = b.vertEdge[q]
        var c = conOfEdge[e]
        while (c >= 0) {
            conCellScale[c] = linkCellScale(c)
            refreshLinkFiber(c) // волокно мышцы (MuscleFiber.kt)
            if (softnessOn) conSoftness[c] = linkSoftness(c)
            c = conNextForEdge[c]
        }
        for (r in b.edgeTriStart[e] until b.edgeTriStart[e + 1]) {
            val id = b.edgeTris[r]
            val w = slot[id]
            if (w in 0 until triCount) {
                triCellScale[w] = triangleCellScale(w)
                refreshTriangleFiber(w) // волокно мышцы (MuscleFiber.kt)
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
    lerpEdgeN = 0; lerpTriN = 0
    lerpStampNow++
    if (lerpEdgeStamp.size < baked.edgeCount) lerpEdgeStamp = IntArray(baked.edgeCount)
    if (lerpTriStamp.size < body.triCount) lerpTriStamp = IntArray(body.triCount)
    if (lerpCellStamp.size < n) lerpCellStamp = IntArray(n)
}

/** Захват «было» ребра [e] при полном пересчёте (captureAllForLerp): ребро без связи не ведётся. */
private fun BodyState.captureEdgeLerp(e: Int) {
    if (lerpEdgeStamp[e] == lerpStampNow) return
    val c = conOfEdge[e]
    if (c < 0) return
    lerpEdgeStamp[e] = lerpStampNow
    noteEdgeLerp(e, conCellScale[c], null)
}

private fun BodyState.captureTriLerp(id: Int, w: Int) {
    if (lerpTriStamp[id] == lerpStampNow) return
    lerpTriStamp[id] = lerpStampNow
    noteTriLerp(id, triCellScale[w], null)
}

/** Запись плавности ребра [e] со значением «было» [from]: в буфер куска воркера [out] или в общие списки. */
private fun BodyState.noteEdgeLerp(e: Int, from: Float, out: CellScaleLerpBuffer?) {
    if (out != null) { out.addEdge(e, from); return }
    if (lerpEdgeN == lerpEdgeIds.size) {
        lerpEdgeIds = lerpEdgeIds.copyOf(lerpEdgeN * 2)
        lerpEdgeFrom = lerpEdgeFrom.copyOf(lerpEdgeN * 2); lerpEdgeTo = lerpEdgeTo.copyOf(lerpEdgeN * 2)
    }
    lerpEdgeIds[lerpEdgeN] = e; lerpEdgeFrom[lerpEdgeN] = from; lerpEdgeN++
}

private fun BodyState.noteTriLerp(id: Int, from: Float, out: CellScaleLerpBuffer?) {
    if (out != null) { out.addTri(id, from); return }
    if (lerpTriN == lerpTriIds.size) {
        lerpTriIds = lerpTriIds.copyOf(lerpTriN * 2)
        lerpTriFrom = lerpTriFrom.copyOf(lerpTriN * 2); lerpTriTo = lerpTriTo.copyOf(lerpTriN * 2)
    }
    lerpTriIds[lerpTriN] = id; lerpTriFrom[lerpTriN] = from; lerpTriN++
}

/** Конец сбора: «станет» — нынешнее значение, а действующее возвращается к «было». */
private fun BodyState.endCellScaleLerp() {
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
    val w = activeWorkers
    if (w != null && w.count > 1 && lerpEdgeN + lerpTriN >= LERP_PARALLEL_MIN) {
        parallelPhysics(w).muscles.lerp(w, f, last)
    } else {
        lerpEdgeRange(0, lerpEdgeN, f, last)
        lerpTriRange(0, lerpTriN, f, last)
    }
    if (last) { lerpEdgeN = 0; lerpTriN = 0 }
}

/** Меньше записей плавности — в одном потоке: 16–32 барьера за тик дороже такой работы. */
private const val LERP_PARALLEL_MIN = 4096

internal fun BodyState.lerpEdgeRange(fromK: Int, toK: Int, f: Float, last: Boolean) {
    // Проход идёт каждый подшаг по связям движущихся мышц: массивы — в локальные, длины — до цикла, запись
    // множителя (setEdgeCellScale) — прямо в цикле, а не вызовом на каждое ребро. См. lengthsBeforeLoop.
    val ids = lerpEdgeIds; val lerpFrom = lerpEdgeFrom; val lerpTo = lerpEdgeTo
    val conOfEdge = conOfEdge; val cs = conCellScale; val nextCon = conNextForEdge
    lengthsBeforeLoop(ids.size or lerpFrom.size or lerpTo.size or conOfEdge.size or cs.size or nextCon.size)
    for (k in fromK until toK) {
        val from = lerpFrom[k]; val to = lerpTo[k]
        val value = if (last) to else from + (to - from) * f
        var c = conOfEdge[ids[k]]
        while (c >= 0) { cs[c] = value; c = nextCon[c] }
    }
}

internal fun BodyState.lerpTriRange(fromK: Int, toK: Int, f: Float, last: Boolean) {
    val slot = constraintBlocks?.triangleSlot ?: return
    val ids = lerpTriIds; val lerpFrom = lerpTriFrom; val lerpTo = lerpTriTo; val ts = triCellScale
    val triCount = triCount
    lengthsBeforeLoop(slot.size or ids.size or lerpFrom.size or lerpTo.size or ts.size)
    for (k in fromK until toK) {
        val w = slot[ids[k]]
        if (w !in 0 until triCount) continue
        val from = lerpFrom[k]; val to = lerpTo[k]
        ts[w] = if (last) to else from + (to - from) * f
    }
}

/** Захват «было» одного куска — в общие списки плавности (см. ParallelMuscles). */
internal fun BodyState.appendCellScaleLerp(part: CellScaleLerpBuffer) {
    val edges = lerpEdgeN + part.edgeN
    if (edges > lerpEdgeIds.size) {
        val cap = maxOf(edges, lerpEdgeIds.size * 2)
        lerpEdgeIds = lerpEdgeIds.copyOf(cap); lerpEdgeFrom = lerpEdgeFrom.copyOf(cap); lerpEdgeTo = lerpEdgeTo.copyOf(cap)
    }
    System.arraycopy(part.edgeIds, 0, lerpEdgeIds, lerpEdgeN, part.edgeN)
    System.arraycopy(part.edgeFrom, 0, lerpEdgeFrom, lerpEdgeN, part.edgeN)
    lerpEdgeN = edges
    val tris = lerpTriN + part.triN
    if (tris > lerpTriIds.size) {
        val cap = maxOf(tris, lerpTriIds.size * 2)
        lerpTriIds = lerpTriIds.copyOf(cap); lerpTriFrom = lerpTriFrom.copyOf(cap); lerpTriTo = lerpTriTo.copyOf(cap)
    }
    System.arraycopy(part.triIds, 0, lerpTriIds, lerpTriN, part.triN)
    System.arraycopy(part.triFrom, 0, lerpTriFrom, lerpTriN, part.triN)
    lerpTriN = tris
}

/** Ближе этого к цели множитель клетки встаёт точно на неё: путь кончается за конечное число тиков. */
private const val CELL_SCALE_SNAP = 1e-4f

fun BodyState.updateMuscleScale(f: Float) {
    if (muscleScaleOf.size != muscleActivation.size) muscleScaleOf = FloatArray(muscleActivation.size)
    if (muscleActivationPrev.size != muscleActivation.size) muscleActivationPrev = FloatArray(muscleActivation.size)
    for (m in muscleActivation.indices) {
        val a = muscleActivationPrev[m] + (muscleActivation[m] - muscleActivationPrev[m]) * f
        muscleScaleOf[m] = 1.0f - a * (1.0f - muscleContraction)
    }
}

fun BodyState.updateMuscles(dt: Float) {
    if (muscleActivationPrev.size != muscleActivation.size) muscleActivationPrev = FloatArray(muscleActivation.size)
    System.arraycopy(muscleActivation, 0, muscleActivationPrev, 0, muscleActivation.size)
    for (m in muscleActivation.indices) {
        val target = muscleTarget[m]
        // Сокращение и распрямление идут с РАЗНОЙ скоростью — см. константы.
        val rate = if (target > muscleActivation[m]) muscleRateContract else muscleRateRelax
        var k = rate * dt
        if (k > 1.0f) k = 1.0f
        muscleActivation[m] += (target - muscleActivation[m]) * k
    }
}
