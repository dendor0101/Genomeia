package io.github.some_example_name.physics

import kotlin.math.sqrt

/** Плотный список пар с принадлежностью вершине: заменить её ограничения можно за её степень. */
class VertexPairs(n: Int) {
    internal var blockLayout: ConstraintBlocks? = null
    internal var blockIndex: BlockIndex? = null
    private val head = IntArray(n) { -1 }
    private var owner = IntArray(maxOf(n, 16))
    private var next = IntArray(owner.size)
    private var prev = IntArray(owner.size)
    var a = IntArray(owner.size); private set
    var b = IntArray(owner.size); private set
    var rest = DoubleArray(owner.size); private set
    var count = 0; private set

    fun clear(v: Int) {
        while (head[v] >= 0) {
            val slot = head[v]
            blockIndex?.remove(slot)
            unlink(slot)
            val last = --count
            if (slot == last) continue
            blockIndex?.move(last, slot)
            a[slot] = a[last]; b[slot] = b[last]; rest[slot] = rest[last]
            owner[slot] = owner[last]; next[slot] = next[last]; prev[slot] = prev[last]
            if (prev[slot] < 0) head[owner[slot]] = slot else next[prev[slot]] = slot
            if (next[slot] >= 0) prev[next[slot]] = slot
        }
    }

    private fun unlink(slot: Int) {
        if (prev[slot] < 0) head[owner[slot]] = next[slot] else next[prev[slot]] = next[slot]
        if (next[slot] >= 0) prev[next[slot]] = prev[slot]
    }

    fun add(v: Int, i: Int, j: Int, body: BodyDefinition) {
        if (i == j) return
        if (count == a.size) {
            val cap = a.size * 2
            a = a.copyOf(cap); b = b.copyOf(cap); rest = rest.copyOf(cap)
            owner = owner.copyOf(cap); next = next.copyOf(cap); prev = prev.copyOf(cap)
        }
        val dx = (body.x[i] - body.x[j]).toDouble(); val dy = (body.y[i] - body.y[j]).toDouble()
        a[count] = i; b[count] = j; rest[count] = sqrt(dx * dx + dy * dy)
        owner[count] = v; prev[count] = -1; next[count] = head[v]
        if (head[v] >= 0) prev[head[v]] = count
        blockIndex?.add(count, blockLayout!!.blockOf(i, j))
        head[v] = count++
    }
}

/** Контур, изгиб и лоскуты обновляются на месте; полного обхода связей при разрыве нет. */
class LocalBoundaryConstraints(
    private val body: BodyDefinition,
    private val topology: BakedTopology,
    private val boneOf: IntArray,
) {
    val a = IntArray(topology.edgeCount)
    val b = IntArray(topology.edgeCount)
    val opposite = IntArray(topology.edgeCount)
    // 1 байт/ребро: знак стороны ткани считается при изменении топологии,
    // чтобы горячий проход не читал координаты покоя (~300 КБ на сетку 100k).
    val orientation = ByteArray(topology.edgeCount)
    private val edgeAt = IntArray(topology.edgeCount)
    private val slotOf = IntArray(topology.edgeCount) { -1 }
    var count = 0; private set
    val bend = VertexPairs(body.count)
    val flap = VertexPairs(body.count)
    private val neighbors = IntArray(body.count)

    fun updateEdge(e: Int) {
        val slot = slotOf[e]
        if (topology.edgeDead[e] || topology.triUse[e] > 1) {
            if (slot < 0) return
            val last = --count
            a[slot] = a[last]; b[slot] = b[last]; opposite[slot] = opposite[last]
            orientation[slot] = orientation[last]
            edgeAt[slot] = edgeAt[last]; slotOf[edgeAt[slot]] = slot
            slotOf[e] = -1
        } else {
            val dst = if (slot >= 0) slot else count++
            a[dst] = topology.edgeU(e); b[dst] = topology.edgeV(e)
            opposite[dst] = topology.opposite(e); edgeAt[dst] = e; slotOf[e] = dst
            val i = a[dst]; val j = b[dst]; val c = opposite[dst]
            orientation[dst] = if (c < 0) 0 else {
                val area = (body.x[j] - body.x[i]).toDouble() * (body.y[c] - body.y[i]) -
                    (body.y[j] - body.y[i]).toDouble() * (body.x[c] - body.x[i])
                if (area > 0.0) 1 else if (area < 0.0) -1 else 0
            }
        }
    }

    fun updateVertex(v: Int) {
        bend.clear(v); flap.clear(v)
        var nb0 = -1; var nb1 = -1; var degree = 0; var bare = 0
        for (s in topology.vertStart[v] until topology.vertStart[v + 1]) {
            val e = topology.vertEdge[s]
            updateEdge(e)
            if (topology.edgeDead[e]) continue
            val u = topology.vertOther[s]
            val use = topology.triUse[e]
            if (use <= 1) {
                if (degree == 0) nb0 = u else if (degree == 1) nb1 = u
                degree++
            }
            if (use == 0 && (boneOf[v] < 0 || boneOf[v] != boneOf[u])) neighbors[bare++] = u
        }
        if (degree == 2) bend.add(v, nb0, nb1, body)
        for (p in 0 until bare) for (q in p + 1 until bare) flap.add(v, neighbors[p], neighbors[q], body)
    }
}
