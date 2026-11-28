package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * ОСТРОВА В RUST (RUST_PORT.md, «Острова в Rust»): мир Rust с островами (ISLANDS=1) сверяется с Kotlin побитово каждый
 * тик при 1, 2, 3 и 8 воркерах. Острова битов не меняют, поэтому отпечаток тот же, кроме кэша широкой фазы и списка
 * касаний к концу тика (RustDigest.ignoredFor). Сцены обязаны включать острова: тела порознь с мышцами, события
 * внутри островов (откат острова и подшаги с миром), откат каждого острова (ISLANDS_ROLLBACK_ALL=1 — проверка полноты
 * снимка), остров из группы сталкивающихся тел и тело на неподвижных частицах (не остров).
 */
class RustIslandsParityTest {
    @Before
    fun needLibrary() {
        assumeTrue("нет библиотеки физики Rust: ${RustPhysics.loadError}", RustPhysics.available)
    }

    private val link = RustScenes.LINK
    private val workerCounts = intArrayOf(1, 2, 3, 8)

    private fun config(workers: Int, rollbackAll: Boolean = false) = PhysicsConfig(mapOf(
        "ISLANDS" to "1", "ISLANDS_ROLLBACK_ALL" to (if (rollbackAll) "1" else "0"),
        "RB_WORKERS" to workers.toString(), "RB_PARALLEL_MIN" to "0"))

    private fun body(x0: Float, y0: Float, cols: Int = 12, rows: Int = 10) =
        RustScenes.lattice(cols, rows, x0 = x0, y0 = y0,
            muscles = listOf(IntArray(cols) { cols * 2 + it }, IntArray(cols) { cols * (rows - 3) + it }),
            bones = listOf(intArrayOf(cols * 4 + 5, cols * 4 + 6, cols * 5 + 5, cols * 5 + 6)))

    /** Тела рядами далеко друг от друга: каждое — свой остров. */
    private fun spread(p: RustScenes.Pair, count: Int, gap: Float = 30f) {
        for (k in 0 until count) {
            val b = body(1f + (k % 4) * gap * link, 1f + (k / 4) * gap * link)
            p.k.addBody(b); p.r.addBody(b)
        }
    }

    private fun gait(p: RustScenes.Pair, t: Int, muscles: Int) {
        val v = if ((t / 10) % 2 == 0) 1f else 0f
        for (m in 0 until muscles) { val s = if (m % 2 == 0) v else 1f - v; p.k.setMuscleTarget(m, s); p.r.setMuscleTarget(m, s) }
    }

    @Test
    fun separateBodiesAreIslands() {
        for (w in workerCounts) RustScenes.Pair(config(w), contacts = true).use { p ->
            spread(p, 8)
            var islandTicks = 0
            for (t in 0 until 60) {
                gait(p, t, 16)
                p.step("тела порознь, воркеров $w")
                if (p.k.state.islandsLastTick > 0) islandTicks++
            }
            println("воркеров $w: тиков с островами Kotlin $islandTicks из 60")
            assertTrue("Kotlin не взял островов — сцена не про них", islandTicks > 50)
        }
    }

    @Test
    fun eventsRollIslandsBack() {
        for (w in workerCounts) RustScenes.Pair(config(w), contacts = true).use { p ->
            spread(p, 6)
            val tornBefore = p.k.state.tornTotal
            for (t in 0 until 70) {
                gait(p, t, 12)
                // Удар по угловой клетке тел 1 и 4 — событие внутри острова (на этой сцене не разрыв, а другое событие
                // топологии, например предел длины связи): тень откатывает остров, и он идёт подшагами с миром.
                if (t == 20 || t == 45) for (b in intArrayOf(1, 4)) {
                    val first = p.k.bodyFirst(b + 1)
                    p.k.setVelocity(first, -60f, 45f); p.r.setVelocity(first, -60f, 45f); p.k.wake(first); p.r.wake(first)
                }
                p.step("события в островах, воркеров $w")
            }
            val st = p.k.state
            println("воркеров $w: разрывов ${st.tornTotal - tornBefore}, погибших ${st.deadCount}, откатов островов Kotlin ${st.islandAborts}")
            assertTrue("событий в островах не было — откатов нет", st.islandAborts > 0)
        }
    }

    @Test
    fun everyIslandRolledBack() {
        for (w in intArrayOf(1, 3)) RustScenes.Pair(config(w, rollbackAll = true), contacts = true).use { p ->
            spread(p, 6)
            for (t in 0 until 40) {
                gait(p, t, 12)
                p.step("откат каждого острова, воркеров $w")
            }
            assertTrue("островов не было", p.k.state.islandAborts > 0)
        }
    }

    @Test
    fun touchingBodiesFormOneIsland() {
        for (w in workerCounts) RustScenes.Pair(config(w), contacts = true).use { p ->
            // Пара тел вплотную — один остров на двоих; ещё два тела порознь; тело на неподвижных частицах — мир.
            val a = body(1f, 1f); val b = body(1f + 12 * link + 0.4f * link, 1.03f)
            p.k.addBody(a); p.r.addBody(a); p.k.addBody(b); p.r.addBody(b)
            for (k in 0 until 2) { val c = body(1f + (2 + k) * 40 * link, 1f); p.k.addBody(c); p.r.addBody(c) }
            val d = body(1f, 1f + 40 * link)
            p.k.addBody(d); p.r.addBody(d)
            for (k in 0 until 20) {
                val x = 1f + k * 0.6f * link; val y = 1f + 38.5f * link
                p.k.addFixed(x, y, 0.3f * link); p.r.addFixed(x, y, 0.3f * link)
            }
            for (c in 0 until 120) { p.k.setVelocity(c, 6f, 0f); p.r.setVelocity(c, 6f, 0f) }
            var islandTicks = 0
            for (t in 0 until 60) {
                gait(p, t, 10)
                p.step("группа тел и рельеф, воркеров $w")
                if (p.k.state.islandsLastTick > 0) islandTicks++
            }
            assertTrue("Kotlin не взял островов", islandTicks > 30)
        }
    }
}
