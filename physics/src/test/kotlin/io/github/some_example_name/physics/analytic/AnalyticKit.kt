package io.github.some_example_name.physics

import kotlin.math.sqrt

/*
 * КИРПИЧИ ЮНИТ-ТЕСТОВ С АНАЛИТИЧЕСКИМ ОТВЕТОМ (план стабильности, этап 1, пункт 2).
 *
 * Эти тесты не сравнивают решатель с ним самим вчерашним. У каждого есть ответ, который можно
 * вывести на бумаге: растяжение связи под постоянной силой, период двух масс на связи, градиент
 * площади треугольника, нулевая поправка кости в своей позе, отскок с заданной долей скорости.
 * Такой тест переживает любую перестройку решателя и перенос на другой язык, а мутационный
 * прогон (gradlew :physics:mutationTest) показывает, какие строки решателя он на самом деле сторожит.
 *
 * Мир — в масштабе игры: связь 0.6 и радиус 0.5 клетки игры при 0.048 единицы физики на клетку.
 */
internal const val T_LINK = 0.0288
internal const val T_RADIUS = 0.024

internal fun gameWorld(config: Map<String, String> = emptyMap()): PhysicsWorld =
    PhysicsWorld(PhysicsConfig(config), workers = null, scale = WorldScale(T_LINK.toFloat(), T_RADIUS * T_RADIUS)).apply { state.quiet = true }

/** Пустота: без сопротивления среды — ни тяги по контуру, ни изотропной части. */
internal fun PhysicsWorld.vacuum(): PhysicsWorld { state.dbgDragOff = true; state.dbgIsoDragOff = true; return this }

/** Цепочка из [count] клеток вдоль x с шагом [step], без треугольников. */
internal fun chain(count: Int, step: Double = T_LINK, x0: Double = 1.0, y0: Double = 1.0): BodyDefinition = BodyDefinition(
    FloatArray(count) { (x0 + it * step).toFloat() }, FloatArray(count) { y0.toFloat() },
    FloatArray(count) { T_RADIUS.toFloat() }, BooleanArray(count), BooleanArray(count),
    IntArray(count - 1) { it }, IntArray(count - 1) { it + 1 },
    IntArray(0), IntArray(0), IntArray(0), FloatArray(0), emptyArray(), emptyArray(), 1f,
)

/**
 * Лоскут шестиугольной решётки [cols] × [rows] клеток со связями и треугольниками, как у тела из
 * редактора: ряды сдвинуты через один на полшага. [bone] и [muscle] — какие клетки кость и мышца
 * (по столбцу и ряду). Костные клетки, связанные между собой, образуют кластеры кости.
 */
internal fun patch(cols: Int, rows: Int, x0: Double = 1.0, y0: Double = 1.0, step: Double = T_LINK,
                   bone: (c: Int, r: Int) -> Boolean = { _, _ -> false },
                   muscle: (c: Int, r: Int) -> Boolean = { _, _ -> false }): BodyDefinition {
    val n = cols * rows
    val rowStep = step * sqrt(3.0) / 2
    val x = FloatArray(n); val y = FloatArray(n)
    val isBone = BooleanArray(n); val isMuscle = BooleanArray(n)
    fun id(c: Int, r: Int) = r * cols + c
    for (r in 0 until rows) for (c in 0 until cols) {
        x[id(c, r)] = (x0 + c * step + if (r % 2 == 1) step / 2 else 0.0).toFloat()
        y[id(c, r)] = (y0 + r * rowStep).toFloat()
        isBone[id(c, r)] = bone(c, r); isMuscle[id(c, r)] = muscle(c, r)
    }
    val la = ArrayList<Int>(); val lb = ArrayList<Int>()
    val ta = ArrayList<Int>(); val tb = ArrayList<Int>(); val tc = ArrayList<Int>()
    fun link(a: Int, b: Int) { la.add(a); lb.add(b) }
    fun tri(a: Int, b: Int, c: Int) {
        // Против часовой: площадь покоя положительна.
        val area = (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a])
        if (area > 0) { ta.add(a); tb.add(b); tc.add(c) } else { ta.add(a); tb.add(c); tc.add(b) }
    }
    for (r in 0 until rows) for (c in 0 until cols) {
        if (c + 1 < cols) link(id(c, r), id(c + 1, r))
        if (r + 1 < rows) {
            // Верхние соседи: у чётного ряда — столбцы c−1 и c, у нечётного — c и c+1.
            val left = if (r % 2 == 0) c - 1 else c
            val right = left + 1
            if (left in 0 until cols) link(id(c, r), id(left, r + 1))
            if (right in 0 until cols) link(id(c, r), id(right, r + 1))
            if (left in 0 until cols && right in 0 until cols) tri(id(c, r), id(left, r + 1), id(right, r + 1))
            if (c + 1 < cols && right in 0 until cols) tri(id(c, r), id(c + 1, r), id(right, r + 1))
        }
    }
    val area2 = FloatArray(ta.size) { t ->
        (x[tb[t]] - x[ta[t]]) * (y[tc[t]] - y[ta[t]]) - (y[tb[t]] - y[ta[t]]) * (x[tc[t]] - x[ta[t]])
    }
    return BodyDefinition(x, y, FloatArray(n) { T_RADIUS.toFloat() }, isBone, isMuscle,
        la.toIntArray(), lb.toIntArray(), ta.toIntArray(), tb.toIntArray(), tc.toIntArray(), area2,
        clusters(n, la, lb, isBone), clusters(n, la, lb, isMuscle), 1f)
}

/** Связные по связям наборы помеченных клеток. */
private fun clusters(n: Int, la: List<Int>, lb: List<Int>, mark: BooleanArray): Array<IntArray> {
    val group = IntArray(n) { it }
    fun find(i: Int): Int { var r = i; while (group[r] != r) r = group[r]; return r }
    for (k in la.indices) if (mark[la[k]] && mark[lb[k]]) group[find(la[k])] = find(lb[k])
    return (0 until n).filter { mark[it] }.groupBy { find(it) }.values.map { it.toIntArray() }.toTypedArray()
}

/** Суммарный импульс и момент импульса живых подвижных частиц мира: px, py, L, масса. */
internal fun PhysicsWorld.momentum(): DoubleArray {
    val st = state
    var mx = 0.0; var my = 0.0; var l = 0.0; var mass = 0.0
    for (i in 0 until st.n) {
        if (st.cellDead[i] || st.restInvMass[i] == 0.0) continue
        val m = 1.0 / st.restInvMass[i]
        mx += m * st.vx[i]; my += m * st.vy[i]; l += m * (st.px[i] * st.vy[i] - st.py[i] * st.vx[i]); mass += m
    }
    return doubleArrayOf(mx, my, l, mass)
}

/** Кинетическая энергия живых подвижных частиц. */
internal fun PhysicsWorld.kinetic(): Double {
    val st = state
    var e = 0.0
    for (i in 0 until st.n) if (!st.cellDead[i] && st.restInvMass[i] != 0.0) e += 0.5 * (st.vx[i] * st.vx[i] + st.vy[i] * st.vy[i]) / st.restInvMass[i]
    return e
}

internal fun PhysicsWorld.distance(i: Int, j: Int): Double {
    val dx = state.px[i] - state.px[j]; val dy = state.py[i] - state.py[j]
    return sqrt(dx * dx + dy * dy)
}

/** Скорость [vx], [vy] всем частицам из [range] — толчок телу целиком. */
internal fun PhysicsWorld.push(range: IntRange, vx: Double, vy: Double) { for (i in range) setVelocity(i, vx, vy); wake(range.first) }

/** Связей за тик → единиц мира физики в секунду. */
internal fun linksPerTick(v: Double): Double = v * T_LINK / DT

/**
 * ОДНО ЯДРО В ДВУХ ВИДАХ. Шаг мира зовёт блочные ядра (BlockConstraintKernels, BlockedIteration),
 * а последовательные по всему миру остались проверкам и замерам стенда. Арифметика у них обязана
 * быть одна, поэтому тесты ядер идут по списку: один и тот же ответ — от обоих.
 */
internal class Kernel(val name: String, val run: (BodyState, Double) -> Unit) { override fun toString() = name }

/** Все блоки и швы раскладки мира — так блочное ядро обходит весь мир. */
internal fun BodyState.eachBlock(run: (Int, ConstraintBlocks) -> Unit) {
    val layout = constraintBlocks!!
    for (block in 0 until layout.buckets) run(block, layout)
}

internal val LINK_KERNELS = listOf(
    Kernel("последовательное") { st, h -> st.solveConstraints(h) },
    Kernel("блочное") { st, h -> st.eachBlock { b, l -> st.solveBlockLinks(h, b, l) } })
internal val AREA_KERNELS = listOf(
    Kernel("последовательное") { st, h -> st.solveAreas(h) },
    Kernel("блочное") { st, h -> st.eachBlock { b, l -> st.solveBlockAreas(h, b, l) } })
internal val FLAP_KERNELS = listOf(
    Kernel("последовательное") { st, h -> st.solveFlaps(h) },
    Kernel("блочное") { st, h -> st.eachBlock { b, l -> st.solveBlockFlaps(h, b, l) } })
internal val BEND_KERNELS = listOf(
    Kernel("последовательное") { st, h -> st.solveBend(h) },
    Kernel("блочное") { st, h -> st.eachBlock { b, l -> st.solveBlockBend(h, b, l) } })
/** Предел длины связей контура: общий проход и блочные — полный и «у порога». */
internal val LIMIT_KERNELS = listOf(
    Kernel("общий проход") { st, _ -> st.solveLinkMaxLength() },
    Kernel("блочное") { st, _ -> st.eachBlock { b, l -> st.solveBlockLimits(b, l) } },
    Kernel("блочное у порога") { st, _ -> st.eachBlock { b, l -> st.solveBlockLimits(b, l); st.solveBlockNearLimits(b, l, 0) } })

/** Номер связи между частицами [i] и [j] в рабочих массивах решателя. */
internal fun BodyState.linkBetween(i: Int, j: Int): Int {
    for (c in 0 until conCount) if ((conA[c] == i && conB[c] == j) || (conA[c] == j && conB[c] == i)) return c
    error("связи $i–$j нет")
}
