package io.github.some_example_name.old.systems.maps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Имя мира — имя файла: оно обязано открываться на любой платформе, куда уедет .bug. */
class MapNamesTest {

    @Test
    fun `обычные имена подходят`() {
        listOf("Мир", "Лес 2", "my.world", "Mundo (3)", "1").forEach {
            assertTrue(MapNames.isValid(it), it)
        }
    }

    @Test
    fun `имена, которые файловая система испортит или не создаст, не подходят`() {
        listOf(
            "", " Мир", "Мир ", ".скрытый", "мир.", "..", "a/b", "a\\b", "что?", "a:b", "a*b",
            "a\"b", "a<b", "a>b", "a|b", "a\tb", "CON", "con", "Nul.txt", "COM1", "lpt9",
            "x".repeat(MapNames.MAX_LENGTH + 1)
        ).forEach {
            assertFalse(MapNames.isValid(it), it)
        }
        assertTrue(MapNames.isValid("x".repeat(MapNames.MAX_LENGTH)))
        assertTrue(MapNames.isValid("CONSOLE"))
    }

    @Test
    fun `имя из внешнего файла чинится или отбрасывается`() {
        assertEquals("лес_1", MapNames.fromFileName("лес:1"))
        assertEquals("мир", MapNames.fromFileName("  мир.  "))
        assertNull(MapNames.fromFileName("..."))
        assertNull(MapNames.fromFileName("CON"))
        assertEquals(MapNames.MAX_LENGTH, MapNames.fromFileName("я".repeat(100))!!.length)
    }

    @Test
    fun `занятое имя получает номер`() {
        val taken = mutableSetOf("Мир", "Мир (2)")
        assertEquals("Лес", MapNames.unique("Лес", taken::contains))
        assertEquals("Мир (3)", MapNames.unique("Мир", taken::contains))

        // Длинное имя укорачивается под приставку, а не вылезает за предел.
        val long = "ж".repeat(MapNames.MAX_LENGTH)
        val numbered = MapNames.unique(long) { it == long }
        assertTrue(MapNames.isValid(numbered), numbered)
        assertTrue(numbered.endsWith(" (2)"), numbered)
    }
}
