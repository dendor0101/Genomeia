package io.github.some_example_name.old.features.levels.editor

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Встроенные уровни кампании: у каждого своё место в курсе и название на всех языках игры.
 * Без Gdx: файлы читаются прямо из assets.
 */
class BuiltinLevelsTest {

    private val assets = File("../assets")

    private val levels: Map<String, LevelInfo> by lazy {
        File(assets, "levels-builtin").listFiles { f -> f.extension == "json" }.orEmpty()
            .associate { it.nameWithoutExtension to LevelFiles.decode(it.readText()) }
    }

    @Test
    fun `у встроенных уровней места в курсе 1 до N без пропусков и повторов`() {
        assertTrue(levels.size >= 20, "встроенных уровней ${levels.size}, ожидалось больше 20")
        val orders = levels.values.map { it.order }.sorted()
        assertEquals((1..levels.size).toList(), orders, "места в курсе: $orders")
    }

    @Test
    fun `курс начинается с Leaf, как реестр клеток`() {
        val first = levels.entries.minBy { it.value.order }.key
        assertEquals("Leaf", first)
    }

    @Test
    fun `у каждого встроенного уровня есть название во всех бандлах`() {
        val bundles = File(assets, "ui/i18n").listFiles { f -> f.name.startsWith("MyBundle") && f.extension == "properties" }.orEmpty()
        assertEquals(4, bundles.size, "бандлов: ${bundles.map { it.name }}")
        for (bundle in bundles) {
            val props = Properties().apply { bundle.reader(Charsets.UTF_8).use(::load) }
            for (name in levels.keys) {
                val key = LevelFiles.titleKey(LevelFiles.BUILTIN_PREFIX + name)
                assertTrue(!props.getProperty(key).isNullOrBlank(), "${bundle.name}: нет $key (уровень $name)")
            }
        }
    }

    @Test
    fun `id встроенных уровней не повторяются`() {
        val ids = levels.mapValues { it.value.id }
        val duplicates = ids.entries.groupBy { it.value }.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "повторы id (отметка прохождения у них общая): $duplicates")
    }
}
