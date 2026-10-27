package io.github.some_example_name.physics

import kotlin.math.sqrt

/*
 * РОСТ ТЕЛА ПО КЛЕТКЕ. Этап A2.4 плана (docs/physics/library-integration-plan-2026-10-25.md).
 *
 * Тело дописывается в мир целиком (appendBody с born), но часть клеток — нерождённые:
 * мёртвые частицы без массы, их рёбра и треугольники мертвы для контура, организмов и
 * решателя, но стоят в рабочих списках на своих местах. Рождение (activateCell) оживляет
 * клетку и её связи там же — без перестройки блоков решателя, плана связей и сетки, — а
 * длины и площади покоя новорождённой ткани дорастают до своих за BIRTH_GROW_TICKS тиков
 * (easeNewborn).
 *
 * Проверки: GrowthCheck (born = все ≡ без born; рост из одной клетки, один поток = N
 * воркеров побитово), PartialBodyCheck (недоросшее тело ведёт себя как вырезанное).
 */

/**
 * Рёбра нерождённых клеток нового тела ([n0, n)) — нерождённые: мертвы для контура,
 * организмов и решателя, но остаются в рабочих списках до рождения клетки
 * (BakedTopology.edgeUnborn). Их треугольники умирают так же и оживут вместе с ними.
 *
 * Блоки решателя здесь ещё старые, нового тела в них нет; новые соберёт
 * initializeConstraintBlocks в конце appendBody — со всеми связями и треугольниками.
 * Нерождённые решатель пропускает по conDead и triAlive, поэтому рождение не трогает
 * блоков: из DenseBlockIndex вынутое назад не вставить.
 */
internal fun BodyState.markUnbornEdges(n0: Int) {
    val blocks = constraintBlocks
    constraintBlocks = null
    for (v in n0 until n) {
        if (!cellUnborn[v]) continue
        for (s in baked.vertStart[v] until baked.vertStart[v + 1]) {
            val e = baked.vertEdge[s]
            if (baked.edgeDead[e]) continue
            baked.markUnborn(e)
            var c = conOfEdge[e]
            while (c >= 0) { conDead[c] = true; c = conNextForEdge[c] }
            updateBoundaryLinkLimit(e)
            // Умершие треугольники открыли края у соседних рёбер.
            for (q in baked.edgeTriStart[e] until baked.edgeTriStart[e + 1]) {
                val t = baked.edgeTris[q]
                for (j in 0..2) { val a = baked.triEdge[3 * t + j]; if (a >= 0) updateBoundaryLinkLimit(a) }
            }
        }
    }
    constraintBlocks = blocks
}

/**
 * Нерождённое ребро уже не родится (тело удалено; клетка родилась в другом куске
 * порванного организма): оно и его нерождённые треугольники становятся просто
 * мёртвыми и уходят из рабочих списков при ближайшем уплотнении — как порванные.
 */
internal fun BodyState.retireUnbornEdge(e: Int) {
    baked.forgetUnborn(e)
    for (q in baked.edgeTriStart[e] until baked.edgeTriStart[e + 1]) {
        val t = baked.edgeTris[q]
        if (baked.isTriUnborn(t)) { baked.forgetTriUnborn(t); constraintBlocks?.removeTriangle(t) }
    }
    updateBoundaryLinkLimit(e)
    if (tearQueueN == tearQueue.size) tearQueue = tearQueue.copyOf(tearQueue.size * 2)
    tearQueue[tearQueueN++] = e
    tearsPending = true
}

/**
 * РОДИТЬ КЛЕТКУ [i] растущего тела (этап A2.4): частица оживает в точке ([x], [y]) со
 * скоростью ([vx0], [vy0]) — где рождается клетка, решает клиент (игра: деление рядом с
 * родителем). Оживают её рёбра к уже рождённым соседям и треугольники, у которых живы
 * все рёбра; контур, связи контура и контакты обновляются по задетым вершинам
 * (refreshSurface). Блоки решателя не перестраиваются. Цена — по соседям клетки.
 *
 * Клетка прирастает только к СВОЕМУ организму (по раскладке тела). Если тело успело
 * порваться и сосед оказался в другом куске, ребро к нему не рождается, а умирает:
 * слияние организмов при рождении — отдельная задача (план, раздел 2.1).
 */
internal fun BodyState.activateCell(i: Int, x: Double, y: Double, vx0: Double, vy0: Double) {
    require(cellUnborn[i]) { "клетка $i не ждёт рождения" }
    cellUnborn[i] = false; cellDead[i] = false
    invMass[i] = restInvMass[i]; matchWeight[i] = restMatchWeight(i)
    px[i] = x; py[i] = y; prevX[i] = x; prevY[i] = y
    vx[i] = vx0; vy[i] = vy0
    localComponents.addCell(i)
    baked.touch(i)
    val org = organismOf[i]
    val edges0 = growEdgeN
    for (s in baked.vertStart[i] until baked.vertStart[i + 1]) {
        val e = baked.vertEdge[s]
        val other = baked.vertOther[s]
        if (!baked.isUnborn(e) || cellUnborn[other]) continue
        if (cellDead[other] || organismOf[other] != org) { retireUnbornEdge(e); continue }
        baked.revive(e)
        var c = conOfEdge[e]
        while (c >= 0) { conDead[c] = false; linkTorn[c] = false; c = conNextForEdge[c] }
        if (growEdgeN == growEdges.size) growEdges = growEdges.copyOf(growEdges.size * 2)
        growEdges[growEdgeN++] = e
        updateBoundaryLinkLimit(e)
    }
    var tris = 0
    for (s in baked.vertexTriStart[i] until baked.vertexTriStart[i + 1]) {
        val t = baked.vertexTriangles[s] / 3
        if (!baked.reviveTriangle(t)) continue
        if (tris == bornTriScratch.size) bornTriScratch = bornTriScratch.copyOf(tris * 2)
        bornTriScratch[tris++] = t
        // Ожившая площадь закрыла края: рёбра треугольника больше не граница.
        for (j in 0..2) { val a = baked.triEdge[3 * t + j]; if (a >= 0) updateBoundaryLinkLimit(a) }
    }
    // Дорастание стартует от длины покоя в долях множителя связи, поэтому кольцо мышцы — раньше
    // (иначе новорождённая связь у мышцы начала бы с прежнего множителя и рванулась к половине
    // длины). Пределы контура длину покоя не читают — порядок им безразличен.
    refreshCellScaleRing(i)
    for (k in edges0 until growEdgeN) {
        var c = conOfEdge[growEdges[k]]
        while (c >= 0) { startLinkGrowth(c); c = conNextForEdge[c] }
    }
    for (k in 0 until tris) startTriangleGrowth(bornTriScratch[k])
    if (boneOf[i] >= 0) restPoseOfBornBone(boneOf[i])
    sleep.requestWake(i)
    refreshSurface()
    structureVersion++
}

/**
 * Связь [c] родилась: длина покоя — фактическая (в долях сокращения её мышцы), цель —
 * прежняя длина покоя. Дальше её ведёт easeNewborn.
 */
private fun BodyState.startLinkGrowth(c: Int) {
    if (conGrowTarget.size < conA.size) conGrowTarget = conGrowTarget.copyOf(conA.size)
    val target = conRest[c]
    if (target <= 0.0) return
    val dx = px[conA[c]] - px[conB[c]]; val dy = py[conA[c]] - py[conB[c]]
    val start = sqrt(dx * dx + dy * dy) / (muscleScale(conMuscle[c]) * conCellScale[c])
    conGrowTarget[c] = target
    conRest[c] = maxOf(start, BIRTH_MIN_FRACTION * target)
}

/**
 * Треугольник [id] (постоянный номер) родился: площадь покоя — фактическая (в долях
 * сокращения мышцы), цель — прежняя. Вывернутый или плоский начинает с малой доли цели.
 */
private fun BodyState.startTriangleGrowth(id: Int) {
    val w = constraintBlocks!!.triangleSlot[id]
    if (w < 0) return
    if (triGrowTarget.size < body.triCount) triGrowTarget = triGrowTarget.copyOf(body.triCount)
    val target = triRestArea2[w]
    if (target <= 0f) return
    val i0 = triA[w]; val i1 = triB[w]; val i2 = triC[w]
    val area2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) - (py[i1] - py[i0]) * (px[i2] - px[i0])
    val s = muscleScale(triMuscle[w]) * triCellScale[w]
    triGrowTarget[id] = target
    triRestArea2[w] = maxOf(area2 / (s * s), BIRTH_MIN_FRACTION * target).toFloat()
    if (growTriN == growTris.size) growTris = growTris.copyOf(growTris.size * 2)
    growTris[growTriN++] = id
}

/**
 * ДОРАСТАНИЕ — раз в тик, до решателя: длины и площади покоя новорождённой ткани шагают к
 * целям на 1/BIRTH_GROW_TICKS цели (см. conGrowTarget). Обходятся только дорастающие;
 * порванные и удалённые выпадают из списков. Шаг до цели — ровно цель, без остатка.
 */
internal fun BodyState.easeNewborn() {
    if (growEdgeN == 0 && growTriN == 0) return
    var m = 0
    for (k in 0 until growEdgeN) {
        val e = growEdges[k]
        if (baked.edgeDead[e]) continue
        var growing = false
        var c = conOfEdge[e]
        while (c >= 0) {
            val target = conGrowTarget[c]
            if (target > 0.0) {
                val step = target / BIRTH_GROW_TICKS
                val r = conRest[c]
                val next = if (r < target) minOf(r + step, target) else maxOf(r - step, target)
                conRest[c] = next
                if (next == target) conGrowTarget[c] = 0.0 else growing = true
            }
            c = conNextForEdge[c]
        }
        if (growing) growEdges[m++] = e
    }
    growEdgeN = m
    val slot = constraintBlocks!!.triangleSlot
    m = 0
    for (k in 0 until growTriN) {
        val id = growTris[k]
        val target = triGrowTarget[id]
        val w = slot[id]
        if (w < 0 || !baked.triAlive[id]) { triGrowTarget[id] = 0f; continue }
        val step = target / BIRTH_GROW_TICKS
        val r = triRestArea2[w]
        val next = if (r < target) minOf(r + step, target) else maxOf(r - step, target)
        triRestArea2[w] = next
        if (next == target) triGrowTarget[id] = 0f else growTris[m++] = id
    }
    growTriN = m
}

/**
 * Поза покоя кости по РОЖДЁННЫМ клеткам: смещения от их центроида, у нерождённых — ноль
 * (их вес в кости ноль, см. projectBoneInto). Пока кость выросла не вся, она держит
 * форму своей рождённой части. У целиком рождённой кости — ровно restPoseOfBone.
 */
internal fun BodyState.restPoseOfBornBone(b: Int) {
    val ids = rigidBones[b]
    var bornCount = 0
    for (i in ids) if (!cellUnborn[i] && boneOf[i] == b) bornCount++
    if (bornCount == ids.size) { restPoseOfBone(b); return }
    val qx = boneRestQx[b]; val qy = boneRestQy[b]
    java.util.Arrays.fill(qx, 0.0); java.util.Arrays.fill(qy, 0.0)
    if (bornCount == 0) return
    var cx = 0.0; var cy = 0.0
    for (i in ids) if (!cellUnborn[i] && boneOf[i] == b) { cx += body.x[i]; cy += body.y[i] }
    cx /= bornCount; cy /= bornCount
    for (k in ids.indices) if (!cellUnborn[ids[k]] && boneOf[ids[k]] == b) {
        qx[k] = body.x[ids[k]] - cx
        qy[k] = body.y[ids[k]] - cy
    }
}

/** Вес клетки [i] в позе её кости без захвата мышью: 0 у кости, которую клиент ещё не включил. */
internal fun BodyState.restMatchWeight(i: Int): Double =
    if (boneClusterOf[i] >= 0 && boneOf[i] != boneClusterOf[i]) 0.0 else 1.0

/**
 * ВКЛЮЧИТЬ КОСТЬ КЛЕТКИ [i] (клиент: клетка стала костью мутацией). Клетка входит в свою кость
 * с весом 1, её связи с уже включёнными клетками той же кости уходят из решателя насовсем — их
 * держит проекция (как внутрикостные связи при загрузке; мутация в кость необратима, а блоки
 * решателя вынутое обратно не вставляют). Поза кости — нынешнее положение её включённых клеток:
 * форма на момент мутации, без рывка, даже если тело согнуто. Контур и поверхность — по месту.
 * Ничего не делает у клетки не из кости описания, мёртвой или уже включённой. Между тиками.
 */
internal fun BodyState.activateBone(i: Int) {
    val b = boneClusterOf[i]
    if (b < 0 || boneOf[i] == b || cellDead[i]) return
    boneOf[i] = b
    if (dragId != i) matchWeight[i] = 1.0
    val blocks = constraintBlocks
    for (q in baked.vertStart[i] until baked.vertStart[i + 1]) {
        val j = baked.vertOther[q]
        baked.touch(j)
        if (boneOf[j] != b) continue
        var c = conOfEdge[baked.vertEdge[q]]
        while (c >= 0) {
            if (!conDead[c]) { conDead[c] = true; blocks?.links?.remove(c) }
            c = conNextForEdge[c]
        }
    }
    restPoseOfCurrentBone(b)
    // Треугольник с костной вершиной у мышцы клетки не сокращается — кольцо заново.
    refreshCellScaleRing(i)
    baked.touch(i)
    contacts?.invalidateStatic()
    sleep.requestWake(i)
    refreshSurface()
    structureVersion++
}

/** Поза кости [b] — нынешнее положение её включённых живых клеток (от их центра, сумма в ноль). */
private fun BodyState.restPoseOfCurrentBone(b: Int) {
    val ids = rigidBones[b]
    val qx = boneRestQx[b]; val qy = boneRestQy[b]
    java.util.Arrays.fill(qx, 0.0); java.util.Arrays.fill(qy, 0.0)
    var cx = 0.0; var cy = 0.0; var count = 0
    for (i in ids) if (boneOf[i] == b && !cellDead[i]) { cx += px[i]; cy += py[i]; count++ }
    if (count == 0) return
    cx /= count; cy /= count
    for (k in ids.indices) {
        val i = ids[k]
        if (boneOf[i] == b && !cellDead[i]) { qx[k] = px[i] - cx; qy[k] = py[i] - cy }
    }
}

/**
 * ГДЕ РОДИТЬСЯ КЛЕТКЕ [i]: образ её точки покоя при повороте и общем масштабе, подогнанных
 * методом наименьших квадратов по рождённым соседям (по соседям соседей, если своих
 * меньше двух), и скорость, средняя по ним. Пишет x, y, vx, vy в [out]. Без соседей —
 * точка покоя тела и нулевая скорость. Цена — по соседям клетки, без аллокаций.
 *
 * Поворот с масштабом СОХРАНЯЕТ ОРИЕНТАЦИЮ: треугольники клетки рождаются невывернутыми,
 * а масштаб учитывает сжатую мышцей окрестность. Отвергнуто: подгонка точки по нынешним
 * длинам покоя (conRest · muscleScale) шагами Гаусса — Ньютона. У обрывка мышцы, который
 * не может сжаться, окружности длин почти касаются, шаги перескакивают через отрезок
 * соседей — треугольник рождается вывернутым. GrowthCheck 25.10: живых рёбер выросшего
 * тела 1706 из 2617 против 2483 у поворота с масштабом (у целой копии — 2612).
 */
internal fun BodyState.birthPlacement(i: Int, out: DoubleArray) {
    val vs = baked.vertStart; val vo = baked.vertOther
    // Опорные клетки подгонки: рождённые соседи; мало — ещё и их рождённые соседи.
    var refN = 0
    val ref = birthRef
    fun addRef(u: Int) {
        if (cellDead[u] || u == i) return
        for (k in 0 until refN) if (ref[k] == u) return
        if (refN < ref.size) ref[refN++] = u
    }
    for (s in vs[i] until vs[i + 1]) addRef(vo[s])
    val own = refN
    if (refN < 2) for (k in 0 until own) { val u = ref[k]; for (s in vs[u] until vs[u + 1]) addRef(vo[s]) }
    if (refN == 0) {
        out[2] = 0.0; out[3] = 0.0
        freeSpot(i, body.x[i].toDouble(), body.y[i].toDouble(), out)
        return
    }

    var rx = 0.0; var ry = 0.0; var cx = 0.0; var cy = 0.0; var mvx = 0.0; var mvy = 0.0
    for (k in 0 until refN) {
        val u = ref[k]
        rx += body.x[u]; ry += body.y[u]; cx += px[u]; cy += py[u]; mvx += vx[u]; mvy += vy[u]
    }
    rx /= refN; ry /= refN; cx /= refN; cy /= refN; mvx /= refN; mvy /= refN
    // s·R = argmin Σ |s·R·a − b|²: cos·s = Σa·b / Σ|a|², sin·s = Σa×b / Σ|a|².
    var dot = 0.0; var cross = 0.0; var aa = 0.0
    for (k in 0 until refN) {
        val u = ref[k]
        val ax = body.x[u] - rx; val ay = body.y[u] - ry
        val bx = px[u] - cx; val by = py[u] - cy
        dot += ax * bx + ay * by; cross += ax * by - ay * bx; aa += ax * ax + ay * ay
    }
    // Одна опорная клетка — поворот не определён: сдвиг позы покоя без поворота.
    val fit = aa > 1e-30 && dot * dot + cross * cross > 1e-60
    val cos = if (fit) dot / aa else 1.0
    val sin = if (fit) cross / aa else 0.0
    val ox = body.x[i] - rx; val oy = body.y[i] - ry
    out[2] = mvx; out[3] = mvy
    freeSpot(i, cx + cos * ox - sin * oy, cy + sin * ox + cos * oy, out)
}

/**
 * СВОБОДНОЕ МЕСТО У ТОЧКИ ПОЗЫ ([x0], [y0]) для клетки [i]; пишет out[0], out[1].
 *
 * Под сокращением край недоросшего тела сползает в пустоту, где позже родится клетка, и
 * точка позы оказывается занята: новорождённая легла бы поверх соседа, и глубокий контакт
 * (центр внутри чужого радиуса) убил бы обоих. GrowthAloneProbe 26.10, тело 947 клеток
 * под гребком: 26 таких рождений, без гребка — ни одного. Занявшая клетка почти всегда
 * сосед соседа: 2–3 ребра по графу тела (54 из 65 случаев в трёх прогонах).
 *
 * Поэтому смотрим на рождённые клетки в BIRTH_SEARCH_RINGS кольцах по ВСЕМ рёбрам тела
 * (живым, нерождённым, порванным), кроме своих соседей по рёбрам — с ними связи, а не
 * контакт. Точка внутри запретного круга соседа выталкивается на его край, несколько
 * проходов; свободная точка остаётся как есть. Связи сдвинутой клетки стартуют с
 * фактической длины и дорастут (startLinkGrowth).
 *
 * ЗАПРЕТНЫЙ КРУГ — ЗОНА СМЕРТИ С ЗАПАСОМ (BIRTH_CLEAR · больший радиус), не полное
 * контактное расстояние: частичное перекрытие разведёт контакт, а клетка остаётся у
 * точки позы. Замер на шести ростах (два тела с мышцами × три темпа рождений), порвано
 * рёбер / погибло клеток: без поиска 480 / 82; круг до суммы радиусов 683 / 137 — щель у
 * большого тела закрыта целиком, и клетку загоняет в соседнюю ткань; зона смерти
 * 216 / 39. Полный круг, когда сдвиг меньше половины связи, иначе зона смерти —
 * 199 / 37, в пределах разброса хаотичной истории; отвергнут как лишняя сложность.
 *
 * Дальние куски того же тела и чужие тела не смотрятся (1–2 случая за рост): для них
 * нужна сетка контактов, а она между тиками не обязана быть свежей.
 */
private fun BodyState.freeSpot(i: Int, x0: Double, y0: Double, out: DoubleArray) {
    val vs = baked.vertStart; val vo = baked.vertOther
    val near = birthNear
    // Обход по кольцам: near[0] = i, дальше кольцо за кольцом. Первое кольцо — свои
    // соседи по рёбрам, препятствия начинаются со второго.
    var count = 1; near[0] = i
    var ringStart = 0
    var obstacleStart = count
    for (ring in 1..BIRTH_SEARCH_RINGS) {
        val ringEnd = count
        if (ring == 2) obstacleStart = ringEnd
        for (k in ringStart until ringEnd) {
            val v = near[k]
            for (s in vs[v] until vs[v + 1]) {
                val u = vo[s]
                var seen = false
                for (q in 0 until count) if (near[q] == u) { seen = true; break }
                if (!seen && count < near.size) near[count++] = u
            }
        }
        ringStart = ringEnd
    }
    var x = x0; var y = y0
    for (iter in 0 until BIRTH_PUSH_ITERATIONS) {
        var moved = false
        for (k in obstacleStart until count) {
            val j = near[k]
            if (cellDead[j]) continue
            // Ближе позы покоя контакт и сам не толкает (пары покоя), так что и круг не шире.
            val rdx = (body.x[i] - body.x[j]).toDouble(); val rdy = (body.y[i] - body.y[j]).toDouble()
            val rest = sqrt(rdx * rdx + rdy * rdy)
            val clear = minOf(rest, BIRTH_CLEAR * maxOf(radius[i], radius[j]))
            val dx = x - px[j]; val dy = y - py[j]
            val d2 = dx * dx + dy * dy
            if (d2 >= clear * clear) continue
            val d = sqrt(d2)
            if (d > 1e-12 * clear) { x = px[j] + dx / d * clear; y = py[j] + dy / d * clear }
            else if (rest > 1e-12) { x = px[j] + rdx / rest * clear; y = py[j] + rdy / rest * clear }
            else x = px[j] + clear  // ровно в центре и в позе покоя тоже: любое направление
            moved = true
        }
        if (!moved) break
    }
    out[0] = x; out[1] = y
}

/** Кольца поиска свободного места: занявшая клетка — в 2–3 рёбрах (см. freeSpot). */
private const val BIRTH_SEARCH_RINGS = 3

/** Проходы выталкивания: из одного круга — один шаг; зажатой между двумя хватает нескольких. */
private const val BIRTH_PUSH_ITERATIONS = 8

/** Запретный круг — чуть шире зоны смерти глубокого контакта (центр внутри чужого радиуса). */
private const val BIRTH_CLEAR = 1.1

/** Опорные клетки подгонки рождения: соседей у клетки единицы, с соседями соседей — десятки. */
private val birthRefPool = ThreadLocal.withInitial { IntArray(64) }
private val BodyState.birthRef: IntArray get() = birthRefPool.get()

/** Клетки трёх колец вокруг рождённой: в треугольной сетке 1 + 6 + 12 + 18 = 37, запас вдвое с лишним. */
private val birthNearPool = ThreadLocal.withInitial { IntArray(128) }
private val BodyState.birthNear: IntArray get() = birthNearPool.get()

