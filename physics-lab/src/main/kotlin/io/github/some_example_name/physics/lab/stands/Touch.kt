package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.DT
import kotlin.math.floor

/**
 * КАСАНИЕ ПО ГЕОМЕТРИИ — ДЛЯ ВОПРОСА «А СЦЕНА ВООБЩЕ ЧТО-НИБУДЬ ПРОВЕРИЛА?».
 *
 * Сцена «два тела растут навстречу» ловит проникновение. Если тела при росте чуть развернуло и
 * они разошлись, не коснувшись, правила промолчат — и сцена будет зелёной, ничего не проверив
 * (замечание автора игры 16.11: так было с ростом двух тел рядом и с ростом у рельефа). Поэтому
 * сцена обязана сказать, что в ней должно случиться (StandWorld.must), а монитор — убедиться,
 * что это случилось.
 *
 * Касание здесь считается по положениям клеток и их радиусам касания из описания фигуры — без
 * единого вопроса к решателю о его контактах. Так и задумано: после смены физики побитово
 * сравнивать будет не с чем, и ответ «тела встретились» не должен зависеть от того, чья физика
 * считала сцену.
 *
 * ДВА ПРИЗНАКА ВСТРЕЧИ.
 *  1. На границе тика круги касания перекрыты (с запасом [TOUCH_SLACK]). Так видна любая
 *     медленная встреча: тела в касании стоят много тиков.
 *  2. Свободный полёт: от положений и скоростей на начало тика тела по инерции сошлись бы внутри
 *     тика. Быстрый удар и отскок умещаются в один тик, и на его границах тела снова врозь —
 *     15.11 так «не встречались» пули (7.5 связи за тик) и встречные тараны на потолке скорости у
 *     трети тел геномов. Раз по инерции они сходились, значит, либо столкнулись, либо прошли
 *     насквозь — и то и другое сцена обязана была проверить.
 *
 * Цена: рамки двух наборов клеток — один проход; если рамки не пересекаются, на этом всё. Иначе
 * перебор пар (малые наборы и быстрые тела) или сетка с ячейкой в диаметр касания (большие и
 * медленные): тело в десять тысяч клеток — это тысячи клеток контура. Условие проверяется только
 * до первого срабатывания.
 */
internal const val TOUCH_SEAL = 1.05

/**
 * Запас расстояния сверх кругов касания для вопроса «встретились ли», единиц игры: решатель
 * начинает толкать на сшивании мембраны (5% радиуса), а тела, сошедшиеся ближе двадцатой доли
 * единицы, уже действуют друг на друга. Больше нельзя: «рядом, не касаясь» (зазор 0.2) стало бы встречей.
 */
internal const val TOUCH_SLACK = 0.05

/** Смещение за тик, единиц игры, с которого встреча ищется ещё и по свободному полёту внутри тика. */
private const val SWEEP_FROM = 0.1

/** Набор кругов касания: на каждый — x, y, r сейчас, x, y на начало тика и смещение за тик по инерции. */
internal class Circles {
    var data = DoubleArray(0); private set
    var n = 0; private set
    var minX = 0.0; var minY = 0.0; var maxX = 0.0; var maxY = 0.0
    var maxR = 0.0
    /** Наибольшее смещение круга за тик по инерции. */
    var maxMove = 0.0

    fun clear() { n = 0; minX = Double.MAX_VALUE; minY = Double.MAX_VALUE; maxX = -Double.MAX_VALUE; maxY = -Double.MAX_VALUE; maxR = 0.0; maxMove = 0.0 }

    fun add(x: Double, y: Double, r: Double, sx: Double = x, sy: Double = y, dx: Double = 0.0, dy: Double = 0.0) {
        if (STRIDE * n + STRIDE > data.size) data = data.copyOf(maxOf(32 * STRIDE, data.size * 2))
        val o = STRIDE * n
        data[o] = x; data[o + 1] = y; data[o + 2] = r; data[o + 3] = sx; data[o + 4] = sy; data[o + 5] = dx; data[o + 6] = dy; n++
        grow(x, y, r); grow(sx, sy, r); grow(sx + dx, sy + dy, r)
        if (r > maxR) maxR = r
        val move = Math.hypot(dx, dy)
        if (move > maxMove) maxMove = move
    }

    private fun grow(x: Double, y: Double, r: Double) {
        if (x - r < minX) minX = x - r; if (x + r > maxX) maxX = x + r
        if (y - r < minY) minY = y - r; if (y + r > maxY) maxY = y + r
    }

    fun apart(o: Circles, slack: Double): Boolean =
        n == 0 || o.n == 0 || minX > o.maxX + slack || o.minX > maxX + slack || minY > o.maxY + slack || o.minY > maxY + slack

    companion object { const val STRIDE = 7 }
}

internal class TouchProbe(private val w: StandWorld) {
    private val a = Circles()
    private val b = Circles()
    private val rocks = Circles()
    private var rocksBuilt = -1
    private val rockGrid = Grid()

    // Положения и скорости на начало тика — для признака «по инерции сошлись бы» (единицы мира физики).
    private var startX = FloatArray(0)
    private var startY = FloatArray(0)
    private var startVx = FloatArray(0)
    private var startVy = FloatArray(0)
    private var startDead = BooleanArray(0)
    private var startN = 0

    /** Запомнить состояние перед шагом мира. Зовёт StandWorld.step, пока у сцены есть несработавшие условия. */
    fun beforeStep() {
        val st = w.world.state
        val n = st.n
        if (startX.size < n) { startX = FloatArray(n); startY = FloatArray(n); startVx = FloatArray(n); startVy = FloatArray(n); startDead = BooleanArray(n) }
        System.arraycopy(st.cellDead, 0, startDead, 0, n)
        System.arraycopy(st.px, 0, startX, 0, n); System.arraycopy(st.py, 0, startY, 0, n)
        System.arraycopy(st.vx, 0, startVx, 0, n); System.arraycopy(st.vy, 0, startVy, 0, n)
        startN = n
    }

    /** Сетка по текущим положениям набора кругов: счётная раскладка без выделений на каждый вызов. */
    private class Grid {
        var cell = 1.0; var x0 = 0.0; var y0 = 0.0; var cols = 0; var rows = 0
        var start = IntArray(0); var items = IntArray(0)
        private var fill = IntArray(0)

        fun build(c: Circles, cellSize: Double) {
            var bx0 = Double.MAX_VALUE; var by0 = Double.MAX_VALUE; var bx1 = -Double.MAX_VALUE; var by1 = -Double.MAX_VALUE
            for (i in 0 until c.n) {
                val x = c.data[Circles.STRIDE * i]; val y = c.data[Circles.STRIDE * i + 1]
                if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y
            }
            cell = cellSize; x0 = bx0; y0 = by0
            cols = maxOf(1, ((bx1 - bx0) / cell).toInt() + 1); rows = maxOf(1, ((by1 - by0) / cell).toInt() + 1)
            val cells = cols * rows
            if (start.size < cells + 1) start = IntArray(cells + 1) else java.util.Arrays.fill(start, 0, cells + 1, 0)
            if (fill.size < cells) fill = IntArray(cells)
            if (items.size < c.n) items = IntArray(c.n)
            for (i in 0 until c.n) start[cellOf(c.data[Circles.STRIDE * i], c.data[Circles.STRIDE * i + 1]) + 1]++
            for (k in 0 until cells) start[k + 1] += start[k]
            System.arraycopy(start, 0, fill, 0, cells)
            for (i in 0 until c.n) items[fill[cellOf(c.data[Circles.STRIDE * i], c.data[Circles.STRIDE * i + 1])]++] = i
        }

        fun col(x: Double): Int = floor((x - x0) / cell).toInt()
        fun row(y: Double): Int = floor((y - y0) / cell).toInt()
        private fun cellOf(x: Double, y: Double): Int = minOf(rows - 1, maxOf(0, row(y))) * cols + minOf(cols - 1, maxOf(0, col(x)))
    }

    private val grid = Grid()

    /** Радиус касания клетки [k] тела: из описания фигуры; у растущего тела клетка внутри взрослой формы пока на краю. */
    private fun radius(body: StandBody, k: Int): Double {
        val c = body.shape.contact[k]
        if (c > 0.0) return c
        val g = body.growth
        return if (g != null && !g.done) body.shape.typicalContact else 0.0
    }

    private fun addParticle(into: Circles, p: Int, r: Double) {
        val k = PHYSICS_PER_GAME
        val x = w.gameX(p); val y = w.gameY(p)
        if (p < startN) into.add(x, y, r, startX[p] / k, startY[p] / k, startVx[p] * DT / k, startVy[p] * DT / k)
        else into.add(x, y, r)
    }

    /**
     * Нет ли частицы [p] в счёте: нерождённая или погибшая раньше этого тика. Погибшая В ЭТОМ тике
     * остаётся: пуля на 7.5 связи за тик и клетка под тараном гибнут в тот же тик, в который
     * долетают, и без них «встретились» не случилось бы у самых сильных ударов (16.11: так было у
     * 168 сцен с пулями по телам геномов).
     */
    private fun gone(st: io.github.some_example_name.physics.BodyState, p: Int): Boolean =
        st.cellUnborn[p] || (st.cellDead[p] && (p >= startN || startDead[p]))

    private fun collect(body: StandBody, into: Circles) {
        into.clear()
        val st = w.world.state
        for (k in 0 until body.count) {
            val p = body.particle(k)
            if (gone(st, p)) continue
            val r = radius(body, k)
            if (r > 0.0) addParticle(into, p, r)
        }
    }

    private fun collectBalls(into: Circles) {
        into.clear()
        val st = w.world.state
        for (id in w.balls) {
            val p = w.world.freeParticle(id)
            if (gone(st, p)) continue
            addParticle(into, p, st.radius[p] / PHYSICS_PER_GAME * ShapeSpec.LONE_CONTACT_SCALE)
        }
    }

    private fun rocksReady() {
        if (rocksBuilt == w.fixed.size) return
        rocks.clear()
        val out = w.rocksOutline()
        var i = 0
        while (i < out.size) { rocks.add(out[i], out[i + 1], out[i + 2]); i += 3 }
        rocksBuilt = w.fixed.size
        if (rocks.n > 0) rockGrid.build(rocks, 2 * rocks.maxR * TOUCH_SEAL + 1e-9)
    }

    private var slack = 0.0
    private var sweep = false

    private fun overlap(p: Circles, i: Int, q: Circles, j: Int): Boolean {
        val pi = Circles.STRIDE * i; val qj = Circles.STRIDE * j
        val reach = (p.data[pi + 2] + q.data[qj + 2]) * TOUCH_SEAL + slack
        val dx = p.data[pi] - q.data[qj]; val dy = p.data[pi + 1] - q.data[qj + 1]
        if (dx * dx + dy * dy <= reach * reach) return true
        if (!sweep) return false
        // Свободный полёт: относительное положение на начало тика плюс доля t относительного смещения за тик.
        val rx = p.data[pi + 3] - q.data[qj + 3]; val ry = p.data[pi + 4] - q.data[qj + 4]
        val vx = p.data[pi + 5] - q.data[qj + 5]; val vy = p.data[pi + 6] - q.data[qj + 6]
        val vv = vx * vx + vy * vy
        if (vv < 1e-18) return false
        val t = minOf(1.0, maxOf(0.0, -(rx * vx + ry * vy) / vv))
        val cx = rx + t * vx; val cy = ry + t * vy
        return cx * cx + cy * cy <= reach * reach
    }

    /** Есть ли встретившаяся пара кругов между наборами; [g] — готовая сетка по [q] или null (тогда строится здесь при нужде). */
    private fun any(p: Circles, q: Circles, g: Grid?, extra: Double, mayMove: Boolean): Boolean {
        slack = extra
        sweep = mayMove && (p.maxMove > SWEEP_FROM || q.maxMove > SWEEP_FROM)
        val reachMax = (p.maxR + q.maxR) * TOUCH_SEAL + slack
        if (p.apart(q, reachMax - p.maxR - q.maxR)) return false
        // Быстрые тела и малые наборы — перебором: сетка по текущим положениям пути внутри тика не знает.
        if (sweep || (g == null && p.n.toLong() * q.n <= 40_000L)) {
            for (i in 0 until p.n) for (j in 0 until q.n) if (overlap(p, i, q, j)) return true
            return false
        }
        val use = g ?: grid.also { it.build(q, reachMax + 1e-9) }
        val span = if (reachMax <= use.cell) 1 else (reachMax / use.cell).toInt() + 1
        for (i in 0 until p.n) {
            val cx = use.col(p.data[Circles.STRIDE * i]); val cy = use.row(p.data[Circles.STRIDE * i + 1])
            for (gy in maxOf(0, cy - span)..minOf(use.rows - 1, cy + span)) for (gx in maxOf(0, cx - span)..minOf(use.cols - 1, cx + span)) {
                val c = gy * use.cols + gx
                for (s in use.start[c] until use.start[c + 1]) if (overlap(p, i, q, use.items[s])) return true
            }
        }
        return false
    }

    /**
     * Тела встретились: круги касания их клеток перекрылись (с запасом [extra] единиц игры) либо,
     * при [moving], тела по инерции сошлись бы внутри последнего тика.
     */
    fun bodies(x: StandBody, y: StandBody, extra: Double = TOUCH_SLACK, moving: Boolean = true): Boolean {
        collect(x, a); collect(y, b); return any(a, b, null, extra, moving)
    }

    /** Тело встретилось с неподвижными частицами рельефа или стенки. */
    fun terrain(x: StandBody, extra: Double = TOUCH_SLACK, moving: Boolean = true): Boolean {
        rocksReady(); if (rocks.n == 0) return false
        collect(x, a); return any(a, rocks, rockGrid, extra, moving)
    }

    /** Хотя бы одна свободная частица встретилась с телом. */
    fun balls(x: StandBody, extra: Double = TOUCH_SLACK, moving: Boolean = true): Boolean {
        collect(x, a); collectBalls(b); return any(b, a, null, extra, moving)
    }

    /** Хотя бы одна свободная частица встретилась с рельефом или стенкой. */
    fun ballsTerrain(extra: Double = TOUCH_SLACK): Boolean {
        rocksReady(); if (rocks.n == 0) return false
        collectBalls(b); return any(b, rocks, rockGrid, extra, true)
    }

    /** Хотя бы две свободные частицы встретились друг с другом. */
    fun ballsEachOther(extra: Double = TOUCH_SLACK): Boolean {
        collectBalls(b)
        if (b.n < 2) return false
        slack = extra
        sweep = b.maxMove > SWEEP_FROM
        if (sweep || b.n <= 300) {
            for (i in 0 until b.n) for (j in i + 1 until b.n) if (overlap(b, i, b, j)) return true
            return false
        }
        val reachMax = 2 * b.maxR * TOUCH_SEAL + slack
        grid.build(b, reachMax + 1e-9)
        for (i in 0 until b.n) {
            val cx = grid.col(b.data[Circles.STRIDE * i]); val cy = grid.row(b.data[Circles.STRIDE * i + 1])
            for (gy in maxOf(0, cy - 1)..minOf(grid.rows - 1, cy + 1)) for (gx in maxOf(0, cx - 1)..minOf(grid.cols - 1, cx + 1)) {
                val c = gy * grid.cols + gx
                for (s in grid.start[c] until grid.start[c + 1]) { val j = grid.items[s]; if (j != i && overlap(b, i, b, j)) return true }
            }
        }
        return false
    }

    // --- толпа: кто из тел уже с кем-нибудь столкнулся ---

    private var met = BooleanArray(0)
    private var boxes = DoubleArray(0)

    /**
     * Доля тел сцены, которые хоть раз встретились с другим телом, рельефом, стенкой или
     * свободной частицей. Отмечается нарастающим итогом: вызывать каждый тик. Пара, где отмечены
     * оба тела, не сверяется.
     */
    fun metShare(): Double {
        val bodies = w.bodies
        if (bodies.isEmpty()) return 0.0
        if (met.size < bodies.size) { met = met.copyOf(bodies.size); boxes = DoubleArray(4 * bodies.size) }
        val st = w.world.state
        val k = PHYSICS_PER_GAME
        for ((i, body) in bodies.withIndex()) {
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            val first = body.first
            for (c in 0 until body.count) {
                val p = first + c
                if (st.cellDead[p] || st.cellUnborn[p]) continue
                val x = w.gameX(p); val y = w.gameY(p)
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                // Рамка накрывает и путь за тик: быстрые тела встречаются внутри тика.
                if (p < startN) {
                    val sx = startX[p] / k; val sy = startY[p] / k
                    if (sx < x0) x0 = sx; if (sx > x1) x1 = sx; if (sy < y0) y0 = sy; if (sy > y1) y1 = sy
                }
            }
            boxes[4 * i] = x0 - 1.0; boxes[4 * i + 1] = y0 - 1.0; boxes[4 * i + 2] = x1 + 1.0; boxes[4 * i + 3] = y1 + 1.0
        }
        val anyBalls = w.balls.isNotEmpty()
        for (i in bodies.indices) {
            if (!met[i] && (terrain(bodies[i]) || (anyBalls && balls(bodies[i])))) met[i] = true
            for (j in i + 1 until bodies.size) {
                if (met[i] && met[j]) continue
                if (boxes[4 * i] > boxes[4 * j + 2] || boxes[4 * j] > boxes[4 * i + 2] || boxes[4 * i + 1] > boxes[4 * j + 3] || boxes[4 * j + 1] > boxes[4 * i + 3]) continue
                if (bodies(bodies[i], bodies[j])) { met[i] = true; met[j] = true }
            }
        }
        var count = 0
        for (i in bodies.indices) if (met[i]) count++
        return count.toDouble() / bodies.size
    }
}
