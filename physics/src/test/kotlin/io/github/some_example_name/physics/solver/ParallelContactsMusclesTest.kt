package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test

class ParallelContactsMusclesTest {
    /** Квадрат width×width из клеток со сдвигом. */
    private fun square(width: Int, shiftX: Float, shiftY: Float): BodyDefinition {
        val n = width * width
        val triangles = ArrayList<IntArray>(); val edges = linkedSetOf<Pair<Int, Int>>()
        fun tri(a: Int, b: Int, c: Int) {
            triangles.add(intArrayOf(a, b, c))
            for ((i, j) in arrayOf(a to b, b to c, c to a)) edges.add(minOf(i, j) to maxOf(i, j))
        }
        for (r in 0 until width - 1) for (c in 0 until width - 1) {
            val a = r * width + c; tri(a, a + 1, a + width); tri(a + 1, a + width + 1, a + width)
        }
        return BodyDefinition(FloatArray(n) { (it % width) + shiftX }, FloatArray(n) { (it / width) + shiftY },
            FloatArray(n) { 0.5f }, BooleanArray(n), BooleanArray(n),
            edges.map { it.first }.toIntArray(), edges.map { it.second }.toIntArray(),
            triangles.map { it[0] }.toIntArray(), triangles.map { it[1] }.toIntArray(), triangles.map { it[2] }.toIntArray(),
            FloatArray(triangles.size) { 1f }, emptyArray(), emptyArray(), 1f)
    }

    private fun crowd(): BodyDefinition {
        // Квадраты с зазором больше касания: перекрытые с самого начала стали бы парами покоя, а не контактами.
        var all = square(WIDTH, 0f, 0f)
        for (k in 1 until SIDE * SIDE) all = all.concat(square(WIDTH, (k % SIDE) * STEP, (k / SIDE) * STEP))
        return all
    }

    private class Run(val state: FloatArray, val maxContacts: Int)

    private fun run(workers: Int): Run {
        val world = PhysicsWorld(PhysicsConfig(), workers = if (workers > 1) PhysicsWorkers.shared(workers) else null)
        world.state.quiet = true
        world.load(crowd())
        world.reset()
        val st = world.state
        // Все летят к центру: край каждого квадрата упирается в соседей.
        val c = SIDE * STEP / 2.0f
        for (i in 0 until st.n) { st.vx[i] = 1.5f * (c - st.px[i]); st.vy[i] = 1.5f * (c - st.py[i]) }
        // Мышцы: каждая третья клетка сжимается — сотни сдвинувшихся клеток на тик.
        for (i in 0 until st.n step 3) world.setCellScale(i, 0.7f)
        var maxContacts = 0
        repeat(TICKS) {
            world.step()
            maxContacts = maxOf(maxContacts, st.contacts!!.contactCount)
        }
        return Run(st.px.copyOf(st.n) + st.py.copyOf(st.n) + st.vx.copyOf(st.n) + st.vy.copyOf(st.n) +
            st.conCellScale.copyOf(st.conCount).map { it.toFloat() }, maxContacts)
    }

    @Test fun contactsAndMusclesMatchBitForBitOnAnyWorkerCount() {
        val serial = run(1)
        assertTrue("контактов мало, параллельная ветка не проверена: ${serial.maxContacts}", serial.maxContacts >= 2048)
        for (workers in intArrayOf(2, 4, 7)) {
            val parallel = run(workers)
            assertArrayEquals("воркеров $workers", serial.state, parallel.state, 0.0f)
        }
    }

    private companion object {
        const val WIDTH = 7
        const val SIDE = 16
        const val STEP = 7.4f
        const val TICKS = 30
    }
}
