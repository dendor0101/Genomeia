package io.github.some_example_name.physics.lab.stands.viewer

import io.github.some_example_name.physics.lab.stands.Limits
import io.github.some_example_name.physics.lab.stands.Rule
import io.github.some_example_name.physics.lab.stands.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Итоги сцен в списке окна. 17.11.2026 сцена сжатия мышечного червя два дня стояла в списке красной,
 * хотя живой прогон проходил все правила: итог брался из файла пакетного прогона, сделанного до
 * починки, а окно своим «прошла» чужое «не прошла» не заменяло.
 */
class VerdictsTest {
    private fun scene(id: String) = Scenario(id, "тест", id, "", "", 10, Limits.INTACT) { }

    private fun file(vararg rows: String, age: Long = 0L): File {
        val f = File.createTempFile("stands-verdicts", ".tsv").also { it.deleteOnExit() }
        f.writeText("id\tpassed\tfailed\terror\tfirstTick\twhy\n" + rows.joinToString("") { it + "\n" })
        if (age > 0L) f.setLastModified(System.currentTimeMillis() - age)
        return f
    }

    private val tears = "${Rule.TEARS.title}: с тика 29 — порвана связь"
    private val deaths = "${Rule.DEATHS.title}: с тика 29 — погибла клетка"
    private val repeat = "${Rule.REPEAT.title}: с тика 41 — разошлось на тике 41"

    @Test
    fun `живой прогон без нарушений снимает красную пометку из файла`() {
        val v = Verdicts(listOf(scene("t/worm")))
        assertEquals(1, v.load(file("t/worm\t0\tTEARS,DEATHS\t\t29\t$tears | $deaths")))
        assertFalse(v["t/worm"]!!.passed)
        v.record("t/worm", passed = true, firstTick = -1, why = "")
        assertTrue("итог из файла о правилах, которые окно судит само, должен уступить живому прогону", v["t/worm"]!!.passed)
        assertEquals("", v["t/worm"]!!.why)
    }

    @Test
    fun `расхождение повтора из файла живой прогон не снимает`() {
        val v = Verdicts(listOf(scene("t/worm")))
        v.load(file("t/worm\t0\tTEARS,REPEAT\t\t29\t$tears | $repeat"))
        v.record("t/worm", passed = true, firstTick = -1, why = "")
        val after = v["t/worm"]!!
        assertFalse("повтор судит только пакетный прогон: окно его проверить не может", after.passed)
        assertEquals("от старого итога должно остаться только расхождение повтора", repeat, after.why)
        assertEquals(29, after.firstTick)
        // И второй живой прогон его не снимает.
        v.record("t/worm", passed = true, firstTick = -1, why = "")
        assertFalse(v["t/worm"]!!.passed)
    }

    @Test
    fun `живое нарушение заменяет текст из файла своим`() {
        val v = Verdicts(listOf(scene("t/worm")))
        v.load(file("t/worm\t0\tTEARS\t\t29\t$tears"))
        v.record("t/worm", passed = false, firstTick = 7, why = deaths)
        val after = v["t/worm"]!!
        assertFalse(after.passed)
        assertEquals(deaths, after.why)
        assertEquals(7, after.firstTick)
    }

    @Test
    fun `зелёная сцена из файла краснеет от живого нарушения`() {
        val v = Verdicts(listOf(scene("t/worm")))
        v.load(file("t/worm\t1\t\t\t-1\t"))
        assertTrue(v["t/worm"]!!.passed)
        v.record("t/worm", passed = false, firstTick = 3, why = tears)
        assertFalse(v["t/worm"]!!.passed)
    }

    @Test
    fun `файл старше собранного кода помечается устаревшим`() {
        val fresh = Verdicts(listOf(scene("t/worm")))
        fresh.load(file("t/worm\t1\t\t\t-1\t"))
        assertFalse("файл, записанный только что, свежее любого собранного кода", fresh.stale)

        val old = Verdicts(listOf(scene("t/worm")))
        old.load(file("t/worm\t1\t\t\t-1\t", age = 20L * 365 * 24 * 3600 * 1000))
        assertTrue("файл двадцатилетней давности старше кода", old.stale)
        assertTrue("шапка списка должна сказать, что итоги старее кода: ${old.source}", old.source.contains("старее кода"))
    }
}
