package io.github.some_example_name.physics

/** Эксперимент: баланс импульса за тик. Внешние изменения скоростей учитываются
 * отдельно; контакты объединяют группы. Разрыв в середине тика не отменяет уже
 * переданный импульс. Раздельные тела вновь разделяются на следующем begin.
 * 12 double + 2 int + boolean на частицу: ~10.5 МБ при 100k, больше L2.
 * Это цена прямого варианта; выигрыш должен подтвердить замер.
 */
class TickMomentum(private val n: Int) {
    private val parent = IntArray(n)
    private val first = IntArray(n)
    private val targetX = DoubleArray(n)
    private val targetY = DoubleArray(n)
    private val targetL = DoubleArray(n)
    private val beforeX = DoubleArray(n)
    private val beforeY = DoubleArray(n)
    private val mass = DoubleArray(n)
    private val cx = DoubleArray(n)
    private val cy = DoubleArray(n)
    private val dx = DoubleArray(n)
    private val dy = DoubleArray(n)
    private val dl = DoubleArray(n)
    private val inertia = DoubleArray(n)
    private val blocked = BooleanArray(n)
    private fun find(i: Int): Int {
        var r = i
        while (parent[r] != r) r = parent[r]
        var v = i
        while (parent[v] != r) { val next = parent[v]; parent[v] = r; v = next }
        return r
    }
    private fun union(i: Int, j: Int) {
        val a = find(i); val b = find(j)
        if (a != b) parent[maxOf(a, b)] = minOf(a, b)
    }
    fun begin(x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray,
              invMass: DoubleArray, org: IntArray) {
        first.fill(-1); blocked.fill(false)
        for (i in 0 until n) parent[i] = i
        for (i in 0 until n) {
            val o = org[i]
            if (o >= 0) { if (first[o] < 0) first[o] = i else union(first[o], i) }
            val m = if (invMass[i] > 0.0) 1.0 / invMass[i] else 0.0
            targetX[i] = m * vx[i]; targetY[i] = m * vy[i]
            targetL[i] = m * (x[i] * vy[i] - y[i] * vx[i])
            blocked[i] = invMass[i] <= 0.0
        }
    }
    fun noteContacts(c: BoundaryContacts) {
        for (k in 0 until c.contactCount) union(c.contactI(k), c.contactJ(k))
    }
    fun beforeExternal(vx: DoubleArray, vy: DoubleArray) {
        System.arraycopy(vx, 0, beforeX, 0, n)
        System.arraycopy(vy, 0, beforeY, 0, n)
    }
    fun afterExternal(x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray,
                      invMass: DoubleArray, floor: BooleanArray? = null) {
        for (i in 0 until n) {
            if (floor != null && floor[i]) blocked[i] = true
            if (invMass[i] <= 0.0) continue
            val m = 1.0 / invMass[i]
            val fx = m * (vx[i] - beforeX[i]); val fy = m * (vy[i] - beforeY[i])
            targetX[i] += fx; targetY[i] += fy
            targetL[i] += x[i] * fy - y[i] * fx
        }
    }
    fun cancel(x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray,
               invMass: DoubleArray) {
        mass.fill(0.0); cx.fill(0.0); cy.fill(0.0)
        dx.fill(0.0); dy.fill(0.0); dl.fill(0.0); inertia.fill(0.0)
        for (i in 0 until n) {
            val g = find(i)
            if (blocked[i] || invMass[i] <= 0.0) { blocked[g] = true; continue }
            val m = 1.0 / invMass[i]
            mass[g] += m; cx[g] += m * x[i]; cy[g] += m * y[i]
            dx[g] += m * vx[i] - targetX[i]; dy[g] += m * vy[i] - targetY[i]
            dl[g] += m * (x[i] * vy[i] - y[i] * vx[i]) - targetL[i]
        }
        for (g in 0 until n) if (mass[g] > 0.0) {
            cx[g] /= mass[g]; cy[g] /= mass[g]
            dl[g] -= cx[g] * dy[g] - cy[g] * dx[g]
            dx[g] /= mass[g]; dy[g] /= mass[g]
        }
        for (i in 0 until n) {
            if (invMass[i] <= 0.0) continue
            val g = find(i); val rx = x[i] - cx[g]; val ry = y[i] - cy[g]
            inertia[g] += (rx * rx + ry * ry) / invMass[i]
        }
        for (g in 0 until n) dl[g] = if (inertia[g] > 1e-18) dl[g] / inertia[g] else 0.0
        for (i in 0 until n) {
            val g = find(i)
            if (blocked[g] || invMass[i] <= 0.0) continue
            vx[i] -= dx[g] - dl[g] * (y[i] - cy[g])
            vy[i] -= dy[g] + dl[g] * (x[i] - cx[g])
        }
    }
}
