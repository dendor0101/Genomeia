package io.github.some_example_name.old.systems.genomics.genome

/**
 * Версия перебора треугольников раскладки (BakedLayout.trianglesVersion), см. [trianglesOfLinks]:
 *  - 0 — RCMSort.collectTriangles до 27.10: пару соседей клетки брал, только если меньший стоял в
 *    её списке соседей раньше большего, а списки шли в порядке появления связей. Треугольник, у
 *    которого больший сосед записан раньше, терялся навсегда — примерно треть: медуза 167 из 252,
 *    Мегалодон 11323 из 15753. Ткань без них держалась на голых связях, без площади, и галочка
 *    «Граница» рисовала внутренние рёбра контуром. Стенд (BodyFile) перебирал по отсортированным
 *    соседям и находил все;
 *  - 1 — все тройки взаимно связанных клеток (27.10). У геномов с пересекающимися связями тройки
 *    налезали друг на друга, и контур рвался (см. [TriangleFaces]); в выпуск не попала;
 *  - 2 — грани сетки: тройки без налезаний, отбор по позе покоя (27.10);
 *  - 3 — плюс пометка связей поперёк граней (BakedLayout.crossingLinks, 27.10).
 * Поменял перебор — увеличь: свои геномы игроков допекутся при запуске (GenomeImport.withBaking),
 * встроенные — :lwjgl3:bakeBundledGenomes.
 */
const val TRIANGLES_VERSION = 3

/**
 * Сетка тела из связей раскладки: треугольники тройками слотов подряд, их удвоенные площади покоя и
 * номера связей поперёк граней ([TriangleFaces.crossingLinks]) по возрастанию.
 */
data class LayoutMesh(val slots: List<Int>, val areas: List<Float>, val crossing: List<Int>)

/**
 * Ниже этой удвоенной площади треугольник считается вырожденным и не запекается.
 *
 * Три клетки на одной прямой дают нулевую площадь, обратная к ней — бесконечность,
 * а ограничение — NaN, который расползётся по vx/vy всего тела. Порог на порядки
 * меньше площади нормального треугольника (сторона ~0.5 даёт ~0.2).
 */
const val MIN_REST_AREA2 = 1e-6f

/**
 * Треугольники сетки тела: тройки взаимно связанных клеток раскладки — тройки слотов подряд и
 * удвоенная площадь каждого в позе покоя ([restX], [restY] по слотам). Связи — пары слотов
 * [linkA]/[linkB] (дубли и петли допустимы, отбрасываются).
 *
 * ЗАЧЕМ
 * -----
 * Сеть пружин с фиксированными длинами в 2D жёсткая на сдвиг и растяжение, но НЕ
 * защищена от выворачивания: вершина проходит сквозь противоположное ребро, все три
 * длины остаются в норме, а тело складывается наизнанку. Раньше это блокировал
 * внутренний repulse — он физически не давал клеткам сойтись; после того как внутренние
 * клетки убрали из сетки коллизий, остался только этот вырожденный путь, и тело
 * складывается даже от собственных мышц. Знаковая площадь при выворачивании меняет ЗНАК,
 * поэтому ограничение на неё ловит именно этот случай, а не просто «стало тесно». В
 * физике-библиотеке треугольник — ещё и площадь ткани и то, по чему считается контур.
 *
 * ПОЧЕМУ ЭТО ПЕЧЁТСЯ
 * ------------------
 * Треугольник — это топология: пара соседей клетки, связанных между собой. Найти их
 * значит перебрать C(deg,2) пар на клетку и для каждой спросить смежность — в рантайме
 * это тысячи проверок за тик, при запекании ноль.
 *
 * ПОЛНОТА ПЕРЕБОРА
 * ----------------
 * Соседи каждой клетки СОРТИРУЮТСЯ, и тройка берётся от наименьшей вершины v парой
 * соседей a < b: каждая встречается ровно один раз, и ни одна не зависит от того, в каком
 * порядке появлялись связи (см. [TRIANGLES_VERSION] — так терялась треть). Из троек остаются
 * грани — те, что не налезают друг на друга ([TriangleFaces]).
 *
 * ПОРЯДОК ВЕРШИН И ТРЕУГОЛЬНИКОВ
 * ------------------------------
 * Тройка разворачивается так, чтобы площадь покоя была положительной. Иначе знак у
 * половины треугольников оказался бы отрицательным просто из-за порядка обхода, и
 * «вывернулся» стало бы неотличимо от «так и было». Треугольники идут по наименьшему слоту —
 * та же причина, что у связей: обход идёт вдоль ленты RCM и попадает в то же горячее окно,
 * что и обход связей.
 *
 * Цена — O(клеток · степень²) с двоичным поиском смежности; только при запекании и допекании.
 */
fun trianglesOfLinks(
    cellCount: Int,
    linkA: IntArray,
    linkB: IntArray,
    restX: FloatArray,
    restY: FloatArray
): LayoutMesh {
    // Соседи слота — отсортированный список без повторов: start[v] until start[v + 1].
    val degree = IntArray(cellCount + 1)
    for (k in linkA.indices) {
        val a = linkA[k]; val b = linkB[k]
        if (a < 0 || b < 0 || a == b) continue
        degree[a + 1]++; degree[b + 1]++
    }
    for (v in 0 until cellCount) degree[v + 1] += degree[v]
    val raw = IntArray(degree[cellCount])
    val fill = degree.copyOf()
    for (k in linkA.indices) {
        val a = linkA[k]; val b = linkB[k]
        if (a < 0 || b < 0 || a == b) continue
        raw[fill[a]++] = b; raw[fill[b]++] = a
    }
    val start = IntArray(cellCount + 1)
    val neighbours = IntArray(raw.size)
    var size = 0
    for (v in 0 until cellCount) {
        start[v] = size
        java.util.Arrays.sort(raw, degree[v], degree[v + 1])
        for (q in degree[v] until degree[v + 1]) {
            if (size > start[v] && neighbours[size - 1] == raw[q]) continue
            neighbours[size++] = raw[q]
        }
    }
    start[cellCount] = size

    // Кандидаты — все тройки взаимно связанных клеток, против часовой, без вырожденных.
    val cand = IntArrayBuilder()
    val candArea = ArrayList<Float>()
    for (v in 0 until cellCount) {
        for (i in start[v] until start[v + 1]) {
            val a = neighbours[i]
            if (a <= v) continue
            for (j in i + 1 until start[v + 1]) {
                val b = neighbours[j]
                if (java.util.Arrays.binarySearch(neighbours, start[a], start[a + 1], b) < 0) continue
                var s1 = a; var s2 = b
                var area2 = (restX[a] - restX[v]) * (restY[b] - restY[v]) - (restY[a] - restY[v]) * (restX[b] - restX[v])
                if (area2 < 0f) {
                    s1 = b; s2 = a
                    area2 = -area2
                }
                if (area2 < MIN_REST_AREA2) continue
                cand.add(v); cand.add(s1); cand.add(s2)
                candArea.add(area2)
            }
        }
    }
    val faces = TriangleFaces(cand.toArray(), restX, restY, cellCount)
    val keep = faces.select()
    val slots = ArrayList<Int>()
    val areas = ArrayList<Float>()
    for (t in candArea.indices) {
        if (!keep[t]) continue
        slots.add(cand[3 * t]); slots.add(cand[3 * t + 1]); slots.add(cand[3 * t + 2])
        areas.add(candArea[t])
    }
    return LayoutMesh(slots, areas, faces.crossingLinks(keep, linkA, linkB))
}

/**
 * ГРАНИ СЕТКИ ИЗ ТРОЕК СВЯЗЕЙ. Тройка взаимно связанных клеток — ещё не грань: у части геномов
 * связи пересекаются (четыре клетки с обеими диагоналями, клетка, связанная со вторым кольцом), и
 * тогда тройки налезают друг на друга. Ребро оказывается в 3–14 треугольниках, внешнее — в двух
 * с одной стороны, и для физики оно уже не край: контур тела рвётся (галочка «Граница», отчёт
 * 27.10 18:39 — у тела в 27 клеток контур из 8 рёбер). До 27.10 так было у Мегалодона (4403 ребра
 * из 17880 больше чем в двух треугольниках), Maggot, Sucker, Crayfish, Big walker и других.
 *
 * Отбор по позе покоя: тройки по возрастанию длиннейшей стороны (компактные — первыми, как в
 * Делоне короткая диагональ четырёхугольника), берётся та, что не налезает на уже взятые и не
 * накрывает чужую клетку. Взятые грани не пересекаются, поэтому у ребра их не больше двух — по
 * одной с каждой стороны. У плоской сетки связей (медуза, Glist, Tardigrade) тройки не налезают,
 * и берутся все.
 *
 * Налезание двух треугольников: с общей стороной — третьи вершины по одну сторону от неё; иначе —
 * собственное пересечение сторон или вершина одного строго внутри другого. Кандидаты на проверку —
 * из равномерной сетки по позе покоя (клетка сетки — средняя сторона треугольника): O(троек).
 */
private class TriangleFaces(
    private val tri: IntArray,
    private val x: FloatArray,
    private val y: FloatArray,
    private val cellCount: Int
) {
    private val count = tri.size / 3
    private val longest = DoubleArray(count) { t -> maxOf(len2(t, 0, 1), len2(t, 1, 2), len2(t, 2, 0)) }

    // Равномерная сетка по позе покоя: клетка — средняя длиннейшая сторона тройки.
    private var minX = Double.MAX_VALUE
    private var minY = Double.MAX_VALUE
    private var cell = 1.0
    private var cols = 1
    private var rows = 1
    private lateinit var grid: Array<IntArrayBuilder>     // взятые треугольники по клеткам сетки
    private lateinit var points: Array<IntArrayBuilder>   // клетки тела по клеткам сетки

    init {
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        var side = 0.0
        for (v in 0 until cellCount) {
            minX = minOf(minX, x[v].toDouble()); maxX = maxOf(maxX, x[v].toDouble())
            minY = minOf(minY, y[v].toDouble()); maxY = maxOf(maxY, y[v].toDouble())
        }
        for (t in 0 until count) side += kotlin.math.sqrt(longest[t])
        if (cellCount > 0) {
            cell = if (count > 0) maxOf(side / count, 1e-6) else maxOf(maxX - minX, maxY - minY, 1e-6)
            cols = ((maxX - minX) / cell).toInt() + 1
            rows = ((maxY - minY) / cell).toInt() + 1
        }
        grid = Array(cols * rows) { IntArrayBuilder() }
        points = Array(cols * rows) { IntArrayBuilder() }
        for (v in 0 until cellCount) points[gx(x[v].toDouble()) + cols * gy(y[v].toDouble())].add(v)
    }

    private fun gx(value: Double) = cellOf(value, minX, cell, cols)
    private fun gy(value: Double) = cellOf(value, minY, cell, rows)

    fun select(): BooleanArray {
        val keep = BooleanArray(count)
        if (count == 0) return keep
        // Порядок отбора: длиннейшая сторона, затем номер — однозначно.
        val order = (0 until count).sortedWith(compareBy<Int>({ longest[it] }, { it }))
        val seen = IntArray(count) { -1 }

        for (t in order) {
            val c0 = gx(bx(t, true)); val c1 = gx(bx(t, false))
            val r0 = gy(by(t, true)); val r1 = gy(by(t, false))
            var ok = true
            loop@ for (r in r0..r1) for (c in c0..c1) {
                val g = grid[r * cols + c]
                for (k in 0 until g.size) {
                    val u = g[k]
                    if (seen[u] == t) continue
                    seen[u] = t
                    if (overlap(t, u)) { ok = false; break@loop }
                }
                val p = points[r * cols + c]
                for (k in 0 until p.size) if (strictlyInside(p[k], t)) { ok = false; break@loop }
            }
            if (!ok) continue
            keep[t] = true
            for (r in r0..r1) for (c in c0..c1) grid[r * cols + c].add(t)
        }
        return keep
    }

    /**
     * СВЯЗИ ПОПЕРЁК ГРАНЕЙ — номера связей ([linkA]/[linkB]). Связь, не ставшая стороной ни одной
     * грани, бывает двух родов. Нитка — отросток в одну клетку, щупальце: она ничего не пересекает и
     * омывается средой. И связь поверх ткани — вторая диагональ четырёхугольника, связь через клетку
     * ко второму кольцу, «перекладина» хребта: она пересекает сторону грани или проходит прямо по
     * чужой клетке. Для библиотеки голая связь — край тела (контур, сопротивление среды, касания), и
     * такая связь посреди ткани становится краем: у Crayfish 139 из 804, контур рисуется по хребту и
     * голове (отчёт 27.10 19:40). Поэтому физика её не получает (LayoutBodies, см.
     * BakedLayout.crossingLinks); в игре связь остаётся.
     *
     * «По чужой клетке» — центр другой клетки ближе четверти длины связи к её середине отрезка
     * (между 10% и 90% длины). Клетка, у которой поперёк граней оказались бы все связи, оставляет
     * их: связь поперёк лучше оторванной клетки.
     */
    fun crossingLinks(keep: BooleanArray, linkA: IntArray, linkB: IntArray): List<Int> {
        val faceEdges = HashSet<Long>()
        for (t in 0 until count) {
            if (!keep[t]) continue
            for (k in 0..2) faceEdges.add(edgeKey(v(t, k), v(t, (k + 1) % 3)))
        }
        val crossing = BooleanArray(linkA.size)
        val stamp = IntArray(count) { -1 }
        for (k in linkA.indices) {
            val a = linkA[k]; val b = linkB[k]
            if (a < 0 || b < 0 || a == b || edgeKey(a, b) in faceEdges) continue
            crossing[k] = overTissue(k, a, b, stamp)
        }
        // Связность: у клетки остаётся хоть одна связь.
        val kept = IntArray(cellCount); val total = IntArray(cellCount)
        for (k in linkA.indices) {
            val a = linkA[k]; val b = linkB[k]
            if (a < 0 || b < 0 || a == b) continue
            total[a]++; total[b]++
            if (!crossing[k]) { kept[a]++; kept[b]++ }
        }
        for (k in linkA.indices) {
            if (!crossing[k]) continue
            val a = linkA[k]; val b = linkB[k]
            if ((kept[a] == 0 && total[a] > 0) || (kept[b] == 0 && total[b] > 0)) crossing[k] = false
        }
        return linkA.indices.filter { crossing[it] }
    }

    private fun overTissue(link: Int, a: Int, b: Int, stamp: IntArray): Boolean {
        val ax = x[a].toDouble(); val ay = y[a].toDouble(); val bx = x[b].toDouble(); val by = y[b].toDouble()
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-12) return false
        val near2 = len2 / 16.0
        val c0 = gx(minOf(ax, bx)); val c1 = gx(maxOf(ax, bx))
        val r0 = gy(minOf(ay, by)); val r1 = gy(maxOf(ay, by))
        for (r in r0..r1) for (c in c0..c1) {
            val g = grid[r * cols + c]
            for (q in 0 until g.size) {
                val t = g[q]
                if (stamp[t] == link) continue
                stamp[t] = link
                for (e in 0..2) if (cross(a, b, v(t, e), v(t, (e + 1) % 3))) return true
            }
            val p = points[r * cols + c]
            for (q in 0 until p.size) {
                val o = p[q]
                if (o == a || o == b) continue
                val ox = x[o] - ax; val oy = y[o] - ay
                val u = (ox * dx + oy * dy) / len2
                if (u < 0.1 || u > 0.9) continue
                val px = ox - u * dx; val py = oy - u * dy
                if (px * px + py * py < near2) return true
            }
        }
        return false
    }

    private fun edgeKey(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

    private fun v(t: Int, k: Int) = tri[3 * t + k]
    private fun len2(t: Int, i: Int, j: Int): Double {
        val dx = (x[v(t, i)] - x[v(t, j)]).toDouble(); val dy = (y[v(t, i)] - y[v(t, j)]).toDouble()
        return dx * dx + dy * dy
    }
    private fun bx(t: Int, min: Boolean): Double {
        val a = x[v(t, 0)].toDouble(); val b = x[v(t, 1)].toDouble(); val c = x[v(t, 2)].toDouble()
        return if (min) minOf(a, b, c) else maxOf(a, b, c)
    }
    private fun by(t: Int, min: Boolean): Double {
        val a = y[v(t, 0)].toDouble(); val b = y[v(t, 1)].toDouble(); val c = y[v(t, 2)].toDouble()
        return if (min) minOf(a, b, c) else maxOf(a, b, c)
    }
    private fun cellOf(value: Double, min: Double, cell: Double, n: Int) = ((value - min) / cell).toInt().coerceIn(0, n - 1)

    /** Удвоенная знаковая площадь (p, q, r): > 0 — r слева от p→q. */
    private fun orient(p: Int, q: Int, r: Int): Double =
        (x[q] - x[p]).toDouble() * (y[r] - y[p]).toDouble() - (y[q] - y[p]).toDouble() * (x[r] - x[p]).toDouble()

    /** Клетка [p] строго внутри треугольника [t] (треугольники — против часовой). */
    private fun strictlyInside(p: Int, t: Int): Boolean {
        val a = v(t, 0); val b = v(t, 1); val c = v(t, 2)
        if (p == a || p == b || p == c) return false
        return orient(a, b, p) > 0.0 && orient(b, c, p) > 0.0 && orient(c, a, p) > 0.0
    }

    /** Собственное пересечение отрезков p1-p2 и q1-q2 (без общих концов). */
    private fun cross(p1: Int, p2: Int, q1: Int, q2: Int): Boolean {
        if (p1 == q1 || p1 == q2 || p2 == q1 || p2 == q2) return false
        val d1 = orient(p1, p2, q1); val d2 = orient(p1, p2, q2)
        val d3 = orient(q1, q2, p1); val d4 = orient(q1, q2, p2)
        return ((d1 > 0.0 && d2 < 0.0) || (d1 < 0.0 && d2 > 0.0)) && ((d3 > 0.0 && d4 < 0.0) || (d3 < 0.0 && d4 > 0.0))
    }

    private fun overlap(t: Int, u: Int): Boolean {
        // Общая сторона: налезают, если третьи вершины по одну сторону от неё.
        for (i in 0..2) for (j in 0..2) {
            val p = v(t, i); val q = v(t, (i + 1) % 3)
            val pu = v(u, j); val qu = v(u, (j + 1) % 3)
            if ((p == pu && q == qu) || (p == qu && q == pu)) {
                val a = v(t, (i + 2) % 3); val b = v(u, (j + 2) % 3)
                return (orient(p, q, a) > 0.0) == (orient(p, q, b) > 0.0)
            }
        }
        for (i in 0..2) for (j in 0..2) {
            if (cross(v(t, i), v(t, (i + 1) % 3), v(u, j), v(u, (j + 1) % 3))) return true
        }
        for (k in 0..2) if (strictlyInside(v(t, k), u) || strictlyInside(v(u, k), t)) return true
        return false
    }
}

/** Растущий IntArray без боксинга — для запекания. */
private class IntArrayBuilder {
    private var data = IntArray(8)
    var size = 0
        private set
    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }
    operator fun get(i: Int) = data[i]
    fun toArray(): IntArray = data.copyOf(size)
}
