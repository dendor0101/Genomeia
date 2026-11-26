package io.github.some_example_name.physics

import org.junit.Assert.*
import org.junit.Test

/**
 * ОСТРОВА НЕ МЕНЯЮТ БИТОВ (25.11.2026, docs/physics/islands.md, IslandRun.kt).
 *
 * Острова — оптимизация, а не другая физика: сумма мира с ними обязана совпасть с суммой без них побитово, при любом
 * числе воркеров. Мир теста — ряд одиночных квадратов далеко друг от друга (каждый — остров) и кучка из четырёх,
 * летящих друг на друга (мир: касания между телами). У всех работают мышцы — каждая третья клетка сжата. Второй
 * случай — остров с разрывом: половины квадрата разлетаются, тень острова встречает событие топологии, остров
 * откатывается к началу тика и идёт с миром. Проверка не пустая: островов в тиках не меньше половины, откат был.
 */
class IslandsTest {
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

    /** Одиночные квадраты в ряд (острова) и кучка из четырёх вплотную выше ряда (мир). */
    private fun scene(): BodyDefinition {
        var all = square(WIDTH, 0f, 0f)
        for (k in 1 until ALONE) all = all.concat(square(WIDTH, k * FAR, 0f))
        for (k in 0 until 4) all = all.concat(square(WIDTH, (k % 2) * NEAR, FAR + (k / 2) * NEAR))
        return all
    }

    private class Run(val state: FloatArray, val islandTicks: Int, val aborts: Long, val bpReuses: Long)

    private fun run(islands: Boolean, workers: Int, tear: Boolean, rollbackAll: Boolean = false, flip: Boolean = false): Run {
        val cfg = HashMap<String, String>()
        cfg["RB_PARALLEL_MIN"] = "1"
        if (islands) cfg["ISLANDS"] = "1"
        if (rollbackAll) cfg["ISLANDS_ROLLBACK_ALL"] = "1"
        val world = PhysicsWorld(PhysicsConfig(cfg), workers = if (workers > 1) PhysicsWorkers.shared(workers) else null)
        world.state.quiet = true
        world.load(scene())
        world.reset()
        val st = world.state
        val per = WIDTH * WIDTH
        // Кучка летит к своему центру: края квадратов упираются в соседей.
        val cx = NEAR / 2 + (WIDTH - 1) / 2.0f; val cy = FAR + NEAR / 2 + (WIDTH - 1) / 2.0f
        for (i in ALONE * per until st.n) { st.vx[i] = 1.5f * (cx - st.px[i]); st.vy[i] = 1.5f * (cy - st.py[i]) }
        // Мышцы: каждая третья клетка сжимается.
        for (i in 0 until st.n step 3) world.setCellScale(i, 0.7f)
        if (tear) {
            // Первый одиночный квадрат: половины разведены на [TEAR_GAP] — связи посередине втрое длиннее покоя и рвутся
            // в первом же проходе пределов его тени. Скоростью половины не развести: связи гасят её за подшаг.
            for (i in 0 until per) st.px[i] += if (i % WIDTH < WIDTH / 2) -TEAR_GAP else TEAR_GAP
        }
        var islandTicks = 0
        repeat(TICKS) { t ->
            // Смена режима каждый тик: острова то есть, то нет — как при выборе по замеру (IslandChooser).
            if (flip) st.islandsOn = t % 2 == 0
            world.step()
            if (st.islandsLastTick > 0) islandTicks++
        }
        val n = st.n
        val dead = FloatArray(st.conCount) { if (st.conDead[it]) 1f else 0f }
        return Run(st.px.copyOf(n) + st.py.copyOf(n) + st.vx.copyOf(n) + st.vy.copyOf(n) +
            st.conCellScale.copyOf(st.conCount) + dead, islandTicks, st.islandAborts, st.islandBpReuses)
    }

    @Test fun islandsGiveTheSameWorldBitForBit() {
        val off = run(islands = false, workers = 1, tear = false)
        for (workers in intArrayOf(1, 4)) {
            val on = run(islands = true, workers = workers, tear = false)
            assertTrue("островов почти нет (${on.islandTicks} тиков из $TICKS) — проверка пустая", on.islandTicks >= TICKS / 2)
            // Широкая фаза острова между тиками (IslandBroadphase): путь с запасом должен быть пройден, иначе он не проверен.
            assertTrue("запас широкой фазы острова ни разу не взят с прошлого тика", on.bpReuses > 0)
            assertArrayEquals("воркеров $workers", off.state, on.state, 0.0f)
        }
    }

    /**
     * Снимок острова полон: каждый остров после всех подшагов откатывается (ISLANDS_ROLLBACK_ALL) и идёт с миром —
     * сумма та же, что без островов. Забытый в снимке массив, который тень пишет, дал бы здесь расхождение.
     */
    @Test fun everyIslandRolledBackGivesTheSameWorld() {
        val off = run(islands = false, workers = 1, tear = false)
        val on = run(islands = true, workers = 4, tear = false, rollbackAll = true)
        assertTrue("откатов мало (${on.aborts}) — проверка пустая", on.aborts >= TICKS / 2)
        assertArrayEquals(off.state, on.state, 0.0f)
    }

    /** Режим меняется каждый тик (так переключает выбор по замеру): переходы не меняют суммы мира. */
    @Test fun switchingIslandsEveryTickGivesTheSameWorld() {
        val off = run(islands = false, workers = 1, tear = false)
        val on = run(islands = true, workers = 4, tear = false, flip = true)
        assertTrue("островов нет — проверка пустая", on.islandTicks >= TICKS / 4)
        assertArrayEquals(off.state, on.state, 0.0f)
    }

    @Test fun tornIslandRollsBackAndGivesTheSameWorld() {
        val off = run(islands = false, workers = 1, tear = true)
        val on = run(islands = true, workers = 4, tear = true)
        assertTrue("событий в острове не было — откат не проверен", on.aborts > 0)
        assertArrayEquals(off.state, on.state, 0.0f)
    }

    private companion object {
        const val WIDTH = 7
        /** Одиночных квадратов. */
        const val ALONE = 6
        /** Шаг одиночных: запас островов (ход за тик на потолке скорости и радиусы) с обеих сторон меньше зазора. */
        const val FAR = 60f
        /** Шаг кучки: зазор 1.4 — больше касания, но кучка сходится за несколько тиков. */
        const val NEAR = 7.4f
        const val TEAR_GAP = 1.0f
        const val TICKS = 40
    }
}
