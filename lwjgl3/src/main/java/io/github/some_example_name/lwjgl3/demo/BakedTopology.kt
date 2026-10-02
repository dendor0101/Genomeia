package io.github.some_example_name.lwjgl3.demo

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
internal class BakedTopology(body: BodyFile) {
    /** Число рёбер — различных пар среди связей выгрузки. */
    val edgeCount: Int
    /** Ребро каждой связи выгрузки, по номеру в body.linkA / body.linkB. */
    val linkEdge: IntArray
    private val edgeKeys: LongArray
    /** Рёбра треугольника t выгрузки: 3t, 3t+1, 3t+2 — пары (A,B), (B,C), (C,A); -1 — таких связей нет. */
    val triEdge: IntArray
    private val edgeTriStart: IntArray
    private val edgeTris: IntArray
    private val useInit: IntArray
    /** Связи каждой клетки: vertStart[i]..vertStart[i+1] — соседи vertOther и рёбра vertEdge. */
    val vertStart: IntArray
    val vertOther: IntArray
    val vertEdge: IntArray

    val edgeDead: BooleanArray
    /** Жив ли треугольник выгрузки. */
    val triAlive: BooleanArray
    /** Сколько живых треугольников опирается на ребро: 0 — голая связь, 1 — граница, 2 — внутри ткани. */
    val triUse: IntArray

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

        edgeDead = BooleanArray(m)
        triAlive = BooleanArray(tc) { true }
        triUse = useInit.copyOf()
        for (e in triUse.indices) if (triUse[e] == 0) bareEdges++
    }

    /** Топология снова целая. */
    fun restore() {
        edgeDead.fill(false)
        triAlive.fill(true)
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
        edgeDead[e] = true
        for (s in edgeTriStart[e] until edgeTriStart[e + 1]) {
            val t = edgeTris[s]
            if (!triAlive[t]) continue
            triAlive[t] = false
            for (k in 0..2) {
                val e2 = triEdge[3 * t + k]
                if (e2 >= 0 && --triUse[e2] == 0) bareEdges++
            }
        }
        return true
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

    private companion object {
        fun key(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

        fun edgeIndex(edgeKeys: LongArray, a: Int, b: Int): Int {
            if (a == b) return -1
            val i = java.util.Arrays.binarySearch(edgeKeys, key(a, b))
            return if (i >= 0) i else -1
        }
    }
}
