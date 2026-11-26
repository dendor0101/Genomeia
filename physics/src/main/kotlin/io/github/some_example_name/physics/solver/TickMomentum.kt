package io.github.some_example_name.physics

/** Эксперимент: баланс импульса за тик. Внешние изменения скоростей учитываются
 * отдельно; контакты объединяют группы. Разрыв в середине тика не отменяет уже
 * переданный импульс. Раздельные тела вновь разделяются на следующем begin.
 * 10 double + 2 float + 2 int + boolean на частицу: ~9.7 МБ при 100k, больше L2.
 * Это цена прямого варианта; выигрыш должен подтвердить замер.
 *
 * ЦЕЛИ И СУММЫ — В DOUBLE, хотя физика во float (22.11, docs/physics/reference.md, находка 36). Момент
 * считается относительно начала координат мира: m (x · vy − y · vx) при x до 25 единиц физики против
 * поправки тела в сотые доли — разность двух больших чисел. Во float её точность съедает рычаг: вдали от
 * нуля момент вращающегося тела утекал (находка 35), а одиночная частица набирала скорость из шума.
 * Произведение двух float в double точное, поэтому здесь рычаг ничего не стоит. Массивы мира не меняются:
 * double только в учёте, который проходит по частице раз в подшаг.
 *
 * [minSpinArm] — наименьший радиус инерции группы, у которого есть вращение (SPIN_MIN_ARM в единицах мира).
 */
/**
 * Внешний импульс одной частицы за подшаг — единственная запись этой арифметики. Массивы учёта идут параметрами:
 * склеенные проходы (FusedPasses) держат их в локальных переменных, а [TickMomentum.externalAt] передаёт свои.
 * [onFloor] — частица стоит на опоре: её группа из учёта выходит.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun momentumExternalAt(i: Int, x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray,
                                       invMass: FloatArray, onFloor: Boolean, beforeX: FloatArray, beforeY: FloatArray,
                                       targetX: DoubleArray, targetY: DoubleArray, targetL: DoubleArray, blocked: BooleanArray) {
    val w = invMass[i]
    if (w <= 0.0f) { if (onFloor) blocked[i] = true; return }
    val m = 1.0 / w
    val fx = m * (vx[i] - beforeX[i]); val fy = m * (vy[i] - beforeY[i])
    val tx = targetX[i] + fx; val ty = targetY[i] + fy
    val tl = targetL[i] + (x[i] * fy - y[i] * fx)
    targetX[i] = tx; targetY[i] = ty; targetL[i] = tl
    if (onFloor) blocked[i] = true
}

class TickMomentum(internal val n: Int, minSpinArm: Float) : Cloneable {
    /**
     * Копия учёта для воркера островов (IslandRun.kt): массивы частиц и организмов общие — остров пишет только свои
     * частицы и организмы, корень группы — наименьшая частица группы, то есть тоже его. Свой — список слияний
     * организмов: слияния острова касаются только его организмов, а чужие (липучки мира, другие острова) — не его.
     * [prev] — копия прошлого тика: её список переходит без выделения.
     */
    internal fun islandView(prev: TickMomentum?): TickMomentum {
        val v = clone() as TickMomentum
        v.orgLinked = prev?.orgLinked ?: IntArray(64)
        v.orgLinkedN = 0
        v.cancelSums = prev?.cancelSums
        return v
    }

    /** Новый остров на той же копии: слияний ещё нет. */
    internal fun resetIslandLinks() { orgLinkedN = 0 }

    /** Дерево групп частиц и организмов — для отката острова (IslandRun.kt). */
    internal val parentArray: IntArray get() = parent
    internal val orgParentArray: IntArray get() = orgParent

    override fun clone(): Any = super.clone()

    private val minArm2 = minSpinArm.toDouble() * minSpinArm
    private val parent = IntArray(n)
    private val first = IntArray(n)
    /**
     * Контакт объединяет целые организмы, поэтому слияние идёт по номерам организмов: маленький
     * массив в кэше вместо поиска корня через частицы (noteContacts был 2.5% главного потока, 10.11).
     * Корень группы тот же — наименьший номер частицы.
     */
    private val orgParent = IntArray(n)
    private var orgOf = IntArray(0)
    // Цели, снимок и признак опоры открыты модулю: склеенные проходы по скоростям (FusedPasses) берут их в
    // локальные переменные до цикла — вызов beforeAt/externalAt на каждую частицу читал бы заголовки этих
    // массивов заново (см. lengthsBeforeLoop).
    internal val targetX = DoubleArray(n)
    internal val targetY = DoubleArray(n)
    internal val targetL = DoubleArray(n)
    internal val beforeX = FloatArray(n)
    internal val beforeY = FloatArray(n)
    private val mass = DoubleArray(n)
    private val cx = DoubleArray(n)
    private val cy = DoubleArray(n)
    private val dx = DoubleArray(n)
    private val dy = DoubleArray(n)
    private val dl = DoubleArray(n)
    private val inertia = DoubleArray(n)
    internal val blocked = BooleanArray(n)
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
        val child = if (first[ra] <= first[rb]) rb else ra
        orgParent[child] = if (child == rb) ra else rb
        // Корнем организм перестаёт быть один раз: присоединяются только корни. См. mergeOrgGroups.
        if (orgLinkedN == orgLinked.size) orgLinked = orgLinked.copyOf(orgLinkedN * 2)
        orgLinked[orgLinkedN++] = child
    }

    /** Таблица сумм кусков для cancel: одна на учёт (у копии острова — своя), не выделяется на каждый тик. */
    private var cancelSums: GroupSums? = null

    /** Организмы, присоединённые за тик к чужой группе (не корни orgParent), в порядке слияний. */
    private var orgLinked = IntArray(64)
    private var orgLinkedN = 0
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
    fun begin(x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray,
              invMass: FloatArray, org: IntArray, sleep: BodySleep? = null, outside: BooleanArray? = null) {
        beginGroups(org, sleep, outside)
        spans(sleep) { from, to -> beginTargets(from, to, x, y, vx, vy, invMass) }
    }

    /** Группы по организмам: общие first и union, в одном потоке. */
    internal fun beginGroups(org: IntArray, sleep: BodySleep?, outside: BooleanArray?) {
        first.fill(-1)
        orgOf = org
        for (o in orgParent.indices) orgParent[o] = o
        orgLinkedN = 0
        spans(sleep) { from, to -> for (i in from until to) parent[i] = i }
        spans(sleep) { from, to ->
            for (i in from until to) {
                val o = org[i]
                if (o >= 0 && (outside == null || !outside[i])) { if (first[o] < 0) first[o] = i else union(first[o], i) }
            }
        }
    }

    /** Импульс частиц [from, to) в начале тика. Кусок параллельного прохода: у каждой частицы свои поля. */
    internal fun beginTargets(from: Int, to: Int, x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray,
                              invMass: FloatArray) {
        // Свои массивы — в локальные, длины — до цикла: см. lengthsBeforeLoop. То же в остальных кусках учёта ниже.
        val targetX = targetX; val targetY = targetY; val targetL = targetL; val blocked = blocked
        lengthsBeforeLoop(x.size or y.size or vx.size or vy.size or invMass.size or targetX.size or targetY.size or
            targetL.size or blocked.size)
        for (i in from until to) {
            val m = if (invMass[i] > 0.0f) 1.0 / invMass[i] else 0.0
            targetX[i] = m * vx[i]; targetY[i] = m * vy[i]
            targetL[i] = m * (x[i].toDouble() * vy[i] - y[i].toDouble() * vx[i])
            blocked[i] = invMass[i] <= 0.0f
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
    /**
     * Частица упёрлась в рамку мира: её группа в этом тике из учёта выходит, как при касании
     * неподвижной частицы. Рамка отнимает у тела импульс, и возвращать его некому. Пока рамка
     * опорой не считалась (до 17.11.2026), учёт в конце тика отдавал телу отнятое стенкой, и тело
     * вдавливалось в неё собственным импульсом тик за тиком: лоскут 8×7 на 2 связях за тик терял
     * 22 клетки из 56 (WorldFrameAnalyticTest). Зовётся из интегрирования — по своей частице
     * куска, поэтому годится и воркерам.
     */
    fun block(i: Int) { blocked[i] = true }

    /** Временная связь (BodyState.solveJoints) объединяет группы концов, как контакт ([noteContacts]). */
    fun noteJoint(i: Int, j: Int, invMass: FloatArray) {
        if (invMass[i] <= 0.0f || invMass[j] <= 0.0f) { blocked[i] = true; blocked[j] = true }
        else unionParticles(i, j)
    }

    /**
     * Ключ пары организмов контакта (i, j) для слияния групп по воркерам: -1 — сливать нечего
     * (неподвижная частица помечает группу, свой организм уже в группе), -2 — поштучный путь.
     * Итог слияния от порядка не зависит: корень группы — наименьший номер частицы.
     */
    internal fun contactKey(i: Int, j: Int, invMass: FloatArray): Long {
        if (invMass[i] <= 0.0f || invMass[j] <= 0.0f) { blocked[i] = true; blocked[j] = true; return -1L }
        val oi = orgOf[i]; val oj = orgOf[j]
        if (oi < 0 || oj < 0 || first[oi] < 0 || first[oj] < 0) return -2L
        if (oi == oj) return -1L
        return if (oi < oj) (oi.toLong() shl 32) or oj.toLong() else (oj.toLong() shl 32) or oi.toLong()
    }

    internal fun unionKey(key: Long) = unionOrg((key ushr 32).toInt(), key.toInt())
    internal fun unionContact(i: Int, j: Int) = unionParticles(i, j)

    fun noteContacts(c: BoundaryContacts, invMass: FloatArray) {
        for (k in 0 until c.contactCount) {
            val i = c.contactI(k); val j = c.contactJ(k)
            if (invMass[i] <= 0.0f || invMass[j] <= 0.0f) { blocked[i] = true; blocked[j] = true }
            else unionParticles(i, j)
        }
    }
    /** Куски без сна (зеркало SwimSolver, тесты) — та же нарезка по организмам, что у мира: см. PassChunks. */
    private val ownChunks = PassChunks()
    private var ownLast = IntArray(0)

    private fun ownChunks(): PassChunks {
        if (ownLast.size != n) ownLast = IntArray(n)
        ownChunks.buildWhole(n, orgOf, ownLast)
        return ownChunks
    }

    /**
     * Только диапазоны бодрствующих частиц: у спящих скорость ноль и не меняется.
     * Без [sleep] (зеркало SwimSolver) — все частицы. См. BodySleep.
     */
    fun beforeExternal(vx: FloatArray, vy: FloatArray, sleep: BodySleep? = null) {
        val spans = sleep?.spanCount ?: 1
        for (k in 0 until spans) {
            val s = if (sleep == null) 0 else sleep.spanStart[k]
            val len = (if (sleep == null) n else sleep.spanEnd[k]) - s
            System.arraycopy(vx, s, beforeX, s, len)
            System.arraycopy(vy, s, beforeY, s, len)
        }
    }
    fun afterExternal(x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray,
                      invMass: FloatArray, floor: BooleanArray? = null, sleep: BodySleep? = null) {
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
    fun beforeAt(i: Int, vx: Float, vy: Float) { beforeX[i] = vx; beforeY[i] = vy }

    /** Внешний импульс одной частицы за подшаг. Тот же код, что в afterExternal: арифметика — в [momentumExternalAt]. */
    fun externalAt(i: Int, x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray,
                   invMass: FloatArray, floor: BooleanArray?) {
        momentumExternalAt(i, x, y, vx, vy, invMass, floor != null && floor[i], beforeX, beforeY, targetX, targetY, targetL, blocked)
    }
    /** Без сна (зеркало SwimSolver, тесты): куски режутся здесь же по организмам последнего [begin]. */
    fun cancel(x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray, invMass: FloatArray) =
        cancel(x, y, vx, vy, invMass, null, ownChunks())

    /** Мир: [chunks] — его куски (BodyState.passChunks), те же, что у воркеров. */
    internal fun cancel(x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray,
                        invMass: FloatArray, sleep: BodySleep?, chunks: PassChunks) {
        spans(sleep) { from, to -> clearRange(from, to) }
        flattenGroups(sleep)
        // Те же куски, что у воркеров: порядок сложения не зависит от их числа.
        val pc = chunks
        val sums = cancelSums ?: GroupSums().also { cancelSums = it }
        // Частичная сумма — на кусок целиком, по всем его частям (кусок области: PassChunks.restrict).
        for (c in 0 until pc.count) {
            sums.clear()
            for (p in pc.partStart[c] until pc.partStart[c + 1]) momentumRange(pc.partFrom[p], pc.partTo[p], x, y, vx, vy, invMass, sums)
            mergeMomentum(sums)
        }
        spans(sleep) { from, to -> centreRange(from, to) }
        for (c in 0 until pc.count) {
            sums.clear()
            for (p in pc.partStart[c] until pc.partStart[c + 1]) inertiaRange(pc.partFrom[p], pc.partTo[p], x, y, invMass, sums)
            mergeInertia(sums)
        }
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

    /**
     * То же, что addMomentum, на части куска: в таблицу куска [sums], общие суммы не трогает. Таблицу чистит
     * вызывающий — один раз на кусок: у куска области частей бывает несколько (PassChunks.restrict).
     */
    internal fun momentumRange(from: Int, to: Int, x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray,
                               invMass: FloatArray, sums: GroupSums) {
        val parent = parent; val blocked = blocked; val targetX = targetX; val targetY = targetY; val targetL = targetL
        lengthsBeforeLoop(x.size or y.size or vx.size or vy.size or invMass.size or parent.size or blocked.size or
            targetX.size or targetY.size or targetL.size)
        for (i in from until to) {
            val g = parent[i]
            if (blocked[i] || invMass[i] <= 0.0f) { blocked[g] = true; continue }
            val m = 1.0 / invMass[i]
            val o = sums.at(g)
            val v = sums.data
            v[o] += m; v[o + 1] += m * x[i]; v[o + 2] += m * y[i]
            v[o + 3] += m * vx[i] - targetX[i]; v[o + 4] += m * vy[i] - targetY[i]
            v[o + 5] += m * (x[i].toDouble() * vy[i] - y[i].toDouble() * vx[i]) - targetL[i]
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

    /** То же, что addInertia, на части куска; таблицу чистит вызывающий (см. momentumRange). */
    internal fun inertiaRange(from: Int, to: Int, x: FloatArray, y: FloatArray, invMass: FloatArray, sums: GroupSums) {
        val parent = parent; val cx = cx; val cy = cy
        lengthsBeforeLoop(x.size or y.size or invMass.size or parent.size or cx.size or cy.size)
        for (i in from until to) {
            if (invMass[i] <= 0.0f) continue
            val g = parent[i]; val rx = x[i] - cx[g]; val ry = y[i] - cy[g]
            val o = sums.at(g)
            sums.data[o] += (rx * rx + ry * ry) / invMass[i]
        }
    }

    internal fun mergeInertia(sums: GroupSums) {
        val v = sums.data
        for (k in 0 until sums.count) inertia[sums.keyAt(k)] += v[sums.offsetAt(k)]
    }

    /**
     * Кусок flattenGroups без сжатия путей. Воркер пишет parent только своих частиц и только настоящий
     * корень дерева частиц, поэтому соседний кусок, идущий по цепочке через уже переписанную частицу,
     * приходит к тому же корню. Слияния организмов к этому времени уже в дереве частиц (mergeOrgGroups).
     */
    internal fun flattenRange(from: Int, to: Int) {
        val parent = parent
        lengthsBeforeLoop(parent.size)
        for (i in from until to) {
            var r = i
            while (parent[r] != r) r = parent[r]
            parent[i] = r
        }
    }

    /**
     * Слияния организмов (orgParent) — в дерево частиц, в одном потоке до сплющивания. Корень группы —
     * наименьший номер частицы всей связной группы: union вешает больший корень под меньший, и от
     * порядка слияний корень не зависит.
     *
     * ЗАЧЕМ. Раньше сплющивание само переводило корень организма в первую частицу корня его группы
     * организмов, и в parent[i] попадал не предок i, а частица чужого дерева. Если та первая частица
     * сама висела под частицей без организма (еда, которой коснулся организм), цепочка через уже
     * переписанную частицу приводила к другому корню, чем через старую: группа зависела от того, какой
     * воркер успел первым, а в одном потоке — от порядка частиц. bugReplayCheck 10.11: запись на
     * 8 воркерах и повтор на одном разошлись в снятии импульса (эпоха 1, тик 2619, последний подшаг).
     *
     * Только присоединённые организмы (orgLinked): проход по всем номерам с поиском корня каждого стоил
     * 0.9% главного потока (профиль 10.11, 34 червя, ~9 тыс. организмов).
     */
    internal fun mergeOrgGroups() {
        for (x in 0 until orgLinkedN) {
            val o = orgLinked[x]
            union(first[o], first[findOrg(o)])
        }
    }

    /** После этого parent[i] — корень группы: кускам нечего сжимать и нечего писать в общее. */
    internal fun flattenGroups(sleep: BodySleep?) {
        mergeOrgGroups()
        spans(sleep) { from, to -> for (i in from until to) parent[i] = find(i) }
    }

    internal fun addMomentum(x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray, invMass: FloatArray, sleep: BodySleep?) {
        spans(sleep) { from, to ->
            for (i in from until to) {
                val g = parent[i]
                if (blocked[i] || invMass[i] <= 0.0f) { blocked[g] = true; continue }
                val m = 1.0 / invMass[i]
                mass[g] += m; cx[g] += m * x[i]; cy[g] += m * y[i]
                dx[g] += m * vx[i] - targetX[i]; dy[g] += m * vy[i] - targetY[i]
                dl[g] += m * (x[i].toDouble() * vy[i] - y[i].toDouble() * vx[i]) - targetL[i]
            }
        }
    }

    internal fun centreRange(from: Int, to: Int) {
        val mass = mass; val cx = cx; val cy = cy; val dx = dx; val dy = dy; val dl = dl
        lengthsBeforeLoop(mass.size or cx.size or cy.size or dx.size or dy.size or dl.size)
        for (g in from until to) if (mass[g] > 0.0) {
            cx[g] /= mass[g]; cy[g] /= mass[g]
            dl[g] -= cx[g] * dy[g] - cy[g] * dx[g]
            dx[g] /= mass[g]; dy[g] /= mass[g]
        }
    }

    internal fun addInertia(x: FloatArray, y: FloatArray, invMass: FloatArray, sleep: BodySleep?) {
        spans(sleep) { from, to ->
            for (i in from until to) {
                if (invMass[i] <= 0.0f) continue
                val g = parent[i]; val rx = x[i] - cx[g]; val ry = y[i] - cy[g]
                inertia[g] += (rx * rx + ry * ry) / invMass[i]
            }
        }
    }

    internal fun spinRange(from: Int, to: Int) {
        // Вращение — только у группы с радиусом инерции больше SPIN_MIN_ARM: см. там, почему не абсолютный порог.
        val dl = dl; val inertia = inertia; val mass = mass
        lengthsBeforeLoop(dl.size or inertia.size or mass.size)
        for (g in from until to) dl[g] = if (inertia[g] > mass[g] * minArm2) dl[g] / inertia[g] else 0.0
    }

    internal fun applyRange(from: Int, to: Int, x: FloatArray, y: FloatArray, vx: FloatArray, vy: FloatArray, invMass: FloatArray) {
        val parent = parent; val blocked = blocked; val dx = dx; val dy = dy; val dl = dl; val cx = cx; val cy = cy
        lengthsBeforeLoop(x.size or y.size or vx.size or vy.size or invMass.size or parent.size or blocked.size or
            dx.size or dy.size or dl.size or cx.size or cy.size)
        for (i in from until to) {
            val g = parent[i]
            if (blocked[g] || invMass[i] <= 0.0f) continue
            vx[i] = (vx[i] - (dx[g] - dl[g] * (y[i] - cy[g]))).toFloat()
            vy[i] = (vy[i] - (dy[g] + dl[g] * (x[i] - cx[g]))).toFloat()
        }
    }
}
