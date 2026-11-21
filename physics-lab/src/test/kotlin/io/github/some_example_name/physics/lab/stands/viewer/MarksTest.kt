package io.github.some_example_name.physics.lab.stands.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Пометки сцен: человек ставит их клавишей, а читают их из файла — файл обязан пережить перезапуск окна. */
class MarksTest {
    private fun temp(): File = File.createTempFile("stands-marks", ".tsv").also { it.delete(); it.deleteOnExit() }

    @Test
    fun `пометка перебирается по кругу и сохраняется в файл`() {
        val file = temp()
        val marks = Marks(file)
        marks.cycle("prim/square6/hit@len", 120, "разрывы в пределах допуска: с тика 40 — порвана связь", "квадрат 6: сильный удар")
        assertEquals(MarkKind.DISAGREE, marks["prim/square6/hit@len"]!!.kind)
        marks.cycle("prim/square6/hit@len", 130, "", "квадрат 6: сильный удар")
        assertEquals(MarkKind.FIX, marks["prim/square6/hit@len"]!!.kind)

        val again = Marks(file).also { it.load() }
        val m = again["prim/square6/hit@len"]
        assertNotNull("пометка не пережила перезапуск", m)
        assertEquals(MarkKind.FIX, m!!.kind)
        assertEquals(130, m.tick)
        assertEquals("квадрат 6: сильный удар", m.title)
        // Последний вид по кругу снимает пометку без заметки.
        repeat(MarkKind.values().size - 1) { again.cycle("prim/square6/hit@len", 1, "", "") }
        assertNull(again["prim/square6/hit@len"])
        assertEquals(0, Marks(file).also { it.load() }.size)
    }

    @Test
    fun `заметка словами хранится вместе с пометкой и по кругу не теряется`() {
        val file = temp()
        val marks = Marks(file)
        marks.note("gen/megalodon/hit", "кости тут и должны гнуться,\tпорог слишком строгий", 300, "кости держат форму: с тика 50", "«Megalodon»: сильный удар")
        assertEquals(MarkKind.LOOK, marks["gen/megalodon/hit"]!!.kind)
        // Табуляция в заметке не должна ломать строку файла.
        val loaded = Marks(file).also { it.load() }
        assertEquals("кости тут и должны гнуться, порог слишком строгий", loaded["gen/megalodon/hit"]!!.note)
        repeat(MarkKind.values().size + 2) { loaded.cycle("gen/megalodon/hit", 1, "", "") }
        assertNotNull("пометка с заметкой снялась перебором видов", loaded["gen/megalodon/hit"])
        loaded.remove("gen/megalodon/hit")
        assertNull(loaded["gen/megalodon/hit"])
        assertTrue(file.readLines().size == 1)
    }

    @Test
    fun `файл пометок читается человеком и машиной - заголовок и по строке на пометку`() {
        val file = temp()
        val marks = Marks(file)
        marks.cycle("a/b", 5, "нарушений нет", "первая")
        marks.note("c/d@long", "удалить: повторяет a/b", 9, "нарушений нет", "вторая")
        val lines = file.readLines()
        assertEquals("id\tmark\tmeaning\tnote\ttick\ttime\tverdict\ttitle", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("a/b\tdisagree\t"))
        assertTrue(lines[2].startsWith("c/d@long\tlook\t"))
    }
}
