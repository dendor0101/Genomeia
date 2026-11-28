package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * ПОРЯДОК ПО ЦВЕТАМ В RUST (RUST_PORT.md; закон с 27.11 — ColorOrder.kt, в Rust solver/color_order.rs). Цвета включены
 * по умолчанию, поэтому остальные тесты сверки Rust уже идут с ними (с воркерами и островами). Здесь — то, чего они не
 * гарантируют: прежний порядок (COLOR_ORDER=0) по-прежнему побитово; перекраска наборов после разрывов, гибели и пробоя
 * (правка набора помечает раскраску устаревшей) при 1, 2, 3 и 8 воркерах; изгибы и створки по цветам. Изгиб в игре
 * выключен (уровень 0) — сцена включает его в Kotlin и переносит в Rust снимком: другого входа у Rust нет.
 */
class RustColorOrderParityTest {
    @Before
    fun needLibrary() {
        assumeTrue("нет библиотеки физики Rust: ${RustPhysics.loadError}", RustPhysics.available)
    }

    private val link = RustScenes.LINK

    private fun config(workers: Int, colors: Boolean) = PhysicsConfig(mapOf(
        "COLOR_ORDER" to (if (colors) "1" else "0"), "ISLANDS" to "0",
        "RB_WORKERS" to workers.toString(), "RB_PARALLEL_MIN" to "0"))

    /** Тело A с костью и мышцей и тело B в трети связи справа — одним описанием (как таран в RustContactParityTest). */
    private fun ram(): BodyDefinition {
        val a = RustScenes.lattice(10, 8, x0 = 1f, y0 = 1f,
            muscles = listOf(IntArray(8) { 10 * 3 + it }),
            bones = listOf(intArrayOf(10 * 6 + 2, 10 * 6 + 3, 10 * 7 + 2, 10 * 7 + 3)))
        val b = RustScenes.lattice(8, 6, x0 = 1f + 10 * link + 0.3f * link, y0 = 1.04f)
        return a.concat(b)
    }

    private fun kick(p: RustScenes.Pair, from: Int, to: Int, vx: Float, vy: Float) {
        for (c in from until to) {
            p.k.setVelocity(c, vx, vy); p.r.setVelocity(c, vx, vy)
            p.k.wake(c); p.r.wake(c)
        }
    }

    @Test
    fun colorsAreTheDefault() {
        assertTrue("цвета по умолчанию выключены — тогда остальные тесты сверки идут без них",
            BodyState(PhysicsConfig()).colorOrderOn)
        assertFalse(BodyState(PhysicsConfig(mapOf("COLOR_ORDER" to "0"))).colorOrderOn)
    }

    @Test
    fun bothOrdersMatchThroughTears() {
        for (colors in booleanArrayOf(true, false)) for (w in intArrayOf(1, 2, 3, 8)) {
            RustScenes.Pair(config(w, colors), contacts = true).use { p ->
                p.k.addBody(ram()); p.r.addBody(ram())
                kick(p, 80, 128, -80f, 0.3f)
                p.k.setMuscleTarget(0, 1f); p.r.setMuscleTarget(0, 1f)
                for (t in 0 until 120) p.step("цвета $colors, воркеров $w")
                val st = p.k.state
                println("цвета $colors, воркеров $w: разрывов ${st.tornTotal}, погибших ${st.deadCount}, " +
                    "пробоев ${st.piercedCount}")
                assertTrue("таран ничего не порвал — перекраска после правки набора не проверена",
                    st.tornTotal + st.deadCount + st.piercedCount > 0)
            }
        }
    }

    @Test
    fun bendsAndFlapsByColor() {
        // Тест сам сверяет Kotlin с Rust своей парой миров. Уровень изгиба он пишет в состояние Kotlin в обход методов
        // (входа нет), а тень `-PrustShadow` у мира Kotlin такую запись в снимке не прощает (как у BoneSplitTest), —
        // поэтому своим мирам тест тень не заводит.
        val shadow = RustShadow.requested
        RustShadow.requested = false
        try {
            for (w in intArrayOf(1, 3)) {
                // Снимок мира Kotlin с включённым изгибом; оба мира пары читают его (Rust читает снимок только в новый мир).
                val (bytes, key) = RustScenes.Pair(config(w, colors = true), contacts = true).use { src ->
                    src.k.addBody(ram())
                    src.k.state.bendLevel = 3
                    java.io.ByteArrayOutputStream().also { src.k.write(java.io.DataOutputStream(it)) }.toByteArray() to
                        src.k.materialKey()
                }
                RustScenes.Pair(config(w, colors = true), contacts = true).use { p -> bendsAndFlaps(p, bytes, key, w) }
            }
        } finally {
            RustShadow.requested = shadow
        }
    }

    private fun bendsAndFlaps(p: RustScenes.Pair, bytes: ByteArray, key: String, w: Int) {
        p.k.read(java.io.DataInputStream(bytes.inputStream())); p.r.read(bytes, key)
        assertTrue("уровень изгиба не дошёл через снимок", p.k.state.bendLevel == 3)
        p.check("изгиб включён снимком")
        kick(p, 80, 128, -80f, 0.3f)
        var bends = 0; var flaps = 0
        for (t in 0 until 120) {
            p.step("изгибы и створки, воркеров $w")
            bends = maxOf(bends, p.k.state.bendCount); flaps = maxOf(flaps, p.k.state.flapCount)
        }
        println("воркеров $w: изгибов до $bends, створок до $flaps")
        assertTrue("в сцене нет изгибов", bends > 0)
        assertTrue("в сцене нет створок", flaps > 0)
    }
}
