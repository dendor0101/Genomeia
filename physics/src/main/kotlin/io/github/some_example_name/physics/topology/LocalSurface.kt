package io.github.some_example_name.physics

import kotlin.math.sqrt

/** Переиспользуемые строки смежности: разрыв меняет только концы ребра и его треугольники. */
class LocalSurface(
    private val body: BodyDefinition,
    private val topology: BakedTopology,
    private val boneOf: IntArray,
    private val dead: BooleanArray,
    private val scale: Double,
    private val seal: Double,
    private val angleEps: Double,
    val free: BooleanArray,
    val probe: BooleanArray,
    private val probesEnabled: Boolean = true,
) {
    val start = topology.vertStart
    val adj = IntArray(topology.vertOther.size)
    val adjEnd = IntArray(body.count)
    val boundary = IntArray(adj.size)
    val opposite = IntArray(adj.size)
    val boundaryEnd = IntArray(body.count)
    val radius = DoubleArray(body.count)
    val active = BooleanArray(body.count)

    fun update(v: Int) {
        var a = start[v]; var b = a; var degree = 0; var r = 0.0
        for (s in start[v] until start[v + 1]) {
            val edge = topology.vertEdge[s]
            if (topology.edgeDead[edge]) continue
            val other = topology.vertOther[s]
            degree++
            if (boneOf[v] < 0 || boneOf[v] != boneOf[other]) adj[a++] = other
            if (topology.triUse[edge] > 1) continue
            boundary[b] = other; opposite[b++] = topology.opposite(edge)
            val dx = (body.x[v] - body.x[other]).toDouble()
            val dy = (body.y[v] - body.y[other]).toDouble()
            r = maxOf(r, 0.5 * sqrt(dx * dx + dy * dy) * seal)
        }
        adjEnd[v] = a; boundaryEnd[v] = b
        free[v] = degree == 0
        probe[v] = free[v] || topology.surfaceAngle(v) < 2.0 * Math.PI - angleEps
        val exposed = free[v] || (probesEnabled && probe[v])
        active[v] = !dead[v] && (b > start[v] || exposed)
        if (r == 0.0 && (a == start[v] || exposed)) r = body.radius[v] * scale
        radius[v] = if (dead[v]) 0.0 else r
    }
}
