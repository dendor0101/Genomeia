package io.github.some_example_name.physics


/**
 * ЗАПЕЧЁННАЯ ТОПОЛОГИЯ ЦЕЛОГО ТЕЛА: рёбра, треугольники на рёбрах и что из этого ещё живо.
 *
 * ЗАЧЕМ. Разрыв меняет ткань по месту — несколько рёбер и треугольники вокруг них, — а
 * пересборка выводила всё заново из списков: сортировала ключи всех рёбер треугольников,
 * искала каждую связь двоичным поиском среди мёртвых. На ударе она зовётся десятками раз за
 * тик: журнал 15.09 12:17 — 475 пересборок за 767 мс, худший тик 30 пересборок за 79 мс.
 *
 * Всё, что от разрыва не зависит, считается здесь один раз при загрузке: номер ребра у
 * каждой связи, рёбра каждого треугольника, треугольники каждого ребра. Разрыв только гасит
 * ребро и вычитает умершие треугольники из счётчиков их рёбер. Граница тела — рёбра, на
 * которые опирается не больше одного живого треугольника, — читается из счётчика напрямую.
 *
 * РЕБРО — НЕУПОРЯДОЧЕННАЯ ПАРА КЛЕТОК, а не связь. Связей с одной парой в выгрузке может
 * быть несколько, и умирают они вместе: так было и раньше, когда мёртвое искалось по паре.
 * Треугольник, у которого пара вершин не связана вовсе, по этому ребру не умирает никогда —
 * тоже как раньше.
 */
class BakedTopology(private var body: BodyDefinition, build: Boolean = true) {
    // Массивы — var: мир дописывает тела (append). Ссылки на них берутся заново после
    // дописывания, держать их у себя дольше одной стадии нельзя.
    /** Число рёбер — различных пар среди связей выгрузки. */
    var edgeCount = 0; private set
    /** Ребро каждой связи выгрузки, по номеру в body.linkA / body.linkB. */
    var linkEdge = IntArray(0); private set
    private var edgeKeys = LongArray(0)
    /** Рёбра треугольника t выгрузки: 3t, 3t+1, 3t+2 — пары (A,B), (B,C), (C,A); -1 — таких связей нет. */
    var triEdge = IntArray(0); private set
    internal var edgeTriStart = IntArray(1); private set
    internal var edgeTris = IntArray(0); private set
    private var useInit = IntArray(0)
    /** Связи каждой клетки: vertStart[i]..vertStart[i+1] — соседи vertOther и рёбра vertEdge. */
    var vertStart = IntArray(1); private set
    var vertOther = IntArray(0); private set
    var vertEdge = IntArray(0); private set

    var edgeDead = BooleanArray(0); private set
    /**
     * НЕРОЖДЁННОЕ РЕБРО (рост тела, этап A2.4): мертво для контура, организмов и решателя,
     * но рабочие списки при уплотнении его не выбрасывают — клетка родится, и ребро
     * оживёт (revive). Отличает «ещё не выросло» от «порвано навсегда».
     */
    var edgeUnborn = BooleanArray(0); private set
    /** То же для треугольника: умер из-за нерождённой клетки, оживёт с ней. */
    var triUnborn = BooleanArray(0); private set
    /** Жив ли треугольник выгрузки. */
    var triAlive = BooleanArray(0); private set
    /** Счётчик по постоянному номеру треугольника: упаковка рабочих списков его не меняет. */
    internal var invertedTicks = ByteArray(body.triCount); private set
    /** Для кэша отрисовки. Замена массива triAlive при загрузке или уплотнении тоже меняет топологию. */
    var triangleRevision = 0L
        private set
    var liveTriangleCount = body.triCount
        private set
    /** Сколько живых треугольников опирается на ребро: 0 — голая связь, 1 — граница, 2 — внутри ткани. */
    var triUse = IntArray(0); private set

    var dirtyVertices = IntArray(body.count); private set
    private var dirty = BooleanArray(body.count)
    var dirtyCount = 0
        private set
    /** Углы граней у вершины: номер угла равен 3 * треугольник + локальная вершина. */
    var vertexTriangles = IntArray(0); private set
    var vertexTriStart = IntArray(1); private set
    private var restAngles = DoubleArray(0)

    internal fun touch(v: Int) {
        if (dirty[v]) return
        dirty[v] = true; dirtyVertices[dirtyCount++] = v
    }

    fun clearDirty() {
        for (k in 0 until dirtyCount) dirty[dirtyVertices[k]] = false
        dirtyCount = 0
    }

    fun surfaceAngle(v: Int): Double {
        var angle = 0.0
        for (s in vertexTriStart[v] until vertexTriStart[v + 1]) {
            val corner = vertexTriangles[s]
            if (triAlive[corner / 3]) angle += restAngles[corner]
        }
        return angle
    }

    fun opposite(e: Int): Int {
        if (triUse[e] != 1) return -1
        val t = liveTri(e)
        if (t < 0) return -1
        val u = edgeU(e); val v = edgeV(e)
        val a = body.triA[t]; val b = body.triB[t]; val c = body.triC[t]
        return if (a != u && a != v) a else if (b != u && b != v) b else c
    }

    /**
     * СКОЛЬКО ЖИВЫХ РЁБЕР ОСТАЛОСЬ БЕЗ ЕДИНОГО ТРЕУГОЛЬНИКА.
     *
     * Ровно из таких рёбер и берутся лоскуты (см. buildFlaps в демо). Пока их нет,
     * искать нечего, а поиск шёл проходом по всем связям на каждую пересборку — семь
     * миллисекунд из сорока на тяжёлом прогоне. Счётчик ведётся здесь, потому что
     * только здесь известно, когда у ребра умер последний треугольник.
     */
    var bareEdges = 0
        private set

    init {
        if (build) buildAll()
    }

    private fun buildAll() {
        val n = body.count
        val lc = body.linkCount
        val keys = LongArray(lc) { key(body.linkA[it], body.linkB[it]) }
        val uniq = keys.copyOf()
        uniq.sort()
        var m = 0
        for (k in uniq.indices) if (k == 0 || uniq[k] != uniq[k - 1]) uniq[m++] = uniq[k]
        edgeCount = m
        edgeKeys = uniq.copyOf(m)
        linkEdge = IntArray(lc) { java.util.Arrays.binarySearch(edgeKeys, keys[it]) }

        val tc = body.triCount
        triEdge = IntArray(tc * 3)
        for (t in 0 until tc) {
            val a = body.triA[t]; val b = body.triB[t]; val c = body.triC[t]
            triEdge[3 * t] = edgeIndex(edgeKeys, a, b)
            triEdge[3 * t + 1] = edgeIndex(edgeKeys, b, c)
            triEdge[3 * t + 2] = edgeIndex(edgeKeys, c, a)
        }
        useInit = IntArray(m)
        for (e in triEdge) if (e >= 0) useInit[e]++
        edgeTriStart = IntArray(m + 1)
        for (e in 0 until m) edgeTriStart[e + 1] = edgeTriStart[e] + useInit[e]
        edgeTris = IntArray(edgeTriStart[m])
        run {
            val fill = edgeTriStart.copyOf()
            for (s in triEdge.indices) {
                val e = triEdge[s]
                if (e >= 0) edgeTris[fill[e]++] = s / 3
            }
        }

        val deg = IntArray(n)
        for (e in 0 until m) { deg[(edgeKeys[e] ushr 32).toInt()]++; deg[edgeKeys[e].toInt()]++ }
        vertStart = IntArray(n + 1)
        for (i in 0 until n) vertStart[i + 1] = vertStart[i] + deg[i]
        vertOther = IntArray(vertStart[n]); vertEdge = IntArray(vertStart[n])
        run {
            val fill = vertStart.copyOf()
            for (e in 0 until m) {
                val u = (edgeKeys[e] ushr 32).toInt(); val v = edgeKeys[e].toInt()
                vertOther[fill[u]] = v; vertEdge[fill[u]++] = e
                vertOther[fill[v]] = u; vertEdge[fill[v]++] = e
            }
        }

        vertexTriStart = IntArray(n + 1)
        for (t in 0 until tc) {
            vertexTriStart[body.triA[t] + 1]++
            vertexTriStart[body.triB[t] + 1]++
            vertexTriStart[body.triC[t] + 1]++
        }
        for (v in 0 until n) vertexTriStart[v + 1] += vertexTriStart[v]
        vertexTriangles = IntArray(tc * 3)
        restAngles = DoubleArray(tc * 3)
        val fillTri = vertexTriStart.copyOf()
        for (t in 0 until tc) {
            val a = body.triA[t]; val b = body.triB[t]; val c = body.triC[t]
            vertexTriangles[fillTri[a]++] = 3 * t
            vertexTriangles[fillTri[b]++] = 3 * t + 1
            vertexTriangles[fillTri[c]++] = 3 * t + 2
            restAngles[3 * t] = angle(a, b, c)
            restAngles[3 * t + 1] = angle(b, a, c)
            restAngles[3 * t + 2] = angle(c, a, b)
        }

        edgeDead = BooleanArray(m)
        triAlive = BooleanArray(tc) { true }
        triUse = useInit.copyOf()
        for (e in triUse.indices) if (triUse[e] == 0) bareEdges++
    }

    /**
     * ДОПИСАТЬ ТЕЛО: [combined] — прежний набор тел и новое после него; новые частицы —
     * [n0] и дальше, связи — с [l0], треугольники — с [t0]. Те же правила, что в init,
     * только по новому телу: номера частиц нового тела больше всех прежних, поэтому его
     * рёбра в сортированном списке ключей встают после всех прежних, и ни один прежний
     * номер ребра, ни одна прежняя строка смежности не сдвигаются. Состояние прежних
     * рёбер и треугольников (смерть, счётчики) остаётся как было. Стоимость — по телу,
     * плюс копирование массивов при росте.
     */
    internal fun append(combined: BodyDefinition, n0: Int, l0: Int, t0: Int) {
        body = combined
        val n = combined.count; val lc = combined.linkCount; val tc = combined.triCount
        val keys = LongArray(lc - l0) { key(combined.linkA[l0 + it], combined.linkB[l0 + it]) }
        val uniq = keys.copyOf()
        uniq.sort()
        var m1 = 0
        for (k in uniq.indices) if (k == 0 || uniq[k] != uniq[k - 1]) uniq[m1++] = uniq[k]
        val m0 = edgeCount; val m = m0 + m1
        check(m1 == 0 || m0 == 0 || uniq[0] > edgeKeys[m0 - 1]) { "новое тело обязано идти после прежних" }
        edgeKeys = edgeKeys.copyOf(m); System.arraycopy(uniq, 0, edgeKeys, m0, m1)
        edgeCount = m
        linkEdge = linkEdge.copyOf(lc)
        for (k in l0 until lc) linkEdge[k] = m0 + java.util.Arrays.binarySearch(uniq, 0, m1, keys[k - l0])

        triEdge = triEdge.copyOf(tc * 3)
        for (t in t0 until tc) {
            val a = combined.triA[t]; val b = combined.triB[t]; val c = combined.triC[t]
            triEdge[3 * t] = edgeIndex(edgeKeys, a, b)
            triEdge[3 * t + 1] = edgeIndex(edgeKeys, b, c)
            triEdge[3 * t + 2] = edgeIndex(edgeKeys, c, a)
        }
        useInit = useInit.copyOf(m)
        for (q in 3 * t0 until 3 * tc) { val e = triEdge[q]; if (e >= 0) useInit[e]++ }
        edgeTriStart = edgeTriStart.copyOf(m + 1)
        for (e in m0 until m) edgeTriStart[e + 1] = edgeTriStart[e] + useInit[e]
        edgeTris = edgeTris.copyOf(edgeTriStart[m])
        run {
            val fill = edgeTriStart.copyOf()
            for (q in 3 * t0 until 3 * tc) {
                val e = triEdge[q]
                if (e >= 0) edgeTris[fill[e]++] = q / 3
            }
        }

        val deg = IntArray(n - n0)
        for (e in m0 until m) { deg[(edgeKeys[e] ushr 32).toInt() - n0]++; deg[edgeKeys[e].toInt() - n0]++ }
        vertStart = vertStart.copyOf(n + 1)
        for (i in n0 until n) vertStart[i + 1] = vertStart[i] + deg[i - n0]
        vertOther = vertOther.copyOf(vertStart[n]); vertEdge = vertEdge.copyOf(vertStart[n])
        run {
            val fill = vertStart.copyOf()
            for (e in m0 until m) {
                val u = (edgeKeys[e] ushr 32).toInt(); val v = edgeKeys[e].toInt()
                vertOther[fill[u]] = v; vertEdge[fill[u]++] = e
                vertOther[fill[v]] = u; vertEdge[fill[v]++] = e
            }
        }

        vertexTriStart = vertexTriStart.copyOf(n + 1)
        for (t in t0 until tc) {
            vertexTriStart[combined.triA[t] + 1]++
            vertexTriStart[combined.triB[t] + 1]++
            vertexTriStart[combined.triC[t] + 1]++
        }
        for (v in n0 until n) vertexTriStart[v + 1] += vertexTriStart[v]
        vertexTriangles = vertexTriangles.copyOf(tc * 3)
        restAngles = restAngles.copyOf(tc * 3)
        run {
            val fillTri = vertexTriStart.copyOf()
            for (t in t0 until tc) {
                val a = combined.triA[t]; val b = combined.triB[t]; val c = combined.triC[t]
                vertexTriangles[fillTri[a]++] = 3 * t
                vertexTriangles[fillTri[b]++] = 3 * t + 1
                vertexTriangles[fillTri[c]++] = 3 * t + 2
                restAngles[3 * t] = angle(a, b, c)
                restAngles[3 * t + 1] = angle(b, a, c)
                restAngles[3 * t + 2] = angle(c, a, b)
            }
        }

        edgeDead = edgeDead.copyOf(m)
        edgeUnborn = edgeUnborn.copyOf(m)
        triUnborn = triUnborn.copyOf(tc)
        triAlive = triAlive.copyOf(tc).also { java.util.Arrays.fill(it, t0, tc, true) }
        invertedTicks = invertedTicks.copyOf(tc)
        liveTriangleCount += tc - t0
        triUse = triUse.copyOf(m)
        for (e in m0 until m) { triUse[e] = useInit[e]; if (triUse[e] == 0) bareEdges++ }
        dirtyVertices = dirtyVertices.copyOf(n)
        dirty = dirty.copyOf(n)
    }

    /**
     * МИР УПЛОТНЁН (WorldCompaction): эта топология (создана с build = false по уплотнённому
     * описанию) заполняется фильтром из [old] — без сортировки ключей и без углов покоя
     * заново: на 100k постройка стоила ~80 мс, фильтр — линейные проходы. Номера частиц
     * уплотнены с сохранением порядка, поэтому оставшиеся рёбра, строки смежности и
     * треугольники идут в прежнем порядке, а результат — ровно тот, что дала бы постройка
     * по новому описанию (CompactCheck сверяет). Изменяемое — смерть и нерождённость
     * рёбер и треугольников, счётчики инверсии, живые треугольники на рёбрах — переезжает.
     * [pNew], [eNew], [tNew] — новые номера частиц, рёбер, треугольников (-1 — выброшены);
     * [linkKept] — оставшиеся связи описания.
     */
    internal fun compactFrom(old: BakedTopology, pNew: IntArray, eNew: IntArray, tNew: IntArray, linkKept: BooleanArray) {
        val n = body.count; val tc = body.triCount
        var m = 0
        for (e in 0 until old.edgeCount) if (eNew[e] >= 0) m++
        edgeCount = m
        edgeKeys = LongArray(m); useInit = IntArray(m); triUse = IntArray(m); edgeDead = BooleanArray(m)
        edgeUnborn = BooleanArray(if (old.edgeUnborn.size >= old.edgeCount) m else 0)
        edgeTriStart = IntArray(m + 1)
        for (e in 0 until old.edgeCount) {
            val k = eNew[e]
            if (k < 0) continue
            edgeKeys[k] = key(pNew[old.edgeU(e)], pNew[old.edgeV(e)])
            useInit[k] = old.useInit[e]; triUse[k] = old.triUse[e]; edgeDead[k] = old.edgeDead[e]
            if (edgeUnborn.isNotEmpty()) edgeUnborn[k] = old.edgeUnborn[e]
            edgeTriStart[k + 1] = edgeTriStart[k] + (old.edgeTriStart[e + 1] - old.edgeTriStart[e])
        }
        edgeTris = IntArray(edgeTriStart[m])
        for (e in 0 until old.edgeCount) {
            val k = eNew[e]
            if (k < 0) continue
            var w = edgeTriStart[k]
            for (s in old.edgeTriStart[e] until old.edgeTriStart[e + 1]) edgeTris[w++] = tNew[old.edgeTris[s]]
        }
        linkEdge = IntArray(body.linkCount)
        run { var l = 0; for (o in linkKept.indices) if (linkKept[o]) linkEdge[l++] = eNew[old.linkEdge[o]] }
        triEdge = IntArray(tc * 3); restAngles = DoubleArray(tc * 3)
        triAlive = BooleanArray(tc); invertedTicks = ByteArray(tc)
        triUnborn = BooleanArray(if (old.triUnborn.size >= old.body.triCount) tc else 0)
        for (t in 0 until old.body.triCount) {
            val k = tNew[t]
            if (k < 0) continue
            for (j in 0..2) {
                val e = old.triEdge[3 * t + j]
                triEdge[3 * k + j] = if (e < 0) -1 else eNew[e]
                restAngles[3 * k + j] = old.restAngles[3 * t + j]
            }
            triAlive[k] = old.triAlive[t]; invertedTicks[k] = old.invertedTicks[t]
            if (triUnborn.isNotEmpty()) triUnborn[k] = old.triUnborn[t]
        }
        vertStart = IntArray(n + 1); vertexTriStart = IntArray(n + 1)
        for (i in 0 until old.body.count) {
            val j = pNew[i]
            if (j < 0) continue
            vertStart[j + 1] = vertStart[j] + (old.vertStart[i + 1] - old.vertStart[i])
            vertexTriStart[j + 1] = vertexTriStart[j] + (old.vertexTriStart[i + 1] - old.vertexTriStart[i])
        }
        vertOther = IntArray(vertStart[n]); vertEdge = IntArray(vertStart[n]); vertexTriangles = IntArray(vertexTriStart[n])
        for (i in 0 until old.body.count) {
            val j = pNew[i]
            if (j < 0) continue
            var w = vertStart[j]
            for (s in old.vertStart[i] until old.vertStart[i + 1]) {
                vertOther[w] = pNew[old.vertOther[s]]; vertEdge[w] = eNew[old.vertEdge[s]]; w++
            }
            w = vertexTriStart[j]
            for (s in old.vertexTriStart[i] until old.vertexTriStart[i + 1]) {
                val corner = old.vertexTriangles[s]
                vertexTriangles[w++] = 3 * tNew[corner / 3] + corner % 3
            }
        }
        liveTriangleCount = 0
        for (k in 0 until tc) if (triAlive[k]) liveTriangleCount++
        bareEdges = 0
        for (k in 0 until m) if (!edgeDead[k] && triUse[k] == 0) bareEdges++
        dirtyVertices = IntArray(n); dirty = BooleanArray(n); dirtyCount = 0
    }

    /** Изменяемое состояние в снимок мира (WorldSnapshot): структура выводится из описания. */
    internal fun writeState(out: java.io.DataOutput) {
        out.writeBooleans(edgeDead, edgeCount); out.writeInts(triUse, edgeCount)
        out.writeBooleans(edgeUnborn, edgeUnborn.size)
        out.writeBooleans(triAlive, body.triCount); out.writeBytes(invertedTicks, body.triCount)
        out.writeBooleans(triUnborn, triUnborn.size)
    }

    /** Состояние из снимка поверх топологии, построенной по тому же описанию. */
    internal fun readState(input: java.io.DataInput) {
        edgeDead = input.readBooleans(edgeCount); triUse = input.readInts(edgeCount)
        edgeUnborn = input.readBooleans(0)
        triAlive = input.readBooleans(body.triCount); invertedTicks = input.readBytes(body.triCount)
        triUnborn = input.readBooleans(0)
        check(edgeDead.size == edgeCount && triAlive.size == body.triCount) { "снимок не от этого описания" }
        liveTriangleCount = 0
        for (t in 0 until body.triCount) if (triAlive[t]) liveTriangleCount++
        bareEdges = 0
        for (e in 0 until edgeCount) if (!edgeDead[e] && triUse[e] == 0) bareEdges++
        clearDirty()
    }

    /** Ребро [e] не рождено: гаснет вместе со своими треугольниками, помечается к оживлению. */
    internal fun markUnborn(e: Int) {
        if (edgeUnborn.size < edgeCount) edgeUnborn = edgeUnborn.copyOf(edgeCount)
        if (triUnborn.size < body.triCount) triUnborn = triUnborn.copyOf(body.triCount)
        if (edgeDead[e]) return
        for (s in edgeTriStart[e] until edgeTriStart[e + 1]) {
            val t = edgeTris[s]
            if (triAlive[t]) triUnborn[t] = true
        }
        kill(e)
        edgeUnborn[e] = true
    }

    internal fun isUnborn(e: Int): Boolean = e < edgeUnborn.size && edgeUnborn[e]
    internal fun isTriUnborn(t: Int): Boolean = t < triUnborn.size && triUnborn[t]

    /** Нерождённое ребро или треугольник больше не родится (тело удалено, ребро порвано): просто мёртв. */
    internal fun forgetUnborn(e: Int) { if (isUnborn(e)) edgeUnborn[e] = false }
    internal fun forgetTriUnborn(t: Int) { if (isTriUnborn(t)) triUnborn[t] = false }

    /** Оживить нерождённое ребро (обе клетки родились). false — ребро не было нерождённым. */
    internal fun revive(e: Int): Boolean {
        if (e >= edgeUnborn.size || !edgeUnborn[e]) return false
        edgeUnborn[e] = false
        edgeDead[e] = false
        if (triUse[e] == 0) bareEdges++
        touch(edgeU(e)); touch(edgeV(e))
        return true
    }

    /** Оживить нерождённый треугольник, если все его рёбра живы. */
    internal fun reviveTriangle(t: Int): Boolean {
        if (t >= triUnborn.size || !triUnborn[t]) return false
        for (k in 0..2) { val e = triEdge[3 * t + k]; if (e >= 0 && edgeDead[e]) return false }
        triUnborn[t] = false
        triAlive[t] = true
        triangleRevision++
        liveTriangleCount++
        touch(body.triA[t]); touch(body.triB[t]); touch(body.triC[t])
        for (k in 0..2) {
            val e = triEdge[3 * t + k]
            if (e >= 0 && triUse[e]++ == 0 && !edgeDead[e]) bareEdges--
        }
        return true
    }

    /** Топология снова целая. */
    fun restore() {
        clearDirty()
        edgeDead.fill(false)
        edgeUnborn.fill(false); triUnborn.fill(false)
        triAlive.fill(true)
        triangleRevision++
        invertedTicks.fill(0)
        liveTriangleCount = body.triCount
        useInit.copyInto(triUse)
        bareEdges = 0
        for (e in triUse.indices) if (triUse[e] == 0) bareEdges++
    }

    /**
     * Погасить ребро. Треугольники на нём умирают, счётчики их рёбер уменьшаются.
     * false — ребро уже было мёртвым.
     */
    fun kill(e: Int): Boolean {
        if (edgeDead[e]) return false
        if (triUse[e] == 0) bareEdges--
        edgeDead[e] = true
        touch(edgeU(e)); touch(edgeV(e))
        for (s in edgeTriStart[e] until edgeTriStart[e + 1]) {
            killTriangle(edgeTris[s])
        }
        return true
    }

    /** Удалить только площадь ткани, сохранив её клетки и связи. */
    internal fun killTriangle(t: Int): Boolean {
        if (!triAlive[t]) return false
        triAlive[t] = false
        triangleRevision++
        invertedTicks[t] = 0
        liveTriangleCount--
        touch(body.triA[t]); touch(body.triB[t]); touch(body.triC[t])
        for (k in 0..2) {
            val e = triEdge[3 * t + k]
            if (e >= 0 && --triUse[e] == 0 && !edgeDead[e]) bareEdges++
        }
        return true
    }

    /**
     * ЕДИНСТВЕННЫЙ ЖИВОЙ ТРЕУГОЛЬНИК НА РЕБРЕ, или -1, если их нет или больше одного
     * живого не нужно различать. Нужен ВНЕШНЕЙ НОРМАЛИ КОНТУРА: у граничного ребра
     * треугольник один, и его третья вершина лежит ВНУТРИ тела — значит направление
     * «наружу» определяется локально, без обхода контура и без знания его намотки.
     */
    fun liveTri(e: Int): Int {
        for (s in edgeTriStart[e] until edgeTriStart[e + 1]) {
            val t = edgeTris[s]
            if (triAlive[t]) return t
        }
        return -1
    }

    /** Меньшая клетка ребра. */
    fun edgeU(e: Int): Int = (edgeKeys[e] ushr 32).toInt()

    /** Большая клетка ребра. */
    fun edgeV(e: Int): Int = edgeKeys[e].toInt()

    /** Ребро пары клеток или -1. Степень клетки мала, поиск линейный. */
    fun edgeOf(i: Int, j: Int): Int {
        for (s in vertStart[i] until vertStart[i + 1]) if (vertOther[s] == j) return vertEdge[s]
        return -1
    }

    private fun angle(v: Int, p: Int, q: Int): Double {
        val ax = (body.x[p] - body.x[v]).toDouble(); val ay = (body.y[p] - body.y[v]).toDouble()
        val bx = (body.x[q] - body.x[v]).toDouble(); val by = (body.y[q] - body.y[v]).toDouble()
        val na = kotlin.math.sqrt(ax * ax + ay * ay); val nb = kotlin.math.sqrt(bx * bx + by * by)
        if (na < 1e-12 || nb < 1e-12) return 0.0
        return StrictMath.acos(((ax * bx + ay * by) / (na * nb)).coerceIn(-1.0, 1.0))
    }

    private companion object {
        fun key(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

        fun edgeIndex(edgeKeys: LongArray, a: Int, b: Int): Int {
            if (a == b) return -1
            val i = java.util.Arrays.binarySearch(edgeKeys, key(a, b))
            return if (i >= 0) i else -1
        }
    }
}
