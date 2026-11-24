package io.github.some_example_name.physics

import kotlin.math.sqrt

/**
 * ПАРАЛЛЕЛЬНЫЙ ОБХОД БЛОКОВ — ПОБИТОВО ТОТ ЖЕ, ЧТО ОДНОПОТОЧНЫЙ.
 *
 * Почему это вообще возможно. Ограничение принадлежит блоку, только если все его
 * частицы в этом блоке; остальные — шов, он решается после обеих чётностей в одном
 * потоке. Значит блок пишет и читает только свои частицы, и блоки одной чётности
 * друг друга не видят: в каком порядке и каким воркером их считать — всё равно.
 *
 * Что мешает — СОБЫТИЯ ТОПОЛОГИИ. Разрыв или раздавливание связи в пределе длины
 * (killEdgeNow, refreshSurface) меняют общие структуры: компоненты организмов,
 * очереди разрывов, контур, списки изгиба, сетку контактов. Их порядок входит в
 * результат. Поэтому воркер, дойдя до нарушения, которое даст событие, ничего не
 * пишет и останавливает блок (limitViolationIsEvent). После барьера остановленные
 * блоки дорабатываются здесь же, в исходном порядке блоков, обычным кодом с той же
 * записи очереди (solveConstraintBlockFrom). События идут в том же порядке, что и в
 * одном потоке.
 *
 * Почему доработка ПОСЛЕ более поздних блоков той же чётности ничего не меняет.
 * Событие в блоке A трогает ограничения не дальше двух связей от своих частиц: само
 * ребро и треугольники при нём, затем обновление контура вокруг задетых вершин
 * (изгиб и лоскут — соседи соседей). Если все частицы в двух связях от любой частицы
 * блока лежат в блоках A-1..A+1, событие меняет только A, соседей другой чётности и
 * шов. Соседи другой чётности идут после всей чётности A в обоих порядках, шов — в
 * конце. Проверка при построении блоков (computeSerialBlocks); тело, которое её не
 * прошло, считается в одном потоке, остальные — по воркерам: тела не делят частицы.
 * Новые связи в рантайме не появляются, разрывы условие только ослабляют.
 *
 * Счётчики (потолки поправок, худшее перерастяжение) воркер копит у себя и
 * складывает после барьера: сумма целых и максимум от порядка не зависят.
 * Отладочные снимки по стадиям, наблюдение за клеткой и разбор щелей мембраны
 * требуют одного потока (parallelBlocksAllowed).
 */
internal class ParallelPhysics(private val state: BodyState, workers: Int) {
    private val scratch = Array(workers) { WorkerScratch(state) }
    val muscles = ParallelMuscles(state)
    private var blocks = IntArray(16)
    /** Точка остановки по номеру блока или набора швов; -1 — дорабатывать нечего. */
    private var aborts = LongArray(16) { -1L }
    private var h = 0.0f
    private var integrate = false
    private lateinit var layout: ConstraintBlocks
    private val job = PhysicsJob { k, worker ->
        val block = blocks[k]
        aborts[block] = state.solveBlockParallel(h, block, layout, scratch[worker], integrate)
    }

    private fun ensureBlocks(layout: ConstraintBlocks) {
        if (blocks.size < layout.buckets) { blocks = IntArray(layout.buckets); aborts = LongArray(layout.buckets) { -1L } }
    }

    /** Дорабатывает блок, остановленный перед событием топологии. См. начало файла. */
    private fun finishBlock(block: Int) {
        val a = aborts[block]
        if (a < 0) return
        aborts[block] = -1L
        state.solveConstraintBlockFrom(h, block, layout, (a ushr 32).toInt(), a.toInt())
    }

    fun solveBlocks(h: Float, integrate: Boolean, layout: ConstraintBlocks) {
        this.h = h; this.integrate = integrate; this.layout = layout
        val w = state.activeWorkers!!
        ensureBlocks(layout)
        for (parity in 0..1) {
            var count = 0
            // Спящий блок: все частицы заморожены сном, двигать нечего. См. BodySleep.
            for (block in layout.dispatchOrder[parity]) if (layout.awake[block] > 0 && !layout.serialBlock[block]) blocks[count++] = block
            if (count > 0) { w.run(count, job); mergeCounters() }
            // Раздача — от тяжёлых к лёгким, доработка и блоки тел без параллельного обхода —
            // по порядку блоков: порядок событий тот же, что в одном потоке.
            var block = parity
            while (block < layout.blockCount) {
                if (!layout.serialBlock[block]) finishBlock(block)
                else if (layout.awake[block] > 0) state.solveBlockSerial(h, block, layout, integrate)
                block += 2
            }
        }
        if (integrate && state.boundsOn) {
            for (s in scratch) if (s.wallHits.size != 0) s.wallHits.drain { state.wallHits.add(it) }
        }
    }

    /**
     * Швы тел по воркерам: тела не делят частицы, внутри тела порядок прежний.
     * Частиц своих у шва нет, поэтому интегрировать нечего. Замер 09.11, толпа 100k клеток:
     * швы были 3.6% тика в одном потоке, пока остальные воркеры стояли.
     */
    fun solveSeams(h: Float, layout: ConstraintBlocks) {
        this.h = h; this.integrate = false; this.layout = layout
        ensureBlocks(layout)
        val count = layout.buckets - layout.seam
        for (k in 0 until count) blocks[k] = layout.seam + k
        state.activeWorkers!!.run(count, job)
        mergeCounters()
        for (bucket in layout.seam until layout.buckets) finishBlock(bucket)
    }

    // --- поштучные проходы по скоростям (FusedPasses) ---
    // Каждая частица считается отдельно, поэтому кусок диапазона бодрствующих частиц
    // можно отдать любому воркеру. Потолок скорости копит счётчики у себя.
    private var spanFrom = IntArray(64)
    private var spanTo = IntArray(64)
    private var spanChunks = 0
    private var passH = 0.0f
    private var passLast = false
    private var passHTick = 0.0f
    private val beforeJob = PhysicsJob { k, _ -> state.velocityBeforeRange(spanFrom[k], spanTo[k], passH) }
    private val afterJob = PhysicsJob { k, worker ->
        state.velocityAfterRange(spanFrom[k], spanTo[k], passLast, passHTick, scratch[worker])
    }

    fun velocityBefore(h: Float) {
        passH = h
        state.activeWorkers!!.run(spanChunks(), beforeJob)
    }

    private var touchParts = 1
    private val touchJob = PhysicsJob { k, worker ->
        val n = state.velTouchN
        state.finishVelocityTouchedRange((n.toLong() * k / touchParts).toInt(), (n.toLong() * (k + 1) / touchParts).toInt(), scratch[worker])
    }

    fun finishTouched() {
        touchParts = 4 * scratch.size
        state.activeWorkers!!.run(touchParts, touchJob)
        for (s in scratch) {
            if (s.peakSpeed2 > state.peakSpeed2) state.peakSpeed2 = s.peakSpeed2
            s.peakSpeed2 = 0.0f
            state.speedCapHits += s.speedCapHits; s.speedCapHits = 0
        }
    }

    // Слияние групп импульса по контактам: ключи пар организмов по кускам без повторов, само слияние — здесь.
    private val noteKeys = Array(4 * workers) { LongArray(256) }
    private val noteKeyN = IntArray(4 * workers)
    private val noteRare = Array(4 * workers) { IntArray(16) }
    private val noteRareN = IntArray(4 * workers)
    private var noteCt: BoundaryContacts? = null
    private val noteJob = PhysicsJob { k, _ ->
        val ct = noteCt!!
        val tm = state.tickMomentum; val inv = state.invMass
        val n = ct.contactCount; val parts = noteKeys.size
        var keys = noteKeys[k]; var kn = 0
        var rare = noteRare[k]; var rn = 0
        for (c in (n.toLong() * k / parts).toInt() until (n.toLong() * (k + 1) / parts).toInt()) {
            val key = tm.contactKey(ct.contactI(c), ct.contactJ(c), inv)
            if (key == -1L) continue
            if (key == -2L) { if (rn == rare.size) { rare = rare.copyOf(rn * 2); noteRare[k] = rare }; rare[rn++] = c; continue }
            if (kn == keys.size) { keys = keys.copyOf(kn * 2); noteKeys[k] = keys }
            keys[kn++] = key
        }
        java.util.Arrays.sort(keys, 0, kn)
        var u = 0
        for (x in 0 until kn) if (u == 0 || keys[u - 1] != keys[x]) keys[u++] = keys[x]
        noteKeyN[k] = u; noteRareN[k] = rn
    }

    fun noteContacts(ct: BoundaryContacts) {
        noteCt = ct
        state.activeWorkers!!.run(noteKeys.size, noteJob)
        val tm = state.tickMomentum
        for (k in noteKeys.indices) {
            val keys = noteKeys[k]
            for (x in 0 until noteKeyN[k]) tm.unionKey(keys[x])
            val rare = noteRare[k]
            for (x in 0 until noteRareN[k]) { val c = rare[x]; tm.unionContact(ct.contactI(c), ct.contactJ(c)) }
        }
    }

    private val fastJob = PhysicsJob { k, worker -> state.velocityFastRange(spanFrom[k], spanTo[k], passH, scratch[worker]) }

    fun velocityFast(h: Float) {
        passH = h
        state.activeWorkers!!.run(spanChunks(), fastJob)
        for (s in scratch) {
            if (s.peakSpeed2 > state.peakSpeed2) state.peakSpeed2 = s.peakSpeed2
            s.peakSpeed2 = 0.0f
            state.speedCapHits += s.speedCapHits; s.speedCapHits = 0
        }
    }

    fun velocityAfter(lastStep: Boolean, hTick: Float) {
        passLast = lastStep; passHTick = hTick
        state.activeWorkers!!.run(spanChunks(), afterJob)
        for (s in scratch) {
            if (s.peakSpeed2 > state.peakSpeed2) state.peakSpeed2 = s.peakSpeed2
            s.peakSpeed2 = 0.0f
            state.speedCapHits += s.speedCapHits; s.speedCapHits = 0
        }
    }

    // --- проходы раз в тик: учёт импульса, снятие дрожи, плата за среду ---
    // Каждый кусок пишет только по своим индексам. Суммы в общие ячейки (группы импульса,
    // запас среды организма) остаются в одном потоке: порядок сложения входит в результат.
    // Замер 09.11, толпа 100k клеток: всё вместе было ~6% тика, пока воркеры стояли.
    private val targetsJob = PhysicsJob { k, _ ->
        val st = state
        st.tickMomentum.beginTargets(spanFrom[k], spanTo[k], st.px, st.py, st.vx, st.vy, st.invMass)
    }
    private val clearJob = PhysicsJob { k, _ -> state.tickMomentum.clearRange(spanFrom[k], spanTo[k]) }
    private val centreJob = PhysicsJob { k, _ -> state.tickMomentum.centreRange(spanFrom[k], spanTo[k]) }
    private val spinJob = PhysicsJob { k, _ -> state.tickMomentum.spinRange(spanFrom[k], spanTo[k]) }
    private val applyJob = PhysicsJob { k, _ ->
        val st = state
        st.tickMomentum.applyRange(spanFrom[k], spanTo[k], st.px, st.py, st.vx, st.vy, st.invMass)
    }
    private val removeJitterJob = PhysicsJob { k, _ -> state.removeJitterRange(spanFrom[k], spanTo[k]) }
    private val restoreJitterJob = PhysicsJob { k, _ -> state.restoreJitterRange(spanFrom[k], spanTo[k]) }
    private val flowPaymentJob = PhysicsJob { k, _ -> state.flowPaymentRange(spanFrom[k], spanTo[k]) }

    fun momentumBegin() {
        val st = state
        st.tickMomentum.beginGroups(st.organismOf, st.sleep, st.cellDead)
        st.activeWorkers!!.run(spanChunks(), targetsJob)
    }

    private var groupSums = emptyArray<TickMomentum.GroupSums>()
    private val flattenJob = PhysicsJob { k, _ -> state.tickMomentum.flattenRange(spanFrom[k], spanTo[k]) }
    private val momentumJob = PhysicsJob { k, _ ->
        val st = state
        st.tickMomentum.momentumRange(spanFrom[k], spanTo[k], st.px, st.py, st.vx, st.vy, st.invMass, groupSums[k])
    }
    private val inertiaJob = PhysicsJob { k, _ ->
        val st = state
        st.tickMomentum.inertiaRange(spanFrom[k], spanTo[k], st.px, st.py, st.invMass, groupSums[k])
    }

    private val comJob = PhysicsJob { k, _ ->
        val st = state; val sums = groupSums[k]
        sums.clear()
        val inv = st.invMass; val org = st.organismOf; val vx = st.vx; val vy = st.vy
        for (i in spanFrom[k] until spanTo[k]) {
            if (inv[i] <= 0.0f) continue
            val m = 1.0f / inv[i]
            val at = sums.at(org[i]); val v = sums.data
            v[at] += m * vx[i]; v[at + 1] += m * vy[i]
        }
    }

    /** Импульс организмов для запаса среды (applyNormalDrag): кусками, сложение — в порядке кусков. */
    fun comSums() {
        val st = state
        val chunks = spanChunks()
        if (groupSums.size < chunks) groupSums = Array(chunks) { TickMomentum.GroupSums() }
        st.activeWorkers!!.run(chunks, comJob)
        for (k in 0 until chunks) {
            val sums = groupSums[k]; val v = sums.data
            for (x in 0 until sums.count) { val o = sums.keyAt(x); val at = sums.offsetAt(x); st.comAccX[o] += v[at].toFloat(); st.comAccY[o] += v[at + 1].toFloat() }
        }
    }

    fun momentumCancel() {
        val st = state
        val tm = st.tickMomentum
        val w = st.activeWorkers!!
        val chunks = spanChunks()
        if (groupSums.size < chunks) groupSums = Array(chunks) { TickMomentum.GroupSums() }
        w.run(chunks, clearJob)
        tm.mergeOrgGroups()
        w.run(chunks, flattenJob)
        w.run(chunks, momentumJob)
        for (k in 0 until chunks) tm.mergeMomentum(groupSums[k])
        w.run(chunks, centreJob)
        w.run(chunks, inertiaJob)
        for (k in 0 until chunks) tm.mergeInertia(groupSums[k])
        w.run(chunks, spinJob)
        w.run(chunks, applyJob)
    }

    fun removeJitter() = state.activeWorkers!!.run(spanChunks(), removeJitterJob)
    fun restoreJitter() = state.activeWorkers!!.run(spanChunks(), restoreJitterJob)
    fun flowPayment() = state.activeWorkers!!.run(spanChunks(), flowPaymentJob)

    // Рёбра контура по организмам: ребро пишет скорости двух частиц своего организма.
    private val dragBuckets = OrgBuckets(workers)
    private var dragH = 0.0f
    private val dragJob = PhysicsJob { t, _ ->
        val db = dragBuckets
        state.normalDragEdgesOf(db.items, db.start[db.task[t]], db.start[db.task[t + 1]], dragH)
    }

    fun normalDrag(h: Float) {
        val st = state
        dragH = h
        val layout = st.constraintBlocks
        if (layout != null) { edgeH = h; runEdges(layout, 1); return }
        val db = dragBuckets
        if (!db.valid(st, 0L)) {
            db.begin()
            for (e in 0 until st.boundCount) db.add(e, st.organismOf[st.boundA[e]])
            db.finish(st.organismCount, st, 0L)
        }
        st.activeWorkers!!.run(db.tasks, dragJob)
    }

    /** Диапазоны бодрствующих частиц, порезанные на куски не длиннее PASS_CHUNK по организмам: см. PassChunks. */
    private fun spanChunks(): Int {
        val pc = state.passChunks()
        spanFrom = pc.from; spanTo = pc.to
        spanChunks = pc.count
        return pc.count
    }

    // --- общий проход предела длины (solveLinkMaxLength) по организмам ---
    // Связь контура соединяет частицы одного организма, и организмы частиц не делят:
    // проход каждого организма по своим связям в порядке общего списка даёт каждой
    // частице те же поправки в том же порядке. Событие топологии останавливает
    // организм (как блок); остановленные дорабатываются подряд по общему списку,
    // начиная с самой ранней остановки, — события идут в том же порядке, что в одном
    // потоке. Связи, дописанные событиями, — в конце списка, их обходит доработка.
    // Элементы раскладки — места в общем списке, а не номера связей: по месту
    // доработка узнаёт, чей это элемент и не позже ли он остановки.
    private val limitBuckets = OrgBuckets(workers)
    private var orgStop = IntArray(2)
    private val limitsJob = PhysicsJob { t, worker ->
        val s = scratch[worker]
        val ids = state.boundaryLinkLimits.ids
        val lb = limitBuckets
        for (o in lb.task[t] until lb.task[t + 1]) {
            for (k in lb.start[o] until lb.start[o + 1]) {
                val g = lb.items[k]
                if (state.limitItemParallel(ids[g], s)) { orgStop[o] = g; break }
            }
        }
    }

    // --- рёбра контура и общий проход пределов по блокам ---
    // Ребро и связь контура лежат в одном блоке или в его наборе швов (blockOfSlot, blockOf): блоки
    // считаются вместе, швы — после. Раскладка готова и разрывами не пересобирается: раньше раскладка по
    // организмам пересобиралась в одном потоке после каждого разрыва (замер 10.11, 34 червя).
    private var edgeLayout: ConstraintBlocks? = null
    private var edgeFrom = 0
    private var edgeMode = 0
    private var edgeH = 0.0f
    private val edgeJob = PhysicsJob { k, _ ->
        val layout = edgeLayout!!
        val bucket = edgeFrom + k
        if (bucket >= layout.seam || layout.awake[bucket] != 0) {
            val list = layout.edges
            val items = list.dense(bucket); val n = list.denseCount[bucket]
            val st = state
            if (edgeMode == 0) {
                val a = st.boundA; val b = st.boundB; val opp = st.boundOpp; val ori = st.localBoundary.orientation
                for (x in 0 until n) orientEdge(items[x], a, b, opp, ori, st.px, st.py, st.invMass, st.orientationBones())
            } else st.normalDragEdgesOf(items, 0, n, edgeH)
        }
    }

    private fun runEdges(layout: ConstraintBlocks, mode: Int) {
        val w = state.activeWorkers!!
        edgeLayout = layout; edgeMode = mode
        edgeFrom = 0; w.run(layout.seam, edgeJob)
        edgeFrom = layout.seam; w.run(layout.buckets - layout.seam, edgeJob)
    }

    private var limitStopItem = IntArray(16)
    private var limitFrom = 0
    private var limitLayout: ConstraintBlocks? = null
    private val blockLimitsJob = PhysicsJob { k, worker ->
        val layout = limitLayout!!
        val bucket = limitFrom + k
        if (bucket >= layout.seam || layout.awake[bucket] != 0) {
            val items = layout.limits.dense(bucket); val n = layout.limits.denseCount[bucket]
            val s = scratch[worker]
            // Края, открытые разрывами этого прохода (у швов — доработкой блоков), ждут его хвоста: см. ConstraintBlocks.
            val skip = layout.passAddedN > 0
            for (x in 0 until n) {
                val c = items[x]
                if (skip && layout.addedInPass(c)) continue
                if (state.limitItemParallel(c, s)) { limitStopItem[bucket] = c; break }
            }
        }
    }

    /**
     * Общий проход пределов по блокам, затем по швам; остановленные перед событием — дорабатываются здесь,
     * края, открытые разрывами прохода, — его хвостом (ConstraintBlocks.beginLimitPass).
     */
    private fun globalLimitsByBlocks(layout: ConstraintBlocks) {
        val w = state.activeWorkers!!
        if (limitStopItem.size < layout.buckets) limitStopItem = IntArray(layout.buckets)
        java.util.Arrays.fill(limitStopItem, 0, layout.buckets, -1)
        limitLayout = layout
        layout.beginLimitPass()
        limitFrom = 0; w.run(layout.seam, blockLimitsJob); mergeCounters(); reworkLimits(layout, 0, layout.seam)
        limitFrom = layout.seam; w.run(layout.buckets - layout.seam, blockLimitsJob); mergeCounters(); reworkLimits(layout, layout.seam, layout.buckets)
        state.solveLimitPassTail(layout)
        layout.endLimitPass()
    }

    private fun reworkLimits(layout: ConstraintBlocks, from: Int, to: Int) {
        for (bucket in from until to) {
            val stop = limitStopItem[bucket]
            if (stop >= 0) state.solveLimitBucketSerial(layout, bucket, stop)
        }
    }

    fun solveGlobalLimits() {
        val blocksLayout = state.constraintBlocks
        if (blocksLayout != null) { globalLimitsByBlocks(blocksLayout); return }
        val limits = state.boundaryLinkLimits
        limits.beginPass()
        val count = limits.count
        val orgs = state.organismCount
        val lb = limitBuckets
        if (!lb.valid(state, limits.modCount.toLong())) {
            lb.begin()
            val ids = limits.ids
            for (g in 0 until count) {
                val c = ids[g]
                lb.add(g, if (c < 0) 0 else state.organismOf[state.conA[c]])
            }
            lb.finish(orgs, state, limits.modCount.toLong())
        }
        if (orgStop.size < orgs) orgStop = IntArray(orgs)
        java.util.Arrays.fill(orgStop, 0, orgs, Int.MAX_VALUE)
        state.activeWorkers!!.run(lb.tasks, limitsJob)
        mergeCounters()
        var first = Int.MAX_VALUE
        for (o in 0 until orgs) if (orgStop[o] < first) first = orgStop[o]
        if (first != Int.MAX_VALUE) {
            var g = first
            while (g < limits.count) {
                if (g >= count || orgStop[lb.seqOrg[g]] <= g) state.solveLimitItemSerial(limits.ids[g])
                g++
            }
        }
        limits.endPass()
    }

    // --- стадии по организмам: ориентация контура и вязкость ---
    // Ограничение трогает частицы одного организма, организмы частиц не делят. Порядок
    // обхода в одном потоке (сплошной список или блоки со сном) режется по организмам с
    // сохранением порядка, и каждый организм проходит свои ограничения сам: каждая
    // частица получает те же поправки в том же порядке. Событий топологии здесь нет.
    private val orientBuckets = OrgBuckets(workers)
    private val viscBuckets = OrgBuckets(workers)
    private var viscK = 0.0f
    private val orientJob = PhysicsJob { t, _ ->
        val st = state
        val ob = orientBuckets
        val a = st.boundA; val b = st.boundB; val opp = st.boundOpp; val ori = st.localBoundary.orientation
        for (k in ob.start[ob.task[t]] until ob.start[ob.task[t + 1]])
            orientEdge(ob.items[k], a, b, opp, ori, st.px, st.py, st.invMass, st.orientationBones())
    }
    private val viscJob = PhysicsJob { t, _ ->
        val vb = viscBuckets
        state.viscousLinksOf(vb.items, vb.start[vb.task[t]], vb.start[vb.task[t + 1]], viscK)
    }

    fun orientation() {
        val st = state
        val layout = st.constraintBlocks
        if (layout != null) { runEdges(layout, 0); return }
        val ob = orientBuckets
        if (!ob.valid(st, 0L)) {
            ob.begin()
            val org = st.organismOf; val a = st.boundA
            val layout = st.constraintBlocks
            if (st.sleep.asleepCount == 0 || layout == null) {
                for (k in 0 until st.boundCount) ob.add(k, org[a[k]])
            } else {
                val index = layout.edges
                for (block in 0 until layout.buckets) {
                    if (block < layout.seam && layout.awake[block] == 0) continue
                    var k = index.head[block]
                    while (k >= 0) { ob.add(k, org[a[k]]); k = index.next[k] }
                }
            }
            ob.finish(st.organismCount, st, 0L)
        }
        st.activeWorkers!!.run(ob.tasks, orientJob)
    }

    // Вязкость по блокам: связь блока пишет только свои частицы, раскладка готова и не зависит от разрывов.
    // Замер 10.11: пересборка раскладки по организмам каждый тик с разрывом была 1.7% главного потока.
    private var viscLayout: ConstraintBlocks? = null
    private var viscFrom = 0
    private val viscBlockJob = PhysicsJob { k, _ ->
        val layout = viscLayout!!
        val b = viscFrom + k
        if (b >= layout.seam || layout.awake[b] != 0) {
            val links = layout.links
            state.viscousLinksOf(links.items, links.start[b], links.start[b] + links.count[b], viscK)
        }
    }

    fun viscosity(k: Float, layout: ConstraintBlocks?) {
        val st = state
        viscK = k
        if (layout != null) {
            val w = st.activeWorkers!!
            viscLayout = layout
            viscFrom = 0; w.run(layout.seam, viscBlockJob)
            viscFrom = layout.seam; w.run(layout.buckets - layout.seam, viscBlockJob)
            return
        }
        val vb = viscBuckets
        if (!vb.valid(st, 0L)) {
            vb.begin()
            val org = st.organismOf; val ca = st.conA
            if (st.sleep.asleepCount == 0 || layout == null) {
                for (c in 0 until st.conCount) vb.add(c, org[ca[c]])
            } else {
                val links = layout.links
                for (block in 0 until layout.buckets) {
                    if (block < layout.seam && layout.awake[block] == 0) continue
                    for (slot in links.start[block] until links.start[block] + links.count[block]) {
                        val c = links.items[slot]
                        if (c >= 0) vb.add(c, org[ca[c]])
                    }
                }
            }
            vb.finish(st.organismCount, st, 0L)
        }
        st.activeWorkers!!.run(vb.tasks, viscJob)
    }

    // --- пластичность раз в тик ---
    // Каждая связь и каждый треугольник текут независимо (см. applyPlasticity): куски
    // по номерам без сна, по бодрствующим блокам и шву со сном — тот же набор.
    private var plasticK = 0.0f
    private var plasticBlocks = IntArray(16)
    private var plasticLayout: ConstraintBlocks? = null
    private val plasticJob = PhysicsJob { k, _ ->
        val layout = plasticLayout
        if (layout == null) {
            val parts = 4 * scratch.size
            val c0 = (state.conCount.toLong() * k / parts).toInt(); val c1 = (state.conCount.toLong() * (k + 1) / parts).toInt()
            state.plasticLinksRange(c0, c1, plasticK)
            val t0 = (state.triCount.toLong() * k / parts).toInt(); val t1 = (state.triCount.toLong() * (k + 1) / parts).toInt()
            state.plasticTrianglesRange(t0, t1, plasticK)
        } else state.plasticBlock(plasticBlocks[k], layout, plasticK)
    }

    fun plasticity(k: Float, layout: ConstraintBlocks?) {
        plasticK = k
        val w = state.activeWorkers!!
        if (state.sleep.asleepCount == 0 || layout == null) {
            plasticLayout = null
            w.run(4 * scratch.size, plasticJob)
            return
        }
        plasticLayout = layout
        if (plasticBlocks.size < layout.buckets) plasticBlocks = IntArray(layout.buckets)
        var count = 0
        for (block in 0 until layout.buckets) if (block >= layout.seam || layout.awake[block] > 0) plasticBlocks[count++] = block
        w.run(count, plasticJob)
    }

    // --- вывернутые треугольники раз в тик ---
    // Счётчик инверсии у каждого треугольника свой; гибель — событие топологии, в конце по возрастанию номеров.
    private val invKills = Array(workers) { IntArray(16) }
    private val invCount = IntArray(workers * 16)
    private var invLayout: ConstraintBlocks? = null
    private val invJob = PhysicsJob { b, worker ->
        val layout = invLayout!!
        if (b >= layout.seam || layout.awake[b] != 0) {
            val areas = layout.areas
            val items = areas.items; val from = areas.start[b]; val to = from + areas.count[b]
            // Массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop.
            val st = state
            val ages = st.baked.invertedTicks; val alive = st.baked.triAlive; val triId = st.triId
            val triA = st.triA; val triB = st.triB; val triC = st.triC; val px = st.px; val py = st.py
            lengthsBeforeLoop(ages.size or alive.size or triId.size or triA.size or triB.size or triC.size or px.size or
                py.size or items.size)
            for (slot in from until to) {
                val t = items[slot]
                if (t >= 0 && inversionExpiredAt(t, ages, alive, triId, triA, triB, triC, px, py)) {
                    val n = invCount[worker * 16]
                    if (n == invKills[worker].size) invKills[worker] = invKills[worker].copyOf(n * 2)
                    invKills[worker][n] = triId[t]
                    invCount[worker * 16] = n + 1
                }
            }
        }
    }

    fun scanInversions(layout: ConstraintBlocks) {
        val w = state.activeWorkers!!
        invLayout = layout
        java.util.Arrays.fill(invCount, 0)
        w.run(layout.buckets, invJob)
        var total = 0
        for (k in invKills.indices) total += invCount[k * 16]
        if (total == 0) return
        val all = IntArray(total)
        var at = 0
        for (k in invKills.indices) { System.arraycopy(invKills[k], 0, all, at, invCount[k * 16]); at += invCount[k * 16] }
        all.sort()
        for (id in all) state.killTriangleNow(id)
    }

    private fun mergeCounters() {
        for (s in scratch) {
            state.linkCapHits += s.linkCapHits; s.linkCapHits = 0
            state.boneCapHits += s.boneCapHits; s.boneCapHits = 0
            if (s.maxOverStrain > state.dbgMaxOverStrain) state.dbgMaxOverStrain = s.maxOverStrain
            s.maxOverStrain = Float.NEGATIVE_INFINITY
        }
    }
}

/**
 * Личное воркера: черновики проекции кости и счётчики.
 * Счётчики пишутся обычной записью; соседние объекты разных воркеров разделены
 * массивами-заглушками, чтобы не делить кэш-линию.
 */
internal class WorkerScratch(state: BodyState) {
    @Suppress("unused") private val padBefore = LongArray(16)
    val boneDx = FloatArray(state.boneDx.size)
    val boneDy = FloatArray(state.boneDx.size)
    val wallHits = BoneWallHits(state.rigidBones.size)
    var linkCapHits = 0
    var boneCapHits = 0
    var maxOverStrain = Float.NEGATIVE_INFINITY
    var speedCapHits = 0
    var peakSpeed2 = 0.0f
    @Suppress("unused") private val padAfter = LongArray(16)
}

/** Рёбра контура по блокам, затем швы, в одном потоке — тот же порядок, что у ParallelPhysics.runEdges. */
internal fun BodyState.edgesByBlocksSerial(layout: ConstraintBlocks, mode: Int, h: Float) {
    for (bucket in 0 until layout.buckets) {
        if (bucket < layout.seam && layout.awake[bucket] == 0) continue
        val items = layout.edges.dense(bucket); val n = layout.edges.denseCount[bucket]
        if (mode == 0) {
            val ori = localBoundary.orientation
            for (x in 0 until n) orientEdge(items[x], boundA, boundB, boundOpp, ori, px, py, invMass, orientationBones())
        } else normalDragEdgesOf(items, 0, n, h)
    }
}

/** Импульс организмов для запаса среды кусками в одном потоке — те же куски, что у ParallelPhysics.comSums. */
internal fun BodyState.comSumsSerial() {
    val sums = TickMomentum.GroupSums()
    val pc = passChunks()
    for (c in 0 until pc.count) {
        sums.clear()
        for (i in pc.from[c] until pc.to[c]) {
            if (invMass[i] <= 0.0f) continue
            val m = 1.0f / invMass[i]
            val at = sums.at(organismOf[i]); val v = sums.data
            v[at] += m * vx[i]; v[at + 1] += m * vy[i]
        }
        val v = sums.data
        for (x in 0 until sums.count) { val o = sums.keyAt(x); val at = sums.offsetAt(x); comAccX[o] += v[at].toFloat(); comAccY[o] += v[at + 1].toFloat() }
    }
}

internal fun BodyState.parallelPhysics(w: PhysicsWorkers): ParallelPhysics {
    val p = parallelPhysicsState
    if (p != null && parallelPhysicsWorkers == w.count) return p
    return ParallelPhysics(this, w.count).also { parallelPhysicsState = it; parallelPhysicsWorkers = w.count }
}

/** Параллельный обход допустим: см. ParallelBlocks. Иначе — обычный, в одном потоке. */
internal fun BodyState.parallelBlocksAllowed(w: PhysicsWorkers, layout: ConstraintBlocks): Boolean =
    w.count > 1 && baked.dirtyCount == 0 &&
        !dbgStages && watchCell < 0 && !dbgSealOn && !(DEBUG_CHECKS && dbgLinkExtremaOn)

/**
 * Блок целиком воркером: те же стадии и та же арифметика, что solveConstraintBlockFrom,
 * без таймеров стадий и снимков. Возвращает -1 или точку остановки перед событием
 * топологии: (стадия << 32) | запись очереди пределов (в позднем проходе — номер в списке у порога).
 */
internal fun BodyState.solveBlockParallel(h: Float, block: Int, layout: ConstraintBlocks,
                                          s: WorkerScratch, integrate: Boolean): Long {
    if (integrate) integrateRange(layout.firstParticle[block], layout.firstParticle[block + 1], h, s.wallHits)
    solveBlockLinks(h, block, layout)
    solveBlockFlaps(h, block, layout)
    if (MAXLEN_EARLY) {
        val stop = solveBlockLimitsParallel(block, layout, s)
        if (stop >= 0) return (BLOCK_STAGE_LIMITS_EARLY.toLong() shl 32) or stop.toLong()
    }
    if (slowConstraintsNow) {
        if (areasOn) solveBlockAreas(h * SLOW_CONSTRAINT_PERIOD, block, layout)
        solveBlockBend(h * SLOW_CONSTRAINT_PERIOD, block, layout)
    }
    if (bonesRigid) {
        var b = layout.bones.head[block]
        while (b >= 0) {
            if (!sleep.isAsleep(organismOf[rigidBones[b][0]]) && projectBoneInto(b, s.boneDx, s.boneDy)) s.boneCapHits++
            b = layout.bones.next[b]
        }
    }
    val stop = if (MAXLEN_EARLY) solveBlockNearLimitsParallel(block, layout, s) else solveBlockLimitsParallel(block, layout, s)
    if (stop >= 0) return (BLOCK_STAGE_LIMITS_LATE.toLong() shl 32) or stop.toLong()
    return -1
}

/**
 * Предел длины блока воркером по всему контуру блока. Та же проверка, что solveBlockLimits, и тот
 * же список у порога (ConstraintBlocks.nearItems): критерий в обоих записан одинаково, поэтому
 * состав и порядок списка от числа воркеров не зависят. Нарушение без события снимается той же
 * поправкой; на событии — выход с номером записи, ничего не записав и не внеся связь события в
 * список: её и остаток внесёт доработка (solveBlockLimits с той же записи). Очередь — плотная копия
 * списка блока: до доработки он не меняется, событий в параллельном проходе нет.
 */
private fun BodyState.solveBlockLimitsParallel(block: Int, layout: ConstraintBlocks, s: WorkerScratch): Int {
    val list = layout.limits
    val q = list.dense(block)
    val size = list.denseCount[block]
    val px = px; val py = py; val conA = conA; val conB = conB; val conRest = conRest; val conMuscle = conMuscle
    // Множители клеток — массив и признак, а не null; длины — до цикла (см. lengthsBeforeLoop).
    val conDead = conDead; val ms = muscleScaleOf; val csOn = cellScalesOn; val cs = conCellScale
    var near = layout.nearItems[block]
    var n = 0
    var stop = -1
    lengthsBeforeLoop(px.size or py.size or conA.size or conB.size or conRest.size or conMuscle.size or conDead.size or
        ms.size or cs.size or q.size)
    for (cursor in 0 until size) {
        val c = q[cursor]
        if (c < 0 || conDead[c]) continue
        val i = conA[c]; val j = conB[c]
        val dx = px[i] - px[j]
        val dy = py[i] - py[j]
        val len2 = dx * dx + dy * dy
        val mus = conMuscle[c]
        val base = if (mus < 0) 1.0f else ms[mus]
        val scale = if (!csOn) base else base * cs[c]
        val rest = conRest[c]
        val crushLim = LINK_CRUSH_RATIO * (rest * limitShrink(c, scale))
        val max = linkMaxLength(limitRest(c, rest, scale), scale)
        val hi2 = max * max; val lo2 = crushLim * crushLim
        val isNear = len2 > hi2 * LIMIT_NEAR_HI2 || (tearingOn && len2 < lo2 * LIMIT_NEAR_LO2)
        if (!isNear) continue
        val crushed = tearingOn && len2 < lo2
        if ((crushed || len2 > hi2) && limitViolationParallel(c, len2, crushed, scale, max, s)) { stop = cursor; break }
        if (n == near.size) near = layout.growNear(block, n)
        near[n++] = c
    }
    layout.nearCount[block] = n
    return stop
}

/** Поздний предел блока воркером — по списку у порога из раннего прохода. Номер в списке перед событием или -1. */
private fun BodyState.solveBlockNearLimitsParallel(block: Int, layout: ConstraintBlocks, s: WorkerScratch): Int {
    val items = layout.nearItems[block]
    val n = layout.nearCount[block]
    for (x in 0 until n) if (limitItemParallel(items[x], s)) return x
    return -1
}

/**
 * Одна связь предела воркером: та же проверка, что в обычном проходе; нарушение без
 * события снимается той же поправкой. true — нарушение даст событие топологии, и
 * ничего не записано: связь дорабатывается в одном потоке.
 */
internal fun BodyState.limitItemParallel(c: Int, s: WorkerScratch): Boolean {
    if (c < 0 || conDead[c]) return false
    val i = conA[c]; val j = conB[c]
    val dx = px[i] - px[j]
    val dy = py[i] - py[j]
    val len2 = dx * dx + dy * dy
    val mus = conMuscle[c]
    val base = if (mus < 0) 1.0f else muscleScaleOf[mus]
    val scale = if (cellScalesOn) base * conCellScale[c] else base
    val rest = conRest[c]
    val restScale = rest * limitShrink(c, scale)
    val crushLim = LINK_CRUSH_RATIO * restScale
    val max = linkMaxLength(limitRest(c, rest, scale), scale)
    val crushed = tearingOn && len2 < crushLim * crushLim
    val over = len2 > max * max
    if (!crushed && !over) return false
    return limitViolationParallel(c, len2, crushed, scale, max, s)
}

/** Нарушение предела у воркера — редкий путь отдельно от цикла. true — будет событие, ничего не записано. */
private fun BodyState.limitViolationParallel(c: Int, len2: Float, crushed: Boolean, scale: Float, max: Float,
                                             s: WorkerScratch): Boolean {
    val i = conA[c]; val j = conB[c]
    if (invMass[i] + invMass[j] == 0.0f) return false
    if (isGrowingLink(c)) return false      // см. isGrowingLink
    if (limitViolationIsEvent(c, len2, crushed, scale)) return true
    val len = sqrt(len2)
    if (len < 1e-12f) return false
    val overStrain = (len - max) / conRest[c]
    if (overStrain > s.maxOverStrain) s.maxOverStrain = overStrain
    applyLinkLimitCorrection(i, j, len, max)
    s.linkCapHits++
    return false
}

/**
 * Условие ParallelBlocks: всё в двух связях от частицы — в соседних блоках. Считается
 * по запечённой топологии (все рёбра, включая внутрикостные). O(n · степень²) при
 * построении блоков. Флаг на тело, а не на мир: раньше одно неудачное тело выключало
 * параллельный обход всем.
 */
internal fun BodyState.computeSerialBlocks(layout: ConstraintBlocks) {
    val unsafe = BooleanArray(layout.bodyRuns)
    for (i in 0 until n) {
        val run = layout.bodyRunOf[layout.particleBlock[i]]
        if (!unsafe[run] && reachesFarBlock(i, layout.particleBlock)) unsafe[run] = true
    }
    var serial = 0
    for (b in 0 until layout.blockCount) {
        layout.serialBlock[b] = unsafe[layout.bodyRunOf[b]]
        if (layout.serialBlock[b]) serial++
    }
    layout.serialBlocks = serial
}

private fun BodyState.reachesFarBlock(i: Int, block: IntArray): Boolean {
    val start = baked.vertStart; val other = baked.vertOther
    val bi = block[i]
    for (s in start[i] until start[i + 1]) {
        val j = other[s]
        if (kotlin.math.abs(block[j] - bi) > 1) return true
        for (t in start[j] until start[j + 1]) {
            if (kotlin.math.abs(block[other[t]] - bi) > 1) return true
        }
    }
    return false
}

/**
 * Порядок обхода стадии, разрезанный по организмам с сохранением порядка: элементы
 * организма o — items[start[o] until start[o + 1]]; задачи воркеров — подряд идущие
 * организмы примерно поровну по числу элементов (task).
 *
 * Раскладка меняется только с топологией (разрыв, гибель — BodyState.structureVersion)
 * и сном (BodySleep.version), поэтому хранится между подшагами: пересборка на каждом
 * подшаге стоила 4% главного потока на 100k и съедала весь выигрыш (замер 25.10).
 * [extra] — свой счётчик стадии (у предела длины — правки общего списка).
 */
internal class OrgBuckets(workers: Int) {
    var start = IntArray(2); private set
    var items = IntArray(16); private set
    /** Организм каждого элемента порядка, по месту в порядке. */
    var seqOrg = IntArray(16); private set
    private var seq = IntArray(16)
    val task = IntArray(4 * workers + 1)
    var tasks = 0; private set
    private var n = 0
    private var builtStructure = -1
    private var builtSleep = -1
    private var builtExtra = -1L

    fun valid(st: BodyState, extra: Long): Boolean =
        builtStructure == st.structureVersion && builtSleep == st.sleep.version && builtExtra == extra

    fun begin() { n = 0 }

    fun add(item: Int, org: Int) {
        if (n == seq.size) { seq = seq.copyOf(n * 2); seqOrg = seqOrg.copyOf(n * 2) }
        seq[n] = item; seqOrg[n] = org; n++
    }

    fun finish(orgs: Int, st: BodyState, extra: Long) {
        if (start.size < orgs + 2) start = IntArray(orgs + 2)
        if (items.size < n) items = IntArray(maxOf(n, items.size * 2))
        java.util.Arrays.fill(start, 0, orgs + 2, 0)
        for (q in 0 until n) start[seqOrg[q] + 2]++
        for (o in 0 until orgs) start[o + 2] += start[o + 1]
        for (q in 0 until n) items[start[seqOrg[q] + 1]++] = seq[q]
        // После раскладки start[o] — начало организма o, start[orgs] — конец.
        val want = minOf(task.size - 1, orgs)
        var t = 0
        task[0] = 0
        for (o in 0 until orgs) {
            if (t + 1 < want && start[o + 1].toLong() * want >= (t + 1).toLong() * n) task[++t] = o + 1
        }
        tasks = t + 1
        task[tasks] = orgs
        builtStructure = st.structureVersion; builtSleep = st.sleep.version; builtExtra = extra
    }
}

/** Разбор для замеров (PERFBLOCKS): идёт ли обход блоков по воркерам и что ему мешает. */
fun BodyState.describeBlocks(): String {
    val layout = constraintBlocks ?: return "блоков нет"
    var small = 0
    for (b in 0 until layout.blockCount) if (layout.firstParticle[b + 1] - layout.firstParticle[b] < 100) small++
    return "блоков=${layout.blockCount} (меньше 100 частиц: $small) наборов_швов=${layout.buckets - layout.seam} организмов=$organismCount " +
        "в_одном_потоке=${layout.serialBlocks} незавершённых_вершин=${baked.dirtyCount} dbgStages=$dbgStages dbgSealOn=$dbgSealOn"
}
