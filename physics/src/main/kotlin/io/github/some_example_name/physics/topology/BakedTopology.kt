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
class BakedTopology(private val body: BodyDefinition) {
    /** Число рёбер — различных пар среди связей выгрузки. */
    val edgeCount: Int
    /** Ребро каждой связи выгрузки, по номеру в body.linkA / body.linkB. */
    val linkEdge: IntArray
    private val edgeKeys: LongArray
    /** Рёбра треугольника t выгрузки: 3t, 3t+1, 3t+2 — пары (A,B), (B,C), (C,A); -1 — таких связей нет. */
    val triEdge: IntArray
    internal val edgeTriStart: IntArray
    internal val edgeTris: IntArray
    private val useInit: IntArray
    /** Связи каждой клетки: vertStart[i]..vertStart[i+1] — соседи vertOther и рёбра vertEdge. */
    val vertStart: IntArray
    val vertOther: IntArray
    val vertEdge: IntArray

    val edgeDead: BooleanArray
    /** Жив ли треугольник выгрузки. */
    val triAlive: BooleanArray
    /** Счётчик по постоянному номеру треугольника: упаковка рабочих списков его не меняет. */
    internal val invertedTicks = ByteArray(body.triCount)
    var liveTriangleCount = body.triCount
        private set
    /** Сколько живых треугольников опирается на ребро: 0 — голая связь, 1 — граница, 2 — внутри ткани. */
    val triUse: IntArray

    val dirtyVertices = IntArray(body.count)
    private val dirty = BooleanArray(body.count)
    var dirtyCount = 0
        private set
    internal val vertexTriangles: IntArray
    internal val vertexTriStart: IntArray
    private val restAngles: DoubleArray

    private fun touch(v: Int) {
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

    /** Топология снова целая. */
    fun restore() {
        clearDirty()
        edgeDead.fill(false)
        triAlive.fill(true)
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
