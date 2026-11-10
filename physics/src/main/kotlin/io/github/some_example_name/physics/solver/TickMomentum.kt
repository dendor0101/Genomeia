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
    /**
     * Контакт объединяет целые организмы, поэтому слияние идёт по номерам организмов: маленький
     * массив в кэше вместо поиска корня через частицы (noteContacts был 2.5% главного потока, 10.11).
     * Корень группы тот же — наименьший номер частицы.
     */
    private val orgParent = IntArray(n)
    private var orgOf = IntArray(0)
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
    private fun findOrg(o: Int): Int {
        var r = o
        while (orgParent[r] != r) r = orgParent[r]
        var v = o
        while (orgParent[v] != r) { val next = orgParent[v]; orgParent[v] = r; v = next }
        return r
    }
    private fun unionOrg(a: Int, b: Int) {
        val ra = findOrg(a); val rb = findOrg(b)
        if (ra == rb) return
        if (first[ra] <= first[rb]) orgParent[rb] = ra else orgParent[ra] = rb
    }
    private fun unionParticles(i: Int, j: Int) {
        val oi = orgOf[i]; val oj = orgOf[j]
        if (oi >= 0 && oj >= 0 && first[oi] >= 0 && first[oj] >= 0) { if (oi != oj) unionOrg(oi, oj) }
        else union(i, j)
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
        orgOf = org
        for (o in orgParent.indices) orgParent[o] = o
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
        else unionParticles(i, j)
    }

    fun noteContacts(c: BoundaryContacts, invMass: DoubleArray) {
        for (k in 0 until c.contactCount) {
            val i = c.contactI(k); val j = c.contactJ(k)
            if (invMass[i] <= 0.0 || invMass[j] <= 0.0) { blocked[i] = true; blocked[j] = true }
            else unionParticles(i, j)
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

    /**
     * Суммы по группам куском: у каждого куска своя таблица, сложение в общие ячейки — в порядке
     * кусков, а куски нарезаны по номерам частиц и от числа воркеров не зависят. Групп в куске
     * единицы, поэтому таблица маленькая. Замер 10.11: суммы были 2.5 мс на тик в одном потоке.
     */
    internal class GroupSums {
        private var keys = IntArray(256) { -1 }
        private var mask = 255
        private var used = IntArray(256)
        private var n = 0
        private var values = DoubleArray(256 * 6)
        private var lastKey = -1
        private var lastSlot = 0

        fun clear() {
            for (k in 0 until n) keys[used[k]] = -1
            n = 0; lastKey = -1
        }

        /** Смещение шести сумм группы [g] в [values]. */
        fun at(g: Int): Int {
            if (g == lastKey) return lastSlot
            var h = (g * -1640531527) and mask
            while (true) {
                val k = keys[h]
                if (k == g) break
                if (k == -1) {
                    if (n * 2 >= keys.size) { grow(); return at(g) }
                    keys[h] = g; used[n++] = h
                    java.util.Arrays.fill(values, h * 6, h * 6 + 6, 0.0)
                    break
                }
                h = (h + 1) and mask
            }
            lastKey = g; lastSlot = h * 6
            return lastSlot
        }

        private fun grow() {
            val oldKeys = keys; val oldValues = values; val oldUsed = used; val oldN = n
            val size = keys.size * 2
            keys = IntArray(size) { -1 }; mask = size - 1; used = IntArray(size); values = DoubleArray(size * 6); n = 0; lastKey = -1
            for (k in 0 until oldN) {
                val slot = oldUsed[k]
                val to = at(oldKeys[slot])
                System.arraycopy(oldValues, slot * 6, values, to, 6)
            }
            lastKey = -1
        }

        val data: DoubleArray get() = values
        val count: Int get() = n
        fun keyAt(k: Int): Int = keys[used[k]]
        fun offsetAt(k: Int): Int = used[k] * 6
    }

    /** То же, что addMomentum, на куске частиц: в таблицу куска, общие суммы не трогает. */
    internal fun momentumRange(from: Int, to: Int, x: DoubleArray, y: DoubleArray, vx: DoubleArray, vy: DoubleArray,
                               invMass: DoubleArray, sums: GroupSums) {
        sums.clear()
        for (i in from until to) {
            val g = parent[i]
            if (blocked[i] || invMass[i] <= 0.0) { blocked[g] = true; continue }
            val m = 1.0 / invMass[i]
            val o = sums.at(g)
            val v = sums.data
            v[o] += m; v[o + 1] += m * x[i]; v[o + 2] += m * y[i]
            v[o + 3] += m * vx[i] - targetX[i]; v[o + 4] += m * vy[i] - targetY[i]
            v[o + 5] += m * (x[i] * vy[i] - y[i] * vx[i]) - targetL[i]
        }
    }

    internal fun mergeMomentum(sums: GroupSums) {
        val v = sums.data
        for (k in 0 until sums.count) {
            val g = sums.keyAt(k); val o = sums.offsetAt(k)
            mass[g] += v[o]; cx[g] += v[o + 1]; cy[g] += v[o + 2]
            dx[g] += v[o + 3]; dy[g] += v[o + 4]; dl[g] += v[o + 5]
        }
    }

    /** То же, что addInertia, на куске. */
    internal fun inertiaRange(from: Int, to: Int, x: DoubleArray, y: DoubleArray, invMass: DoubleArray, sums: GroupSums) {
        sums.clear()
        for (i in from until to) {
            if (invMass[i] <= 0.0) continue
            val g = parent[i]; val rx = x[i] - cx[g]; val ry = y[i] - cy[g]
            val o = sums.at(g)
            sums.data[o] += (rx * rx + ry * ry) / invMass[i]
        }
    }

    internal fun mergeInertia(sums: GroupSums) {
        val v = sums.data
        for (k in 0 until sums.count) inertia[sums.keyAt(k)] += v[sums.offsetAt(k)]
    }

    /** Кусок flattenGroups без сжатия путей: воркеры только читают родителей. */
    internal fun flattenRange(from: Int, to: Int) {
        for (i in from until to) {
            var r = i
            while (parent[r] != r) r = parent[r]
            val o = orgOf[r]
            parent[i] = if (o >= 0 && first[o] == r) {
                var ro = o
                while (orgParent[ro] != ro) ro = orgParent[ro]
                first[ro]
            } else r
        }
    }

    /** После этого parent[i] — корень группы: кускам нечего сжимать и нечего писать в общее. */
    internal fun flattenGroups(sleep: BodySleep?) {
        spans(sleep) { from, to ->
            for (i in from until to) {
                val r = find(i)
                val o = orgOf[r]
                parent[i] = if (o >= 0 && first[o] == r) first[findOrg(o)] else r
            }
        }
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
