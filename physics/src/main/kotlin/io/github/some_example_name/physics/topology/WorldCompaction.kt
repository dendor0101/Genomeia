package io.github.some_example_name.physics

/*
 * УПЛОТНЕНИЕ МИРА. Этап A2.3b плана (docs/physics/library-integration-plan-2026-10-25.md).
 *
 * Удалённое тело гибнет на месте (removeBody): его частицы, рёбра и треугольники остаются в
 * массивах мёртвыми, а новое тело всегда дописывается после всех прежних. Уплотнение
 * выбрасывает удалённые тела целиком и сдвигает всё остальное вниз С СОХРАНЕНИЕМ ПОРЯДКА:
 * частицы, рёбра, треугольники, связи, организмы, кости и мышцы оставшихся тел встают
 * подряд в том же порядке, что и были. Поэтому:
 *  - состояние оставшихся тел (разрывы, нерождённые клетки, пластика, дорастание, сон,
 *    запас среды) едет вместе со своими массивами — переносить поле за полем не нужно;
 *  - мир после уплотнения устроен ровно как мир, где удалённых тел не было: описание —
 *    склейка описаний оставшихся тел, рёбра — в том же сортированном порядке.
 *
 * Топология и поверхность тоже фильтруются: их строки и номера идут в порядке частиц и
 * рёбер (BakedTopology.compactFrom, LocalSurface.compactFrom; постройка заново стоила ~80 и
 * ~24 мс на 100k). Пересобираются: контур (в нём жили записи удалённых, порядок после
 * уплотнения канонический), контакты (тот же объект, путь загрузки reconfigure +
 * attachSurface), блоки решателя, план связей, пределы контура.
 *
 * Номера частиц у тел после удалённого меняются: клиент обращается к телу по его номеру
 * (PhysicsWorld.bodyFirst), а не по номеру частицы.
 *
 * Проверка — CompactCheck: мир, где тело удалено и мир уплотнён, побитово совпадает с
 * миром, где этого тела не было и который уплотнился в тот же тик.
 */

/**
 * Выбросить частицы [removed] — удалённые тела целиком (связей между телами нет, поэтому
 * их рёбра, треугольники, связи, кости, мышцы и организмы не задевают остальные).
 * Зовётся между тиками. Стоимость — порядка мира: проход по всем массивам и пересборка
 * производного, как при загрузке, но без запекания пар покоя.
 */
internal fun BodyState.compactWorld(removed: BooleanArray) {
    // Отложенные разрывы удалённого тела (removeBody) доигрываются сейчас: тогда в рабочих
    // списках не остаётся его записей, и очередь разрывов пуста.
    var lapT = System.nanoTime()
    fun lap(k: Int) { val now = System.nanoTime(); dbgCompactNs[k] = now - lapT; lapT = now }
    if (tearsPending) rebuildTimed()
    lap(0)
    val nOld = n
    require(removed.size >= nOld) { "removed — по частице мира" }
    val old = body
    val oldBaked = baked

    // --- карты номеров: новый номер или -1, порядок сохраняется ---
    val pNew = IntArray(nOld)
    var nNew = 0
    for (i in 0 until nOld) pNew[i] = if (removed[i]) -1 else nNew++
    val linkKept = BooleanArray(old.linkCount) { !removed[old.linkA[it]] }
    val triKept = BooleanArray(old.triCount) { !removed[old.triA[it]] }
    val tNew = numbering(triKept)
    val edgeKept = BooleanArray(oldBaked.edgeCount) { !removed[oldBaked.edgeU(it)] }
    val eNew = numbering(edgeKept)
    val muscleKept = BooleanArray(old.muscleClusters.size) { old.muscleClusters[it].isNotEmpty() && !removed[old.muscleClusters[it][0]] }
    val mNew = numbering(muscleKept)
    val boneKept = BooleanArray(rigidBones.size) { !removed[rigidBones[it][0]] }
    val bNew = numbering(boneKept)
    val orgCount = organismCount
    val orgKept = BooleanArray(orgCount) { true }
    for (i in 0 until nOld) if (removed[i]) { val o = organismOf[i]; if (o in 0 until orgCount) orgKept[o] = false }
    val oNew = numbering(orgKept)
    val orgNewCount = oNew.count { it >= 0 }

    val def = old.keep(removed, pNew, nNew, linkKept, triKept, muscleKept)
    lap(1)

    // --- частицы ---
    fun p(a: DoubleArray) = if (a.size < nOld) a else pick(a, pNew, nOld, nNew)
    fun p(a: FloatArray) = if (a.size < nOld) a else pick(a, pNew, nOld, nNew)
    fun p(a: BooleanArray) = if (a.size < nOld) a else pick(a, pNew, nOld, nNew)
    fun p(a: IntArray, values: IntArray? = null) = if (a.size < nOld) a else pick(a, pNew, nOld, nNew, values)
    cellDead = p(cellDead); cellUnborn = p(cellUnborn); cellScale = p(cellScale); dragScale = p(dragScale)
    px = p(px); py = p(py); prevX = p(prevX); prevY = p(prevY); vx = p(vx); vy = p(vy)
    invMass = p(invMass); restInvMass = p(restInvMass); matchWeight = p(matchWeight)
    inContact = p(inContact); radius = p(radius); isFree = p(isFree)
    probe = p(probe); probeAngle = p(probeAngle); crushedCell = p(crushedCell)
    boneOf = p(boneOf, bNew); muscleOf = p(muscleOf, mNew); organismOf = p(organismOf, oNew)
    trapTicks = p(trapTicks); trapOrg = p(trapOrg, oNew); foldTicks = p(foldTicks)
    sleepAnchorX = p(sleepAnchorX); sleepAnchorY = p(sleepAnchorY)
    tickJitterX = p(tickJitterX); tickJitterY = p(tickJitterY)
    contactPressure = p(contactPressure); contactPressureN = p(contactPressureN)
    dbgOnly = dbgOnly?.let { p(it) }
    // Снимки разбора берутся заново в начале тика; сетка ловушек — черновик.
    dbgSnapX = DoubleArray(0); dbgSnapY = DoubleArray(0); dbgSnapVx = DoubleArray(0); dbgSnapVy = DoubleArray(0)
    orgQueue = IntArray(nNew)
    if (dragId >= 0) dragId = pNew[dragId]
    if (watchCell in 0 until nOld) watchCell = pNew[watchCell]
    trapCrashCell = -1; trapCrashTri = -1

    // --- организмы: ёмкость по числу частиц, как при загрузке ---
    fun o(a: DoubleArray) = pick(a, oNew, orgCount, nNew)
    organismSize = pick(organismSize, oNew, orgCount, nNew); organismMass = o(organismMass)
    flowVX = o(flowVX); flowVY = o(flowVY)
    if (orgSleepDrift2.size >= orgCount) orgSleepDrift2 = o(orgSleepDrift2)
    comAccX = DoubleArray(nNew); comAccY = DoubleArray(nNew)
    if (orgPrev.isNotEmpty()) { orgPrev = IntArray(nNew); orgSeed = IntArray(nNew) }
    if (orgFlowX.isNotEmpty()) { orgFlowX = DoubleArray(nNew); orgFlowY = DoubleArray(nNew) }
    organismCount = orgNewCount

    // --- кости и мышцы ---
    val bones = ArrayList<IntArray>(); val qx = ArrayList<DoubleArray>(); val qy = ArrayList<DoubleArray>()
    for (b in rigidBones.indices) if (boneKept[b]) {
        bones.add(IntArray(rigidBones[b].size) { pNew[rigidBones[b][it]] }); qx.add(boneRestQx[b]); qy.add(boneRestQy[b])
    }
    rigidBones = bones.toTypedArray(); boneRestQx = qx.toTypedArray(); boneRestQy = qy.toTypedArray()
    degenerateBones = def.boneClusters.count { it.size < 3 }
    val mc = old.muscleClusters.size
    val mcNew = def.muscleClusters.size
    muscleActivation = pick(muscleActivation, mNew, mc, mcNew)
    muscleTarget = pick(muscleTarget, mNew, mc, mcNew)
    muscleScaleOf = pick(muscleScaleOf, mNew, mc, mcNew).also { if (muscleScaleOf.size < mc) it.fill(1.0) }
    if (muscleActivationPrev.size >= mc) muscleActivationPrev = pick(muscleActivationPrev, mNew, mc, mcNew)

    // --- рабочие списки: ёмкость по полному описанию, как при загрузке, хвост пуст ---
    compactLinks(pNew, eNew, def.linkCount)
    compactConstraints(pNew, eNew, mNew, def.linkCount)
    compactTriangleList(pNew, tNew, mNew, def.triCount)
    if (triGrowTarget.isNotEmpty()) triGrowTarget = pick(triGrowTarget, tNew, minOf(old.triCount, triGrowTarget.size), def.triCount)
    var m = 0
    for (k in 0 until growEdgeN) { val e = eNew[growEdges[k]]; if (e >= 0) growEdges[m++] = e }
    growEdgeN = m
    m = 0
    for (k in 0 until growTriN) { val t = tNew[growTris[k]]; if (t >= 0) growTris[m++] = t }
    growTriN = m
    // Хвосты очередей за их длиной — устаревшие номера прежней раскладки: обнулить.
    java.util.Arrays.fill(growEdges, growEdgeN, growEdges.size, 0)
    java.util.Arrays.fill(growTris, growTriN, growTris.size, 0)
    tearQueueN = 0
    java.util.Arrays.fill(tearQueue, 0)

    lap(2)
    // --- описание и топология ---
    body = def
    n = nNew
    baked = BakedTopology(def, build = false).also { it.compactFrom(oldBaked, pNew, eNew, tNew, linkKept) }
    lap(3)
    restPairs = restPairs?.keep(pNew, nNew)
    sleep.compact(oNew, orgCount, pNew, n)
    rebuildDerived(localSurface, pNew, ::lap)
}

/**
 * ПРОИЗВОДНОЕ ПО ЯДРУ — общий путь уплотнения и загрузки снимка (WorldSnapshot). Ядро —
 * описание, частицы, рабочие списки, смерть и нерождённость рёбер и треугольников,
 * организмы, сон, кости, мышцы — уже на месте; здесь заново: разметка связей по рёбрам,
 * поверхность и контур, компоненты, пределы контура, контакты (resize + путь загрузки
 * reconfigure), план связей и блоки решателя. Порядок всего этого канонический: одно и то
 * же ядро даёт один и тот же мир, откуда бы оно ни пришло.
 *
 * [oldSurface] с [pNew] — строки поверхности переезжают фильтром (уплотнение); null —
 * поверхность считается заново по всем клеткам (загрузка; CompactCheck сверяет, что это
 * одно и то же).
 */
internal fun BodyState.rebuildDerived(oldSurface: LocalSurface?, pNew: IntArray?, lap: (Int) -> Unit = {}) {
    fractureOrientation = FractureOrientation(body, baked)
    conOfEdge = IntArray(baked.edgeCount).also { java.util.Arrays.fill(it, -1) }
    conNextForEdge = IntArray(conA.size)
    for (c in 0 until conCount) {
        conNextForEdge[c] = conOfEdge[conEdge[c]]
        conOfEdge[conEdge[c]] = c
    }
    // Когда-либо связанные пары — функция описания: все связи без внутрикостных, как при
    // загрузке (fillConstraints). Ключ пары однозначно не раскладывается при миллионе
    // частиц, поэтому не переводится, а строится заново.
    if (everLinked != null) everLinked = everLinkedOf(body)
    deadCount = 0
    for (i in 0 until n) if (cellDead[i] && !cellUnborn[i]) deadCount++

    // Поверхность — фильтром (её строки — строки топологии) или заново; контур — заново:
    // его порядок канонический, как после загрузки (в нём жили записи удалённых тел).
    val surfaceFrom = oldSurface
    localSurface = LocalSurface(body, baked, boneOf, cellDead, radius, CONTACT_SCALE,
        config.contacts.CONTACT_SEAL, PROBE_ANGLE_EPS, isFree, probe, probeOn)
    if (surfaceFrom != null) localSurface.compactFrom(surfaceFrom, pNew!!) else for (v in 0 until n) localSurface.update(v)
    localBoundary = LocalBoundaryConstraints(body, baked, boneOf)
    // Клетке без живого ребра на одном треугольнике или без треугольников updateVertex
    // ничего не добавит — ни ребра контура, ни изгиба, ни лоскута; пропуск не меняет ни
    // состава, ни порядка. Внутренних клеток — почти все.
    val vs = baked.vertStart; val ve = baked.vertEdge; val use = baked.triUse; val edgeDead = baked.edgeDead
    for (v in 0 until n) {
        var surface = false
        for (s in vs[v] until vs[v + 1]) { val e = ve[s]; if (!edgeDead[e] && use[e] <= 1) { surface = true; break } }
        if (surface) localBoundary.updateVertex(v)
    }
    syncBoundaryArrays()
    baked.clearDirty()
    lap(4)
    localComponents = LocalComponents(baked, organismOf, organismSize, organismMass,
        restInvMass, cellDead, flowVX, flowVY, organismCount, sleep)
    for (c in 0 until conCount) {
        conBoundary[c] = !baked.edgeDead[conEdge[c]] && use[conEdge[c]] <= 1
        conTendon[c] = (muscleOf[conA[c]] >= 0) != (muscleOf[conB[c]] >= 0)
    }
    rebuildBoundaryLinkLimits()
    lap(5)
    val ct = contacts
    if (ct != null) {
        ct.resize(n, radius)
        ct.reconfigure(conA, conB, conCount, boundA, boundB, boundCount, boundOpp,
            body.x, body.y, probeCells(), cellDead, restPairs, everLinked)
        ct.attachSurface(localSurface)
        ct.organismOf = organismOf
        ct.pressure = contactPressure; ct.pressureN = contactPressureN
        dbgSealMinR = ct.minContactRadius
    }
    lap(6)
    // Множители связей и треугольников — по нынешним множителям клеток (списки новые).
    conCellScale = FloatArray(conA.size) { 1f }; triCellScale = FloatArray(triA.size) { 1f }
    recomputeCellScales()
    internalMom = InternalMomentum(n)
    distanceSweepOrder = DistanceSweepOrder.create(this)
    constraintBlocks = null
    initializeConstraintBlocks()
    structureVersion++
    lap(7)
}

/** Новые номера оставшихся по порядку, -1 — выброшенным. */
private fun numbering(kept: BooleanArray): IntArray {
    var k = 0
    return IntArray(kept.size) { if (kept[it]) k++ else -1 }
}

private fun pick(a: DoubleArray, map: IntArray, count: Int, cap: Int): DoubleArray {
    val r = DoubleArray(cap)
    for (i in 0 until minOf(count, a.size)) { val j = map[i]; if (j >= 0) r[j] = a[i] }
    return r
}

private fun pick(a: FloatArray, map: IntArray, count: Int, cap: Int): FloatArray {
    val r = FloatArray(cap)
    for (i in 0 until minOf(count, a.size)) { val j = map[i]; if (j >= 0) r[j] = a[i] }
    return r
}

private fun pick(a: BooleanArray, map: IntArray, count: Int, cap: Int): BooleanArray {
    val r = BooleanArray(cap)
    for (i in 0 until minOf(count, a.size)) { val j = map[i]; if (j >= 0) r[j] = a[i] }
    return r
}

/** То же для номеров; [values] — карта самих значений (номер организма, кости, мышцы), -1 остаётся -1. */
private fun pick(a: IntArray, map: IntArray, count: Int, cap: Int, values: IntArray? = null): IntArray {
    val r = IntArray(cap)
    for (i in 0 until minOf(count, a.size)) {
        val j = map[i]
        if (j < 0) continue
        val v = a[i]
        r[j] = if (values == null || v < 0) v else values[v]
    }
    return r
}

/** Связи рабочего списка оставшихся тел в прежнем порядке, с новыми номерами частиц и рёбер. */
private fun BodyState.compactLinks(pNew: IntArray, eNew: IntArray, cap: Int) {
    val a = IntArray(cap); val b = IntArray(cap); val e = IntArray(cap)
    var m = 0
    for (k in 0 until lnkCount) {
        val i = pNew[lnkA[k]]
        if (i < 0) continue
        a[m] = i; b[m] = pNew[lnkB[k]]; e[m] = eNew[lnkEdge[k]]; m++
    }
    lnkA = a; lnkB = b; lnkEdge = e; lnkCount = m
}

/** Связи решателя оставшихся тел: длина покоя, пластика, смерть и дорастание едут с ними. */
private fun BodyState.compactConstraints(pNew: IntArray, eNew: IntArray, mNew: IntArray, cap: Int) {
    val a = IntArray(cap); val b = IntArray(cap); val rest = DoubleArray(cap)
    val mus = IntArray(cap); val edge = IntArray(cap)
    val dead = BooleanArray(cap); val torn = BooleanArray(cap)
    val hold = if (conHold.isNotEmpty()) ByteArray(cap) else conHold
    val grow = if (conGrowTarget.isNotEmpty()) DoubleArray(cap) else conGrowTarget
    var m = 0
    for (c in 0 until conCount) {
        val i = pNew[conA[c]]
        if (i < 0) continue
        a[m] = i; b[m] = pNew[conB[c]]; rest[m] = conRest[c]
        mus[m] = if (conMuscle[c] < 0) conMuscle[c] else mNew[conMuscle[c]]
        edge[m] = eNew[conEdge[c]]; dead[m] = conDead[c]; torn[m] = linkTorn[c]
        if (hold.isNotEmpty() && c < conHold.size) hold[m] = conHold[c]
        if (grow.isNotEmpty() && c < conGrowTarget.size) grow[m] = conGrowTarget[c]
        m++
    }
    conA = a; conB = b; conRest = rest; conMuscle = mus; conEdge = edge
    conDead = dead; linkTorn = torn; conHold = hold; conGrowTarget = grow
    conBoundary = BooleanArray(cap); conTendon = BooleanArray(cap)
    conCount = m
}

/** Треугольники рабочего списка оставшихся тел: площадь покоя, инверсия и пластика едут с ними. */
private fun BodyState.compactTriangleList(pNew: IntArray, tNew: IntArray, mNew: IntArray, cap: Int) {
    val a = IntArray(cap); val b = IntArray(cap); val c = IntArray(cap)
    val area = FloatArray(cap); val id = IntArray(cap); val mus = IntArray(cap); val inv = BooleanArray(cap)
    val hold = if (triHold.isNotEmpty()) ByteArray(cap) else triHold
    var m = 0
    for (t in 0 until triCount) {
        val i = pNew[triA[t]]
        if (i < 0) continue
        a[m] = i; b[m] = pNew[triB[t]]; c[m] = pNew[triC[t]]
        area[m] = triRestArea2[t]; id[m] = tNew[triId[t]]
        mus[m] = if (triMuscle[t] < 0) triMuscle[t] else mNew[triMuscle[t]]
        inv[m] = triInverted[t]
        if (hold.isNotEmpty() && t < triHold.size) hold[m] = triHold[t]
        m++
    }
    triA = a; triB = b; triC = c; triRestArea2 = area; triId = id; triMuscle = mus; triInverted = inv
    triHold = hold
    triCount = m
}

/**
 * Описание оставшихся тел: частицы, связи, треугольники и кластеры в прежнем порядке, с
 * новыми номерами частиц. Это ровно склейка описаний оставшихся тел (concat).
 */
private fun BodyDefinition.keep(removed: BooleanArray, pNew: IntArray, nNew: Int, linkKept: BooleanArray,
                                triKept: BooleanArray, muscleKept: BooleanArray): BodyDefinition {
    val idx = IntArray(nNew); var k = 0
    for (i in 0 until count) if (!removed[i]) idx[k++] = i
    val links = IntArray(linkKept.count { it }); k = 0
    for (l in 0 until linkCount) if (linkKept[l]) links[k++] = l
    val tris = IntArray(triKept.count { it }); k = 0
    for (t in 0 until triCount) if (triKept[t]) tris[k++] = t
    fun remap(c: IntArray) = IntArray(c.size) { pNew[c[it]] }
    val bones = boneClusters.filter { it.isNotEmpty() && !removed[it[0]] }.map(::remap).toTypedArray()
    val muscles = muscleClusters.indices.filter { muscleKept[it] }.map { remap(muscleClusters[it]) }.toTypedArray()
    return BodyDefinition(
        x = FloatArray(nNew) { x[idx[it]] }, y = FloatArray(nNew) { y[idx[it]] },
        radius = FloatArray(nNew) { radius[idx[it]] },
        isBone = BooleanArray(nNew) { isBone[idx[it]] }, isMuscle = BooleanArray(nNew) { isMuscle[idx[it]] },
        linkA = IntArray(links.size) { pNew[linkA[links[it]]] }, linkB = IntArray(links.size) { pNew[linkB[links[it]]] },
        triA = IntArray(tris.size) { pNew[triA[tris[it]]] }, triB = IntArray(tris.size) { pNew[triB[tris[it]]] },
        triC = IntArray(tris.size) { pNew[triC[tris[it]]] },
        triRestArea2 = FloatArray(tris.size) { triRestArea2[tris[it]] },
        boneClusters = bones, muscleClusters = muscles, scale = scale,
    )
}

/** Пары связей описания без внутрикостных — как everLinked при загрузке. */
internal fun BodyState.everLinkedOf(def: BodyDefinition): LinkedPairs {
    val a = IntArray(def.linkCount); val b = IntArray(def.linkCount)
    var m = 0
    for (l in 0 until def.linkCount) {
        val i = def.linkA[l]; val j = def.linkB[l]
        if (boneOf[i] != -1 && boneOf[i] == boneOf[j]) continue
        a[m] = i; b[m] = j; m++
    }
    return BoundaryContacts.linkedPairs(a, b, m)
}

/**
 * Пары покоя оставшихся частиц: строки по частице в прежнем порядке, с новыми номерами.
 * Пары покоя бывают только внутри тела, поэтому строка оставшейся частицы целиком остаётся.
 */
private fun RestPairs.keep(pNew: IntArray, nNew: Int): RestPairs {
    val nOld = start.size - 1
    val sNew = IntArray(other.size) { -1 }
    val st = IntArray(nNew + 1)
    var total = 0
    for (i in 0 until nOld) {
        val j = pNew[i]
        if (j < 0) continue
        for (s in start[i] until start[i + 1]) if (pNew[other[s]] >= 0) sNew[s] = total++
        st[j + 1] = total
    }
    val o = IntArray(total); val mi = IntArray(total)
    val dd = DoubleArray(total); val di = DoubleArray(total); val ev = BooleanArray(total)
    for (s in other.indices) {
        val k = sNew[s]
        if (k < 0) continue
        o[k] = pNew[other[s]]; mi[k] = sNew[mirror[s]]; dd[k] = d2[s]; di[k] = dist[s]; ev[k] = ever[s]
    }
    return RestPairs(st, o, mi, dd, di, ev)
}
