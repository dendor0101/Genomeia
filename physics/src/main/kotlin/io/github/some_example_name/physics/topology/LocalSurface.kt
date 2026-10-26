package io.github.some_example_name.physics

import kotlin.math.sqrt

/** Переиспользуемые строки смежности: разрыв меняет только концы ребра и его треугольники. */
class LocalSurface(
    private var body: BodyDefinition,
    private val topology: BakedTopology,
    private var boneOf: IntArray,
    private var dead: BooleanArray,
    private val scale: Double,
    private val seal: Double,
    private val angleEps: Double,
    free: BooleanArray,
    probe: BooleanArray,
    private val probesEnabled: Boolean = true,
) {
    var free = free; private set
    var probe = probe; private set
    var start = topology.vertStart; private set
    var adj = IntArray(topology.vertOther.size); private set
    var adjEnd = IntArray(body.count); private set
    var boundary = IntArray(adj.size); private set
    var opposite = IntArray(adj.size); private set
    var boundaryEnd = IntArray(body.count); private set
    var radius = DoubleArray(body.count); private set
    var active = BooleanArray(body.count); private set

    /**
     * Мир дописал тело (BakedTopology.append): строки прежних клеток стоят там же, их
     * состояние копируется как есть; строки новых заполнит update. Массивы клеток
     * мира (boneOf, dead, free, probe) после роста — новые объекты, ссылки обновляются.
     */
    internal fun grow(body: BodyDefinition, boneOf: IntArray, dead: BooleanArray, free: BooleanArray, probe: BooleanArray) {
        this.body = body; this.boneOf = boneOf; this.dead = dead; this.free = free; this.probe = probe
        val n = body.count
        start = topology.vertStart
        adj = adj.copyOf(topology.vertOther.size)
        boundary = boundary.copyOf(adj.size); opposite = opposite.copyOf(adj.size)
        adjEnd = adjEnd.copyOf(n); boundaryEnd = boundaryEnd.copyOf(n)
        radius = radius.copyOf(n); active = active.copyOf(n)
    }

    /**
     * МИР УПЛОТНЁН: эта поверхность (над уплотнённой топологией) заполняется из [old] — строки
     * оставшихся клеток переезжают на свои новые начала, номера клеток в них переводятся
     * ([pNew]). Длина строки у оставшейся клетки та же: все её рёбра остались. Пересчёт
     * update по всем клеткам на 100k стоил ~24 мс; здесь — копирование.
     */
    internal fun compactFrom(old: LocalSurface, pNew: IntArray) {
        val os = old.start
        for (i in 0 until old.adjEnd.size) {
            val j = pNew[i]
            if (j < 0) continue
            val from = os[i]; val to = start[j]
            for (s in from until old.adjEnd[i]) adj[to + s - from] = pNew[old.adj[s]]
            adjEnd[j] = to + old.adjEnd[i] - from
            for (s in from until old.boundaryEnd[i]) {
                boundary[to + s - from] = pNew[old.boundary[s]]
                val c = old.opposite[s]
                opposite[to + s - from] = if (c < 0) c else pNew[c]
            }
            boundaryEnd[j] = to + old.boundaryEnd[i] - from
            radius[j] = old.radius[i]; active[j] = old.active[i]
        }
    }

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
