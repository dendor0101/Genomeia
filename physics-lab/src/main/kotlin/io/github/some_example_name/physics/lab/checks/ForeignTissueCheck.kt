package io.github.some_example_name.physics.lab.checks

/** Независимая проверка для стендов: полный перебор, без контактных радиусов и исключений решателя. */
internal fun foreignTissueCount(n: Int, x: DoubleArray, y: DoubleArray, org: IntArray,
                              dead: BooleanArray, a: IntArray, b: IntArray, c: IntArray,
                              triangles: Int): Int {
    var inside = 0
    for (i in 0 until n) {
        if (dead[i]) continue
        for (t in 0 until triangles) {
            val u = a[t]; val v = b[t]; val w = c[t]
            if (org[i] == org[u] || dead[u] || dead[v] || dead[w]) continue
            val px = x[i]; val py = y[i]
            if (px <= minOf(x[u], x[v], x[w]) || px >= maxOf(x[u], x[v], x[w]) ||
                py <= minOf(y[u], y[v], y[w]) || py >= maxOf(y[u], y[v], y[w])) continue
            val s0 = (x[v] - x[u]) * (py - y[u]) - (y[v] - y[u]) * (px - x[u])
            val s1 = (x[w] - x[v]) * (py - y[v]) - (y[w] - y[v]) * (px - x[v])
            val s2 = (x[u] - x[w]) * (py - y[w]) - (y[u] - y[w]) * (px - x[w])
            if ((s0 > 0 && s1 > 0 && s2 > 0) || (s0 < 0 && s1 < 0 && s2 < 0)) {
                inside++; break
            }
        }
    }
    return inside
}
