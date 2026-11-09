package io.github.some_example_name.physics

/** Эксперимент: баланс импульса за тик. Внешние изменения скоростей учитываются
 * отдельно; контакты объединяют группы. Разрыв в середине тика не отменяет уже
 * переданный импульс. Раздельные тела вновь разделяются на следующем begin.
 * 12 double + 2 int + boolean на частицу: ~10.5 МБ при 100k, больше L2.
 * Это цена прямого варианта; выигрыш должен подтвердить замер.
 */
class TickMomentum(internal val n: Int) {
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
    /**
     * Диапазоны частиц, по которым идёт учёт: бодрствующие (см. BodySleep.spanStart)
     * или все, если сна нет ([sleep] == null у зеркала SwimSolver). Спящая частица
     * заморожена: в группы не входит, её касание блокирует группу соседа (noteContacts).
     */
    private inline fun spans(sleep: BodySleep?, body: (Int, Int) -> Unit) {
        if (sleep == null) { body(0, n); return }
        for (k in 0 until sleep.spanCount) body(sleep.spanStart[k], sleep.spanEnd[k])
    }

    /**
     * [outside] — мёртвые клетки (BodyState.cellDead; нерождённые тоже мертвы): в группу своего
     * организма не входят, остаются одиночками без массы и группу организма не блокируют. Раньше
     * исключались только нерождённые, и первая же погибшая клетка навсегда выключала учёт
     * импульса всему организму (BirthKickProbe 03.11: у Crayfish со стадии 25 роста).
     */
    fun begin(x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray,
              invMass: DoubleArray, org: IntArray, sleep: BodySleep? = null, outside: BooleanArray? = null) {
        beginGroups(org, sleep, outside)
        spans(sleep) { from, to -> beginTargets(from, to, x, y, vx, vy, invMass) }
    }

    /** Группы по организмам: общие first и union, в одном потоке. */
    internal fun beginGroups(org: IntArray, sleep: BodySleep?, outside: BooleanArray?) {
        first.fill(-1)
        spans(sleep) { from, to -> for (i in from until to) parent[i] = i }
        spans(sleep) { from, to ->
            for (i in from until to) {
                val o = org[i]
                if (o >= 0 && (outside == null || !outside[i])) { if (first[o] < 0) first[o] = i else union(first[o], i) }
            }
        }
    }

    /** Импульс частиц [from, to) в начале тика. Кусок параллельного прохода: у каждой частицы свои поля. */
    internal fun beginTargets(from: Int, to: Int, x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray,
                              invMass: DoubleArray) {
        for (i in from until to) {
            val m = if (invMass[i] > 0.0) 1.0 / invMass[i] else 0.0
            targetX[i] = m * vx[i]; targetY[i] = m * vy[i]
            targetL[i] = m * (x[i] * vy[i] - y[i] * vx[i])
            blocked[i] = invMass[i] <= 0.0
        }
    }

    /**
     * Частица проснулась посреди тика: в начале тика она была неподвижной и
     * блокировала свою группу до конца тика. Так и остаётся — как было бы при учёте
     * всех частиц, когда спящая входила в begin с нулевой массой.
     */
    fun admitFrozen(i: Int) {
        parent[i] = i; blocked[i] = true
        targetX[i] = 0.0; targetY[i] = 0.0; targetL[i] = 0.0
    }

    /**
     * Контакт объединяет группы. КАСАНИЕ НЕПОДВИЖНОЙ ЧАСТИЦЫ (спящей, мёртвой) НЕ
     * ОБЪЕДИНЯЕТ, а блокирует группу касающегося: раньше объединение с заблокированной
     * частицей давало тот же итог — заблокированная группа не корректируется, — но у
     * спящих индексы вне диапазонов учёта, и их parent не обновлялся бы.
     */
    /** Временная связь (BodyState.solveJoints) объединяет группы концов, как контакт ([noteContacts]). */
    fun noteJoint(i: Int, j: Int, invMass: DoubleArray) {
        if (invMass[i] <= 0.0 || invMass[j] <= 0.0) { blocked[i] = true; blocked[j] = true }
        else union(i, j)
    }

    fun noteContacts(c: BoundaryContacts, invMass: DoubleArray) {
        for (k in 0 until c.contactCount) {
            val i = c.contactI(k); val j = c.contactJ(k)
            if (invMass[i] <= 0.0 || invMass[j] <= 0.0) { blocked[i] = true; blocked[j] = true }
            else union(i, j)
        }
    }
    /**
     * Только диапазоны бодрствующих частиц: у спящих скорость ноль и не меняется.
     * Без [sleep] (зеркало SwimSolver) — все частицы. См. BodySleep.
     */
    fun beforeExternal(vx: DoubleArray, vy: DoubleArray, sleep: BodySleep? = null) {
        val spans = sleep?.spanCount ?: 1
        for (k in 0 until spans) {
            val s = if (sleep == null) 0 else sleep.spanStart[k]
            val len = (if (sleep == null) n else sleep.spanEnd[k]) - s
            System.arraycopy(vx, s, beforeX, s, len)
            System.arraycopy(vy, s, beforeY, s, len)
        }
    }
    fun afterExternal(x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray,
                      invMass: DoubleArray, floor: BooleanArray? = null, sleep: BodySleep? = null) {
        // Сначала все чтения частицы, потом записи: см. BodyState.integrate. Худшая
        // раскладка массивов на 100k: 16.7 мс за тик против 12.9 подряд.
        val spans = sleep?.spanCount ?: 1
        for (k in 0 until spans) {
            val from = if (sleep == null) 0 else sleep.spanStart[k]
            val to = if (sleep == null) n else sleep.spanEnd[k]
            for (i in from until to) externalAt(i, x, y, vx, vy, invMass, floor)
        }
    }

    /** Снимок скорости одной частицы до внешних стадий. Склеенный проход: см. FusedPasses. */
    fun beforeAt(i: Int, vx: Double, vy: Double) { beforeX[i] = vx; beforeY[i] = vy }

    /** Внешний импульс одной частицы за подшаг. Тот же код, что в afterExternal. */
    fun externalAt(i: Int, x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray,
                   invMass: DoubleArray, floor: BooleanArray?) {
        val onFloor = floor != null && floor[i]
        val w = invMass[i]
        if (w <= 0.0) { if (onFloor) blocked[i] = true; return }
        val m = 1.0 / w
        val fx = m * (vx[i] - beforeX[i]); val fy = m * (vy[i] - beforeY[i])
        val tx = targetX[i] + fx; val ty = targetY[i] + fy
        val tl = targetL[i] + (x[i] * fy - y[i] * fx)
        targetX[i] = tx; targetY[i] = ty; targetL[i] = tl
        if (onFloor) blocked[i] = true
    }
    fun cancel(x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray,
               invMass: DoubleArray, sleep: BodySleep? = null) {
        spans(sleep) { from, to -> clearRange(from, to) }
        flattenGroups(sleep)
        addMomentum(x, y, vx, vy, invMass, sleep)
        spans(sleep) { from, to -> centreRange(from, to) }
        addInertia(x, y, invMass, sleep)
        spans(sleep) { from, to -> spinRange(from, to) }
        spans(sleep) { from, to -> applyRange(from, to, x, y, vx, vy, invMass) }
    }

    // Разбито на шаги для ParallelPhysics.momentumCancel. Куски *Range пишут только по своим
    // индексам: их можно на воркеров. Суммы по группам (addMomentum, addInertia) идут в общие
    // ячейки и порядок сложения входит в результат, поэтому остаются в одном потоке.
    // Корень группы — её наименьший индекс (union), то есть тоже внутри диапазонов.
    internal fun clearRange(from: Int, to: Int) {
        java.util.Arrays.fill(mass, from, to, 0.0); java.util.Arrays.fill(cx, from, to, 0.0)
        java.util.Arrays.fill(cy, from, to, 0.0); java.util.Arrays.fill(dx, from, to, 0.0)
        java.util.Arrays.fill(dy, from, to, 0.0); java.util.Arrays.fill(dl, from, to, 0.0)
        java.util.Arrays.fill(inertia, from, to, 0.0)
    }

    /** После этого parent[i] — корень группы: кускам нечего сжимать и нечего писать в общее. */
    internal fun flattenGroups(sleep: BodySleep?) {
        spans(sleep) { from, to -> for (i in from until to) parent[i] = find(i) }
    }

    internal fun addMomentum(x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray, invMass: DoubleArray, sleep: BodySleep?) {
        spans(sleep) { from, to ->
            for (i in from until to) {
                val g = parent[i]
                if (blocked[i] || invMass[i] <= 0.0) { blocked[g] = true; continue }
                val m = 1.0 / invMass[i]
                mass[g] += m; cx[g] += m * x[i]; cy[g] += m * y[i]
                dx[g] += m * vx[i] - targetX[i]; dy[g] += m * vy[i] - targetY[i]
                dl[g] += m * (x[i] * vy[i] - y[i] * vx[i]) - targetL[i]
            }
        }
    }

    internal fun centreRange(from: Int, to: Int) {
        for (g in from until to) if (mass[g] > 0.0) {
            cx[g] /= mass[g]; cy[g] /= mass[g]
            dl[g] -= cx[g] * dy[g] - cy[g] * dx[g]
            dx[g] /= mass[g]; dy[g] /= mass[g]
        }
    }

    internal fun addInertia(x: DoubleArray, y: DoubleArray, invMass: DoubleArray, sleep: BodySleep?) {
        spans(sleep) { from, to ->
            for (i in from until to) {
                if (invMass[i] <= 0.0) continue
                val g = parent[i]; val rx = x[i] - cx[g]; val ry = y[i] - cy[g]
                inertia[g] += (rx * rx + ry * ry) / invMass[i]
            }
        }
    }

    internal fun spinRange(from: Int, to: Int) {
        for (g in from until to) dl[g] = if (inertia[g] > 1e-18) dl[g] / inertia[g] else 0.0
    }

    internal fun applyRange(from: Int, to: Int, x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray, invMass: DoubleArray) {
        for (i in from until to) {
            val g = parent[i]
            if (blocked[g] || invMass[i] <= 0.0) continue
            vx[i] -= dx[g] - dl[g] * (y[i] - cy[g])
            vy[i] -= dy[g] + dl[g] * (x[i] - cx[g])
        }
    }
}
