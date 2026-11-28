package io.github.some_example_name.physics

import io.github.some_example_name.physics.rust.RustPhysics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * ТЕНЬ НА RUST сама — инструмент проверки (RustShadow, RUST_PORT.md), и она обязана ловить то,
 * ради чего сделана: идти за миром через его методы и сверять каждый тик; правку стенда в плоских
 * данных переносить и считать; правку структуры в обход методов — останавливать; в строгом режиме
 * (без переноса) — останавливать любую правку. Тест включает тень своим мирам сам.
 */
class RustShadowTest {
    private var wasRequested = false
    private var wasAdopt = true

    @Before
    fun needLibrary() {
        assumeTrue("нет библиотеки физики Rust: ${RustPhysics.loadError}", RustPhysics.available)
        wasRequested = RustShadow.requested; wasAdopt = RustShadow.adoptOn
        RustShadow.requested = true
    }

    @After
    fun restore() {
        RustShadow.requested = wasRequested; RustShadow.adoptOn = wasAdopt
    }

    private fun world() = PhysicsWorld(PhysicsConfig(), workers = null, scale = RustScenes.SCALE).apply {
        state.quiet = true
        state.contactsOn = true
    }

    private fun body(x0: Float = 1f) =
        RustScenes.lattice(cols = 12, rows = 8, x0 = x0, muscles = listOf(IntArray(8) { 12 * 3 + it }))

    @Test
    fun followsTheWorldThroughItsMethods() {
        val w = world()
        // Два тела одним описанием: дописывание тел в Rust — следующая веха (RUST_PORT.md).
        w.addBody(body().concat(body(1f + 13 * RustScenes.LINK)))
        // Первое тело — навстречу второму (методом мира: тень видит).
        for (i in 0 until 96) w.setVelocity(i, 0.6f, 0f)
        val before = RustShadow.checkedTicks
        var maxContacts = 0
        for (t in 0 until 40) {
            w.setMuscleTarget(0, if (t % 10 < 5) 1f else 0f)
            w.step()
            maxContacts = maxOf(maxContacts, w.state.contacts!!.contactCount)
        }
        assertEquals("сверка после каждого тика", 40L, RustShadow.checkedTicks - before)
        // Сцена настоящая: тела столкнулись.
        assertTrue("касаний не было", maxContacts > 0)
    }

    @Test
    fun flatEditBehindTheMethodsIsAdopted() {
        val w = world()
        w.addBody(body())
        w.step()
        val adopted = RustShadow.adoptions
        val checked = RustShadow.checkedTicks
        w.state.vx[3] += 0.25f          // стенд пишет прямо в массив
        w.step(); w.step()
        assertEquals("перенос ровно один", 1L, RustShadow.adoptions - adopted)
        assertEquals("тики после переноса сверены", 2L, RustShadow.checkedTicks - checked)
    }

    @Test
    fun structuralEditBehindTheMethodsStops() {
        val w = world()
        w.addBody(body())
        w.step()
        w.state.conRest[0] *= 1.5f      // длина покоя связи — структура, переносить нельзя
        val e = assertThrows(IllegalStateException::class.java) { w.step() }
        assertTrue(e.message, e.message!!.contains("в обход методов") && e.message!!.contains("constraints"))
    }

    @Test
    fun strictModeStopsAnyEdit() {
        RustShadow.adoptOn = false
        val w = world()
        w.addBody(body())
        w.step()
        w.state.vx[3] += 0.25f
        val e = assertThrows(IllegalStateException::class.java) { w.step() }
        assertTrue(e.message, e.message!!.contains("тень Rust разошлась"))
    }

    /** Тень идёт за миром через дописывание тел, газ, удаление с уплотнением и снимок (запись сверяет байты). */
    @Test
    fun followsWorldOperationsAndSnapshot() {
        val w = world()
        w.addBody(body())
        val second = w.addBody(body(1f + 13 * RustScenes.LINK))
        for (k in 0 until 50) w.addFree(1.2f + k * 0.01f, 0.95f, 0.3f * RustScenes.LINK, 0.1f, 0f)
        val before = RustShadow.checkedTicks
        for (t in 0 until 30) {
            if (t == 10) w.removeBody(second)
            if (t == 20) java.io.ByteArrayOutputStream().also { w.write(java.io.DataOutputStream(it)) }
            w.step()
        }
        assertEquals("сверка после каждого тика и после записи снимка", 31L, RustShadow.checkedTicks - before)
    }

    /**
     * Эталонный решатель в Rust не переносится: мир, перешедший на него, снимает тень — это считается и печатается в
     * итоге прогона, а дальнейшие тики этого мира не сверяются (и не падают на ложном расхождении).
     */
    @Test
    fun referenceStepDetachesShadow() {
        val w = world()
        w.sleepOn = false      // эталон не знает сна (PhysicsWorld.stepReference)
        w.addBody(body())
        w.step()
        val detached = RustShadow.detachments
        val checked = RustShadow.checkedTicks
        w.stepReference()
        w.step()
        assertEquals("снятие тени сосчитано", 1, RustShadow.detachments - detached)
        assertEquals("тики после снятия не сверяются", 0L, RustShadow.checkedTicks - checked)
    }
}
