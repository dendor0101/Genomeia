package io.github.some_example_name.physics

/**
 * ОСТРОВА НА ВОРКЕРАХ (вторая и третья вехи, 25.11.2026; docs/physics/islands.md).
 *
 * Остров (TickScope.kt) — тело, которое за тик не может коснуться никого снаружи. Его подшаги считает один воркер
 * целиком, острова идут параллельно, мир — после них, как прежде, с воркерами по блокам. Первая веха доказала, что
 * подшаги по областям дают ту же сумму мира, что и без них; здесь те же подшаги идут одновременно.
 *
 * ТЕНЬ. Воркер считает остров на мелкой копии состояния мира (BodyState.shallowCopy): все массивы — те же ссылки
 * (остров пишет только свои частицы, связи, треугольники, кости и организмы), а всё, что не по номеру сущности, —
 * своё: раскладка блоков с областью и очередью пределов (ConstraintBlocks.islandView), диапазоны частиц
 * (BodySleep.islandView), список слияний учёта импульса (TickMomentum.islandView), касания со своими списками пар и
 * сеткой (BoundaryContacts.islandFork), черновики кости, рамки и касаний скоростей, множители мышц-кластеров (их
 * updateMuscleScale пишет все разом, по доле своего подшага), списки плавности мышц и роста — только записи острова.
 * Код стадий тот же самый, что у мира в одном потоке: тень без воркеров. Тень на воркер одна на тик, острова на ней
 * идут по очереди; между островами сбрасывается только то, что остров начинает с фазы мира (обход связей и касаний).
 *
 * СОБЫТИЯ. Тень не исполняет событий топологии: разрыв и раздавливание связи, гибель клетки, пробой, пробуждение
 * соседа трогают общие списки мира. Перед событием тень бросает [IslandAbort], остров восстанавливается из снимка
 * начала тика ([IslandKit.snapshot]) и идёт подшагами мира вместе с ним — так же, как шёл бы без островов. Снимок —
 * то, что подшаги острова меняют до последнего места события: частицы, давление касаний, учёт импульса, запас среды.
 *
 * ЧЕГО ТЕНЬ НЕ ДЕЛАЕТ. Не трогает чужих наборов, организмов и вершин; не меняет счётчиков мира, кроме своих (их
 * складывает [mergeIslandKits]); метки на общих массивах вершин (касания) и частиц (касания скоростей) продолжаются от
 * меток мира, а мир после островов берёт наибольшую — старая метка у вершины с новой совпасть не может.
 */
internal object IslandAbort : RuntimeException(null, null, false, false) {
    private fun readResolve(): Any = IslandAbort
}

/** Событие топологии в тени острова: остров откатывается и идёт с миром. Без стека — только флаг. */
internal fun islandAbort(): Nothing = throw IslandAbort

/**
 * Списки по островам подряд: остров k — items[start[k] until start[k + 1]]. Два способа наполнения: по счёту
 * ([begin], [count] на каждый элемент, [seal], затем [add] в том же порядке) — для списков, собираемых одним проходом
 * по миру; дописыванием ([beginAppend], [push], [close] по островам по порядку) — для собираемых по острову.
 */
internal class IslandLists {
    var start = IntArray(2); private set
    var items = IntArray(16); private set
    private var cursor = IntArray(1)
    private var groups = 0
    private var length = 0

    fun from(k: Int) = start[k]
    fun to(k: Int) = start[k + 1]

    fun begin(groups: Int) {
        this.groups = groups
        if (start.size < groups + 1) start = IntArray(maxOf(groups + 1, start.size * 2))
        java.util.Arrays.fill(start, 0, groups + 1, 0)
        if (cursor.size < groups) cursor = IntArray(maxOf(groups, cursor.size * 2))
    }

    fun count(k: Int) { start[k + 1]++ }

    fun seal() {
        for (g in 0 until groups) start[g + 1] += start[g]
        val total = start[groups]
        if (items.size < total) items = IntArray(maxOf(total, items.size * 2))
        System.arraycopy(start, 0, cursor, 0, groups)
    }

    fun add(k: Int, v: Int) { items[cursor[k]++] = v }

    /** По счёту из готовых областей: элемент x (0 until n) — в список острова scopes[x], если он остров. */
    fun fill(scopes: IntArray, n: Int, groups: Int) {
        begin(groups)
        for (x in 0 until n) { val k = scopes[x]; if (k >= 0) count(k) }
        seal()
        for (x in 0 until n) { val k = scopes[x]; if (k >= 0) add(k, x) }
    }

    fun beginAppend(groups: Int) {
        this.groups = groups
        if (start.size < groups + 1) start = IntArray(maxOf(groups + 1, start.size * 2))
        start[0] = 0; length = 0
    }

    fun push(v: Int) {
        if (length == items.size) items = items.copyOf(length * 2)
        items[length++] = v
    }

    /** Дописанных при дописывании. */
    val size: Int get() = length

    /** [v], если его ещё нет среди дописанных с места [from] (списки там короткие — по одному на тело). */
    fun pushUnique(v: Int, from: Int) {
        for (x in from until length) if (items[x] == v) return
        push(v)
    }

    /** Дописанные с места [from] — по возрастанию (вставками: их единицы). */
    fun sortTail(from: Int) {
        for (x in from + 1 until length) {
            val v = items[x]; var y = x - 1
            while (y >= from && items[y] > v) { items[y + 1] = items[y]; y-- }
            items[y + 1] = v
        }
    }

    fun close(k: Int) { start[k + 1] = length }
}

/** Списки островов этого тика. Собирает [buildIslandPlan] в главном потоке, воркеры только читают. */
internal class IslandPlan {
    var count = 0
    /** Блоки острова по возрастанию номера. */
    val blocks = IslandLists()
    /** Наборы швов острова по возрастанию: по одному на его тело из нескольких блоков. */
    val seams = IslandLists()
    /** Диапазоны частиц острова: from = spanFrom[x], to = spanTo[x], x в spans[k]. */
    val spans = IslandLists()
    var spanFrom = IntArray(16)
    var spanTo = IntArray(16)
    /** Организмы острова (без повторов). */
    val orgs = IslandLists()
    /** Вершины контура острова в порядке списка вершин мира (BoundaryContacts.vertexAt). */
    val verts = IslandLists()
    /** Кости острова: из списков костей его блоков и швов. */
    val bones = IslandLists()
    /** Номера записей плавности мышц и роста мира, по островам, в порядке списков мира. */
    val lerpEdges = IslandLists()
    val lerpTris = IslandLists()
    val growEdges = IslandLists()
    val growTris = IslandLists()
    /** Частиц острова. */
    var cells = IntArray(1)
    var maxCells = 0
    var maxOrgs = 0
    /** Острова от больших к меньшим: последний кусок у барьера — самый короткий. */
    var order = IntArray(1)
    /** Остров откатился (событие) и идёт с миром. Пишет воркер своего острова. */
    var aborted = BooleanArray(1)
    private var orderKeys = LongArray(1)
    private var orgMark = IntArray(0)
    private var orgStamp = 0
    /** Область каждой записи плавности: [0] мышцы — рёбра, [1] мышцы — треугольники, [2] рост — рёбра, [3] рост — треугольники. */
    private val entryScopes = Array(4) { IntArray(64) }

    /** Области записей списка [list] на [n] записей — массив под них. */
    fun scopeOf(list: Int, n: Int): IntArray {
        if (entryScopes[list].size < n) entryScopes[list] = IntArray(maxOf(n, entryScopes[list].size * 2))
        return entryScopes[list]
    }

    /** Запись [x] списка [list] остаётся миру: её область — мир или откатившийся остров. */
    fun worldEntry(list: Int, x: Int): Boolean { val k = entryScopes[list][x]; return k < 0 || aborted[k] }

    fun reset(count: Int) {
        this.count = count
        if (cells.size < count) cells = IntArray(maxOf(count, cells.size * 2))
        if (order.size < count) { order = IntArray(maxOf(count, order.size * 2)); orderKeys = LongArray(order.size) }
        if (aborted.size < count) aborted = BooleanArray(maxOf(count, aborted.size * 2))
        java.util.Arrays.fill(cells, 0, count, 0)
        java.util.Arrays.fill(aborted, 0, count, false)
        maxCells = 0; maxOrgs = 0
    }

    /** Диапазоны дописываются подряд: номер диапазона и есть его место в [spans], from/to — в [spanFrom]/[spanTo]. */
    private var spanN = 0

    fun beginSpans() { spans.beginAppend(count); spanN = 0 }

    fun addSpan(from: Int, to: Int) {
        if (spanN == spanFrom.size) { spanFrom = spanFrom.copyOf(spanN * 2); spanTo = spanTo.copyOf(spanN * 2) }
        spanFrom[spanN] = from; spanTo[spanN] = to
        spans.push(spanN); spanN++
    }

    /** Пометки организмов на [organismCount] — до первого острова тика. */
    fun ensureOrgs(organismCount: Int) {
        if (orgMark.size < organismCount) { orgMark = IntArray(maxOf(organismCount, orgMark.size * 2)); orgStamp = 0 }
    }

    /** Пометка организма для списка острова: true — впервые в этом острове. */
    fun markOrg(o: Int): Boolean {
        if (orgMark[o] == orgStamp) return false
        orgMark[o] = orgStamp
        return true
    }

    /** Новый остров: новая метка (пометки прошлых островов ей не равны). */
    fun nextOrgStamp() {
        if (orgStamp == Int.MAX_VALUE) { java.util.Arrays.fill(orgMark, 0); orgStamp = 0 }
        orgStamp++
    }

    fun sortOrder() {
        for (k in 0 until count) orderKeys[k] = ((Int.MAX_VALUE - cells[k]).toLong() shl 32) or k.toLong()
        java.util.Arrays.sort(orderKeys, 0, count)
        for (x in 0 until count) order[x] = orderKeys[x].toInt()
    }
}

/**
 * Списки островов: блоки, швы, диапазоны, организмы, вершины, кости, записи плавности; порядок раздачи. Зовётся
 * из partitionIslands, когда области уже расставлены. Один проход по блокам, частицам островов, вершинам контура и
 * спискам плавности — O(мира) один раз за тик, не на остров.
 */
internal fun BodyState.buildIslandPlan(layout: ConstraintBlocks, isl: IslandScratch, count: Int) {
    val plan = islandPlan
    plan.reset(count)
    val fp = layout.firstParticle
    // Блоки и шов.
    val bl = plan.blocks
    bl.begin(count)
    for (b in 0 until layout.blockCount) { val k = isl.island[layout.bodyRunOf[b]]; if (k >= 0) bl.count(k) }
    bl.seal()
    for (b in 0 until layout.blockCount) {
        val r = layout.bodyRunOf[b]
        val k = isl.island[r]
        if (k < 0) continue
        bl.add(k, b)
        plan.cells[k] += fp[b + 1] - fp[b]
    }
    // Диапазоны (блоки подряд — один диапазон), организмы, швы (свой у каждого тела острова из нескольких блоков), кости.
    plan.beginSpans()
    plan.ensureOrgs(organismCount)
    plan.orgs.beginAppend(count)
    plan.seams.beginAppend(count)
    plan.bones.beginAppend(count)
    for (k in 0 until count) {
        var open = -1; var end = -1
        plan.nextOrgStamp()
        var orgs = 0
        val seamFrom = plan.seams.size
        for (x in bl.from(k) until bl.to(k)) {
            val b = bl.items[x]
            val a = fp[b]; val z = fp[b + 1]
            if (open >= 0 && a == end) end = z
            else { if (open >= 0) plan.addSpan(open, end); open = a; end = z }
            for (i in a until z) {
                val o = organismOf[i]
                if (plan.markOrg(o)) { plan.orgs.push(o); orgs++ }
            }
            var bone = layout.bones.head[b]
            while (bone >= 0) { plan.bones.push(bone); bone = layout.bones.next[bone] }
            if (isl.blocks[layout.bodyRunOf[b]] > 1) plan.seams.pushUnique(layout.seamBucket[b], seamFrom)
        }
        if (open >= 0) plan.addSpan(open, end)
        plan.seams.sortTail(seamFrom)
        for (x in seamFrom until plan.seams.size) {
            var bone = layout.bones.head[plan.seams.items[x]]
            while (bone >= 0) { plan.bones.push(bone); bone = layout.bones.next[bone] }
        }
        plan.spans.close(k); plan.orgs.close(k); plan.seams.close(k); plan.bones.close(k)
        if (plan.cells[k] > plan.maxCells) plan.maxCells = plan.cells[k]
        if (orgs > plan.maxOrgs) plan.maxOrgs = orgs
    }
    // Вершины контура — в порядке списка мира: от него зависит порядок сложения в CCD.
    val ct = contacts
    val vl = plan.verts
    vl.begin(count)
    if (ct != null) {
        val vs = islandVertexScope
        for (a in 0 until ct.vertexCount) { val k = vs[ct.vertexAt(a)]; if (k >= 0) vl.count(k) }
        vl.seal()
        for (a in 0 until ct.vertexCount) { val v = ct.vertexAt(a); val k = vs[v]; if (k >= 0) vl.add(k, v) }
    } else vl.seal()
    // Записи плавности мышц и роста — номера в списках мира, в их порядке. Остров записи — по частице (islandVertexScope:
    // организм целиком в одной области), один раз на запись: на стенде с «дыханием» записей десятки тысяч за тик, и
    // повторный разбор (счёт, раскладка, отбор мира после островов) был заметной долей разбиения (профиль 25.11).
    val vs = islandVertexScope
    val slot = layout.triangleSlot
    val es = plan.scopeOf(0, lerpEdgeN)
    for (x in 0 until lerpEdgeN) es[x] = vs[baked.edgeU(lerpEdgeIds[x])]
    plan.lerpEdges.fill(es, lerpEdgeN, count)
    val ts = plan.scopeOf(1, lerpTriN)
    for (x in 0 until lerpTriN) ts[x] = triScope(slot, lerpTriIds[x], false)
    plan.lerpTris.fill(ts, lerpTriN, count)
    val gs = plan.scopeOf(2, growLerpN)
    for (x in 0 until growLerpN) { val e = growLerpEdge[x]; gs[x] = if (baked.edgeDead[e]) SCOPE_WORLD else vs[baked.edgeU(e)] }
    plan.growEdges.fill(gs, growLerpN, count)
    val gts = plan.scopeOf(3, growLerpTriN)
    for (x in 0 until growLerpTriN) gts[x] = triScope(slot, growLerpTri[x], true)
    plan.growTris.fill(gts, growLerpTriN, count)
    plan.sortOrder()
}

/** Остров треугольника с постоянным номером [id] (живого — [alive]), -1 — мир или мёртвый. */
private fun BodyState.triScope(slot: IntArray, id: Int, alive: Boolean): Int {
    val w = slot[id]
    if (w !in 0 until triCount) return SCOPE_WORLD
    if (alive && !baked.triAlive[id]) return SCOPE_WORLD
    return islandVertexScope[triA[w]]
}

/**
 * Комплект воркера островов: тень состояния на этот тик и её черновики, снимок острова для отката. Создаётся воркером
 * по его номеру и живёт между тиками (черновики не выделяются заново).
 */
internal class IslandKit {
    var tick = -1L
    var shadow: BodyState? = null
    private var layout: ConstraintBlocks? = null
    private var sleep: BodySleep? = null
    private var momentum: TickMomentum? = null
    var contacts: BoundaryContacts? = null; private set
    private val chunks = PassChunks()
    private val comSums = TickMomentum.GroupSums()
    private var wallHits: BoneWallHits? = null
    private var boneDx = FloatArray(0)
    private var boneDy = FloatArray(0)
    private var velTouchList = IntArray(0)
    private var muscleScale = FloatArray(0)
    private var lerpEdgeIds = IntArray(16); private var lerpEdgeFrom = FloatArray(16); private var lerpEdgeTo = FloatArray(16)
    private var lerpTriIds = IntArray(16); private var lerpTriFrom = FloatArray(16); private var lerpTriTo = FloatArray(16)
    private var growEdge = IntArray(16); private var growFrom = FloatArray(16); private var growTo = FloatArray(16)
    private var growTri = IntArray(16); private var growTriFrom = FloatArray(16); private var growTriTo = FloatArray(16)

    // Счётчики тени в начале тика: мир прибавляет разность (mergeIslandKits).
    var speedCapHits0 = 0; var linkCapHits0 = 0; var boneCapHits0 = 0
    var bpBuilds0 = 0L; var bpCalls0 = 0L; var bpMoved0 = 0L; var ccdTotal0 = 0L

    /** Тень на тик [tick]: копия мира со своими черновиками. Воркер зовёт на первом своём острове тика. */
    fun beginTick(main: BodyState, tick: Long) {
        this.tick = tick
        val s = main.shallowCopy()
        s.workers = null; s.parallelPhysicsState = null; s.parallelPhysicsWorkers = 0
        layout = main.constraintBlocks!!.islandView(layout); s.constraintBlocks = layout
        sleep = main.sleep.islandView(sleep); s.sleep = sleep!!
        momentum = main.tickMomentumState?.islandView(momentum); s.tickMomentumState = momentum
        contacts = main.contacts?.islandFork(contacts); s.contacts = contacts
        s.scopeChunkCache = chunks
        s.comSumsScratch = comSums
        val wh = wallHits
        wallHits = if (wh != null && wh.capacity == main.rigidBones.size) wh else BoneWallHits(main.rigidBones.size)
        s.wallHits = wallHits!!
        if (boneDx.size < main.boneDx.size) { boneDx = FloatArray(main.boneDx.size); boneDy = FloatArray(main.boneDx.size) }
        s.boneDx = boneDx; s.boneDy = boneDy
        if (velTouchList.size < main.islandPlan.maxCells) velTouchList = IntArray(maxOf(main.islandPlan.maxCells, velTouchList.size * 2))
        s.velTouchList = velTouchList; s.velTouchN = 0
        if (muscleScale.size != main.muscleScaleOf.size) muscleScale = FloatArray(main.muscleScaleOf.size)
        s.muscleScaleOf = muscleScale
        s.dbgTimeOn = false; s.dbgSubstepHook = null
        s.islandKits = emptyArray(); s.islandRun = this
        speedCapHits0 = s.speedCapHits; linkCapHits0 = s.linkCapHits; boneCapHits0 = s.boneCapHits
        val ct = contacts
        if (ct != null) { bpBuilds0 = ct.bpBuilds; bpCalls0 = ct.bpCalls; bpMoved0 = ct.bpMoved; ccdTotal0 = ct.dbgCcdTotal }
        shadow = s
    }

    /**
     * Остров [k] целиком на тени: настроить её на остров, снять снимок, подшаги. false — событие топологии: остров
     * восстановлен из снимка и пойдёт с миром.
     */
    fun runIsland(main: BodyState, k: Int, h: Float, sweep0: Boolean, ct0: Boolean): Boolean {
        val s = shadow!!
        val plan = main.islandPlan
        layout!!.setIsland(k, plan.blocks.items, plan.blocks.from(k), plan.blocks.to(k),
            plan.seams.items, plan.seams.from(k), plan.seams.to(k))
        val sl = sleep!!
        sl.setIslandSpans(k, plan.spanFrom, plan.spanTo, plan.spans.from(k), plan.spans.to(k))
        sl.setIslandOrgs(plan.orgs.items, plan.orgs.from(k), plan.orgs.to(k))
        // Куски области — сразу: куски мира собраны до островов (buildIslandPlan), и passChunks тени их только читает.
        chunks.restrict(main.passChunkCache, sl.spanCount, sl.spanStart, sl.spanEnd)
        chunks.markBuilt(s.structureVersion, sl.version, s.n, sl.scopeVersion)
        momentum?.resetIslandLinks()
        contacts?.setIsland(k, plan.verts.items, plan.verts.from(k), plan.verts.to(k),
            plan.bones.items, plan.bones.from(k), plan.bones.to(k), ct0)
        gatherLerp(main, s, k)
        s.wallHits.drain { }
        s.velTouchN = 0
        s.sweepBackwards = sweep0
        val speed = s.speedCapHits; val link = s.linkCapHits; val bone = s.boneCapHits
        val peak = s.peakSpeed2; val over = s.dbgMaxOverStrain
        snapshot(main, k, save = true)
        try {
            s.solveSubsteps(h, null)
            if (main.islandsRollbackAll) islandAbort()
            return true
        } catch (e: IslandAbort) {
            snapshot(main, k, save = false)
            s.speedCapHits = speed; s.linkCapHits = link; s.boneCapHits = bone
            s.peakSpeed2 = peak; s.dbgMaxOverStrain = over
            return false
        }
    }

    /** Записи плавности мышц и роста острова [k] — в свои списки тени, в порядке списков мира. */
    private fun gatherLerp(main: BodyState, s: BodyState, k: Int) {
        val plan = main.islandPlan
        var a = plan.lerpEdges.from(k); var n = plan.lerpEdges.to(k) - a
        if (lerpEdgeIds.size < n) { lerpEdgeIds = IntArray(n * 2); lerpEdgeFrom = FloatArray(n * 2); lerpEdgeTo = FloatArray(n * 2) }
        for (x in 0 until n) {
            val q = plan.lerpEdges.items[a + x]
            lerpEdgeIds[x] = main.lerpEdgeIds[q]; lerpEdgeFrom[x] = main.lerpEdgeFrom[q]; lerpEdgeTo[x] = main.lerpEdgeTo[q]
        }
        s.lerpEdgeIds = lerpEdgeIds; s.lerpEdgeFrom = lerpEdgeFrom; s.lerpEdgeTo = lerpEdgeTo; s.lerpEdgeN = n
        a = plan.lerpTris.from(k); n = plan.lerpTris.to(k) - a
        if (lerpTriIds.size < n) { lerpTriIds = IntArray(n * 2); lerpTriFrom = FloatArray(n * 2); lerpTriTo = FloatArray(n * 2) }
        for (x in 0 until n) {
            val q = plan.lerpTris.items[a + x]
            lerpTriIds[x] = main.lerpTriIds[q]; lerpTriFrom[x] = main.lerpTriFrom[q]; lerpTriTo[x] = main.lerpTriTo[q]
        }
        s.lerpTriIds = lerpTriIds; s.lerpTriFrom = lerpTriFrom; s.lerpTriTo = lerpTriTo; s.lerpTriN = n
        a = plan.growEdges.from(k); n = plan.growEdges.to(k) - a
        if (growEdge.size < n) { growEdge = IntArray(n * 2); growFrom = FloatArray(n * 2); growTo = FloatArray(n * 2) }
        for (x in 0 until n) {
            val q = plan.growEdges.items[a + x]
            growEdge[x] = main.growLerpEdge[q]; growFrom[x] = main.growLerpFrom[q]; growTo[x] = main.growLerpTo[q]
        }
        s.growLerpEdge = growEdge; s.growLerpFrom = growFrom; s.growLerpTo = growTo; s.growLerpN = n
        a = plan.growTris.from(k); n = plan.growTris.to(k) - a
        if (growTri.size < n) { growTri = IntArray(n * 2); growTriFrom = FloatArray(n * 2); growTriTo = FloatArray(n * 2) }
        for (x in 0 until n) {
            val q = plan.growTris.items[a + x]
            growTri[x] = main.growLerpTri[q]; growTriFrom[x] = main.growLerpTriFrom[q]; growTriTo[x] = main.growLerpTriTo[q]
        }
        s.growLerpTri = growTri; s.growLerpTriFrom = growTriFrom; s.growLerpTriTo = growTriTo; s.growLerpTriN = n
    }

    // --- СНИМОК ОСТРОВА ДЛЯ ОТКАТА ---
    // Всё, что подшаги острова меняют в общих массивах: частицы (положения, скорости, опора, дрожь тика, место покоя,
    // давление касаний), учёт импульса (цели, снимок скоростей, опора групп, дерево групп), организмы (запас среды,
    // удаление от места покоя, дерево групп организмов). Множители мышц и длины покоя роста не нужны: подшаг мира
    // пишет их заново из списков плавности раньше любого чтения. Рабочие массивы касаний на вершину — тоже: их
    // каждый проход начинает с нуля по меткам.
    private var bufF = Array(14) { FloatArray(0) }
    private var bufD = Array(3) { DoubleArray(0) }
    private var bufI = Array(2) { IntArray(0) }
    private var bufZ = Array(2) { BooleanArray(0) }
    private var orgF = Array(5) { FloatArray(0) }
    private var orgI = IntArray(0)

    private fun snapshot(main: BodyState, k: Int, save: Boolean) {
        val plan = main.islandPlan
        val cells = plan.cells[k]
        if (save && bufF[0].size < cells) {
            val cap = maxOf(cells, plan.maxCells)
            bufF = Array(14) { FloatArray(cap) }; bufD = Array(3) { DoubleArray(cap) }
            bufI = Array(2) { IntArray(cap) }; bufZ = Array(2) { BooleanArray(cap) }
        }
        val n = main.n
        val tm = if (main.cancelSpinOn) main.tickMomentumState else null
        spans(main, k, main.px, bufF[0], save); spans(main, k, main.py, bufF[1], save)
        spans(main, k, main.prevX, bufF[2], save); spans(main, k, main.prevY, bufF[3], save)
        spans(main, k, main.vx, bufF[4], save); spans(main, k, main.vy, bufF[5], save)
        spans(main, k, main.inContact, bufZ[0], save)
        if (main.tickJitterX.size >= n) { spans(main, k, main.tickJitterX, bufF[6], save); spans(main, k, main.tickJitterY, bufF[7], save) }
        if (main.sleepAnchorX.size >= n) { spans(main, k, main.sleepAnchorX, bufF[8], save); spans(main, k, main.sleepAnchorY, bufF[9], save) }
        if (main.contactPressure.size >= n) spans(main, k, main.contactPressure, bufF[10], save)
        if (main.contactPressureN.size >= n) spans(main, k, main.contactPressureN, bufI[0], save)
        if (tm != null && tm.n == n) {
            spans(main, k, tm.targetX, bufD[0], save); spans(main, k, tm.targetY, bufD[1], save)
            spans(main, k, tm.targetL, bufD[2], save)
            spans(main, k, tm.beforeX, bufF[11], save); spans(main, k, tm.beforeY, bufF[12], save)
            spans(main, k, tm.blocked, bufZ[1], save); spans(main, k, tm.parentArray, bufI[1], save)
        }
        // Организмы острова.
        val a = plan.orgs.from(k); val b = plan.orgs.to(k)
        if (save && orgI.size < b - a) {
            val cap = maxOf(b - a, plan.maxOrgs)
            orgF = Array(5) { FloatArray(cap) }; orgI = IntArray(cap)
        }
        val orgArrays = arrayOf(main.comAccX, main.comAccY, main.flowVX, main.flowVY, main.orgSleepDrift2)
        for (q in orgArrays.indices) {
            val arr = orgArrays[q]; val buf = orgF[q]
            for (x in a until b) {
                val o = plan.orgs.items[x]
                if (o >= arr.size) continue
                if (save) buf[x - a] = arr[o] else arr[o] = buf[x - a]
            }
        }
        if (tm != null && tm.n == n) {
            val op = tm.orgParentArray
            for (x in a until b) { val o = plan.orgs.items[x]; if (save) orgI[x - a] = op[o] else op[o] = orgI[x - a] }
        }
    }

    /** Частицы острова [k]: в буфер ([save]) или обратно. Массив любого типа — System.arraycopy. */
    private fun spans(main: BodyState, k: Int, arr: Any, buf: Any, save: Boolean) {
        val plan = main.islandPlan
        var off = 0
        for (x in plan.spans.from(k) until plan.spans.to(k)) {
            val from = plan.spanFrom[x]; val len = plan.spanTo[x] - from
            if (save) System.arraycopy(arr, from, buf, off, len) else System.arraycopy(buf, off, arr, from, len)
            off += len
        }
    }
}

/**
 * Подшаги всех островов тика — по воркерам, острова от больших к меньшим. Возвращает, сколько островов откатилось
 * (событие топологии); их области уже переведены в мир, и подшаги мира их посчитают.
 */
internal fun BodyState.runIslands(h: Float, sweep0: Boolean, tick: Long): Int {
    val plan = islandPlan
    val w = activeWorkers
    val kitCount = if (w != null && w.count > 1) w.count else 1
    if (islandKits.size < kitCount) islandKits = islandKits.copyOf(kitCount)
    val kits = islandKits
    val ct0 = contacts?.ctBackwards ?: false
    val main = this
    val job = PhysicsJob { x, worker ->
        val k = plan.order[x]
        val kit = kits[worker] ?: IslandKit().also { kits[worker] = it }
        if (kit.tick != tick) kit.beginTick(main, tick)
        plan.aborted[k] = !kit.runIsland(main, k, h, sweep0, ct0)
    }
    if (w != null && w.count > 1) w.run(plan.count, job) else for (x in 0 until plan.count) job.run(x, 0)
    mergeIslandKits(tick)
    var aborted = 0
    for (k in 0 until plan.count) if (plan.aborted[k]) { moveIslandToWorld(k); aborted++ }
    islandAborts += aborted
    // Подшаги мира — по его частицам: маленький мир без раздачи по воркерам (см. scopeParticles).
    var worldCells = n
    for (k in 0 until plan.count) if (!plan.aborted[k]) worldCells -= plan.cells[k]
    scopeParticles = worldCells
    islandCellsLastTick = n - worldCells
    contacts?.workers = activeWorkers
    keepWorldLerp()
    return aborted
}

/**
 * Списки плавности мышц и роста мира — только его записи (и откатившихся островов: они теперь мира), в прежнем
 * порядке. Записи островов уже отработали тени по своим копиям. Мир идёт обычным путём, без отбора по области.
 */
private fun BodyState.keepWorldLerp() {
    val plan = islandPlan
    var m = 0
    for (x in 0 until lerpEdgeN) {
        if (!plan.worldEntry(0, x)) continue
        lerpEdgeIds[m] = lerpEdgeIds[x]; lerpEdgeFrom[m] = lerpEdgeFrom[x]; lerpEdgeTo[m] = lerpEdgeTo[x]; m++
    }
    lerpEdgeN = m
    m = 0
    for (x in 0 until lerpTriN) {
        if (!plan.worldEntry(1, x)) continue
        lerpTriIds[m] = lerpTriIds[x]; lerpTriFrom[m] = lerpTriFrom[x]; lerpTriTo[m] = lerpTriTo[x]; m++
    }
    lerpTriN = m
    m = 0
    for (x in 0 until growLerpN) {
        if (!plan.worldEntry(2, x)) continue
        growLerpEdge[m] = growLerpEdge[x]; growLerpFrom[m] = growLerpFrom[x]; growLerpTo[m] = growLerpTo[x]; m++
    }
    growLerpN = m
    m = 0
    for (x in 0 until growLerpTriN) {
        if (!plan.worldEntry(3, x)) continue
        growLerpTri[m] = growLerpTri[x]; growLerpTriFrom[m] = growLerpTriFrom[x]; growLerpTriTo[m] = growLerpTriTo[x]; m++
    }
    growLerpTriN = m
}

/** Счётчики теней — в мир: суммы разностей, наибольшие значения, метки — наибольшие. */
private fun BodyState.mergeIslandKits(tick: Long) {
    for (kit in islandKits) {
        if (kit == null || kit.tick != tick) continue
        val s = kit.shadow ?: continue
        speedCapHits += s.speedCapHits - kit.speedCapHits0
        linkCapHits += s.linkCapHits - kit.linkCapHits0
        boneCapHits += s.boneCapHits - kit.boneCapHits0
        if (s.peakSpeed2 > peakSpeed2) peakSpeed2 = s.peakSpeed2
        if (s.dbgMaxOverStrain > dbgMaxOverStrain) dbgMaxOverStrain = s.dbgMaxOverStrain
        if (s.velTouchPass > velTouchPass) velTouchPass = s.velTouchPass
        val f = kit.contacts
        if (f != null) contacts?.mergeIslandFork(f, kit.bpBuilds0, kit.bpCalls0, kit.bpMoved0, kit.ccdTotal0)
    }
}

/** Откатившийся остров [k] — снова мира: его наборы, организмы и частицы считают подшаги мира. */
private fun BodyState.moveIslandToWorld(k: Int) {
    val plan = islandPlan
    val layout = constraintBlocks!!
    for (x in plan.blocks.from(k) until plan.blocks.to(k)) layout.bucketScope[plan.blocks.items[x]] = SCOPE_WORLD
    for (x in plan.seams.from(k) until plan.seams.to(k)) layout.bucketScope[plan.seams.items[x]] = SCOPE_WORLD
    for (x in plan.orgs.from(k) until plan.orgs.to(k)) islandOrgScope[plan.orgs.items[x]] = SCOPE_WORLD
    for (x in plan.spans.from(k) until plan.spans.to(k)) {
        java.util.Arrays.fill(islandVertexScope, plan.spanFrom[x], plan.spanTo[x], SCOPE_WORLD)
    }
}
