package io.github.some_example_name.old.editor.system.render

import io.github.some_example_name.old.systems.genomics.genome.trianglesOfLinks
import it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * Связи и контур тела на текущем тике редактора генома — для галочек отладочного режима
 * «Связи» и «Граница».
 *
 * В симуляции их отдаёт мир физики. У редактора мира физики нет, а связей в записи роста
 * нет тоже, поэтому они выводятся из положения клеток. Это законно по инварианту
 * редактора: две клетки перекрываются тогда и только тогда, когда связаны (критерий тот
 * же, что у CellSearchManager.getAllCloseNeighboursEditor: d² ≤ (r₁ + r₂)²). Треугольники —
 * той же функцией [trianglesOfLinks], которой их запекают для игры, а контур — по тому же
 * правилу, что в физике (BodyState.buildBoundary): связь, входящая не больше чем в один
 * треугольник. Поэтому картинка в редакторе совпадает с тем, что получит мир.
 *
 * Номера клеток — порядок буфера клеток кадра (EditorRenderSystem.fillBuffer).
 *
 * ЦЕНА. Соседи — через равномерную сетку со стороной в два наибольших радиуса, O(клеток).
 * Треугольники — O(клеток · степень²). Пересчёт — только если клетки или их положение
 * изменились (сравнение входа с прошлым, O(клеток) без аллокаций); на неподвижном
 * организме — ни одной аллокации в кадр. Это редактор (сотни, редко тысячи клеток), а не
 * горячий путь симуляции.
 */
internal class EditorBodyMesh {

    private var count = -1
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var rs = FloatArray(0)

    /** Концы связей подряд: a0, b0, a1, b1, … (a < b). */
    val links = IntArrayList()

    /** Концы рёбер контура подряд, как [links]. */
    val boundary = IntArrayList()

    /** Треугольники — тройки номеров клеток подряд; нужны только контуру. */
    private var triangles = IntArray(0)
    private var triangleIndexCount = 0

    // Рабочие массивы сетки и смежности, переиспользуются между пересчётами.
    private var bucketStart = IntArray(0)
    private var order = IntArray(0)
    private var adjacencyStart = IntArray(0)
    private var adjacency = IntArray(0)
    private var adjacencyLink = IntArray(0)
    private var triangleUse = IntArray(0)
    private var crossing = BooleanArray(0)

    /**
     * Обновить по клеткам кадра: [n] клеток, положения и радиусы в [x], [y], [r]
     * (их начало — порядок буфера). Пересчитывает, только если вход изменился.
     */
    fun update(n: Int, x: FloatArray, y: FloatArray, r: FloatArray) {
        if (n == count && sameInput(n, x, y, r)) return
        remember(n, x, y, r)

        links.clear()
        boundary.clear()
        triangleIndexCount = 0
        if (n < 2) return

        findLinks(n)
        if (links.isEmpty) return

        val linkCount = links.size / 2
        val linkA = IntArray(linkCount) { links.getInt(2 * it) }
        val linkB = IntArray(linkCount) { links.getInt(2 * it + 1) }
        val mesh = trianglesOfLinks(n, linkA, linkB, xs, ys)

        if (triangles.size < mesh.slots.size) triangles = IntArray(mesh.slots.size)
        for (i in mesh.slots.indices) triangles[i] = mesh.slots[i]
        triangleIndexCount = mesh.slots.size

        // Связи поперёк граней мир физики не получает (LayoutBodies, BakedLayout.crossingLinks):
        // голая такая связь посреди ткани была бы краем. Здесь их тоже нет — ни в «Связях»,
        // ни в «Границе» (у Burdock leaf их 1131, и без этого контур шёл по всему листу).
        if (crossing.size < linkCount) crossing = BooleanArray(linkCount)
        java.util.Arrays.fill(crossing, 0, linkCount, false)
        for (k in mesh.crossing) crossing[k] = true

        findBoundary(n, linkCount)
        dropCrossingLinks(linkCount)
    }

    private fun dropCrossingLinks(linkCount: Int) {
        var kept = 0
        for (k in 0 until linkCount) {
            if (crossing[k]) continue
            links.set(2 * kept, links.getInt(2 * k))
            links.set(2 * kept + 1, links.getInt(2 * k + 1))
            kept++
        }
        links.size(2 * kept)
    }

    private fun sameInput(n: Int, x: FloatArray, y: FloatArray, r: FloatArray): Boolean {
        for (i in 0 until n) {
            if (xs[i] != x[i] || ys[i] != y[i] || rs[i] != r[i]) return false
        }
        return true
    }

    private fun remember(n: Int, x: FloatArray, y: FloatArray, r: FloatArray) {
        if (xs.size < n) {
            xs = FloatArray(n); ys = FloatArray(n); rs = FloatArray(n)
        }
        System.arraycopy(x, 0, xs, 0, n)
        System.arraycopy(y, 0, ys, 0, n)
        System.arraycopy(r, 0, rs, 0, n)
        count = n
    }

    /**
     * Пары перекрывающихся клеток через сетку: сторона ячейки — два наибольших радиуса,
     * поэтому любой сосед клетки лежит в её ячейке или в восьми соседних. Клетки
     * раскладываются по ячейкам подсчётом (без хэш-таблицы), пары берутся как a < b.
     */
    private fun findLinks(n: Int) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var maxR = 0f
        for (i in 0 until n) {
            minX = minOf(minX, xs[i]); maxX = maxOf(maxX, xs[i])
            minY = minOf(minY, ys[i]); maxY = maxOf(maxY, ys[i])
            maxR = maxOf(maxR, rs[i])
        }
        if (maxR <= 0f) return

        // Редко разбросанные клетки дали бы сетку больше, чем клеток, — тогда ячейка крупнее.
        var size = 2f * maxR
        var width: Int
        var height: Int
        while (true) {
            width = ((maxX - minX) / size).toInt() + 1
            height = ((maxY - minY) / size).toInt() + 1
            if (width.toLong() * height <= 4L * n + 64) break
            size *= 2f
        }

        val buckets = width * height
        if (bucketStart.size < buckets + 1) bucketStart = IntArray(buckets + 1)
        java.util.Arrays.fill(bucketStart, 0, buckets + 1, 0)
        if (order.size < n) order = IntArray(n)
        for (i in 0 until n) bucketStart[bucketOf(i, minX, minY, size, width) + 1]++
        for (b in 0 until buckets) bucketStart[b + 1] += bucketStart[b]
        // Заполнение сдвигает начала; после него bucketStart[b] — конец ячейки b, поэтому
        // ячейка b — это bucketStart[b - 1] until bucketStart[b] (у нулевой — с нуля).
        for (i in 0 until n) order[bucketStart[bucketOf(i, minX, minY, size, width)]++] = i

        for (a in 0 until n) {
            val cx = ((xs[a] - minX) / size).toInt()
            val cy = ((ys[a] - minY) / size).toInt()
            for (gy in maxOf(0, cy - 1)..minOf(height - 1, cy + 1)) {
                for (gx in maxOf(0, cx - 1)..minOf(width - 1, cx + 1)) {
                    val bucket = gy * width + gx
                    val from = if (bucket == 0) 0 else bucketStart[bucket - 1]
                    for (q in from until bucketStart[bucket]) {
                        val b = order[q]
                        if (b <= a) continue
                        val dx = xs[b] - xs[a]
                        val dy = ys[b] - ys[a]
                        val rr = rs[a] + rs[b]
                        if (dx * dx + dy * dy <= rr * rr) {
                            links.add(a); links.add(b)
                        }
                    }
                }
            }
        }
    }

    private fun bucketOf(i: Int, minX: Float, minY: Float, size: Float, width: Int): Int =
        ((ys[i] - minY) / size).toInt() * width + ((xs[i] - minX) / size).toInt()

    /**
     * Контур — правило мира физики (BodyState.buildBoundary): ребро внутри тела делят два
     * треугольника, граничное — один, а связь в отростке шириной в клетку — ни одного.
     */
    private fun findBoundary(n: Int, linkCount: Int) {
        // Смежность с номером связи: по ребру треугольника находим его связь.
        if (adjacencyStart.size < n + 1) adjacencyStart = IntArray(n + 1)
        java.util.Arrays.fill(adjacencyStart, 0, n + 1, 0)
        for (k in 0 until linkCount) {
            adjacencyStart[links.getInt(2 * k) + 1]++
            adjacencyStart[links.getInt(2 * k + 1) + 1]++
        }
        for (v in 0 until n) adjacencyStart[v + 1] += adjacencyStart[v]
        val entries = adjacencyStart[n]
        if (adjacency.size < entries) {
            adjacency = IntArray(entries); adjacencyLink = IntArray(entries)
        }
        // Как в findLinks: после заполнения adjacencyStart[v] — конец списка v.
        for (k in 0 until linkCount) {
            val a = links.getInt(2 * k)
            val b = links.getInt(2 * k + 1)
            var p = adjacencyStart[a]++
            adjacency[p] = b; adjacencyLink[p] = k
            p = adjacencyStart[b]++
            adjacency[p] = a; adjacencyLink[p] = k
        }

        if (triangleUse.size < linkCount) triangleUse = IntArray(linkCount)
        java.util.Arrays.fill(triangleUse, 0, linkCount, 0)
        var t = 0
        while (t < triangleIndexCount) {
            val a = triangles[t]; val b = triangles[t + 1]; val c = triangles[t + 2]
            countEdge(a, b); countEdge(b, c); countEdge(c, a)
            t += 3
        }

        for (k in 0 until linkCount) {
            if (triangleUse[k] > 1 || crossing[k]) continue
            boundary.add(links.getInt(2 * k)); boundary.add(links.getInt(2 * k + 1))
        }
    }

    private fun countEdge(a: Int, b: Int) {
        val from = if (a == 0) 0 else adjacencyStart[a - 1]
        for (p in from until adjacencyStart[a]) {
            if (adjacency[p] == b) {
                triangleUse[adjacencyLink[p]]++
                return
            }
        }
    }
}
