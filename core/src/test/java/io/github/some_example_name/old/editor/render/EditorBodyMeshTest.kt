package io.github.some_example_name.old.editor.render

import io.github.some_example_name.old.editor.system.render.EditorBodyMesh
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Связи и контур тела в редакторе (галочки «Связи» и «Граница»): то, что в симуляции даёт
 * мир физики, здесь выводится из перекрытия клеток.
 */
class EditorBodyMeshTest {

    /** Пары как множество «меньший-больший», чтобы не зависеть от порядка обхода сетки. */
    private fun pairs(list: it.unimi.dsi.fastutil.ints.IntArrayList): Set<Pair<Int, Int>> =
        (0 until list.size / 2).map { minOf(list.getInt(2 * it), list.getInt(2 * it + 1)) to maxOf(list.getInt(2 * it), list.getInt(2 * it + 1)) }.toSet()

    /** Центр и шесть соседей шестиугольником, шаг 1, радиус 0.55: соседи по кольцу тоже перекрыты. */
    private fun hexagon(): Triple<FloatArray, FloatArray, FloatArray> {
        val x = FloatArray(7); val y = FloatArray(7); val r = FloatArray(7) { 0.55f }
        for (k in 0 until 6) {
            val a = Math.PI / 3 * k
            x[k + 1] = cos(a).toFloat(); y[k + 1] = sin(a).toFloat()
        }
        return Triple(x, y, r)
    }

    @Test
    fun hexagonHasRimAsBoundaryAndSpokesInside() {
        val (x, y, r) = hexagon()
        val mesh = EditorBodyMesh()
        mesh.update(7, x, y, r)

        val spokes = (1..6).map { 0 to it }.toSet()
        val rim = (1..6).map { minOf(it, it % 6 + 1) to maxOf(it, it % 6 + 1) }.toSet()
        assertEquals(spokes + rim, pairs(mesh.links), "связи — шесть спиц и шесть рёбер обода")
        // Спицу делят два треугольника, ребро обода — один: контур ровно по ободу.
        assertEquals(rim, pairs(mesh.boundary))
    }

    @Test
    fun bareChainIsAllBoundary() {
        // Нитка в одну клетку: треугольников нет, каждая связь — контур (как хвост в физике).
        val x = floatArrayOf(0f, 1f, 2f, 5f)
        val y = FloatArray(4)
        val r = FloatArray(4) { 0.55f }
        val mesh = EditorBodyMesh()
        mesh.update(4, x, y, r)

        assertEquals(setOf(0 to 1, 1 to 2), pairs(mesh.links), "дальняя клетка ни с кем не перекрыта")
        assertEquals(setOf(0 to 1, 1 to 2), pairs(mesh.boundary))
    }

    @Test
    fun linkOverAnotherCellIsDropped() {
        // Крупные клетки в ряд: крайние перекрыты между собой, но их связь идёт прямо по
        // средней клетке. Мир физики такую связь не получает — её нет ни в связях, ни в
        // контуре, иначе посреди ткани появился бы край.
        val x = floatArrayOf(0f, 1f, 2f)
        val y = FloatArray(3)
        val r = FloatArray(3) { 1.05f }
        val mesh = EditorBodyMesh()
        mesh.update(3, x, y, r)

        assertEquals(setOf(0 to 1, 1 to 2), pairs(mesh.links))
        assertEquals(setOf(0 to 1, 1 to 2), pairs(mesh.boundary))
    }

    @Test
    fun movedCellIsRecomputed() {
        val (x, y, r) = hexagon()
        val mesh = EditorBodyMesh()
        mesh.update(7, x, y, r)
        // Клетку 1 утащили далеко: её связи пропадают, контур идёт по оставшимся пяти.
        x[1] = 10f; y[1] = 10f
        mesh.update(7, x, y, r)

        assertEquals(false, pairs(mesh.links).any { it.first == 1 || it.second == 1 })
        assertEquals(setOf(0 to 2, 0 to 6, 2 to 3, 3 to 4, 4 to 5, 5 to 6), pairs(mesh.boundary))
    }
}
