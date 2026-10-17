package io.github.some_example_name.physics

/** После удаления ребра меняется только меньший из отделившихся кусков. ID остальных стабильны. */
class LocalComponents(
    private val topology: BakedTopology,
    private val of: IntArray,
    private val size: IntArray,
    private val mass: DoubleArray,
    private val restInvMass: DoubleArray,
    private val dead: BooleanArray,
    private val flowX: DoubleArray,
    private val flowY: DoubleArray,
    var count: Int,
) {
    private val seen = IntArray(of.size)
    private val frontA = IntArray(of.size)
    private val frontB = IntArray(of.size)
    private var stamp = 0

    fun removeCell(v: Int) {
        val o = of[v]
        size[o]--
        if (restInvMass[v] > 0.0) mass[o] -= 1.0 / restInvMass[v]
    }

    fun split(edge: Int) {
        val u = topology.edgeU(edge); val v = topology.edgeV(edge)
        if (of[u] != of[v]) return
        if (stamp > Int.MAX_VALUE - 3) { seen.fill(0); stamp = 0 }
        stamp += 2
        val aMark = stamp; val bMark = stamp + 1
        seen[u] = aMark; seen[v] = bMark
        frontA[0] = u; frontB[0] = v
        var ah = 0; var at = 1; var bh = 0; var bt = 1
        while (ah < at && bh < bt) {
            val a = frontA[ah++]
            for (s in topology.vertStart[a] until topology.vertStart[a + 1]) {
                if (topology.edgeDead[topology.vertEdge[s]]) continue
                val w = topology.vertOther[s]
                if (seen[w] == bMark) return
                if (seen[w] != aMark) { seen[w] = aMark; frontA[at++] = w }
            }
            val b = frontB[bh++]
            for (s in topology.vertStart[b] until topology.vertStart[b + 1]) {
                if (topology.edgeDead[topology.vertEdge[s]]) continue
                val w = topology.vertOther[s]
                if (seen[w] == aMark) return
                if (seen[w] != bMark) { seen[w] = bMark; frontB[bt++] = w }
            }
        }
        val queue = if (ah == at) frontA else frontB
        val length = if (ah == at) at else bt
        val old = of[queue[0]]; val fresh = count++
        size[fresh] = 0; mass[fresh] = 0.0
        flowX[fresh] = flowX[old]; flowY[fresh] = flowY[old]
        for (k in 0 until length) {
            val w = queue[k]
            of[w] = fresh
            if (dead[w]) continue
            size[fresh]++
            if (restInvMass[w] > 0.0) mass[fresh] += 1.0 / restInvMass[w]
        }
        size[old] -= size[fresh]; mass[old] -= mass[fresh]
    }
}
