package io.github.some_example_name.physics.lab.checks

/**
 * Независимая проверка для стендов: полный перебор, без контактных радиусов и исключений решателя.
 * Позиции мира физики во float, знаки площадей — в double: стенд мерит точнее, чем считает физика.
 */
internal fun foreignTissueCount(n: Int, x: FloatArray, y: FloatArray, org: IntArray,
                              dead: BooleanArray, a: IntArray, b: IntArray, c: IntArray,
                              triangles: Int): Int {
    var inside = 0
    for (i in 0 until n) {
        if (dead[i]) continue
        for (t in 0 until triangles) {
            val u = a[t]; val v = b[t]; val w = c[t]
            if (org[i] == org[u] || dead[u] || dead[v] || dead[w]) continue
            val px = x[i].toDouble(); val py = y[i].toDouble()
            val xu = x[u].toDouble(); val xv = x[v].toDouble(); val xw = x[w].toDouble()
            val yu = y[u].toDouble(); val yv = y[v].toDouble(); val yw = y[w].toDouble()
            if (px <= minOf(xu, xv, xw) || px >= maxOf(xu, xv, xw) ||
                py <= minOf(yu, yv, yw) || py >= maxOf(yu, yv, yw)) continue
            val s0 = (xv - xu) * (py - yu) - (yv - yu) * (px - xu)
            val s1 = (xw - xv) * (py - yv) - (yw - yv) * (px - xv)
            val s2 = (xu - xw) * (py - yw) - (yu - yw) * (px - xw)
            if ((s0 > 0 && s1 > 0 && s2 > 0) || (s0 < 0 && s1 < 0 && s2 < 0)) {
                inside++; break
            }
        }
    }
    return inside
}
