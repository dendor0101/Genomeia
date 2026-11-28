package io.github.some_example_name.physics.lab.stands

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тела настоящих геномов: разбор файла тела, порядок рождения по стадиям и вся библиотека целиком. */
class GenomeBodiesTest {
    /**
     * Тело из семи клеток шестиугольником: зигота в середине (слот 0), шесть клеток вокруг рождаются
     * по две за стадию; клетки 1–3 — кость, включается на третьей стадии; клетка 4 — мышца.
     */
    private val text = """
        # genomeia stand body 1
        name	Тестовое тело
        source	test
        P 0 10.00000 5.00000 0.5000 stem 0
        P 1 10.60000 5.00000 0.5000 bone 1
        P 2 10.30000 5.51962 0.5000 bone 1
        P 3 9.70000 5.51962 0.5000 bone 1
        P 4 9.40000 5.00000 0.4000 muscle 0
        P 5 9.70000 4.48038 0.3000 suction_cup 0
        P 6 10.30000 4.48038 0.5000 leaf 0
        L 0 1
        L 0 2
        L 0 3
        L 0 4
        L 0 5
        L 0 6
        L 1 2
        L 2 3
        L 3 4
        L 4 5
        L 5 6
        L 6 1
        X 1 3
        T 0 1 2
        T 0 2 3
        T 0 3 4
        T 0 4 5
        T 0 5 6
        T 0 6 1
        G 1 1 0
        G 1 4 0
        G 2 2 1
        G 2 5 4
        G 3 3 2
        G 3 6 5
        O 3 1
        O 3 2
        O 3 3
    """.trimIndent().lines()

    private val shape = GenomeBodies.parse("test", text)

    @Test
    fun `файл тела разобран - поза, радиусы, типы, связи`() {
        assertEquals(7, shape.count)
        assertEquals("Тестовое тело", shape.name)
        assertEquals(12, shape.linkCount)
        assertEquals("связь поперёк граней в связи физики не попадает", false, shape.linked(1, 3))
        assertEquals(13, shape.genome!!.allLinkA.size)
        assertEquals(18, shape.triangles.size)
        assertArrayEquals(booleanArrayOf(false, true, true, true, false, false, false), shape.bone)
        assertArrayEquals(intArrayOf(-1, -1, -1, -1, 0, -1, -1), shape.muscle)
        assertArrayEquals(intArrayOf(5), shape.cups())
        assertEquals(0.3, shape.r[5], 1e-9)
        // Середина рамки тела — в нуле.
        assertEquals(0.0, (shape.x.min() + shape.x.max()) / 2, 1e-9)
        assertFalse("у тела генома вариаций нет", shape.varies)
        assertTrue(Variation.use(Variation.LONG) { shape.x } === Variation.use(Variation.BASE) { shape.x })
    }

    @Test
    fun `порядок рождения — стадии генома, кость включается на своей стадии`() {
        val g = shape.genome!!
        assertEquals(0, g.zygote)
        assertEquals(3, g.stages)
        val order = shape.growthFrom(g.zygote)
        assertArrayEquals(intArrayOf(0, 1, 4, 2, 5, 3, 6), order.order)
        assertArrayEquals(intArrayOf(-1, 0, 1, 2, 0, 4, 5), order.parent)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 1, 2, 3), order.depth)
        assertArrayEquals(intArrayOf(-1, 3, 3, 3, -1, -1, -1), order.boneStage!!)
        // От другой клетки тело растёт обычным обходом в ширину.
        assertEquals(null, shape.growthFrom(3).boneStage)
    }

    @Test
    fun `тело генома растёт в мире по стадиям и вырастает целиком`() {
        val w = StandWorld()
        val b = w.growing(shape, 0.0, 0.0, seed = shape.genome!!.zygote, period = 5, batch = 0)
        val st = w.world.state
        fun born() = (0 until b.count).count { !st.cellUnborn[b.particle(it)] }
        assertEquals(1, born())
        repeat(3) { w.step() }
        assertEquals("после первой стадии родились две клетки", 3, born())
        assertFalse("кость включена раньше своей стадии", w.world.isBone(b.particle(1)))
        repeat(40) { w.step() }
        assertEquals(7, born())
        assertTrue("рост не закончен", b.growth!!.done)
        assertTrue("кость не включилась после своей стадии", w.world.isBone(b.particle(1)) && w.world.isBone(b.particle(2)) && w.world.isBone(b.particle(3)))
        assertEquals(0, st.deadCount)
    }

    @Test
    fun `тело без костей и недорослое тело`() {
        val soft = shape.withoutBones()
        assertEquals(0, soft.boneCount)
        assertTrue(soft.genome!!.boneStage.all { it < 0 })
        assertEquals(7, soft.count)

        val half = shape.grownTo(0.7)
        // Семь десятых тела — это зигота и первые две стадии (пять клеток из семи): клетки 0, 1, 4, 2, 5.
        assertEquals(5, half.count)
        assertEquals("кость недорослого тела ещё не включена: она включается на третьей стадии", 0, half.boneCount)
        val def = half.definition(0.0, 0.0)
        assertEquals(5, def.count)
        assertTrue("у недорослого тела должны остаться треугольники между рождёнными клетками", def.triCount >= 2)
        val w = StandWorld()
        w.body(half, 0.0, 0.0)
        repeat(20) { w.step() }
        assertEquals(0, w.world.state.deadCount)
    }

    @Test
    fun `мышцы тела раскладываются по сторонам и вдоль его длинной оси`() {
        val entry = GenomeBodies.entries.firstOrNull { it.muscles >= 20 && it.cells in 60..400 } ?: return
        val s = GenomeBodies.shape(entry.slug)
        val sides = s.muscleSides()
        assertEquals(s.muscleCount, sides.muscleCount)
        assertEquals("${entry.slug}: по сторонам от оси должно выйти две группы", 2, sides.muscleGroups)
        val along = s.muscleAlong(4)
        assertEquals(s.muscleCount, along.muscleCount)
        assertTrue(along.muscleGroups in 2..4)
    }

    @Test
    fun `вся библиотека тел читается и годится для мира`() {
        val entries = GenomeBodies.entries
        assertTrue("библиотека тел геномов пуста: нет ресурсов stand-genomes", entries.size > 50)
        for (e in entries) {
            val s = GenomeBodies.shape(e.slug)
            val g = s.genome!!
            assertEquals("${e.slug}: клеток в списке и в теле", e.cells, s.count)
            assertEquals("${e.slug}: связей", e.links, s.linkCount)
            assertEquals("${e.slug}: костных клеток", e.bones, s.boneCount)
            for (k in 0 until s.count) assertTrue("${e.slug}: радиус клетки $k = ${s.r[k]}", s.r[k] > 0.05 && s.r[k] <= 0.5 + 1e-6)
            // Каждая клетка рождается ровно один раз и после своей родительницы.
            val order = g.growthOrder()
            val born = BooleanArray(s.count)
            for ((i, cell) in order.order.withIndex()) {
                assertFalse("${e.slug}: клетка $cell рождается дважды", born[cell])
                if (i > 0 && order.parent[cell] >= 0) assertTrue("${e.slug}: клетка $cell рождается раньше родительницы", born[order.parent[cell]])
                born[cell] = true
            }
            assertTrue("${e.slug}: рождаются не все клетки", born.all { it })
            for (k in 0 until s.count) if (s.bone[k]) assertTrue("${e.slug}: кость $k становится костью раньше, чем родилась", g.boneStage[k] >= g.stageOf[k])
        }
        // Описание тела для мира собирается у малых, средних и самого большого.
        for (e in listOf(entries.first(), entries[entries.size / 2], entries.last())) {
            val def = GenomeBodies.shape(e.slug).definition(0.0, 0.0)
            assertEquals(e.cells, def.count)
            for (t in 0 until def.triCount) assertTrue("${e.slug}: треугольник $t не развёрнут до положительной площади", def.triRestArea2[t] > 0f)
        }
    }
}
