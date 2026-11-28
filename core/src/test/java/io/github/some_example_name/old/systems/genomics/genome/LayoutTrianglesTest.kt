package io.github.some_example_name.old.systems.genomics.genome

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutTrianglesTest {

    /**
     * Решётка w×h с одной диагональю в каждой клетке: треугольников ровно 2·(w-1)·(h-1).
     * Связи перемешаны — прежний перебор (TRIANGLES_VERSION 0) на таком порядке терял часть.
     */
    @Test
    fun `все тройки связей найдены при любом порядке связей`() {
        val w = 7; val h = 5
        val n = w * h
        val x = FloatArray(n) { (it % w).toFloat() }
        val y = FloatArray(n) { (it / w).toFloat() }
        val pairs = ArrayList<IntArray>()
        for (r in 0 until h) for (c in 0 until w) {
            val v = r * w + c
            if (c + 1 < w) pairs += intArrayOf(v, v + 1)
            if (r + 1 < h) pairs += intArrayOf(v, v + w)
            if (c + 1 < w && r + 1 < h) pairs += intArrayOf(v, v + w + 1)
        }
        // Дубль и петля: в связях раскладки бывают, на треугольники влиять не должны.
        pairs += intArrayOf(1, 0)
        pairs += intArrayOf(3, 3)
        var reference: LayoutMesh? = null
        repeat(5) { seed ->
            val shuffled = pairs.shuffled(Random(seed)).map { if (Random(seed + 100).nextBoolean()) it else intArrayOf(it[1], it[0]) }
            val result = trianglesOfLinks(n, IntArray(shuffled.size) { shuffled[it][0] }, IntArray(shuffled.size) { shuffled[it][1] }, x, y)
            assertEquals(2 * (w - 1) * (h - 1), result.areas.size)
            assertEquals(emptyList(), result.crossing, "у плоской сетки связей поперёк граней нет")
            val first = reference
            if (first == null) reference = result
            else assertEquals(first.slots to first.areas, result.slots to result.areas, "порядок связей изменил треугольники")
        }
        val (slots, areas) = reference!!
        val seen = HashSet<List<Int>>()
        for (t in areas.indices) {
            val a = slots[3 * t]; val b = slots[3 * t + 1]; val c = slots[3 * t + 2]
            val area2 = (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a])
            assertTrue(area2 > 0f, "треугольник $t обходится по часовой")
            assertEquals(area2, areas[t])
            assertTrue(seen.add(listOf(a, b, c).sorted()), "треугольник $t встретился дважды")
            if (t > 0) assertTrue(slots[3 * (t - 1)] <= a, "треугольники не по наименьшему слоту")
        }
    }

    /**
     * Обе диагонали в каждой клетке решётки: связи пересекаются, троек вчетверо больше, чем
     * квадратов, и они налезают друг на друга. Граней — по две на квадрат, у ребра не больше двух.
     */
    @Test
    fun `пересекающиеся связи дают грани без налезаний`() {
        val w = 5; val h = 4
        val n = w * h
        val x = FloatArray(n) { (it % w).toFloat() }
        val y = FloatArray(n) { (it / w).toFloat() }
        val la = ArrayList<Int>(); val lb = ArrayList<Int>()
        fun link(a: Int, b: Int) { la += a; lb += b }
        for (r in 0 until h) for (c in 0 until w) {
            val v = r * w + c
            if (c + 1 < w) link(v, v + 1)
            if (r + 1 < h) link(v, v + w)
            if (c + 1 < w && r + 1 < h) { link(v, v + w + 1); link(v + 1, v + w) }
        }
        val (slots, areas, crossing) = trianglesOfLinks(n, la.toIntArray(), lb.toIntArray(), x, y)
        assertEquals(2 * (w - 1) * (h - 1), areas.size)
        assertTrue(maxEdgeUse(slots) <= 2, "ребро в трёх и больше треугольниках")
        // Вторая диагональ каждого квадрата лежит поперёк граней — физика её не получает.
        assertEquals((w - 1) * (h - 1), crossing.size)
        for (k in crossing) assertTrue((la[k] - lb[k]).let { it == -(w + 1) || it == w + 1 || it == w - 1 || it == -(w - 1) }, "поперёк граней — не диагональ: связь $k")
    }

    /** Клетка внутри связанной тройки, связанная со всеми тремя: граней три, большая тройка — не грань. */
    @Test
    fun `тройка с клеткой внутри — не грань`() {
        val x = floatArrayOf(0f, 4f, 2f, 2f)
        val y = floatArrayOf(0f, 0f, 3f, 1f)
        val (slots, areas) = trianglesOfLinks(4, intArrayOf(0, 1, 2, 0, 1, 2), intArrayOf(1, 2, 0, 3, 3, 3), x, y)
        assertEquals(3, areas.size)
        for (t in areas.indices) assertTrue(3 in listOf(slots[3 * t], slots[3 * t + 1], slots[3 * t + 2]))
    }

    /**
     * «Перекладина» через клетку (0 — 2 поверх 1, все на одной прямой, 1 в грани) — поперёк граней;
     * нитка-щупальце (4 — 5 — 6 от треугольника) ничего не пересекает и остаётся физике.
     */
    @Test
    fun `перекладина через клетку поперёк граней, щупальце нет`() {
        val x = floatArrayOf(0f, 1f, 2f, 1f, 2f, 3f, 4f)
        val y = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f)
        // Связи: 0-1, 1-2, 0-3, 1-3, 2-3 (две грани), 0-2 (перекладина), 3-4, 4-5, 5-6 (щупальце).
        val la = intArrayOf(0, 1, 0, 1, 2, 0, 3, 4, 5)
        val lb = intArrayOf(1, 2, 3, 3, 3, 2, 4, 5, 6)
        val mesh = trianglesOfLinks(7, la, lb, x, y)
        assertEquals(2, mesh.areas.size)
        assertEquals(listOf(5), mesh.crossing)
    }

    private fun maxEdgeUse(slots: List<Int>): Int {
        val use = HashMap<Long, Int>()
        for (t in 0 until slots.size / 3) for (k in 0..2) {
            val a = slots[3 * t + k]; val b = slots[3 * t + (k + 1) % 3]
            val key = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
            use[key] = (use[key] ?: 0) + 1
        }
        return use.values.maxOrNull() ?: 0
    }

    @Test
    fun `вырожденная тройка не становится треугольником`() {
        // Три клетки на одной прямой, все попарно связаны.
        val result = trianglesOfLinks(3, intArrayOf(0, 1, 0), intArrayOf(1, 2, 2), floatArrayOf(0f, 1f, 2f), floatArrayOf(0f, 0f, 0f))
        assertEquals(0, result.areas.size)
    }

    /**
     * Встроенные геномы перепечены нынешним перебором (:lwjgl3:bakeBundledGenomes): иначе тело
     * в игре получило бы на треть меньше треугольников, чем связей хватает.
     */
    @Test
    fun `встроенные геномы с полными треугольниками`() {
        val dir = listOf(File("assets/genomes"), File("../assets/genomes")).first { it.isDirectory }
        val files = dir.listFiles { f -> f.extension == "genome" }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val layout = decodeGenome(file.readBytes()).sortedGraph ?: continue
            if (!layout.hasRestPose) continue
            assertTrue(layout.hasCurrentTriangles, "${file.name}: треугольники прежнего перебора — :lwjgl3:bakeBundledGenomes")
            assertEquals(layout.withTrianglesFromLinks(), layout, "${file.name}: треугольники не совпадают с перебором по связям")
            assertTrue(maxEdgeUse(layout.triangleSlots) <= 2, "${file.name}: ребро в трёх и больше треугольниках")
        }
    }
}
