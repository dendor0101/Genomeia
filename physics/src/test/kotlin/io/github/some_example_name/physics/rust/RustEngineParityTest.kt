package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * ФИЗИКА НА RUST В ИГРЕ (RustEngine.kt, RUST_PORT.md «Rust в игре»): два фасада [PhysicsWorld] — на Kotlin и на Rust —
 * идут одними вызовами, как их делает мост игры (WorldPhysics): подсадка растущего тела, рождение клеток и деление,
 * множители, мягкость, касания, хватка, прямые записи моста в цели мышц, свободные частицы и неподвижные, липучки,
 * гибель, кость, толчки, мышь, сон, среда, рамка, удаление тела с уплотнением, снимок. После каждой правки и каждого
 * тика сверяется всё, что игра читает у мира: массивы зеркала (позиции, скорости, гибель, рождение, кости, множители,
 * мягкость, касания, хватка, радиус, сон), журналы гибели и смены края, край по клеткам, топология, тела, свободные
 * частицы, липучки. Rust = Kotlin побитово, поэтому всё обязано совпасть.
 */
class RustEngineParityTest {
    @Before
    fun needLibrary() {
        assumeTrue("нет библиотеки физики Rust: ${RustPhysics.loadError}", RustPhysics.available)
    }

    @After
    fun restore() { RustEngine.preferRust = false }

    private val link = RustScenes.LINK

    private fun pair(config: PhysicsConfig = PhysicsConfig(), workers: PhysicsWorkers? = null): Pair<PhysicsWorld, PhysicsWorld> {
        RustEngine.preferRust = false
        val k = PhysicsWorld(config, workers = workers, scale = RustScenes.SCALE).apply { state.quiet = true }
        RustEngine.preferRust = true
        val r = PhysicsWorld(config, workers = workers, scale = RustScenes.SCALE)
        RustEngine.preferRust = false
        assertTrue("мир не взял движок Rust", r.isRustEngine)
        assertTrue(!k.isRustEngine)
        return k to r
    }

    /** То, что игра читает у мира, — побитово. */
    private fun same(k: PhysicsWorld, r: PhysicsWorld, what: String) {
        val n = k.particleCount
        assertEquals("$what: частиц", n, r.particleCount)
        // Пустой мир (до первой подсадки): массивов Kotlin ещё нет — игра их тогда и не читает (WorldPhysics.publish).
        if (n == 0) return
        fun f(name: String, a: FloatArray, b: FloatArray) =
            assertArrayEquals("$what: $name", a.copyOf(n).map { it.toRawBits() }.toIntArray(), b.copyOf(n).map { it.toRawBits() }.toIntArray())
        fun z(name: String, a: BooleanArray, b: BooleanArray) =
            assertTrue("$what: $name", a.copyOf(n).contentEquals(b.copyOf(n)))
        val ks = k.state; val rs = r.state
        f("x", k.x, r.x); f("y", k.y, r.y); f("vx", k.vx, r.vx); f("vy", k.vy, r.vy)
        f("cellScale", ks.cellScale, rs.cellScale); f("cellScaleTarget", ks.cellScaleTarget, rs.cellScaleTarget)
        f("cellSoftness", ks.cellSoftness, rs.cellSoftness); f("cellGrip", ks.cellGrip, rs.cellGrip)
        f("radius", ks.radius, rs.radius)
        z("cellDead", ks.cellDead, rs.cellDead); z("cellUnborn", ks.cellUnborn, rs.cellUnborn)
        z("cellNoContact", ks.cellNoContact, rs.cellNoContact)
        assertArrayEquals("$what: boneOf", ks.boneOf.copyOf(n), rs.boneOf.copyOf(n))
        assertEquals("$what: cellScalesOn", ks.cellScalesOn, rs.cellScalesOn)
        assertEquals("$what: cellScalesMoving", ks.cellScalesMoving, rs.cellScalesMoving)
        assertEquals("$what: уплотнений", k.compactions, r.compactions)
        assertEquals("$what: гибель рёбер", k.edgeDeathCount, r.edgeDeathCount)
        for (e in 0 until k.edgeDeathCount) {
            assertEquals("$what: ребро $e", k.edgeDeathA(e), r.edgeDeathA(e)); assertEquals(k.edgeDeathB(e), r.edgeDeathB(e))
        }
        assertEquals("$what: гибель клеток", k.cellDeathCount, r.cellDeathCount)
        for (c in 0 until k.cellDeathCount) assertEquals("$what: гибель $c", k.cellDeath(c), r.cellDeath(c))
        assertEquals("$what: смен края", k.surfaceChangeCount, r.surfaceChangeCount)
        for (c in 0 until k.surfaceChangeCount) assertEquals("$what: смена края $c", k.surfaceChange(c), r.surfaceChange(c))
        for (i in 0 until n) {
            assertEquals("$what: край $i", k.isSurfaceCell(i), r.isSurfaceCell(i))
            assertEquals("$what: сон $i", k.isAsleep(i), r.isAsleep(i))
            assertEquals("$what: связей $i", k.liveDegree(i), r.liveDegree(i))
            assertEquals("$what: соседей $i", k.neighborCount(i), r.neighborCount(i))
        }
        assertEquals("$what: контур", k.boundaryEdgeCount, r.boundaryEdgeCount)
        assertEquals("$what: deepKillOn", k.deepKillOn, r.deepKillOn)
        assertEquals("$what: sleepOn", k.sleepOn, r.sleepOn)
        assertEquals("$what: свободных", k.freeCount, r.freeCount)
    }

    private inline fun both(k: PhysicsWorld, r: PhysicsWorld, what: String, op: (PhysicsWorld) -> Unit) {
        op(k); op(r); same(k, r, what)
    }

    private fun grower(x0: Float, y0: Float) = RustScenes.lattice(8, 6, x0 = x0, y0 = y0,
        muscles = listOf(IntArray(8) { 8 * 2 + it }), bones = listOf(intArrayOf(8 * 3 + 3, 8 * 3 + 4, 8 * 4 + 3, 8 * 4 + 4)))

    /** Подсадка, рост, правки клеток, прямые записи моста, гибель, кость, свободные частицы, липучки, сон и тики. */
    private fun gameScene(workers: PhysicsWorkers?, ticks: Int) {
        val (k, r) = pair(workers = workers)
        both(k, r, "рамка") { it.setBounds(-2f, -2f, 4f, 4f) }
        val born = BooleanArray(48) { it == 8 * 2 + 3 }
        var bodyK = -1; var bodyR = -1
        both(k, r, "подсадка зиготы") { w -> val b = w.addBody(grower(1f, 1f), born, BooleanArray(48) { true }); if (w === k) bodyK = b else bodyR = b }
        assertEquals(bodyK, bodyR)
        val other = run { var b0 = -1; both(k, r, "второе тело") { w -> b0 = w.addBody(grower(1f + 12 * link, 1.02f)) }; b0 }
        val first = k.bodyFirst(bodyK)
        assertEquals(first, r.bodyFirst(bodyR))
        val food = IntArray(6); val fixed = IntArray(3)
        for (q in food.indices) both(k, r, "еда $q") { w -> food[q] = w.addFree(0.5f + q * 0.05f, 0.4f, 0.3f * link, vx = 0.02f * q) }
        for (q in fixed.indices) both(k, r, "стенка $q") { w -> fixed[q] = w.addFixed(-1f + q * link, -1.5f, 0.5f * link) }
        both(k, r, "среда") { it.setEnvironment(-0.2f, 1f, 1f, 1f, 1f) }
        var joint = -1
        var bornN = 0; var edgeDeaths = 0; var cellDeaths = 0; var surfaceChanges = 0
        for (t in 0 until ticks) {
            bornN = (0 until 48).count { !k.isUnborn(first + it) }
            // Рост: каждые 2 тика рождается следующая клетка, через раз — делением от рождённого соседа.
            if (t % 2 == 0) for (c in 0 until 48) if (k.isUnborn(first + c)) {
                val parent = (0 until 48).firstOrNull { p -> !k.isUnborn(first + p) && !k.state.cellDead[first + p] }
                if (parent != null && t % 4 == 0) both(k, r, "деление $c") { it.activateFromParent(first + c, first + parent) }
                else both(k, r, "рождение $c") { it.activate(first + c) }
                break
            }
            // Мышцы через метод (как редкий путь моста) и прямо в зеркало (быстрый путь моста при выключенном сне).
            val s = if ((t / 8) % 2 == 0) 0.7f else 1.0f
            if (t % 3 == 0) for (c in 16 until 24) if (!k.isUnborn(first + c)) both(k, r, "мышца $c") { it.setCellScale(first + c, s) }
            if (t == 30) both(k, r, "сон выкл") { it.sleepOn = false }
            if (t in 31 until 56 && t % 5 == 0) for (w in listOf(k, r)) {
                val st = w.state
                val i = k.bodyFirst(other) + (t % 48)
                if (st.cellScaleTarget[i] != s) { st.cellScaleTarget[i] = s; st.cellScalesOn = true }
                if (st.cellScale[i] != s) st.cellScalesMoving = true
            }
            if (t == 12) both(k, r, "мягкость") { it.setSoftness(first + 20, 2.5f) }
            if (t == 14) both(k, r, "без касаний") { it.setContactless(first + 21, true) }
            if (t == 16) both(k, r, "хватка") { it.setGrip(first + 22, 0.9f) }
            if (t == 18) both(k, r, "сопротивление") { it.setDrag(first + 23, 0.5f) }
            if (t == 20) both(k, r, "волокно") { it.setCellFiber(first + 18, 1f, 0f) }
            if (t == 24) { var a = false; both(k, r, "кость") { w -> val b = w.setBone(first + 8 * 3 + 3); if (w === k) a = b else assertEquals(a, b) } }
            if (t == 26) both(k, r, "толчок") { it.setVelocity(k.bodyFirst(other) + 5, -0.4f, 0.1f); it.wake(k.bodyFirst(other) + 5) }
            if (t == 28) both(k, r, "перенос") { it.place(k.freeParticle(food[0]), 0.6f, 0.45f) }
            if (t == 32) both(k, r, "тяга") { it.pullToward(k.bodyFirst(other) + 9, 0.9f, 1.1f) }
            if (t == 34) both(k, r, "мышь") { it.grab(k.bodyFirst(other) + 12); it.dragTo(1.4f, 1.3f) }
            if (t == 44) both(k, r, "отпустить") { it.release() }
            if (t == 36) { var j0 = -1; both(k, r, "липучка") { w -> val j = w.addJoint(k.bodyFirst(other), k.freeParticle(food[1]), link, 1e-4f); if (w === k) j0 = j else assertEquals(j0, j) }; joint = j0 }
            if (t == 40) both(k, r, "мягче липучка") { it.setJointCompliance(joint, 1e-3f) }
            if (t == 46) { assertEquals(k.isJointLive(joint), r.isJointLive(joint)); both(k, r, "снять липучку") { it.removeJoint(joint) } }
            if (t == 38) { both(k, r, "гибель") { it.kill(first + 2) }; edgeDeaths += k.edgeDeathCount; cellDeaths += k.cellDeathCount }
            // Таран: второе тело целиком летит в растущее — разрывы и гибель от касаний в шаге (журналы шага).
            if (t == 43) both(k, r, "таран") { w ->
                val o = k.bodyFirst(other)
                for (c in 0 until 48) { w.setVelocity(o + c, -80f, 0.3f); w.wake(o + c) }
            }
            if (t == 42) both(k, r, "убрать еду") { it.removeFree(food[2]) }
            if (t == 48) both(k, r, "deepKill выкл") { it.deepKillOn = false }
            if (t == 52) both(k, r, "deepKill вкл") { it.deepKillOn = true }
            if (t == 50) both(k, r, "заснуть стенке") { it.sleepNow(k.freeParticle(fixed[0])) }
            if (t == 54) both(k, r, "сон вкл") { it.sleepOn = true }
            if (t == 56) both(k, r, "удалить тело") { it.removeBody(other) }
            if (t == 58) both(k, r, "уплотнение") { it.compact() }
            both(k, r, "тик $t") { it.step() }
            edgeDeaths += k.edgeDeathCount; cellDeaths += k.cellDeathCount; surfaceChanges += k.surfaceChangeCount
            both(k, r, "журнал края") { it.clearSurfaceChanges() }
        }
        println("клеток рождено $bornN из 48, гибель рёбер $edgeDeaths, клеток $cellDeaths, смен края $surfaceChanges, " +
            "уплотнений ${k.compactions}")
        assertTrue("рост не шёл", bornN > 20)
        assertTrue("гибели рёбер не было", edgeDeaths > 0)
        assertTrue("смен края не было", surfaceChanges > 0)
        assertTrue("уплотнения не было", k.compactions > 0)
        // Снимок: Kotlin и Rust пишут одни байты; прочитанные миры идут дальше вровень.
        val kb = java.io.ByteArrayOutputStream().also { k.write(java.io.DataOutputStream(it)) }.toByteArray()
        val rb = java.io.ByteArrayOutputStream().also { r.write(java.io.DataOutputStream(it)) }.toByteArray()
        assertArrayEquals("снимки разные", kb, rb)
        same(k, r, "после записи")
        val (k2, r2) = pair(workers = workers)
        k2.read(java.io.DataInputStream(kb.inputStream())); r2.read(java.io.DataInputStream(rb.inputStream()))
        same(k2, r2, "прочитанные")
        for (t in 0 until 20) both(k2, r2, "после чтения, тик $t") { it.step() }
        r.close(); r2.close()
    }

    @Test
    fun gameLikeSceneMatchesOneThread() = gameScene(null, 70)

    @Test
    fun gameLikeSceneMatchesWorkers() = gameScene(PhysicsWorkers.shared(3), 70)
}
