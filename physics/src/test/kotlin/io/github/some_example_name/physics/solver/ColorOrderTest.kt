package io.github.some_example_name.physics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ПОРЯДОК ПО ЦВЕТАМ — ЗАКОН С 27.11.2026 (ColorOrder.kt, находка 43).
 *
 * 1. Раскраска верна: у каждого набора (блок решателя или швы) списки связей, площадей, изгибов и створок по цветам — те
 *    же живые номера, в порядке «цвет, затем прежний порядок», цвет — наименьший свободный у частиц (сверка со второй,
 *    простой реализацией), и внутри цвета ни одна частица не повторяется.
 * 2. Мир с цветами не зависит от числа воркеров и от островов, в том числе после разрывов (раскраска пересобирается).
 * 3. Порядок действительно другой: мир с цветами отличается от мира по номерам (COLOR_ORDER=0), иначе проверки выше пустые.
 * 4. Момент импульса тела в пустоте по цветам блуждает не сильнее, чем по номерам (среднее по 24 телам и вращениям).
 *
 * Мир — квадраты из треугольников (крупный — на два блока, есть швы), цепочка без треугольников (у неё створки), мышцы
 * сжаты и расслаблены, у части клеток волокно; кучка квадратов сталкивается.
 */
class ColorOrderTest {
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

    /** Цепочка клеток без треугольников: голые связи, у каждой внутренней клетки — створка. Слегка зигзагом. */
    private fun chain(length: Int, shiftX: Float, shiftY: Float): BodyDefinition =
        BodyDefinition(FloatArray(length) { it + shiftX }, FloatArray(length) { (it % 2) * 0.3f + shiftY },
            FloatArray(length) { 0.5f }, BooleanArray(length), BooleanArray(length),
            IntArray(length - 1) { it }, IntArray(length - 1) { it + 1 },
            IntArray(0), IntArray(0), IntArray(0), FloatArray(0), emptyArray(), emptyArray(), 1f)

    private fun scene(): BodyDefinition {
        var all = square(26, 0f, 0f)
        all = all.concat(chain(24, 0f, 40f))
        for (k in 0 until 4) all = all.concat(square(7, 60f + (k % 2) * 7.4f, (k / 2) * 7.4f))
        return all
    }

    private fun world(color: Boolean, workers: Int, islands: Boolean, tear: Boolean): PhysicsWorld {
        val cfg = HashMap<String, String>()
        cfg["RB_PARALLEL_MIN"] = "1"
        // Цвета — закон; прежний порядок по номерам — ключом COLOR_ORDER=0.
        if (!color) cfg["COLOR_ORDER"] = "0"
        if (islands) cfg["ISLANDS"] = "1"
        val world = PhysicsWorld(PhysicsConfig(cfg), workers = if (workers > 1) PhysicsWorkers.shared(workers) else null)
        world.state.quiet = true
        world.sleepOn = false
        world.load(scene())
        world.reset()
        val st = world.state
        for (i in 0 until st.n) {
            if (i % 3 == 0) world.setCellScale(i, 0.7f) else if (i % 7 == 0) world.setCellScale(i, 1.3f)
            if (i % 2 == 0) world.setCellFiber(i, 0.8f, 0.6f)
        }
        // Кучка из четырёх квадратов летит к своему центру.
        val first = 26 * 26 + 24
        for (i in first until st.n) { st.vx[i] = 1.5f * (63.7f - st.px[i]); st.vy[i] = 1.5f * (6.7f - st.py[i]) }
        // Разрыв: половины крупного квадрата разведены — связи посередине втрое длиннее покоя.
        if (tear) for (i in 0 until 26 * 26) st.px[i] += if (i % 26 < 13) -1.0f else 1.0f
        return world
    }

    private fun state(world: PhysicsWorld): FloatArray {
        val st = world.state; val n = st.n
        val dead = FloatArray(st.conCount) { if (st.conDead[it]) 1f else 0f }
        return st.px.copyOf(n) + st.py.copyOf(n) + st.vx.copyOf(n) + st.vy.copyOf(n) + dead
    }

    private fun run(color: Boolean, workers: Int, islands: Boolean = false, tear: Boolean = false): FloatArray {
        val world = world(color, workers, islands, tear)
        repeat(TICKS) { world.step() }
        return state(world)
    }

    @Test fun colorOrderIsTheSameWithWorkersAndIslands() {
        val off = run(color = false, workers = 1)
        val one = run(color = true, workers = 1)
        var differ = 0
        for (k in off.indices) if (off[k].toRawBits() != one[k].toRawBits()) differ++
        assertTrue("мир с цветами не отличается от мира без них — ключ ничего не меняет, проверки пустые", differ > off.size / 10)
        assertArrayEquals("4 воркера", one, run(color = true, workers = 4), 0.0f)
        assertArrayEquals("4 воркера и острова", one, run(color = true, workers = 4, islands = true), 0.0f)
    }

    @Test fun colorOrderAfterTearsIsTheSameWithWorkersAndIslands() {
        val world = world(color = true, workers = 1, islands = false, tear = true)
        val linksBefore = world.state.conCount
        repeat(TICKS) { world.step() }
        val one = state(world)
        // Порванная связь после пересборки топологии уходит из массивов, поэтому считается и убыль, и ещё не убранные.
        val torn = linksBefore - world.state.conCount + (0 until world.state.conCount).count { world.state.conDead[it] }
        assertTrue("разрывов нет (связей было $linksBefore, стало ${world.state.conCount}) — пересборка раскраски не проверена", torn > 0)
        assertArrayEquals("4 воркера", one, run(color = true, workers = 4, tear = true), 0.0f)
        assertArrayEquals("4 воркера и острова", one, run(color = true, workers = 4, islands = true, tear = true), 0.0f)
    }

    /** Ожидаемый порядок по цветам — простой второй реализацией: наименьший свободный цвет, затем по цвету устойчиво. */
    private fun expected(ids: List<Int>, particles: (Int) -> IntArray): Pair<List<Int>, List<Int>> {
        val taken = HashMap<Int, HashSet<Int>>()
        val colorOf = ids.map { id ->
            val ps = particles(id)
            var color = 0
            while (ps.any { taken[it]?.contains(color) == true }) color++
            for (p in ps) taken.getOrPut(p) { HashSet() }.add(color)
            color
        }
        val order = ids.indices.sortedWith(compareBy({ colorOf[it] }, { it }))
        return order.map { ids[it] } to order.map { colorOf[it] }
    }

    private fun checkList(what: String, bucket: Int, live: List<Int>, colored: List<Int>, particles: (Int) -> IntArray): Int {
        val (order, colors) = expected(live, particles)
        assertEquals("$what, набор $bucket: порядок по цветам", order, colored)
        // Внутри цвета ни одна частица не повторяется.
        var k = 0
        while (k < colors.size) {
            val seen = HashSet<Int>()
            var e = k
            while (e < colors.size && colors[e] == colors[k]) {
                for (p in particles(order[e])) assertTrue("$what, набор $bucket, цвет ${colors[k]}: частица $p дважды", seen.add(p))
                e++
            }
            k = e
        }
        return colored.size
    }

    @Test fun colorsAreAValidGreedyColoring() {
        val world = world(color = true, workers = 1, islands = false, tear = true)
        repeat(TICKS) { world.step() } // с разрывами: раскраска пересобиралась
        val st = world.state
        val layout = st.constraintBlocks!!
        var links = 0; var areas = 0; var bends = 0; var flaps = 0
        for (b in 0 until layout.buckets) {
            val l = layout.links
            val liveLinks = (l.start[b] until l.start[b] + l.count[b]).map { l.items[it] }.filter { it >= 0 }
            val cl = st.coloredLinks(layout, b)
            links += checkList("связи", b, liveLinks, (0 until l.colorCount[b]).map { cl[l.start[b] + it] }) { intArrayOf(st.conA[it], st.conB[it]) }
            val a = layout.areas
            val liveAreas = (a.start[b] until a.start[b] + a.count[b]).map { a.items[it] }.filter { it >= 0 }
            val ca = st.coloredAreas(layout, b)
            areas += checkList("площади", b, liveAreas, (0 until a.colorCount[b]).map { ca[a.start[b] + it] }) { intArrayOf(st.triA[it], st.triB[it], st.triC[it]) }
            val bendIds = layout.bends.dense(b).copyOf(layout.bends.denseCount[b]).toList()
            val cb = st.coloredBends(layout, b)
            bends += checkList("изгибы", b, bendIds, (0 until layout.bends.colorCount[b]).map { cb[it] }) { intArrayOf(st.bendA[it], st.bendB[it]) }
            val flapIds = layout.flaps.dense(b).copyOf(layout.flaps.denseCount[b]).toList()
            val cf = st.coloredFlaps(layout, b)
            flaps += checkList("створки", b, flapIds, (0 until layout.flaps.colorCount[b]).map { cf[it] }) { intArrayOf(st.flapA[it], st.flapB[it], st.flapV[it]) }
        }
        assertTrue("связей $links, площадей $areas, изгибов $bends, створок $flaps — какой-то стадии нет, она не проверена",
            links > 1000 && areas > 1000 && bends > 50 && flaps > 5)
    }

    /**
     * Момент импульса тела в пустоте за 300 тиков в обоих порядках, на 24 телах и вращениях: во float он блуждает (шум
     * скорости ~2.5e-5 на клетку), и у одного тела в одном порядке может уйти на 1e-2. Сторожится не каждое тело, а среднее:
     * по цветам оно не больше, чем в полтора раза, чем по номерам. 27.11: 2.23e-3 по номерам, 2.25e-3 по цветам.
     */
    @Test fun angularMomentumDriftBothOrders() {
        val sums = DoubleArray(2); var worse = 0; var cases = 0
        for (cols in intArrayOf(4, 6, 9, 13)) for (rows in intArrayOf(4, 7)) for (turn in floatArrayOf(0.02f, 0.04f, 0.08f)) {
            val drift = DoubleArray(2)
            for (k in 0..1) {
                val w = gameWorld(if (k == 1) emptyMap() else mapOf("COLOR_ORDER" to "0")).vacuum()
                val def = patch(cols, rows)
                val first = w.bodyFirst(w.addBody(def))
                val ids = first until first + def.count
                val st = w.state
                val cx = ids.sumOf { st.px[it].toDouble() }.toFloat() / def.count; val cy = ids.sumOf { st.py[it].toDouble() }.toFloat() / def.count
                val omega = turn / DT
                for (i in ids) w.setVelocity(i, 0.01f / DT - omega * (st.py[i] - cy), omega * (st.px[i] - cx))
                w.wake(first)
                fun spin(m: FloatArray): Float {
                    val x = ids.sumOf { st.px[it].toDouble() }.toFloat() / def.count; val y = ids.sumOf { st.py[it].toDouble() }.toFloat() / def.count
                    return m[2] - (x * m[1] - y * m[0])
                }
                val l0 = spin(w.momentum())
                repeat(300) { w.step() }
                drift[k] = kotlin.math.abs(spin(w.momentum()) / l0 - 1.0).toDouble()
            }
            sums[0] += drift[0]; sums[1] += drift[1]; cases++
            if (drift[1] > drift[0]) worse++
            println("момент: тело ${cols}×$rows, поворот $turn за тик — по номерам %.2e, по цветам %.2e".format(drift[0], drift[1]))
        }
        println("момент: в среднем по номерам %.2e, по цветам %.2e; цвета хуже в $worse из $cases".format(sums[0] / cases, sums[1] / cases))
        assertTrue("момент по цветам блуждает в среднем ${sums[1] / cases} против ${sums[0] / cases} по номерам — снос",
            sums[1] <= 1.5 * sums[0])
        assertTrue("момент не блуждает вовсе (${sums[0] / cases}) — замер пустой", sums[0] / cases > 1e-5)
    }

    private companion object {
        const val TICKS = 30
    }
}
