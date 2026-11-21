package io.github.some_example_name.physics.lab.stands

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вариации длин связей и радиусов: тело в любой вариации обязано быть таким, какое можно
 * собрать в редакторе генома, иначе стенд проверял бы физику на телах, которых в игре нет.
 */
class VariationTest {
    private val shapes: List<ShapeSpec> = listOf(
        Shapes.square(6), Shapes.square(12), Shapes.disc(6), Shapes.rect(16, 4), Shapes.ring(6, 3), Shapes.ribbon(14), Shapes.rope(12),
        Shapes.triangle(9), Shapes.ellipse(8, 4),
    ) + complexShapes() + boneShapes() + listOf(armShape(30, 7), fishShape(), tadpoleShape(), clawShape(), Shapes.rect(24, 4).muscleSegments(6))

    @Test
    fun `в любой вариации связи и радиусы — в пределах редактора генома`() {
        // Редактор: связанные клетки — не ближе 0.6 суммы радиусов (CellPlacement.KEEP) и ближе суммы (иначе связи бы не было);
        // несвязанные не перекрываются. Радиус клетки — от 0.2 до 0.5.
        for (v in Variation.values()) Variation.use(v) {
            for (shape in shapes) {
                val x = shape.x; val y = shape.y; val r = shape.r
                for (k in 0 until shape.count) assertTrue("${shape.id}@${v.id}: радиус клетки $k = ${r[k]}", r[k] >= 0.2 - 1e-9 && r[k] <= 0.5 + 1e-9)
                for (i in 0 until shape.count) for (j in i + 1 until shape.count) {
                    val d = Math.hypot(x[i] - x[j], y[i] - y[j])
                    val sum = r[i] + r[j]
                    if (shape.linked(i, j)) {
                        assertTrue("${shape.id}@${v.id}: связь $i-$j длиной $d короче 0.6 суммы радиусов $sum", d >= 0.6 * sum - 1e-9)
                        assertTrue("${shape.id}@${v.id}: связь $i-$j длиной $d не короче суммы радиусов $sum — в редакторе её бы не было", d < sum)
                    } else assertTrue("${shape.id}@${v.id}: клетки $i и $j перекрыты ($d при сумме радиусов $sum) и не связаны", d >= sum - 1e-9)
                }
            }
        }
    }

    @Test
    fun `вариации и правда разные`() {
        val s = Shapes.square(12)
        fun links(v: Variation): DoubleArray = Variation.use(v) { DoubleArray(s.linkCount) { Math.hypot(s.x[s.linkA[it]] - s.x[s.linkB[it]], s.y[s.linkA[it]] - s.y[s.linkB[it]]) } }
        fun radii(v: Variation): DoubleArray = Variation.use(v) { s.r.copyOf() }
        assertEquals(0.6, links(Variation.BASE).min(), 1e-9); assertEquals(0.6, links(Variation.BASE).max(), 1e-9)
        assertEquals(0.9, links(Variation.LONG).min(), 1e-9); assertEquals(0.9, links(Variation.LONG).max(), 1e-9)
        assertEquals(0.3, links(Variation.SMALL).max(), 1e-9); assertEquals(0.25, radii(Variation.SMALL).max(), 1e-12)
        val mixed = links(Variation.MIXLEN)
        assertTrue("случайные длины не дошли до коротких связей: ${mixed.min()}", mixed.min() < 0.68)
        assertTrue("случайные длины не дошли до длинных связей: ${mixed.max()}", mixed.max() > 0.88)
        val rad = radii(Variation.RADII)
        assertTrue("нет мелких клеток: ${rad.min()}", rad.min() < 0.3)
        assertTrue("нет крупных клеток: ${rad.max()}", rad.max() > 0.48)
        assertTrue("у смешанной вариации радиусы одинаковые", radii(Variation.MIXED).let { it.max() - it.min() > 0.05 })
    }

    @Test
    fun `ровная решётка — те же самые числа, что до появления вариаций`() {
        val s = Shapes.disc(6).boneCore()
        assertSame("поза ровной решётки — сами узлы решётки, без пересчёта", s.lx, Variation.use(Variation.BASE) { s.x })
        assertTrue(Variation.use(Variation.BASE) { s.r.all { it == GAME_RADIUS } })
    }

    @Test
    fun `вариация одна и та же при каждом обращении и у всех раскладок костей и мышц одной фигуры`() {
        val plain = Shapes.rect(18, 6)
        val spine = Shapes.rect(18, 6).boneSpine()
        val muscles = Shapes.rect(18, 6).muscleTopBottom()
        for (v in Variation.values()) Variation.use(v) {
            assertArrayEquals("${v.id}: поза зависит от раскладки костей", plain.x, spine.x, 0.0)
            assertArrayEquals("${v.id}: поза зависит от раскладки мышц", plain.y, muscles.y, 0.0)
            assertArrayEquals("${v.id}: радиусы зависят от раскладки", plain.r, spine.r, 0.0)
            assertSame("${v.id}: поза пересчитывается при каждом обращении", plain.x, plain.x)
        }
        // Раскладка костей и мышц считается по ровной решётке, какая бы вариация ни действовала.
        val inside = Variation.use(Variation.LONG) { Shapes.rect(18, 6).boneSpine() }
        assertArrayEquals(spine.bone, inside.bone)
    }

    @Test
    fun `радиус касания фигуры совпадает с тем, что считает решатель`() {
        // Решатель: половина самой длинной связи клетки по контуру с запасом 5% на сшивание мембраны; у клетки внутри ткани — ноль.
        val seal = 1.05
        // Только мягкие фигуры: костную клетку решатель считает по-своему (внутри кости у неё нет связей в его счёте, и
        // радиус касания у неё — радиус клетки на 0.6, даже в глубине тела). На расстановку это не влияет: ближайшими к
        // чужому телу оказываются клетки контура, а у них правило то же.
        for (v in Variation.values()) for (shape in listOf(Shapes.square(8), Shapes.ring(6, 3), Shapes.rope(8), Shapes.star(5, 8, 3), Shapes.ribbon(10), Shapes.blob(2, 6))) {
            Variation.use(v) {
                val w = StandWorld()
                val b = w.body(shape, 0.0, 0.0)
                w.step()
                val contacts = w.world.state.contacts!!
                for (k in 0 until shape.count) {
                    val solver = contacts.contactRadiusOf(b.particle(k)) / PHYSICS_PER_GAME
                    assertEquals("${shape.id}@${v.id}: радиус касания клетки $k", shape.contact[k] * seal, solver, 1e-4)
                }
            }
        }
    }

    @Test
    fun `расстановка даёт заданный зазор в любой вариации`() {
        for (v in Variation.values()) Variation.use(v) {
            for ((a, b) in listOf(Shapes.square(8) to Shapes.disc(4), Shapes.crescent(7) to Shapes.uShape(12, 9, 3), Shapes.ring(6, 3) to Shapes.rope(12))) {
                // До первого касания левому телу лететь по прямой ровно три связи (зазор между кругами при этом может быть
                // меньше: ближайшие клетки двух тел не обязаны стоять на одной горизонтали).
                val apart = separation(a, b, CONTACT_DISTANCE) + START_GAP
                assertEquals("${a.id} и ${b.id}@${v.id}: путь до касания", START_GAP, travelToContact(a, -apart / 2, 0.0, 0.0, b, apart / 2, 0.0, 0.0, 1.0, 0.0), 1e-6)
                val w = StandWorld()
                val (first, second) = w.duel(a, b, 0.0)
                assertTrue("${a.id} и ${b.id}@${v.id}: тела на старте касаются", w.clearance(first, second) > 0.5)
                val near = separation(a, b, CONTACT_DISTANCE)
                val wa = StandWorld()
                val pair = wa.body(a, 0.0, 0.0) to wa.body(b, near, 0.0)
                assertEquals("${a.id} и ${b.id}@${v.id}: вплотную — зазор ноль", 0.0, wa.clearance(pair.first, pair.second), 1e-6)
            }
        }
    }

    @Test
    fun `свободные частицы получают радиус вариации`() {
        for (v in Variation.values()) Variation.use(v) {
            val w = StandWorld()
            val ids = (0 until 40).map { w.ball(it * 2.0, 0.0) }
            val radii = ids.map { w.world.state.radius[w.world.freeParticle(it)] / PHYSICS_PER_GAME }
            for (r in radii) assertTrue("${v.id}: радиус частицы $r", r >= 0.2 - 1e-9 && r <= 0.5 + 1e-9)
            if (v.changesRadius && v != Variation.SMALL) assertTrue("${v.id}: частицы одного радиуса", radii.max() - radii.min() > 0.1)
            if (!v.changesRadius) assertTrue("${v.id}: радиус частицы обязан остаться 0.5", radii.all { Math.abs(it - 0.5) < 1e-9 })
        }
    }
}
