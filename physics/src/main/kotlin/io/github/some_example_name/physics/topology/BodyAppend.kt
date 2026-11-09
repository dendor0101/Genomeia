package io.github.some_example_name.physics

import kotlin.math.sqrt

/**
 * ДОПИСАТЬ ТЕЛО В ЖИВОЙ МИР. Этап A2.2 плана (docs/physics/library-integration-plan-2026-10-25.md).
 *
 * Новое тело получает номера частиц после всех прежних, его связи — после прежних связей,
 * треугольники — после прежних треугольников. Всё производное (рёбра и смежность,
 * кости, мышцы, организмы, сон, пары покоя, контур, контакты) дописывается по новому
 * телу теми же правилами, что при загрузке. Состояние прежних тел не трогается: ни
 * разрывы, ни пластика, ни сон, ни множители контактов.
 *
 * Проверка — оракул: загрузка набора тел разом и загрузка первого с дописыванием
 * остальных (с теми же постоянными мира, WorldScale) дают побитово один мир.
 *
 * Пересобирается целиком пока только дешёвое: блоки решателя и план связей (26 и 4 мс
 * на 100k) и список связей контура. Широкая фаза пересобирается в ближайшем подшаге.
 * Массивы растут копированием — на 100k это десятки мегабайт за рождение; ёмкость с
 * удвоением — следующий шаг, если замер покажет, что это заметно.
 */
internal fun BodyState.appendBody(def: BodyDefinition, born: BooleanArray? = null, boneOn: BooleanArray? = null) {
    check(n > 0) { "первое тело загружается load, дописывается — следующее" }
    val n0 = n; val l0 = body.linkCount; val t0 = body.triCount
    val bones0 = body.boneClusters.size; val muscles0 = body.muscleClusters.size
    val lnk0 = lnkCount; val tri0 = triCount; val con0 = conCount
    val combined = body.concat(def)
    body = combined
    n = combined.count
    val lc = combined.linkCount; val tc = combined.triCount
    growParticleArrays(n0)

    // --- частицы: поза покоя, массы по постоянной мира ---
    val meanArea = worldScale!!.meanCellArea
    for (i in n0 until n) {
        // prev не трогаем, как сброс мира: интегрирование ставит его до первого чтения.
        px[i] = body.x[i].toDouble(); py[i] = body.y[i].toDouble()
        val r = body.radius[i].toDouble()
        val m = if (meanArea > 0.0) (r * r) / meanArea else 1.0
        restInvMass[i] = if (m > 1e-12) 1.0 / m else 1.0
        invMass[i] = restInvMass[i]; matchWeight[i] = 1.0
        radius[i] = r
        boneOf[i] = -1; boneClusterOf[i] = -1; muscleOf[i] = -1
    }
    // --- нерождённые клетки (рост тела, A2.4): мертвы до рождения, без массы и веса в кости ---
    if (born != null) {
        require(born.size == n - n0) { "born — по клетке нового тела" }
        for (k in born.indices) if (!born[k]) {
            val v = n0 + k
            cellDead[v] = true; cellUnborn[v] = true
            invMass[v] = 0.0; matchWeight[v] = 0.0
            prevX[v] = px[v]; prevY[v] = py[v]
        }
    }

    // --- кости: кластеры от трёх клеток, как при загрузке ---
    val newBones = ArrayList<IntArray>()
    for (k in bones0 until body.boneClusters.size) {
        val ids = body.boneClusters[k]
        if (ids.size >= 3) newBones.add(ids) else degenerateBones++
    }
    val b0 = rigidBones.size
    rigidBones = Array(b0 + newBones.size) { if (it < b0) rigidBones[it] else newBones[it - b0] }
    boneRestQx = Array(rigidBones.size) { if (it < b0) boneRestQx[it] else DoubleArray(rigidBones[it].size) }
    boneRestQy = Array(rigidBones.size) { if (it < b0) boneRestQy[it] else DoubleArray(rigidBones[it].size) }
    var maxBone = boneDx.size
    for (b in b0 until rigidBones.size) {
        val ids = rigidBones[b]
        for (i in ids) { boneOf[i] = b; boneClusterOf[i] = b }
        if (ids.size > maxBone) maxBone = ids.size
    }
    // Кости, которые клиент включит позже (setBone): пока — мягкая ткань без веса в кости.
    if (boneOn != null) {
        require(boneOn.size == n - n0) { "boneOn — по клетке нового тела" }
        for (k in boneOn.indices) if (!boneOn[k] && boneOf[n0 + k] >= 0) { boneOf[n0 + k] = -1; matchWeight[n0 + k] = 0.0 }
    }
    for (b in b0 until rigidBones.size) restPoseOfBornBone(b)
    if (maxBone > boneDx.size) { boneDx = DoubleArray(maxBone); boneDy = DoubleArray(maxBone) }

    // --- мышцы ---
    val m0 = muscles0
    for (m in m0 until body.muscleClusters.size) for (i in body.muscleClusters[m]) muscleOf[i] = m
    growMuscles(t0)
    val mc = body.muscleClusters.size
    muscleActivation = muscleActivation.copyOf(mc)
    muscleScaleOf = muscleScaleOf.copyOf(mc).also { java.util.Arrays.fill(it, m0, mc, 1.0) }
    muscleTarget = muscleTarget.copyOf(mc)
    if (muscleActivationPrev.size == m0) muscleActivationPrev = muscleActivationPrev.copyOf(mc)

    // --- рёбра и треугольники нового тела ---
    baked.append(body, n0, l0, t0)
    fractureOrientation = FractureOrientation(body, baked)

    // --- полный список связей и треугольников: в конец рабочих списков ---
    // ЁМКОСТЬ — ПО ПОЛНОМУ ЧИСЛУ связей и треугольников мира, а не по живым: после
    // разрывов списки уплотняются, а блоки решателя индексируют треугольник постоянным
    // номером (triId). Урезанная ёмкость падала за границу массива на первом же
    // дописывании после разрыва (RuntimeWorldCheck 25.10).
    val addLinks = lc - l0
    val lnkCap = lnkA.size + addLinks
    lnkA = lnkA.copyOf(lnkCap); lnkB = lnkB.copyOf(lnkCap); lnkEdge = lnkEdge.copyOf(lnkCap)
    for (k in 0 until addLinks) {
        lnkA[lnk0 + k] = body.linkA[l0 + k]; lnkB[lnk0 + k] = body.linkB[l0 + k]
        lnkEdge[lnk0 + k] = baked.linkEdge[l0 + k]
    }
    lnkCount = lnk0 + addLinks
    val addTris = tc - t0
    val triCap = triA.size + addTris
    triA = triA.copyOf(triCap); triB = triB.copyOf(triCap); triC = triC.copyOf(triCap)
    triRestArea2 = triRestArea2.copyOf(triCap); triId = triId.copyOf(triCap)
    triMuscle = triMuscle.copyOf(triCap); triInverted = triInverted.copyOf(triCap)
    triCellScale = triCellScale.copyOf(triCap).also { java.util.Arrays.fill(it, tri0, triCap, 1f) }
    triSoftness = triSoftness.copyOf(triCap).also { java.util.Arrays.fill(it, tri0, triCap, 1f) }
    if (triHold.isNotEmpty()) triHold = triHold.copyOf(maxOf(triHold.size, triCap))
    for (k in 0 until addTris) {
        val t = tri0 + k
        triA[t] = body.triA[t0 + k]; triB[t] = body.triB[t0 + k]; triC[t] = body.triC[t0 + k]
        triRestArea2[t] = body.triRestArea2[t0 + k]; triId[t] = t0 + k
        triMuscle[t] = muscleOfTri(triA[t], triB[t], triC[t])
    }
    triCount = tri0 + addTris

    // --- связи решателя: внутрикостные не создаются, как в fillConstraints ---
    val conCap = conA.size + addLinks
    conA = conA.copyOf(conCap); conB = conB.copyOf(conCap); conRest = conRest.copyOf(conCap)
    conMuscle = conMuscle.copyOf(conCap); conEdge = conEdge.copyOf(conCap)
    conCellScale = conCellScale.copyOf(conCap).also { java.util.Arrays.fill(it, con0, conCap, 1f) }
    conSoftness = conSoftness.copyOf(conCap).also { java.util.Arrays.fill(it, con0, conCap, 1f) }
    var c = con0
    for (k in lnk0 until lnkCount) {
        val i = lnkA[k]; val j = lnkB[k]
        if (boneOf[i] != -1 && boneOf[i] == boneOf[j]) continue
        val dx = body.x[i] - body.x[j]; val dy = body.y[i] - body.y[j]
        conA[c] = i; conB[c] = j
        conRest[c] = sqrt((dx * dx + dy * dy).toDouble())
        conEdge[c] = lnkEdge[k]
        conMuscle[c] = muscleOfPair(i, j)
        c++
    }
    conCount = c
    linkTorn = linkTorn.copyOf(conCap); conDead = conDead.copyOf(conCap)
    conBoundary = conBoundary.copyOf(conCap); conTendon = conTendon.copyOf(conCap)
    if (conHold.isNotEmpty()) conHold = conHold.copyOf(maxOf(conHold.size, conCap))
    val oldEdges = conOfEdge.size
    conOfEdge = conOfEdge.copyOf(baked.edgeCount).also { java.util.Arrays.fill(it, oldEdges, baked.edgeCount, -1) }
    conNextForEdge = conNextForEdge.copyOf(conCap)
    val use = baked.triUse
    boundaryLinkLimits.grow(conCap)
    for (k in con0 until conCount) {
        val e = conEdge[k]
        conNextForEdge[k] = conOfEdge[e]
        conOfEdge[e] = k
        conBoundary[k] = use[e] <= 1
        conTendon[k] = (muscleOf[conA[k]] >= 0) != (muscleOf[conB[k]] >= 0)
        if (conBoundary[k]) boundaryLinkLimits.add(k)
    }

    // --- организмы нового тела: обход по рёбрам, как buildOrganisms ---
    // Нерождённые входят в организм по полной раскладке тела, но не в его размер и массу.
    val org0 = organismCount
    appendOrganisms(n0)
    sleep.append(n, organismOf, n0, org0, organismCount)
    if (born != null) markUnbornEdges(n0)

    // --- свободные клетки и зонды, как buildContacts/buildProbes ---
    for (i in n0 until n) isFree[i] = true
    for (k in lnk0 until lnkCount) { isFree[lnkA[k]] = false; isFree[lnkB[k]] = false }
    val full = 2.0 * Math.PI - PROBE_ANGLE_EPS
    for (t in tri0 until triCount) {
        val a = triA[t]; val b = triB[t]; val cc = triC[t]
        probeAngle[a] += angleAt(a, b, cc); probeAngle[b] += angleAt(b, a, cc); probeAngle[cc] += angleAt(cc, a, b)
    }
    for (i in n0 until n) probe[i] = isFree[i] || probeAngle[i] < full

    // --- пары покоя нового тела: только внутри тела, в его номерах ---
    val addN = n - n0
    val localA = IntArray(addLinks) { body.linkA[l0 + it] - n0 }
    val localB = IntArray(addLinks) { body.linkB[l0 + it] - n0 }
    val localX = FloatArray(addN) { body.x[n0 + it] }; val localY = FloatArray(addN) { body.y[n0 + it] }
    val localR = DoubleArray(addN) { radius[n0 + it] }
    var lcount = 0
    val localConA = IntArray(conCount - con0); val localConB = IntArray(conCount - con0)
    for (k in con0 until conCount) { localConA[lcount] = conA[k] - n0; localConB[lcount] = conB[k] - n0; lcount++ }
    val localLinked = BoundaryContacts.linkedPairs(localConA, localConB, lcount)
    val addPairs = BoundaryContacts.bakeRestPairs(addN,
        BoundaryContacts.maxContactRadius(addN, localA, localB, addLinks, localR, CONTACT_SCALE,
            localX, localY, config.contacts.CONTACT_SEAL), localX, localY, localLinked)
    // Связанные пары мира — в общих номерах (линейный ключ пары, как в linkedPairs).
    val worldConA = IntArray(conCount - con0) { conA[con0 + it] }
    val worldConB = IntArray(conCount - con0) { conB[con0 + it] }
    everLinked = everLinked?.plus(BoundaryContacts.linkedPairs(worldConA, worldConB, worldConA.size))

    // --- контур: поверхность и ограничения контура по новым вершинам ---
    localBoundary.detachBlocks()
    localSurface.grow(body, boneOf, cellDead, radius, isFree, probe)
    localBoundary.grow(body, boneOf)
    for (v in n0 until n) { localSurface.update(v); localBoundary.updateVertex(v) }
    syncBoundaryArrays()
    // Новые вершины обновлены целиком, вместе с пометками нерождённых рёбер.
    if (born != null) baked.clearDirty()
    localComponents = LocalComponents(baked, organismOf, organismSize, organismMass,
        restInvMass, cellDead, flowVX, flowVY, organismCount, sleep)

    // --- контакты ---
    val ct = contacts!!
    ct.grow(n, radius)
    ct.appendRestPairs(addPairs, n0)
    restPairs = ct.restPairs
    ct.attachSurface(localSurface)
    val fresh = IntArray(addN) { n0 + it }
    ct.noContact = cellNoContact
    ct.group = cellGroup
    ct.updateSurface(localSurface, fresh, addN)
    ct.organismOf = organismOf
    if (contactPressure.size != n) contactPressure = DoubleArray(n)
    if (contactPressureN.size != n) contactPressureN = IntArray(n)
    ct.pressure = contactPressure; ct.pressureN = contactPressureN
    dbgSealMinR = ct.minContactRadius
    internalMom = InternalMomentum(n)

    // --- решатель: план связей и блоки по всему миру (дёшево), см. заголовок ---
    distanceSweepOrder = DistanceSweepOrder.create(this)
    constraintBlocks = null
    initializeConstraintBlocks()
    structureVersion++
}

/** Массивы по частицам и по организмам — до нового числа частиц; новые элементы по умолчанию. */
private fun BodyState.growParticleArrays(n0: Int) {
    cellDead = cellDead.copyOf(n); cellUnborn = cellUnborn.copyOf(n)
    cellScale = cellScale.copyOf(n).also { java.util.Arrays.fill(it, n0, n, 1f) }
    cellScaleTarget = cellScaleTarget.copyOf(n).also { java.util.Arrays.fill(it, n0, n, 1f) }
    cellSoftness = cellSoftness.copyOf(n).also { java.util.Arrays.fill(it, n0, n, 1f) }
    cellNoContact = cellNoContact.copyOf(n)
    cellGroup = cellGroup.copyOf(n)
    cellGrip = cellGrip.copyOf(n)
    cellHomeCompliance = cellHomeCompliance.copyOf(n); cellHomeX = cellHomeX.copyOf(n); cellHomeY = cellHomeY.copyOf(n)
    dragScale = dragScale.copyOf(n).also { java.util.Arrays.fill(it, n0, n, 1.0) }
    px = px.copyOf(n); py = py.copyOf(n); prevX = prevX.copyOf(n); prevY = prevY.copyOf(n)
    vx = vx.copyOf(n); vy = vy.copyOf(n)
    invMass = invMass.copyOf(n); restInvMass = restInvMass.copyOf(n); matchWeight = matchWeight.copyOf(n)
    inContact = inContact.copyOf(n)
    boneOf = boneOf.copyOf(n); boneClusterOf = boneClusterOf.copyOf(n); muscleOf = muscleOf.copyOf(n)
    radius = radius.copyOf(n); isFree = isFree.copyOf(n)
    probe = probe.copyOf(n); probeAngle = probeAngle.copyOf(n)
    crushedCell = crushedCell.copyOf(n)
    if (trapTicks.size == n0) trapTicks = trapTicks.copyOf(n)
    if (trapOrg.size == n0) trapOrg = trapOrg.copyOf(n).also { java.util.Arrays.fill(it, n0, n, -1) }
    if (foldTicks.size == n0) foldTicks = foldTicks.copyOf(n)
    if (sleepAnchorX.size >= n0) {
        sleepAnchorX = sleepAnchorX.copyOf(n); sleepAnchorY = sleepAnchorY.copyOf(n)
        orgSleepDrift2 = orgSleepDrift2.copyOf(n)
    }
    // По организмам: ёмкость по числу частиц, как в buildOrganisms.
    organismOf = organismOf.copyOf(n); orgQueue = IntArray(n)
    if (orgPrev.size >= n0) { orgPrev = orgPrev.copyOf(n); orgSeed = orgSeed.copyOf(n) }
    organismSize = organismSize.copyOf(n); organismMass = organismMass.copyOf(n)
    flowVX = flowVX.copyOf(n); flowVY = flowVY.copyOf(n)
    comAccX = comAccX.copyOf(n); comAccY = comAccY.copyOf(n)
    if (orgFlowX.size >= n0) { orgFlowX = orgFlowX.copyOf(n); orgFlowY = orgFlowY.copyOf(n) }
}

/** Поза покоя кости [b] — те же шаги, что buildBoneRestPose для одного кластера. */
internal fun BodyState.restPoseOfBone(b: Int) {
    val ids = rigidBones[b]
    val qx = boneRestQx[b]; val qy = boneRestQy[b]
    var cx = 0.0; var cy = 0.0
    for (i in ids) { cx += body.x[i]; cy += body.y[i] }
    cx /= ids.size; cy /= ids.size
    for (k in ids.indices) {
        qx[k] = body.x[ids[k]] - cx
        qy[k] = body.y[ids[k]] - cy
    }
    for (pass in 0 until 3) {
        var sx = 0.0; var sy = 0.0
        for (k in ids.indices) { sx += qx[k]; sy += qy[k] }
        if (sx == 0.0 && sy == 0.0) break
        val dx = sx / ids.size; val dy = sy / ids.size
        for (k in ids.indices) { qx[k] -= dx; qy[k] -= dy }
    }
}

/**
 * Организмы новых частиц [n0, n): связные компоненты по живым рёбрам, номера — после
 * прежних, в порядке первой частицы — как buildOrganisms на целом мире. Размер и масса —
 * по позе покоя, запас среды нулевой.
 */
private fun BodyState.appendOrganisms(n0: Int) {
    val vs = baked.vertStart; val vo = baked.vertOther; val ve = baked.vertEdge
    val dead = baked.edgeDead
    java.util.Arrays.fill(organismOf, n0, n, -1)
    var comp = organismCount
    val queue = orgQueue
    for (s in n0 until n) {
        if (organismOf[s] != -1) continue
        var head = 0; var tail = 0
        organismOf[s] = comp; queue[tail++] = s
        while (head < tail) {
            val v = queue[head++]
            for (k in vs[v] until vs[v + 1]) {
                if (dead[ve[k]]) continue
                val u = vo[k]
                if (organismOf[u] == -1) { organismOf[u] = comp; queue[tail++] = u }
            }
        }
        organismSize[comp] = 0; organismMass[comp] = 0.0
        flowVX[comp] = 0.0; flowVY[comp] = 0.0; comAccX[comp] = 0.0; comAccY[comp] = 0.0
        comp++
    }
    for (i in n0 until n) if (!cellDead[i]) organismSize[organismOf[i]]++
    for (i in n0 until n) if (!cellDead[i] && restInvMass[i] > 0.0) organismMass[organismOf[i]] += 1.0 / restInvMass[i]
    organismCount = comp
}

/**
 * УДАЛИТЬ ТЕЛО — гибель на месте (этап A2.3). Частицы [first, first + count) замирают
 * (invMass = 0) и выходят из контактов, их рёбра гаснут, треугольники умирают, связи
 * выходят из решателя, контур закрывается — тем же путём, что гибель клетки, но без
 * деления организма на куски на каждом ребре: организм исчезает целиком. Номера частиц
 * остаются за мёртвыми; их повторная выдача — следующий шаг (новое тело пока всегда
 * дописывается после всех прежних, иначе ломается порядок рёбер).
 */
internal fun BodyState.removeBody(first: Int, count: Int) {
    val end = first + count
    if (dragId in first until end) { matchWeight[dragId] = 1.0; dragId = -1 }
    for (v in first until end) {
        if (cellDead[v]) continue
        cellDead[v] = true; deadCount++
        vx[v] = 0.0; vy[v] = 0.0; invMass[v] = 0.0
        localComponents.removeCell(v)
    }
    for (v in first until end) {
        // Нерождённая клетка удалённого тела уже не родится.
        cellUnborn[v] = false
        for (s in baked.vertStart[v] until baked.vertStart[v + 1]) {
            val e = baked.vertEdge[s]
            if (baked.isUnborn(e)) { retireUnbornEdge(e); continue }
            // Связи пары, предел контура, треугольники, очередь пересборки — внутри killEdge.
            killEdge(e, split = false)
        }
    }
    val ct = contacts
    if (ct != null) for (v in first until end) ct.dropFromContacts(v)
    refreshSurface()
    structureVersion++
}
