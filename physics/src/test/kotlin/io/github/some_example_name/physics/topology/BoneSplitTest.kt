package io.github.some_example_name.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs

/**
 * КОСТЬ ДЕЛИТСЯ ВМЕСТЕ С ТЕЛОМ (BoneSplit.kt).
 *
 * Костный брусок [cols] × [rows]: клетка (столбец c, ряд r) имеет номер first + r·cols + c.
 * Убитый столбец режет брусок на две половины, между которыми не остаётся ни одной живой связи.
 */
class BoneSplitTest {
    private val v = linksPerTick(1.0)

    private class Bar(val w: PhysicsWorld, val first: Int, val cols: Int, val rows: Int) {
        val st: BodyState get() = w.state
        fun cell(c: Int, r: Int) = first + r * cols + c
        fun column(c: Int) = IntArray(rows) { cell(c, it) }
        fun columns(range: IntRange): IntArray = range.flatMap { column(it).toList() }.toIntArray()
        val all: IntArray get() = IntArray(cols * rows) { first + it }
    }

    private fun bar(cols: Int = 7, rows: Int = 3, vacuum: Boolean = true, bone: (Int, Int) -> Boolean = { _, _ -> true }): Bar {
        val w = gameWorld().also { if (vacuum) it.vacuum() }
        w.sleepOn = false
        return Bar(w, w.bodyFirst(w.addBody(patch(cols, rows, bone = bone))), cols, rows)
    }

    private fun distances(w: PhysicsWorld, ids: IntArray) = Array(ids.size) { a -> DoubleArray(ids.size) { b -> w.distance(ids[a], ids[b]) } }

    private fun shapeError(w: PhysicsWorld, ids: IntArray, rest: Array<DoubleArray>): Double {
        var worst = 0.0
        for (a in ids.indices) for (b in a + 1 until ids.size) worst = maxOf(worst, abs(w.distance(ids[a], ids[b]) - rest[a][b]))
        return worst
    }

    private fun liveBones(st: BodyState) = st.rigidBones.count { it.isNotEmpty() }

    /** Состав костей согласован: список кости — ровно клетки с этим номером кластера, живые. */
    private fun assertConsistent(st: BodyState) {
        for (b in st.rigidBones.indices) for (i in st.rigidBones[b]) {
            assertEquals("клетка $i в списке кости $b", b, st.boneClusterOf[i])
            assertFalse("погибшая клетка $i в списке кости $b", st.cellDead[i])
            assertEquals(st.rigidBones[b].size, st.boneRestQx[b].size); assertEquals(st.rigidBones[b].size, st.boneRestQy[b].size)
        }
        for (i in 0 until st.n) {
            val b = st.boneOf[i]
            if (b >= 0) { assertEquals("включённая клетка $i — в своём кластере", b, st.boneClusterOf[i]); assertTrue(i in st.rigidBones[b]) }
            if (st.cellDead[i]) { assertEquals("погибшая клетка $i вышла из кости", -1, st.boneOf[i]); assertEquals(-1, st.boneClusterOf[i]) }
        }
    }

    @Test fun `разрез поперёк кости даёт две кости, погибшие клетки из кости выходят`() {
        val b = bar()
        val st = b.st
        assertEquals(1, liveBones(st))
        for (i in b.column(3)) b.w.kill(i)
        assertEquals("две половины — две кости", 2, liveBones(st))
        assertConsistent(st)
        val left = b.columns(0..2); val right = b.columns(4..6)
        val boneLeft = st.boneOf[left[0]]; val boneRight = st.boneOf[right[0]]
        assertTrue(boneLeft >= 0 && boneRight >= 0 && boneLeft != boneRight)
        for (i in left) assertEquals(boneLeft, st.boneOf[i])
        for (i in right) assertEquals(boneRight, st.boneOf[i])
        for (i in b.column(3)) { assertFalse(b.w.isBone(i)); assertEquals("вес погибшей клетки в кости", 0.0, st.matchWeight[i], 0.0) }
        b.w.step()
        assertTrue("половины — разные организмы", st.organismOf[left[0]] != st.organismOf[right[0]])
    }

    @Test fun `половины разрезанной кости расходятся и не влияют друг на друга, каждая остаётся твёрдой`() {
        val b = bar()
        val left = b.columns(0..2); val right = b.columns(4..6)
        val restLeft = distances(b.w, left); val restRight = distances(b.w, right)
        for (i in b.column(3)) b.w.kill(i)
        val x = DoubleArray(right.size) { b.w.x[right[it]] }; val y = DoubleArray(right.size) { b.w.y[right[it]] }
        // Левую половину толкнули прочь и закрутили: правая обязана стоять, где стояла.
        for (i in left) b.w.setVelocity(i, -2 * v, 0.5 * v * (b.w.y[i] - 1.0) / T_LINK)
        b.w.wake(left[0])
        repeat(60) {
            b.w.step()
            assertEquals("левая половина твёрдая", 0.0, shapeError(b.w, left, restLeft), 1e-4 * T_LINK)
        }
        assertTrue("левая половина уехала: ${(b.w.x[left[0]] - 1.0) / T_LINK} связи", b.w.x[left[0]] < 1.0 - 20 * T_LINK)
        for (k in right.indices) {
            assertEquals("правая половина не сдвинулась (x), клетка $k", x[k], b.w.x[right[k]], 1e-9 * T_LINK)
            assertEquals("правая половина не сдвинулась (y), клетка $k", y[k], b.w.y[right[k]], 1e-9 * T_LINK)
        }
        assertEquals("правая половина твёрдая", 0.0, shapeError(b.w, right, restRight), 1e-4 * T_LINK)
    }

    @Test fun `гибель клетки с краю не делит кость и не дёргает её`() {
        val b = bar()
        val st = b.st
        val alive = b.all.filter { it != b.cell(0, 0) }.toIntArray()
        val rest = distances(b.w, alive)
        val x = DoubleArray(alive.size) { b.w.x[alive[it]] }
        b.w.kill(b.cell(0, 0))
        assertEquals(1, liveBones(st)); assertConsistent(st)
        assertEquals(alive.size, st.rigidBones[st.boneOf[alive[0]]].size)
        repeat(30) { b.w.step() }
        assertEquals("кость твёрдая", 0.0, shapeError(b.w, alive, rest), 1e-6 * T_LINK)
        for (k in alive.indices) assertEquals("кость не сдвинулась", x[k], b.w.x[alive[k]], 1e-6 * T_LINK)
        // Погибшая клетка заморожена на месте: кость её за собой больше не таскает.
        val deadX = b.w.x[b.cell(0, 0)]
        b.w.push(alive.first()..alive.first(), 0.0, 0.0)
        for (i in alive) b.w.setVelocity(i, 3 * v, 0.0)
        repeat(20) { b.w.step() }
        assertEquals("погибшая клетка стоит", deadX, b.w.x[b.cell(0, 0)], 0.0)
        assertTrue("кость уехала", b.w.x[alive[0]] > x[0] + 10 * T_LINK)
        assertEquals(0.0, shapeError(b.w, alive, rest), 1e-4 * T_LINK)
    }

    @Test fun `одиночная клетка костью быть перестаёт, а кусок из двух клеток остаётся жёстким стержнем`() {
        // Брусок в один ряд: убиваем клетки 2 и 5 — остаются куски [0,1] (две клетки), [3,4] (две) и [6] (одна).
        val b = bar(7, 1)
        val st = b.st
        b.w.kill(b.cell(2, 0)); b.w.kill(b.cell(5, 0))
        assertConsistent(st)
        assertFalse("одиночная клетка — уже не кость", b.w.isBone(b.cell(6, 0)))
        assertEquals(-1, st.boneClusterOf[b.cell(6, 0)])
        assertTrue(b.w.isBone(b.cell(0, 0))); assertTrue(b.w.isBone(b.cell(3, 0)))
        assertTrue("куски — разные кости", st.boneOf[b.cell(0, 0)] != st.boneOf[b.cell(3, 0)])
        assertEquals(st.boneOf[b.cell(0, 0)], st.boneOf[b.cell(1, 0)])
        // Стержень из двух клеток держит длину, хотя связи между ними в решателе нет.
        val rod = w(b).distance(b.cell(0, 0), b.cell(1, 0))
        b.w.setVelocity(b.cell(0, 0), -2 * v, v); b.w.wake(b.cell(0, 0))
        repeat(40) { b.w.step(); assertEquals("стержень держит длину", rod, b.w.distance(b.cell(0, 0), b.cell(1, 0)), 1e-4 * T_LINK) }
        assertTrue("стержень уехал целиком", b.w.x[b.cell(1, 0)] < 1.0 + T_LINK)
    }
    private fun w(b: Bar) = b.w

    @Test fun `кость, у которой погибли все клетки, пустеет, мир шагает и уплотняется`() {
        val b = bar(3, 2)
        val st = b.st
        val soft = b.w.bodyFirst(b.w.addBody(patch(3, 2, x0 = 1.0 + 8 * T_LINK)))
        for (i in b.all) b.w.kill(i)
        assertEquals(0, liveBones(st)); assertConsistent(st)
        repeat(10) { b.w.step() }
        b.w.removeBody(0)
        b.w.compact()
        assertEquals("пустая кость выброшена уплотнением", 0, b.st.rigidBones.size)
        repeat(10) { b.w.step() }
        assertTrue(soft >= 0)
    }

    @Test fun `гибель внутренней связи без гибели клеток тоже делит кость - на следующем шаге`() {
        val b = bar()
        val st = b.st
        // Клиент рвёт все рёбра между столбцами 3 и 4 — разрез без единой погибшей клетки.
        val leftSide = b.columns(0..3).toHashSet()
        var killed = 0
        for (e in 0 until st.baked.edgeCount) {
            val u = st.baked.edgeU(e); val q = st.baked.edgeV(e)
            if ((u in leftSide) != (q in leftSide)) { st.killEdgeNow(e); killed++ }
        }
        assertTrue(killed > 0)
        assertEquals("до шага кость ещё одна", 1, liveBones(st))
        b.w.step()
        assertEquals("после шага — две", 2, liveBones(st)); assertConsistent(st)
        assertTrue(st.boneOf[b.cell(0, 0)] != st.boneOf[b.cell(6, 0)])
        assertTrue(st.organismOf[b.cell(0, 0)] != st.organismOf[b.cell(6, 0)])
    }

    @Test fun `кусок кости держит ту форму, в которой кость жила, а не форму описания`() {
        val b = bar()
        val st = b.st
        // Позу кости сняли с согнутого положения (как при включении кости мутацией): правую часть повернули.
        val pivotX = b.w.x[b.cell(3, 1)]; val pivotY = b.w.y[b.cell(3, 1)]
        for (i in b.columns(4..6)) {
            val rx = st.px[i] - pivotX; val ry = st.py[i] - pivotY
            st.px[i] = pivotX + rx * 0.8 - ry * 0.6; st.py[i] = pivotY + rx * 0.6 + ry * 0.8
            st.prevX[i] = st.px[i]; st.prevY[i] = st.py[i]
        }
        val ids = st.rigidBones[0]
        val cx = ids.sumOf { st.px[it] } / ids.size; val cy = ids.sumOf { st.py[it] } / ids.size
        for (k in ids.indices) { st.boneRestQx[0][k] = st.px[ids[k]] - cx; st.boneRestQy[0][k] = st.py[ids[k]] - cy }
        val right = b.columns(4..6); val left = b.columns(0..2)
        val restRight = distances(b.w, right); val restLeft = distances(b.w, left)
        for (i in b.column(3)) b.w.kill(i)
        repeat(30) { b.w.step() }
        assertEquals("правая часть сохранила свою форму", 0.0, shapeError(b.w, right, restRight), 1e-6 * T_LINK)
        assertEquals("левая часть сохранила свою форму", 0.0, shapeError(b.w, left, restLeft), 1e-6 * T_LINK)
    }

    @Test fun `сон куска не выключает кость соседнего куска`() {
        val w = gameWorld()
        val first = w.bodyFirst(w.addBody(patch(7, 3, bone = { _, _ -> true })))
        fun cell(c: Int, r: Int) = first + r * 7 + c
        val right = (4..6).flatMap { c -> (0..2).map { cell(c, it) } }.toIntArray()
        val rest = distances(w, right)
        // Первая клетка списка кости — в левой половине; разрезаем и даём левой половине уснуть.
        for (r in 0..2) w.kill(cell(3, r))
        repeat(SLEEP_TICKS + 30) { w.step() }
        assertTrue("левая половина уснула", w.isAsleep(cell(0, 0)))
        // Правую половину будим ударом по одной клетке: кость обязана остаться твёрдой.
        w.setVelocity(right[0], 3 * v, 2 * v); w.wake(right[0])
        repeat(20) { w.step(); assertEquals("кость бодрствующего куска твёрдая", 0.0, shapeError(w, right, rest), 1e-3 * T_LINK) }
        assertTrue("левая половина всё ещё спит", w.isAsleep(cell(0, 0)))
    }

    @Test fun `разделение кости не создаёт движения из ничего`() {
        val b = bar()
        for (i in b.column(3)) b.w.kill(i)
        val live = b.columns(0..2) + b.columns(4..6)
        val x = DoubleArray(live.size) { b.w.x[live[it]] }; val y = DoubleArray(live.size) { b.w.y[live[it]] }
        repeat(60) { b.w.step() }
        for (k in live.indices) {
            assertEquals("клетка $k не сдвинулась (x)", x[k], b.w.x[live[k]], 1e-6 * T_LINK)
            assertEquals("клетка $k не сдвинулась (y)", y[k], b.w.y[live[k]], 1e-6 * T_LINK)
        }
        assertEquals(0.0, b.w.kinetic(), 1e-12 * v * v)
    }

    @Test fun `не включённая клетка кластера после разреза входит в кость своего куска`() {
        // Столбец 5 пока мягкая ткань (кость клиент включит позже), остальное — кость.
        val w = gameWorld().vacuum()
        w.sleepOn = false
        val def = patch(7, 3, bone = { _, _ -> true })
        val on = BooleanArray(def.count) { it % 7 != 5 }
        val first = w.bodyFirst(w.addBody(def, null, on))
        fun cell(c: Int, r: Int) = first + r * 7 + c
        val st = w.state
        assertFalse(w.isBone(cell(5, 1)))
        for (r in 0..2) w.kill(cell(3, r))
        assertConsistent(st)
        val pieceRight = st.boneOf[cell(4, 0)]
        assertEquals("не включённая клетка числится за костью своего куска", pieceRight, st.boneClusterOf[cell(5, 1)])
        assertTrue("клиент включает её — она входит в эту кость", w.setBone(cell(5, 1)))
        assertEquals(pieceRight, st.boneOf[cell(5, 1)])
        assertTrue(st.boneOf[cell(0, 0)] != pieceRight)
        repeat(10) { w.step() }
        assertConsistent(st)
    }

    @Test fun `снимок мира с разделённой костью читается и продолжается бит в бит`() {
        val b = bar(vacuum = false)
        for (i in b.column(3)) b.w.kill(i)
        for (i in b.columns(0..2)) b.w.setVelocity(i, -v, 0.3 * v)
        b.w.wake(b.cell(0, 0))
        repeat(5) { b.w.step() }
        val bytes = ByteArrayOutputStream()
        b.w.write(DataOutputStream(bytes))
        val copy = gameWorld()
        copy.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        val a = b.st; val c = copy.state
        assertEquals(a.rigidBones.size, c.rigidBones.size)
        for (k in a.rigidBones.indices) assertTrue("состав кости $k", a.rigidBones[k].contentEquals(c.rigidBones[k]))
        assertTrue(a.boneOf.copyOf(a.n).contentEquals(c.boneOf.copyOf(c.n)))
        assertTrue(a.boneClusterOf.copyOf(a.n).contentEquals(c.boneClusterOf.copyOf(c.n)))
        repeat(40) { b.w.step(); copy.step() }
        for (i in 0 until a.n) {
            assertEquals("x частицы $i", java.lang.Double.doubleToRawLongBits(a.px[i]), java.lang.Double.doubleToRawLongBits(c.px[i]))
            assertEquals("y частицы $i", java.lang.Double.doubleToRawLongBits(a.py[i]), java.lang.Double.doubleToRawLongBits(c.py[i]))
        }
    }

    @Test fun `сброс мира возвращает кости к описанию`() {
        val b = bar()
        for (i in b.column(3)) b.w.kill(i)
        assertEquals(2, liveBones(b.st))
        b.w.reset()
        assertEquals(1, b.st.rigidBones.size)
        assertEquals(21, b.st.rigidBones[0].size)
        for (i in b.all) { assertTrue(b.w.isBone(i)); assertFalse(b.st.cellDead[i]) }
        val rest = distances(b.w, b.all)
        repeat(10) { b.w.step() }
        assertEquals(0.0, shapeError(b.w, b.all, rest), 1e-6 * T_LINK)
    }
}
