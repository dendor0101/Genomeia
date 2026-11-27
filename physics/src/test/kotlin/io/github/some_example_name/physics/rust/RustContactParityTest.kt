package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * ПОБИТОВАЯ СВЕРКА С КАСАНИЯМИ (RUST_PORT.md): широкая фаза, CCD, пробой, гибель от глубокого
 * касания, XPBD-касания, кости в касаниях, отскок, сон по касаниям. Два тела — одним описанием
 * (склейка), чтобы не зависеть от дописывания тел.
 */
class RustContactParityTest {
    @Before
    fun needLibrary() {
        assumeTrue("нет библиотеки физики Rust: ${RustPhysics.loadError}", RustPhysics.available)
    }

    private val link = RustScenes.LINK

    /** Тело A с костью и мышцами слева и тело B справа на расстоянии [gap] связей. */
    private fun pair(gap: Float, bones: Boolean = true): BodyDefinition {
        val a = RustScenes.lattice(10, 8, x0 = 1f, y0 = 1f,
            muscles = listOf(IntArray(8) { 10 * 3 + it }),
            bones = if (bones) listOf(intArrayOf(10 * 6 + 2, 10 * 6 + 3, 10 * 7 + 2, 10 * 7 + 3)) else emptyList())
        val b = RustScenes.lattice(8, 6, x0 = 1f + 10 * link + gap * link, y0 = 1.04f)
        return a.concat(b)
    }

    private fun kick(p: RustScenes.Pair, from: Int, to: Int, vx: Float, vy: Float) {
        for (c in from until to) {
            p.k.setVelocity(c, vx, vy); p.r.setVelocity(c, vx, vy)
            p.k.wake(c); p.r.wake(c)
        }
    }

    @Test
    fun gentleTouchMatches() {
        RustScenes.Pair(contacts = true).use { p ->
            p.k.addBody(pair(1.5f)); p.r.addBody(pair(1.5f))
            p.check("загрузка")
            kick(p, 80, 128, -0.15f, 0f)
            var touched = 0
            for (t in 0 until 200) {
                p.step("мягкое касание")
                touched = maxOf(touched, p.k.state.contacts!!.contactCount)
            }
            assertTrue("тела так и не коснулись", touched > 0)
        }
    }

    /**
     * Настройка игры: в Kotlin острова по замеру (в Rust их нет); сверяется всё, кроме кэша широкой фазы и списка
     * касаний последнего подшага (RustDigest.ignoredFor). Острова в сцене обязаны быть.
     */
    @Test
    fun gentleTouchWithIslandsMatches() {
        RustScenes.Pair(RustScenes.GAME, contacts = true).use { p ->
            p.k.addBody(pair(1.5f)); p.r.addBody(pair(1.5f))
            kick(p, 80, 128, -0.15f, 0f)
            var islandTicks = 0
            for (t in 0 until 200) {
                p.step("мягкое касание, острова")
                if (p.k.state.islandsLastTick > 0) islandTicks++
            }
            println("острова: тиков с островами $islandTicks из 200")
            assertTrue("острова в сцене не случились", islandTicks > 0)
        }
    }

    /**
     * Таран на нескольких скоростях. Каждая обязана дать касания; вместе — разрывы, пробой или гибель и подшаги с CCD:
     * иначе пути разрушения в сверке не участвуют. Закон с 21.11 крепче, а потолок скорости гасит удар уже после
     * первого подшага, поэтому зазор — треть связи, а скорость первого подшага — до нескольких связей за подшаг
     * (80 ед/с ≈ 4.6 связи): только так тело B проскакивает в A за подшаг, и касание ловит его по времени удара.
     */
    @Test
    fun ramWithTearsPierceAndKillsMatches() {
        var damage = 0
        var ccdAll = 0
        for (speed in floatArrayOf(5f, 20f, 40f, 80f)) RustScenes.Pair(contacts = true).use { p ->
            p.k.addBody(pair(0.3f)); p.r.addBody(pair(0.3f))
            kick(p, 80, 128, -speed, 0.3f)
            var maxContacts = 0
            var ccdTicks = 0
            p.k.state.dbgSubstepHook = { if (p.k.state.contacts!!.lastToiClamps > 0) ccdTicks++ }
            for (t in 0 until 150) {
                p.step("таран $speed")
                maxContacts = maxOf(maxContacts, p.k.state.contacts!!.contactCount)
            }
            val st = p.k.state
            println("таран $speed: касаний до $maxContacts, подшагов с CCD $ccdTicks, разрывов ${st.tornTotal}, " +
                "пробоев ${st.piercedCount}, погибших ${st.deadCount}, организмов ${st.organismCount}")
            assertTrue("таран $speed без касаний: $maxContacts", maxContacts > 5)
            damage += st.tornTotal + st.deadCount + st.piercedCount
            ccdAll += ccdTicks
        }
        assertTrue("тараны ничего не порвали, не пробили и не убили", damage > 0)
        assertTrue("тараны ни разу не дошли до CCD", ccdAll > 0)
    }

    @Test
    fun selfFoldByMuscleMatches() {
        val body = RustScenes.lattice(16, 5, muscles = listOf(IntArray(16) { 16 * 2 + it }, IntArray(16) { 16 * 4 + it }))
        RustScenes.Pair(contacts = true).use { p ->
            p.k.addBody(body); p.r.addBody(body)
            p.k.setMuscleTarget(0, 1f); p.r.setMuscleTarget(0, 1f)
            for (t in 0 until 160) {
                if (t == 60) { p.k.setMuscleTarget(1, 1f); p.r.setMuscleTarget(1, 1f) }
                if (t == 110) { p.k.setAllMuscleTargets(0f); p.r.setAllMuscleTargets(0f) }
                p.step("складка мышцей")
            }
        }
    }

    @Test
    fun hitWakesSleepingBodyMatches() {
        RustScenes.Pair(contacts = true).use { p ->
            p.k.addBody(pair(3f, bones = false)); p.r.addBody(pair(3f, bones = false))
            // Сначала оба тела засыпают (сон по SLEEP_TICKS), потом B бьёт в спящее A.
            repeat(90) { p.step("засыпание") }
            assertTrue("тело не уснуло", p.k.state.sleep.asleepCount > 0)
            kick(p, 80, 128, -1.2f, 0f)
            var woke = false
            for (t in 0 until 120) {
                p.step("удар в спящего")
                if (!p.k.isAsleep(0)) woke = true
            }
            assertTrue("удар не разбудил спящее тело", woke)
        }
    }

    @Test
    fun noDeepKillAndContactlessMatch() {
        RustScenes.Pair(contacts = true).use { p ->
            p.k.addBody(pair(1f)); p.r.addBody(pair(1f))
            p.k.deepKillOn = false; p.r.setDeepKillOn(false)
            for (c in 0 until 10) { p.k.setContactless(c * 10 + 9, true); p.r.setContactless(c * 10 + 9, true) }
            kick(p, 80, 128, -1.8f, -0.2f)
            repeat(120) { p.step("без смерти, присоски") }
        }
    }
}
