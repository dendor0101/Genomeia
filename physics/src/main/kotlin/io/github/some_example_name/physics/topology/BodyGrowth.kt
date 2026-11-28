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
 * мёртвыми и уходят из рабочих списков при ближайшем уплотнении — как порванные. [quiet] —
 * снята временная связь роста (TempLinks.kt): в счёт разрывов не идёт.
 */
internal fun BodyState.retireUnbornEdge(e: Int, quiet: Boolean = false) {
    baked.forgetUnborn(e)
    for (q in baked.edgeTriStart[e] until baked.edgeTriStart[e + 1]) {
        val t = baked.edgeTris[q]
        if (baked.isTriUnborn(t)) { baked.forgetTriUnborn(t); constraintBlocks?.removeTriangle(t) }
    }
    updateBoundaryLinkLimit(e)
    if (tearQueueN == tearQueue.size) tearQueue = tearQueue.copyOf(tearQueue.size * 2)
    tearQueue[tearQueueN++] = e
    if (quiet) tearQueueQuiet++
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
internal fun BodyState.activateCell(i: Int, x: Float, y: Float, vx0: Float, vy0: Float) {
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
    // Клетка легла на временные связи роста — они сняты (TempLinks.kt). Только после того, как ожили её связи:
    // раньше снятие на миг рассекало организм (связь была единственной между её концами), и связь новорождённой
    // к «чужому» куску гасла как разрыв (TempLinksTest).
    retireTempEdgesOf(i)
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
    if (target <= 0.0f) return
    val dx = px[conA[c]] - px[conB[c]]; val dy = py[conA[c]] - py[conB[c]]
    val start = sqrt(dx * dx + dy * dy) / muscleLengthFactor(c, muscleScale(conMuscle[c]) * conCellScale[c]) /* волокно мышцы: MuscleFiber.kt */
    conGrowTarget[c] = target
    conRest[c] = maxOf(start, BIRTH_MIN_FRACTION * target)
    if (conGrowAge.size < conA.size) conGrowAge = conGrowAge.copyOf(conA.size)
    conGrowAge[c] = 0
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
    val sa = muscleScale(triMuscle[w]) * triCellScale[w] // множитель площади — по волокну или по площади (MuscleFiber.kt)
    triGrowTarget[id] = target
    triRestArea2[w] = maxOf(area2 / triangleAreaFactor(w, sa), BIRTH_MIN_FRACTION * target).toFloat()
    if (growTriN == growTris.size) growTris = growTris.copyOf(growTris.size * 2)
    growTris[growTriN++] = id
}

/**
 * ДОРАСТАНИЕ — раз в тик, до решателя (см. conGrowTarget). Обходятся только дорастающие;
 * порванные и удалённые выпадают из списков.
 *
 * СВЯЗЬ — МЯГКАЯ ПРУЖИНА, КОТОРАЯ ТВЕРДЕЕТ (деление, 02.11). Длина покоя молодой связи —
 * фактическая длина плюс доля age/BIRTH_GROW_TICKS пути до цели: в первый тик связь почти
 * ничего не требует, к BIRTH_GROW_TICKS она обычной твёрдости. Так и связь с родителем
 * выталкивает новорождённую мягко, и связи с соседями не дёргают её, пока она выходит из
 * родителя. Прежний шаг на 1/BIRTH_GROW_TICKS цели от начальной длины не смотрел, где клетка
 * сейчас, и тянул её к заданной длине жёстко. Площадь треугольника — по-прежнему шагом.
 * Связь, рождённая много длиннее цели, тянет не сильнее BIRTH_MAX_PULL и дорастает, пока не
 * подойдёт к цели, — дольше BIRTH_GROW_TICKS.
 */
internal fun BodyState.easeNewborn() {
    markGrowingCells()
    growLerpN = 0; growLerpTriN = 0
    if (growEdgeN == 0 && growTriN == 0) return
    var m = 0
    for (k in 0 until growEdgeN) {
        val e = growEdges[k]
        if (baked.edgeDead[e]) continue
        var growing = false
        var c = conOfEdge[e]
        while (c >= 0) {
            val target = conGrowTarget[c]
            val was = conRest[c]
            if (target > 0.0f) {
                // Возраст не растёт дальше BIRTH_GROW_TICKS: далёкая связь (ниже) дорастает
                // дольше, а байт переполнился бы.
                val age = minOf(conGrowAge[c] + 1, BIRTH_GROW_TICKS)
                conGrowAge[c] = age.toByte()
                val dx = px[conA[c]] - px[conB[c]]; val dy = py[conA[c]] - py[conB[c]]
                val len = sqrt(dx * dx + dy * dy) / muscleLengthFactor(c, muscleScale(conMuscle[c]) * conCellScale[c]) /* волокно мышцы: MuscleFiber.kt */
                val pull = BIRTH_MAX_PULL * target
                if (age >= BIRTH_GROW_TICKS && len - target <= pull) {
                    conRest[c] = target; conGrowTarget[c] = 0.0f
                } else {
                    val eased = len + (target - len) * (age.toFloat() / BIRTH_GROW_TICKS)
                    conRest[c] = maxOf(eased, len - pull, BIRTH_MIN_FRACTION * target)
                    growing = true
                }
            }
            // Шаг этого тика решатель проходит по подшагам (applyGrowthLerp): запись на каждую связь ребра, по цепочке.
            noteGrowthLerp(e, was, conRest[c])
            conRest[c] = was
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
        noteGrowthLerpTri(id, r, next)
        if (next == target) triGrowTarget[id] = 0f else growTris[m++] = id
    }
    growTriN = m
}

// --- плавность дорастания по подшагам -------------------------------------------------------------------------
//
// ШАГ ДОРАСТАНИЯ РАЗ В ТИК ПРИ ЖЁСТКОЙ ТКАНИ — РЫВОК (24.11.2026). easeNewborn сдвигает длину покоя молодой связи и
// площадь покоя молодого треугольника раз в тик, целиком. Пока ткань была в восемь раз мягче, а подшагов шесть, такой
// шаг сглаживался сам. С жёсткой тканью и 16–32 подшагами решатель выполняет его за первые подшаги тика, а
// восстановление скоростей делит сдвиг на малый шаг: новорождённая упирается в потолок скорости, молодые связи рвутся.
// Каталог стендов, рост тел геномов (983 сцены gen-grow), жёсткая ткань без клина и гашения: сцен со срывом в потолок
// скорости 120 при 16 подшагах и 361 при 32 (при шести и мягкой ткани — 11), с разрушением 88 и 136 (было 67).
// Та же беда была у мышцы клетки (27.10, MuscleDriver.applyCellScaleLerp) и вылечена так же: шаг раз в тик, как
// прежде, а решатель идёт от «было» к «станет» линейно по подшагам; к концу тика значение ровно то, что дал шаг.

private fun BodyState.noteGrowthLerp(e: Int, from: Float, to: Float) {
    if (growLerpN == growLerpEdge.size) {
        growLerpEdge = growLerpEdge.copyOf(growLerpN * 2); growLerpFrom = growLerpFrom.copyOf(growLerpN * 2); growLerpTo = growLerpTo.copyOf(growLerpN * 2)
    }
    growLerpEdge[growLerpN] = e; growLerpFrom[growLerpN] = from; growLerpTo[growLerpN] = to; growLerpN++
}

private fun BodyState.noteGrowthLerpTri(id: Int, from: Float, to: Float) {
    if (growLerpTriN == growLerpTri.size) {
        growLerpTri = growLerpTri.copyOf(growLerpTriN * 2); growLerpTriFrom = growLerpTriFrom.copyOf(growLerpTriN * 2)
        growLerpTriTo = growLerpTriTo.copyOf(growLerpTriN * 2)
    }
    growLerpTri[growLerpTriN] = id; growLerpTriFrom[growLerpTriN] = from; growLerpTriTo[growLerpTriN] = to; growLerpTriN++
}

/**
 * Подшаг [step] из [count]: длина покоя молодых связей и площадь покоя молодых треугольников — на долю
 * (step + 1) / count пути от «было» к «станет»; на последнем — ровно «станет», и списки пустеют. Ребро и постоянный
 * номер треугольника переживают перепаковку рабочих списков посреди тика; порванное и мёртвое пропускается.
 * Дорастающих мало (клетки последних BIRTH_GROW_TICKS тиков), поэтому в одном потоке.
 */
internal fun BodyState.applyGrowthLerp(step: Int, count: Int) {
    if (growLerpN == 0 && growLerpTriN == 0) return
    val last = step == count - 1
    val f = (step + 1).toFloat() / count
    // С островами у каждой области свои списки: у тени острова — его записи, у мира — только мира (IslandRun.kt).
    var k = 0
    while (k < growLerpN) {
        val e = growLerpEdge[k]
        // Записи ребра идут в порядке его цепочки связей; ребро, порванное посреди тика, пропускается целиком.
        var c = if (baked.edgeDead[e]) -1 else conOfEdge[e]
        while (k < growLerpN && growLerpEdge[k] == e) {
            if (c >= 0) {
                val from = growLerpFrom[k]; val to = growLerpTo[k]
                conRest[c] = if (last) to else from + (to - from) * f
                c = conNextForEdge[c]
            }
            k++
        }
    }
    val slot = constraintBlocks?.triangleSlot
    if (slot != null) for (q in 0 until growLerpTriN) {
        val id = growLerpTri[q]
        val w = slot[id]
        if (w !in 0 until triCount || !baked.triAlive[id]) continue
        val from = growLerpTriFrom[q]; val to = growLerpTriTo[q]
        triRestArea2[w] = if (last) to else from + (to - from) * f
    }
    if (last) { growLerpN = 0; growLerpTriN = 0 }
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
    java.util.Arrays.fill(qx, 0.0f); java.util.Arrays.fill(qy, 0.0f)
    if (bornCount == 0) return
    var cx = 0.0f; var cy = 0.0f
    for (i in ids) if (!cellUnborn[i] && boneOf[i] == b) { cx += body.x[i]; cy += body.y[i] }
    cx /= bornCount; cy /= bornCount
    for (k in ids.indices) if (!cellUnborn[ids[k]] && boneOf[ids[k]] == b) {
        qx[k] = body.x[ids[k]] - cx
        qy[k] = body.y[ids[k]] - cy
    }
}

/** Вес клетки [i] в позе её кости без захвата мышью: 0 у кости, которую клиент ещё не включил. */
internal fun BodyState.restMatchWeight(i: Int): Float =
    if (boneClusterOf[i] >= 0 && boneOf[i] != boneClusterOf[i]) 0.0f else 1.0f

/**
 * ВКЛЮЧИТЬ КОСТЬ КЛЕТКИ [i] (клиент: клетка стала костью мутацией). Клетка входит в свою кость
 * с весом 1, её связи с уже включёнными клетками той же кости уходят из решателя насовсем — их
 * держит проекция (как внутрикостные связи при загрузке; мутация в кость необратима, а блоки
 * решателя вынутое обратно не вставляют). Поза кости — нынешнее положение её включённых клеток:
 * форма на момент мутации, без рывка, даже если тело согнуто. Контур и поверхность — по месту.
 * Ничего не делает у клетки не из кости описания, мёртвой или уже включённой. Между тиками.
 *
 * НОВОРОЖДЁННАЯ КОСТЬЮ НЕ СТАНОВИТСЯ, пока выходит из родителя ([isGrowingNewborn]): деление
 * кладёт её почти в центр родителя (divisionPlacement), и поза, снятая в этот момент, держала бы
 * кость сжатой навсегда. GrowthCheck 03.11: у Long bone связи внутри кости застывали на 0.08
 * длины генома, у User genome (оболочка из костей) — 0.01…2.18, в среднем 0.58: оболочка
 * сдавливала ткань внутри. Сначала дочь выходит мягкой тканью, костью — когда её связи
 * доросли (BIRTH_GROW_TICKS), с позой на своём месте. Отказ — false: клиент повторяет вызов
 * (игра зовёт каждый тик, пока клетка-кость не включена, — WorldPhysics.syncOrganism).
 */
internal fun BodyState.activateBone(i: Int): Boolean {
    val b = boneClusterOf[i]
    if (b < 0 || cellDead[i]) return false
    if (boneOf[i] == b) return true
    if (isGrowingNewborn(i)) return false
    boneOf[i] = b
    if (dragId != i) matchWeight[i] = 1.0f
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
    return true
}

/** Поза кости [b] — нынешнее положение её включённых живых клеток (от их центра, сумма в ноль). */
private fun BodyState.restPoseOfCurrentBone(b: Int) {
    val ids = rigidBones[b]
    val qx = boneRestQx[b]; val qy = boneRestQy[b]
    java.util.Arrays.fill(qx, 0.0f); java.util.Arrays.fill(qy, 0.0f)
    var cx = 0.0f; var cy = 0.0f; var count = 0
    for (i in ids) if (boneOf[i] == b && !cellDead[i]) { cx += px[i]; cy += py[i]; count++ }
    if (count == 0) return
    cx /= count; cy /= count
    for (k in ids.indices) {
        val i = ids[k]
        if (boneOf[i] == b && !cellDead[i]) { qx[k] = px[i] - cx; qy[k] = py[i] - cy }
    }
}

/**
 * Метки неуязвимости делящихся (BoundaryContacts.immuneStamp): концы дорастающих рёбер —
 * новорождённая и её соседи по связям. Раз в шаг, до контактов; O(дорастающих рёбер).
 * Номер шага растёт и без них, чтобы прошлые метки истекали.
 */
internal fun BodyState.markGrowingCells() {
    growStampNow++
    if (growStamp.size != n) growStamp = IntArray(n)
    val s = growStamp; val now = growStampNow
    for (k in 0 until growEdgeN) {
        val e = growEdges[k]
        s[baked.edgeU(e)] = now; s[baked.edgeV(e)] = now
    }
    contacts?.let { it.immuneStamp = s; it.immuneNow = now }
    rampNewbornContacts()
}

/**
 * МЯГКИЙ СТАРТ КАСАНИЙ НОВОРОЖДЁННОЙ: расстояние упора её касаний — доля age/BIRTH_GROW_TICKS
 * от обычного, как и длина покоя её связей (startLinkGrowth). Раз в шаг, после меток.
 *
 * Зачем (GrowthCheck 03.11, Burdock leaf): дочь рождается почти в центре родителя
 * (divisionPlacement), а соседи родителя стоят от этого центра на длине связи — для двух
 * несвязанных клеток это глубокое перекрытие (сумма радиусов больше связи). Касание
 * расталкивало их в первом же подшаге: связь родителя с соседом растягивалась до 1.27–1.44
 * длины покоя и рвалась по пределу. При быстром росте этого не видно — соседняя связь сама
 * ещё дорастала и пределу не подчинялась; при 20+ тиках на стадию — 3 разрыва за рост.
 * Пока дочь выходит из родителя, она его соседей не толкает; в своей точке позы она от них
 * дальше суммы радиусов (иначе это была бы пара покоя), и полное касание к тому времени
 * ничего не задевает. Пара остаётся в кандидатах широкой фазы — касание включается сразу.
 * Только с клетками своего организма: упор в рельеф и в чужое тело полный с первого подшага
 * (BoundaryContacts.contactRamp, находка 30 — иначе тело врастало в стену и в соседа).
 *
 * Новорождённая — клетка, у которой дорастают ВСЕ живые связи: у родителя и соседей есть
 * взрослые, их касания обычные. Выводится из дорастающих связей (conGrowAge в снимке),
 * поэтому само в снимок не входит. O(дорастающих рёбер · степень) за шаг; без роста — ноль,
 * и BoundaryContacts.contactRamp = null (в паре касания — одна проверка).
 */
private fun BodyState.rampNewbornContacts() {
    if (contactRamp.size != n) contactRamp = FloatArray(n) { 1f }
    // Сброс по прошлому списку: после уплотнения номера в нём устарели, но все, кроме
    // записанных в список, и так равны 1 — переставить их в 1 безвредно.
    for (k in 0 until rampN) if (rampCells[k] < n) contactRamp[rampCells[k]] = 1f
    rampN = 0
    for (k in 0 until growEdgeN) {
        val e = growEdges[k]
        if (baked.edgeDead[e]) continue
        val c = conOfEdge[e]
        if (c < 0 || conGrowTarget[c] <= 0.0f) continue
        val f = conGrowAge[c].toFloat() / BIRTH_GROW_TICKS
        rampIfNewborn(baked.edgeU(e), f)
        rampIfNewborn(baked.edgeV(e), f)
    }
    contacts?.contactRamp = if (rampN > 0) contactRamp else null
}

private fun BodyState.rampIfNewborn(v: Int, f: Float) {
    if (f >= contactRamp[v] || !isGrowingNewborn(v)) return
    if (contactRamp[v] == 1f) {
        if (rampN == rampCells.size) rampCells = rampCells.copyOf(rampN * 2)
        rampCells[rampN++] = v
    }
    contactRamp[v] = f
}

/**
 * Новорождённая, которая ещё выходит из родителя: у неё дорастают ВСЕ живые связи (у родителя и
 * соседей есть взрослые). Выводится из дорастающих связей — в снимок не входит.
 */
internal fun BodyState.isGrowingNewborn(v: Int): Boolean {
    var any = false
    for (s in baked.vertStart[v] until baked.vertStart[v + 1]) {
        val e = baked.vertEdge[s]
        if (baked.edgeDead[e]) continue
        val c = conOfEdge[e]
        if (c < 0 || c >= conGrowTarget.size || conGrowTarget[c] <= 0.0f) return false
        any = true
    }
    return any
}

/**
 * ДЕЛЕНИЕ: точка рождения клетки [i] у родителя [parent] — на DIVISION_START_FRACTION пути от
 * центра родителя к её точке позы (birthPlacement), скорость — родителя. Дальше её выталкивают
 * дорастающие связи (startLinkGrowth: длина покоя от фактической до цели за BIRTH_GROW_TICKS),
 * а пока они дорастают, глубокое касание не убивает (markGrowingCells). Поиска свободного
 * места (freeSpot) нет намеренно: клетка и должна родиться внутри родителя.
 *
 * ТОЛЬКО ПРИ СВЯЗИ С РОДИТЕЛЕМ. Родителя назначает игра, и по раскладке он не обязан быть
 * соседом (отчёт 03.11 14:04, weighted_neural_link: s1 родится от s7, а связь у s1 только с
 * s6). Без связи выталкивать некому: клетка ложится в родителя, контакт разводит перекрытие
 * одним рывком, пара разлетается со скоростью 0.4 клетки/тик и рвёт соседние связи. Такая
 * клетка рождается по позе, как без родителя. Связь — постоянная: временная связь роста
 * (TempLinks.kt) снимается, как только на неё ляжет ткань, и могла бы уйти раньше, чем вытолкнет
 * дочь, — та осталась бы в родителе (Megalodon, 798 таких рождений: гибель в своей ткани 4 → 523
 * клеток, находка 32).
 */
internal fun BodyState.divisionPlacement(i: Int, parent: Int, out: FloatArray) {
    birthPlacement(i, out, seed = parent)
    if (!linkedInLayout(i, parent)) return
    val f = DIVISION_START_FRACTION
    out[0] = px[parent] + (out[0] - px[parent]) * f
    out[1] = py[parent] + (out[1] - py[parent]) * f
    out[2] = vx[parent]; out[3] = vy[parent]
}

/** Есть ли у [i] постоянная связь с [j] в раскладке тела (живая, нерождённая или порванная; временные — не в счёт). */
private fun BodyState.linkedInLayout(i: Int, j: Int): Boolean {
    val vs = baked.vertStart; val vo = baked.vertOther
    for (s in vs[i] until vs[i + 1]) if (vo[s] == j && !isTempEdge(baked.vertEdge[s])) return true
    return false
}

/**
 * ГДЕ РОДИТЬСЯ КЛЕТКЕ [i]: образ её точки покоя при повороте и общем масштабе, подогнанных
 * методом наименьших квадратов по рождённым соседям (по соседям соседей, если своих
 * меньше двух), и скорость, средняя по ним. Пишет x, y, vx, vy в [out]. Без соседей —
 * опора [seed] (родитель при делении) и его рождённые соседи; нет и его — точка покоя тела
 * и нулевая скорость. Цена — по соседям клетки, без аллокаций.
 *
 * Поворот с масштабом СОХРАНЯЕТ ОРИЕНТАЦИЮ: треугольники клетки рождаются невывернутыми,
 * а масштаб учитывает сжатую мышцей окрестность. Отвергнуто: подгонка точки по нынешним
 * длинам покоя (conRest · muscleScale) шагами Гаусса — Ньютона. У обрывка мышцы, который
 * не может сжаться, окружности длин почти касаются, шаги перескакивают через отрезок
 * соседей — треугольник рождается вывернутым. GrowthCheck 25.10: живых рёбер выросшего
 * тела 1706 из 2617 против 2483 у поворота с масштабом (у целой копии — 2612).
 *
 * ОПОРА — РОДИТЕЛЬ, ЕСЛИ СОСЕДЕЙ НЕТ (20.11.2026, рост из зиготы, reference.md, находка 30).
 * Родителя назначает геном, и по раскладке он не обязан быть соседом; бывает, что у клетки к
 * её стадии не родился ни один сосед. Раньше такая клетка ложилась в свою точку ИСХОДНОЙ
 * раскладки (body.x, body.y) — где стояло бы взрослое тело, поставленное зиготой при посадке.
 * Растущее тело к тому времени сдвинуто и повёрнуто: у Crayfish клетка 27 рождалась в шести
 * связях от родителя, и стадии, привязывающие её к телу, рождались со связями в 5–8 раз
 * длиннее покоя. Теперь она встаёт в свою точку позы относительно нынешнего тела.
 */
internal fun BodyState.birthPlacement(i: Int, out: FloatArray, seed: Int = -1) {
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
    if (refN == 0 && seed >= 0 && !cellDead[seed]) {
        addRef(seed)
        for (s in vs[seed] until vs[seed + 1]) addRef(vo[s])
    }
    if (refN == 0) {
        out[2] = 0.0f; out[3] = 0.0f
        freeSpot(i, body.x[i].toFloat(), body.y[i].toFloat(), out)
        return
    }

    var rx = 0.0f; var ry = 0.0f; var cx = 0.0f; var cy = 0.0f; var mvx = 0.0f; var mvy = 0.0f
    for (k in 0 until refN) {
        val u = ref[k]
        rx += body.x[u]; ry += body.y[u]; cx += px[u]; cy += py[u]; mvx += vx[u]; mvy += vy[u]
    }
    rx /= refN; ry /= refN; cx /= refN; cy /= refN; mvx /= refN; mvy /= refN
    // s·R = argmin Σ |s·R·a − b|²: cos·s = Σa·b / Σ|a|², sin·s = Σa×b / Σ|a|².
    var dot = 0.0f; var cross = 0.0f; var aa = 0.0f
    for (k in 0 until refN) {
        val u = ref[k]
        val ax = body.x[u] - rx; val ay = body.y[u] - ry
        val bx = px[u] - cx; val by = py[u] - cy
        dot += ax * bx + ay * by; cross += ax * by - ay * bx; aa += ax * ax + ay * ay
    }
    // Одна опорная клетка — поворот не определён: сдвиг позы покоя без поворота.
    val fit = aa > 1e-30f && dot * dot + cross * cross > 1e-60f
    val cos = if (fit) dot / aa else 1.0f
    val sin = if (fit) cross / aa else 0.0f
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
private fun BodyState.freeSpot(i: Int, x0: Float, y0: Float, out: FloatArray) {
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
            val rdx = (body.x[i] - body.x[j]).toFloat(); val rdy = (body.y[i] - body.y[j]).toFloat()
            val rest = sqrt(rdx * rdx + rdy * rdy)
            val clear = minOf(rest, BIRTH_CLEAR * maxOf(radius[i], radius[j]))
            val dx = x - px[j]; val dy = y - py[j]
            val d2 = dx * dx + dy * dy
            if (d2 >= clear * clear) continue
            val d = sqrt(d2)
            if (d > 1e-12f * clear) { x = px[j] + dx / d * clear; y = py[j] + dy / d * clear }
            else if (rest > 1e-12f) { x = px[j] + rdx / rest * clear; y = py[j] + rdy / rest * clear }
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
private const val BIRTH_CLEAR = 1.1f

/** Опорные клетки подгонки рождения: соседей у клетки единицы, с соседями соседей — десятки. */
private val birthRefPool = ThreadLocal.withInitial { IntArray(64) }
private val BodyState.birthRef: IntArray get() = birthRefPool.get()

/** Клетки трёх колец вокруг рождённой: в треугольной сетке 1 + 6 + 12 + 18 = 37, запас вдвое с лишним. */
private val birthNearPool = ThreadLocal.withInitial { IntArray(128) }
private val BodyState.birthNear: IntArray get() = birthNearPool.get()

