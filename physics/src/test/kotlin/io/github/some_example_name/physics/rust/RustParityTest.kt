package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * ПОБИТОВАЯ СВЕРКА МИРА НА RUST С KOTLIN без касаний (`contactsOn = false`), всё остальное — как
 * в игре: связи, площади, кости, пределы длины с разрывом, среда, мышцы кластерами и по клетке,
 * мягкость, сон, тяга мышью, пластика, давление, временные связи, хватка, рамка мира (RUST_PORT.md).
 */
class RustParityTest {
    @Before
    fun needLibrary() {
        assumeTrue("нет библиотеки физики Rust: ${RustPhysics.loadError}", RustPhysics.available)
    }

    private fun body() = RustScenes.lattice(
        cols = 14, rows = 10,
        muscles = listOf(IntArray(10) { 14 * 2 + it }, IntArray(10) { 14 * 6 + it }),
        bones = listOf(intArrayOf(14 * 8 + 10, 14 * 8 + 11, 14 * 9 + 10, 14 * 9 + 11)),
    )

    @Test
    fun loadMatches() {
        RustScenes.Pair().use { p ->
            p.k.addBody(body()); p.r.addBody(body())
            p.check("загрузка")
            val st = p.k.state
            // Сцена обязана быть настоящей: связи, треугольники, контур, кости, мышцы.
            assertTrue("частиц ${st.n}", st.n == 140)
            assertTrue("связей ${st.conCount}", st.conCount > 300)
            assertTrue("треугольников ${st.triCount}", st.triCount > 200)
            assertTrue("рёбер контура ${st.localBoundary.count}", st.localBoundary.count > 40)
            assertEquals(1, st.rigidBones.size)
            assertEquals(2, st.muscleActivation.size)
        }
    }

    /** Сверка обязана ловить расхождение: один бит скорости в мире Rust — и отпечатки разные. */
    @Test
    fun digestCatchesOneBitDifference() {
        RustScenes.Pair().use { p ->
            p.k.addBody(body()); p.r.addBody(body())
            repeat(3) { p.step("до подмены") }
            val v = p.k.state.vx[70]
            p.k.setVelocity(70, v, p.k.state.vy[70])
            p.r.setVelocity(70, Float.fromBits(v.toRawBits() xor 1), p.k.state.vy[70])
            val diff = RustDigest.firstDifference(RustDigest.of(p.k.state), p.r.digest())
            assertTrue("подмена одного бита не замечена", diff != null && diff.contains("«vx»"))
            // Один бит скорости законно тонет в округлении x + vx·h (скорость потом заново из
            // позиций), поэтому для «переживает ли тик» сдвиг крупнее: 1e-3·h ≈ 1.7e-6 — больше
            // ulp координаты около 1 (1.2e-7).
            p.r.setVelocity(70, v + 1e-3f, p.k.state.vy[70])
            p.k.step(); p.r.step()
            val after = RustDigest.firstDifference(RustDigest.of(p.k.state), p.r.digest())
            assertTrue("расхождение исчезло за тик: $after", after != null)
        }
    }

    @Test
    fun idleBodyFallsAsleepTheSame() {
        RustScenes.Pair().use { p ->
            p.k.addBody(body()); p.r.addBody(body())
            repeat(120) { p.step("покой") }
            assertTrue("тело не уснуло", p.k.state.sleep.asleepCount > 0)
            assertTrue("тело Rust не уснуло", p.r.isAsleep(0))
        }
    }

    @Test
    fun muscleGaitMatches() {
        RustScenes.Pair().use { p ->
            p.k.addBody(body()); p.r.addBody(body())
            val x0 = p.k.state.px.copyOf()
            for (t in 0 until 240) {
                val phase = (t / 8) % 2
                for (m in 0 until 2) {
                    val v = if ((m + phase) % 2 == 0) 1f else 0f
                    p.k.setMuscleTarget(m, v); p.r.setMuscleTarget(m, v)
                }
                p.step("гребок")
            }
            var moved = 0f
            for (i in x0.indices) moved = maxOf(moved, kotlin.math.abs(p.k.state.px[i] - x0[i]))
            assertTrue("мышцы не двигают тело: $moved", moved > 0.2f * RustScenes.LINK)
        }
    }

    @Test
    fun cellScalesAndSoftnessMatch() {
        RustScenes.Pair().use { p ->
            p.k.addBody(body()); p.r.addBody(body())
            for (t in 0 until 200) {
                if (t % 20 == 0) {
                    val s = if ((t / 20) % 2 == 0) 0.6f else 1.3f
                    for (c in 30 until 40) { p.k.setCellScale(c, s); p.r.setCellScale(c, s) }
                }
                if (t == 15) for (c in 50 until 60) { p.k.setSoftness(c, 2.5f); p.r.setSoftness(c, 2.5f) }
                if (t == 90) for (c in 55 until 58) { p.k.setSoftness(c, 1f); p.r.setSoftness(c, 1f) }
                p.step("множители клеток")
            }
            val st = p.k.state
            assertTrue("множители клеток не включились", st.cellScalesOn && st.softnessOn)
            assertTrue("множитель связи не сдвинулся", (0 until st.conCount).any { st.conCellScale[it] != 1f })
        }
    }

    /** Волокна мышц (закон с 21.11): сжатие вдоль волокна, расслабление мягче вдоль него. */
    @Test
    fun fibersMatch() {
        RustScenes.Pair().use { p ->
            p.k.addBody(body()); p.r.addBody(body())
            for (c in 28 until 38) { p.k.setCellFiber(c, 1f, 0f); p.r.setCellFiber(c, 1f, 0f) }
            for (t in 0 until 160) {
                if (t % 40 == 0) {
                    val s = if ((t / 40) % 2 == 0) 0.6f else 1.4f
                    for (c in 28 until 38) { p.k.setCellScale(c, s); p.r.setCellScale(c, s) }
                }
                p.step("волокна")
            }
            assertTrue("волокна не включились", p.k.state.fibersOn)
        }
    }

    @Test
    fun tearingKicksMatch() {
        RustScenes.Pair().use { p ->
            p.k.addBody(body()); p.r.addBody(body())
            for (t in 0 until 160) {
                if (t == 5 || t == 40) {
                    // Удар по краю — до разрыва связей и распада на куски.
                    for (c in 0 until 6) {
                        p.k.setVelocity(c, -3f, 2f); p.r.setVelocity(c, -3f, 2f)
                        p.k.wake(c); p.r.wake(c)
                    }
                }
                if (t == 70) { p.k.kill(77); p.r.kill(77) }
                p.step("удар и разрыв")
            }
            val st = p.k.state
            assertTrue("удар ничего не порвал: ${st.tornTotal}", st.tornTotal > 0)
            assertTrue("тело не распалось: организмов ${st.organismCount}", st.organismCount > 1)
            assertTrue("нет погибшей клетки", st.cellDead[77])
        }
    }

    @Test
    fun dragJointsGripBoundsMatch() {
        RustScenes.Pair().use { p ->
            p.k.addBody(body()); p.r.addBody(body())
            p.k.setBounds(0.9f, 0.9f, 1.6f, 1.4f); p.r.setBounds(0.9f, 0.9f, 1.6f, 1.4f)
            for (t in 0 until 180) {
                if (t == 3) { p.k.grab(120); p.r.grab(120) }
                if (t in 3 until 60) {
                    val x = 1.3f + 0.002f * t; val y = 1.2f - 0.001f * t
                    p.k.dragTo(x, y); p.r.dragTo(x, y)
                }
                if (t == 60) { p.k.release(); p.r.release() }
                if (t == 70) {
                    val a = p.k.addJoint(0, 139, 0.05f, 1e-6f); val b = p.r.addJoint(0, 139, 0.05f, 1e-6f)
                    check(a == b) { "номер связи: $a против $b" }
                }
                if (t == 80) for (c in 14 until 20) { p.k.setGrip(c, 0.9f); p.r.setGrip(c, 0.9f) }
                if (t == 100) { p.k.setHome(5, 1.1f, 1.0f, 1e-6f); p.r.setHome(5, 1.1f, 1.0f, 1e-6f) }
                if (t == 120) { p.k.setDrag(7, 3f); p.r.setDrag(7, 3f); p.k.setMass(8, 4f); p.r.setMass(8, 4f) }
                if (t == 140) {
                    p.k.setEnvironment(-0.5f, 1.5f, 0.8f, 1.2f, 0.7f); p.r.setEnvironment(-0.5f, 1.5f, 0.8f, 1.2f, 0.7f)
                }
                p.step("тяга, связи, хватка, рамка, среда")
            }
            val st = p.k.state
            assertTrue("связь между телами не заведена", st.jointLiveCount + st.jointFreeN > 0)
            assertTrue("хватка не включилась", st.gripN > 0)
            // Рамка держит: ни одна частица не вышла за неё.
            for (i in 0 until st.n) assertTrue("частица $i вне рамки", st.px[i] in 0.9f..1.6f && st.py[i] in 0.9f..1.4f)
        }
    }
}
