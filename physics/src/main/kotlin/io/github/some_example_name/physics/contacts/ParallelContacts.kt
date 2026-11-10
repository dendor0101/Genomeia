package io.github.some_example_name.physics

/**
 * Ключи новых пар подшага (см. BoundaryContacts.updateCandidates): newKeys — пары, где
 * перекладываемая вершина меньшая, идут по вершинам подряд; lowKeys — остальные,
 * сортируются целиком перед слиянием.
 */
internal class PairKeyBuffer {
    var newKeys = LongArray(256)
    var newN = 0
    var lowKeys = LongArray(256)
    var lowN = 0

    fun addNew(k: Long) {
        if (newN == newKeys.size) newKeys = newKeys.copyOf(newN * 2)
        newKeys[newN++] = k
    }

    fun addLow(k: Long) {
        if (lowN == lowKeys.size) lowKeys = lowKeys.copyOf(lowN * 2)
        lowKeys[lowN++] = k
    }

    /** Дописать свои ключи в конец [to]. */
    fun appendTo(to: PairKeyBuffer) {
        if (to.newN + newN > to.newKeys.size) to.newKeys = to.newKeys.copyOf(maxOf(to.newKeys.size * 2, to.newN + newN))
        System.arraycopy(newKeys, 0, to.newKeys, to.newN, newN); to.newN += newN
        if (to.lowN + lowN > to.lowKeys.size) to.lowKeys = to.lowKeys.copyOf(maxOf(to.lowKeys.size * 2, to.lowN + lowN))
        System.arraycopy(lowKeys, 0, to.lowKeys, to.lowN, lowN); to.lowN += lowN
    }
}

/**
 * ОТБОР УЦЕЛЕВШИХ ПАР И СЛИЯНИЕ ПО ВОРКЕРАМ — побитово тот же список.
 *
 * Отбор — фильтр с сохранением порядка: каждый кусок сначала
 * считает, сколько оставит, потом пишет со своего смещения (префиксная сумма).
 *
 * Слияние: три упорядоченных источника (уцелевшие, новые, нижние) дают один
 * канонический список. Выход делится по ключам-разделителям: кусок p берёт из
 * КАЖДОГО источника ключи в [S_p, S_{p+1}) (двоичный поиск) и сливает их сам. Ключи различны, поэтому где кончается один
 * кусок и начинается следующий — однозначно, а смещение куска на выходе — число
 * ключей меньше S_p во всех источниках. Результат тот же, что у слияния подряд.
 */
internal class ParallelPairs(private val ct: BoundaryContacts, private val workers: Int) {
    private val parts = 4 * workers
    private val from = IntArray(parts + 1)
    private val count = IntArray(parts)
    private val at = IntArray(parts + 1)
    private val keepCount = PhysicsJob { k, _ -> count[k] = ct.countKept(from[k], from[k + 1]) }
    private val keepWrite = PhysicsJob { k, _ -> ct.writeKept(from[k], from[k + 1], at[k]) }

    /** Уцелевшие пары из [pairN] в keptKeys; возвращает их число. */
    fun keep(w: PhysicsWorkers, pairN: Int): Int {
        split(pairN)
        w.run(parts, keepCount)
        prefix()
        w.run(parts, keepWrite)
        return at[parts]
    }

    // --- слияние ---
    // Нижние ключи приходят отсортированными кусками (прогонами): ParallelCandidates
    // сортирует нижние ключи своего куска сам. Кусок слияния p собирает из каждого
    // прогона ключи в [S_p, S_{p+1}) в свой буфер, сортирует его (сотни ключей) и
    // сливает три источника: уцелевшие, новые, свои нижние. Девять голов на каждую
    // пару (по прогону на воркер) стоили дороже слияния подряд: 16.8 мс за тик на 100k.
    private var runEnd = IntArray(1)
    private var runs = 0
    private val splitters = LongArray(parts + 1)
    private val outFrom = IntArray(parts + 1)
    private val lowBuf = Array(parts) { LongArray(256) }
    private var kept = 0
    private var fresh = 0
    private val mergeJob = PhysicsJob { p, _ -> mergePart(p) }

    /**
     * [lowRunEnd] — концы отсортированных прогонов нижних ключей ([lowRuns] штук);
     * null — нижние не отсортированы, сортируются здесь одним прогоном.
     * false — источники слишком малы для разделителей: слияние подряд (mergePairs).
     */
    fun merge(w: PhysicsWorkers, kept: Int, fresh: Int, low: Int, lowRunEnd: IntArray?, lowRuns: Int): Boolean {
        val keys = ct.pairKeyBuffer
        val src: LongArray; val srcN: Int
        when {
            kept >= parts * 4 -> { src = ct.keptKeyArray; srcN = kept }
            fresh >= parts * 4 -> { src = keys.newKeys; srcN = fresh }
            else -> return false
        }
        this.kept = kept; this.fresh = fresh
        if (lowRunEnd == null) {
            java.util.Arrays.sort(keys.lowKeys, 0, low)
            runEnd[0] = low; runs = 1
        } else {
            if (runEnd.size < lowRuns) runEnd = IntArray(lowRuns)
            System.arraycopy(lowRunEnd, 0, runEnd, 0, lowRuns); runs = lowRuns
        }
        splitters[0] = Long.MIN_VALUE
        for (p in 1 until parts) splitters[p] = src[(srcN.toLong() * p / parts).toInt()]
        splitters[parts] = Long.MAX_VALUE
        val total = kept + fresh + low
        ct.ensurePairCapacity(total)
        w.run(parts, mergeJob)
        outFrom[parts] = total
        ct.finishParallelPairs(total)
        ct.ensureNearCapacity()
        return true
    }

    /**
     * Кусок [p] слияния. Его смещение на выходе — число ключей меньше S_p во всех
     * источниках; конец — смещение плюс его собственные ключи. Соседние куски его не
     * ждут: границы каждый находит сам.
     */
    private fun mergePart(p: Int) {
        val keys = ct.pairKeyBuffer
        val kk = ct.keptKeyArray; val nk = keys.newKeys; val lk = keys.lowKeys
        val lo = splitters[p]; val hi = splitters[p + 1]
        var i = lowerBound(kk, 0, kept, lo); val iEnd = lowerBound(kk, 0, kept, hi)
        var j = lowerBound(nk, 0, fresh, lo); val jEnd = lowerBound(nk, 0, fresh, hi)
        var out = i + j
        var buf = lowBuf[p]
        var lowN = 0
        var runStart = 0
        for (r in 0 until runs) {
            val a = lowerBound(lk, runStart, runEnd[r], lo)
            val b = lowerBound(lk, a, runEnd[r], hi)
            out += a - runStart
            if (lowN + b - a > buf.size) { buf = buf.copyOf(maxOf(buf.size * 2, lowN + b - a)); lowBuf[p] = buf }
            System.arraycopy(lk, a, buf, lowN, b - a); lowN += b - a
            runStart = runEnd[r]
        }
        if (runs > 1) java.util.Arrays.sort(buf, 0, lowN)
        outFrom[p] = out
        var l = 0
        val end = out + (iEnd - i) + (jEnd - j) + lowN
        while (out < end) {
            // Наименьший из трёх голов; ключи различны.
            var best = Long.MAX_VALUE; var from = 0
            if (i < iEnd) { best = kk[i]; from = 1 }
            if (j < jEnd && nk[j] < best) { best = nk[j]; from = 2 }
            if (l < lowN && buf[l] < best) { best = buf[l]; from = 3 }
            when (from) { 1 -> i++; 2 -> j++; else -> l++ }
            ct.setPair(out++, best)
        }
    }

    private fun lowerBound(a: LongArray, from: Int, to: Int, key: Long): Int {
        var lo = from; var hi = to
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < key) lo = m + 1 else hi = m }
        return lo
    }

    private fun split(n: Int) { for (k in 0..parts) from[k] = (n.toLong() * k / parts).toInt() }

    private fun prefix() {
        at[0] = 0
        for (k in 0 until parts) at[k + 1] = at[k] + count[k]
    }
}

/** Куски [0, n) поровну на 4·воркеров; общая часть фильтров с сохранением порядка. */
internal open class OrderedChunks(workers: Int) {
    protected val parts = 4 * workers
    protected val from = IntArray(parts + 1)
    protected val count = IntArray(parts)
    protected val at = IntArray(parts + 1)

    protected fun split(n: Int) { for (k in 0..parts) from[k] = (n.toLong() * k / parts).toInt() }

    protected fun prefix(): Int {
        at[0] = 0
        for (k in 0 until parts) at[k + 1] = at[k] + count[k]
        return at[parts]
    }
}

/** Кластеры костей по кускам: у каждого свои масса, центр и момент инерции. */
internal class ParallelBones(private val ct: BoundaryContacts, workers: Int) : OrderedChunks(workers) {
    private var px = DoubleArray(0); private var py = DoubleArray(0); private var invMass = DoubleArray(0)
    private val job = PhysicsJob { k, _ -> for (b in from[k] until from[k + 1]) ct.updateBone(b, px, py, invMass) }

    fun run(w: PhysicsWorkers, bones: Int, px: DoubleArray, py: DoubleArray, invMass: DoubleArray) {
        this.px = px; this.py = py; this.invMass = invMass
        split(bones)
        w.run(parts, job)
    }
}

/** Уехавшие вершины: счёт по кускам, потом запись со своего смещения. */
internal class ParallelMoved(private val ct: BoundaryContacts, workers: Int) : OrderedChunks(workers) {
    private var px = DoubleArray(0); private var py = DoubleArray(0)
    private val countJob = PhysicsJob { k, _ -> count[k] = ct.countMoved(from[k], from[k + 1], px, py) }
    private val writeJob = PhysicsJob { k, _ -> ct.writeMoved(from[k], from[k + 1], at[k], px, py) }

    fun find(w: PhysicsWorkers, movers: Int, px: DoubleArray, py: DoubleArray): Int {
        this.px = px; this.py = py
        split(movers)
        w.run(parts, countJob)
        val total = prefix()
        w.run(parts, writeJob)
        return total
    }
}

/**
 * Сборка контактов: тот же фильтр. Контакт пишется только из перекрытой пары, и
 * его номер — смещение куска плюс номер внутри куска, то есть тот же, что подряд.
 * Слот пары покоя (pairContactDistance) кэшируется в ячейке самой пары: два прохода
 * пишут одно и то же значение в одно и то же место.
 */
internal class ParallelBuild(private val ct: BoundaryContacts, workers: Int) : OrderedChunks(workers) {
    private var px = DoubleArray(0); private var py = DoubleArray(0)
    private var vx = DoubleArray(0); private var vy = DoubleArray(0)
    private val lists = Array(parts) { IntArray(64) }
    private val countJob = PhysicsJob { k, _ ->
        var list = lists[k]
        var c = 0
        for (p in from[k] until from[k + 1]) if (ct.contactTouches(p, px, py)) {
            if (c == list.size) { list = list.copyOf(c * 2); lists[k] = list }
            list[c++] = p
        }
        count[k] = c
    }
    private val writeJob = PhysicsJob { k, _ ->
        val list = lists[k]
        for (x in 0 until count[k]) ct.writeContact(list[x], at[k] + x, px, py, vx, vy)
    }

    fun build(w: PhysicsWorkers, near: Int, px: DoubleArray, py: DoubleArray, vx: DoubleArray, vy: DoubleArray): Int {
        this.px = px; this.py = py; this.vx = vx; this.vy = vy
        split(near)
        w.run(parts, countJob)
        val total = prefix()
        ct.ensureContactCapacity(total)
        // Запись идёт по найденным парам. Последовательно — только совсем мелкий список: запись контакта
        // читает пары покоя вразброс, и 3.4 тысячи контактов в одном потоке стоили 7% тика (замер 10.11).
        if (total < WRITE_PARALLEL_MIN) { for (k in 0 until parts) { val list = lists[k]; for (x in 0 until count[k]) ct.writeContact(list[x], at[k] + x, px, py, vx, vy) } }
        else w.run(parts, writeJob)
        return total
    }

    private companion object {
        const val WRITE_PARALLEL_MIN = 256
    }
}

/**
 * CCD: почти все ближние пары тело цикла пропускает, ничего не записав (ccdSkips).
 * Их отсев — по кускам; оставшиеся пары каждого куска лежат по порядку, и тело
 * цикла (ccdPair) идёт по ним подряд в исходном порядке — те же записи в том же
 * порядке, что у прохода по всем парам.
 */
internal class ParallelCcd(private val ct: BoundaryContacts, workers: Int) : OrderedChunks(workers) {
    private var lists = Array(parts) { IntArray(64) }
    private var px = DoubleArray(0); private var py = DoubleArray(0)
    private var qx = DoubleArray(0); private var qy = DoubleArray(0)
    private var invMass = DoubleArray(0)
    val chunks get() = parts
    private val scanJob = PhysicsJob { k, _ ->
        var list = lists[k]
        var c = 0
        for (p in from[k] until from[k + 1]) {
            if (ct.ccdSkips(p, px, py, qx, qy, invMass)) continue
            if (c == list.size) { list = list.copyOf(c * 2); lists[k] = list }
            list[c++] = p
        }
        count[k] = c
    }

    fun found(k: Int): IntArray = lists[k]
    fun foundCount(k: Int): Int = count[k]

    fun scan(w: PhysicsWorkers, near: Int, px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray,
             invMass: DoubleArray) {
        this.px = px; this.py = py; this.qx = qx; this.qy = qy; this.invMass = invMass
        split(near)
        w.run(parts, scanJob)
    }
}

/**
 * ПЕРЕКЛАДКА УЕХАВШИХ ВЕРШИН В СЕТКУ ПО ВОРКЕРАМ.
 *
 * Сначала по кускам moved: новая привязка (у вершины своя) и след — ключи ячеек пути
 * подшага в буфер куска. Затем по частям сетки (BoundaryContacts.shardOf): воркер
 * части s проходит все вершины по порядку, вынимает из своей части их старые записи
 * и кладёт ключи, которые достались его части. Части — независимые таблицы со своими
 * следами вершин, общего между ними нет. Набор записей тот же, что у перекладки
 * подряд; раскладка по таблицам на поиск соседей не влияет (см. shards).
 */
internal class ParallelReinsert(private val ct: BoundaryContacts, private val workers: Int) : OrderedChunks(workers) {
    private val cells = Array(parts) { LongArray(256) }
    /** Конец следа каждой вершины куска в cells[k]. */
    private val cellEnd = Array(parts) { IntArray(64) }
    private val ddaX = Array(workers) { IntArray(BoundaryContacts.DDA_MAX) }
    private val ddaY = Array(workers) { IntArray(BoundaryContacts.DDA_MAX) }
    private var px = DoubleArray(0); private var py = DoubleArray(0)
    private var qx = DoubleArray(0); private var qy = DoubleArray(0)

    private val footJob = PhysicsJob { k, worker ->
        val moved = ct.moved
        val dx = ddaX[worker]; val dy = ddaY[worker]
        var buf = cells[k]
        var ends = cellEnd[k]
        if (ends.size < from[k + 1] - from[k]) { ends = IntArray(from[k + 1] - from[k]); cellEnd[k] = ends }
        var size = 0
        for (a in from[k] until from[k + 1]) {
            val v = moved[a]
            ct.anchorVertex(v, px, py, qx, qy)
            val c = ct.ddaInto(qx[v], qy[v], px[v], py[v], dx, dy)
            if (size + c > buf.size) { buf = buf.copyOf(maxOf(buf.size * 2, size + c)); cells[k] = buf }
            for (i in 0 until c) buf[size++] = ct.cellKey(dx[i], dy[i])
            ends[a - from[k]] = size
        }
    }

    private val shardJob = PhysicsJob { s, _ ->
        val g = ct.shard(s)
        val moved = ct.moved
        for (k in 0 until parts) {
            val buf = cells[k]; val ends = cellEnd[k]
            var start = 0
            for (a in from[k] until from[k + 1]) {
                val v = moved[a]
                g.remove(v)
                val end = ends[a - from[k]]
                for (i in start until end) { val cell = buf[i]; if (ct.shardOf(cell) == s) g.insert(v, cell) }
                start = end
            }
        }
    }

    fun run(w: PhysicsWorkers, px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray) {
        this.px = px; this.py = py; this.qx = qx; this.qy = qy
        split(ct.movedCount)
        w.run(parts, footJob)
        w.run(ct.shardCount, shardJob)
    }
}

/**
 * ПОИСК СОСЕДЕЙ ПЕРЕКЛАДЫВАЕМЫХ ВЕРШИН ПО ВОРКЕРАМ — побитово тот же список пар.
 *
 * Поиск только читает: сетки, привязки, радиусы. Пишет он пометку «уже видел» и
 * ключи пар — у каждого воркера своя пометка, у каждого куска свой буфер. Куски —
 * отрезки moved подряд, и склейка их буферов по порядку кусков даёт ровно ту
 * последовательность newKeys, что один проход (внутри вершины ключи сортируются,
 * между вершинами идут по moved). lowKeys перед слиянием сортируются целиком, им
 * порядок не важен — ключи различны.
 *
 * Память: пометка — IntArray(n) на воркер, 400 КБ на 100k; читается по номерам
 * соседей, то есть так же случайно, как и в одном потоке.
 */
internal class ParallelCandidates(private val ct: BoundaryContacts, workers: Int) {
    private val marks = Array(workers) { IntArray(ct.n) }
    /** Счётчики пометки, по 16 Int на воркер: разные воркеры — разные кэш-линии. */
    private val stamps = IntArray(workers * 16)
    private val ddaX = Array(workers) { IntArray(BoundaryContacts.DDA_MAX) }
    private val ddaY = Array(workers) { IntArray(BoundaryContacts.DDA_MAX) }
    /** Кусков вшестнадцатеро больше воркеров: цена вершины зависит от числа соседей. */
    private var buffers = Array(16 * workers) { PairKeyBuffer() }
    private var from = IntArray(16 * workers)
    private var to = IntArray(16 * workers)
    /** Концы отсортированных прогонов нижних ключей после склейки кусков (ParallelPairs). */
    val lowRunEnd = IntArray(16 * workers)
    var chunks = 0; private set
    private var px = DoubleArray(0); private var py = DoubleArray(0)
    private var qx = DoubleArray(0); private var qy = DoubleArray(0)
    private val job = PhysicsJob { k, worker -> query(k, worker) }

    fun buffer(k: Int): PairKeyBuffer = buffers[k]

    fun run(w: PhysicsWorkers, px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray) {
        this.px = px; this.py = py; this.qx = qx; this.qy = qy
        val count = ct.movedCount
        val k = minOf(buffers.size, maxOf(1, count / 64))
        for (c in 0 until k) { from[c] = (count.toLong() * c / k).toInt(); to[c] = (count.toLong() * (c + 1) / k).toInt() }
        chunks = k
        w.run(k, job)
    }

    private fun query(k: Int, worker: Int) {
        val out = buffers[k]
        out.newN = 0; out.lowN = 0
        val mark = marks[worker]
        val moved = ct.moved
        var stamp = stamps[worker * 16]
        for (a in from[k] until to[k]) {
            stamp++
            ct.queryVertex(moved[a], px, py, qx, qy, mark, stamp, ddaX[worker], ddaY[worker], out)
        }
        stamps[worker * 16] = stamp
        // Нижние куска — отсортированным прогоном: слиянию останется собрать их по ключам.
        java.util.Arrays.sort(out.lowKeys, 0, out.lowN)
    }
}

/** Пробитые рёбра куска пар подряд: концы (a, b) двумя числами на ребро. */
internal class PierceBuffer {
    var edges = IntArray(32)
    var n = 0

    fun add(a: Int, b: Int) {
        if (n == edges.size) edges = edges.copyOf(n * 2)
        edges[n++] = a; edges[n++] = b
    }
}

/**
 * ПОИСК ПРОБОЯ ПО ВОРКЕРАМ — побитово тот же список. Проверка только читает позиции и контур,
 * найденные рёбра каждый кусок копит у себя; склейка кусков по порядку даёт порядок прохода
 * по парам подряд, а от порядка рёбер зависит порядок разрывов.
 *
 * Куски мелкие: у пары своего организма работы нет, а у чужой до четырёх проверок отрезков.
 * Раздачу уравнивает общий курсор воркеров. Замер 09.11, толпа 100k клеток, 8 воркеров: поиск
 * был 13% главного потока (~14 мс из 111), воркеры в это время стояли.
 */
internal class ParallelPierce(private val ct: BoundaryContacts, workers: Int) {
    private val buffers = Array(16 * workers) { PierceBuffer() }
    private val from = IntArray(16 * workers + 1)
    private var px = DoubleArray(0); private var py = DoubleArray(0)
    private var qx = DoubleArray(0); private var qy = DoubleArray(0)
    private var org = IntArray(0)
    private val job = PhysicsJob { k, _ ->
        val out = buffers[k]
        out.n = 0
        ct.findPierceRange(from[k], from[k + 1], px, py, qx, qy, org, out)
    }
    private val boundChunks = 4 * workers
    private val boundsJob = PhysicsJob { k, _ ->
        val verts = ct.contourVertexCount
        ct.pierceBoundsRange((verts.toLong() * k / boundChunks).toInt(), (verts.toLong() * (k + 1) / boundChunks).toInt(), px, py, qx, qy)
    }

    fun run(w: PhysicsWorkers, px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray, org: IntArray) {
        this.px = px; this.py = py; this.qx = qx; this.qy = qy; this.org = org
        w.run(boundChunks, boundsJob)
        val pairs = ct.candidatePairCount
        val k = minOf(buffers.size, maxOf(1, pairs / 64))
        for (c in 0..k) from[c] = (pairs.toLong() * c / k).toInt()
        w.run(k, job)
        for (c in 0 until k) ct.addPierces(buffers[c])
    }
}
