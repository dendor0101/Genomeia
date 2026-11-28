package io.github.some_example_name.old.systems.rules

import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.cells.base.CellTypes
import io.github.some_example_name.old.core.GlobalSimulationSettings
import io.github.some_example_name.old.physics.bridge.WorldPhysics
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Настройки мира (WorldRule, CellRule): ключи — формат журнала и сохранения, стандарт обязан
 * лежать внутри ползунка, у каждого пункта — подпись и подсказка на всех языках и только
 * буквами, которые есть в шрифте игры. Сами WorldRules держат сущности мира и в тестах :core
 * не строятся (контейнеры тянут OpenGL); их проверяет bugReplayCheck (шаги 7 и 18).
 */
class WorldRuleTest {

    @Test
    fun `ключи правил не повторяются`() {
        assertEquals(WorldRule.entries.size, WorldRule.entries.map { it.key }.toSet().size)
        assertEquals(CellRule.entries.size, CellRule.entries.map { it.key }.toSet().size)
        for (rule in WorldRule.entries) assertEquals(rule, WorldRule.byKey(rule.key))
        for (rule in CellRule.entries) assertEquals(rule, CellRule.byKey(rule.key))
    }

    @Test
    fun `стандарт нового мира — внутри ползунка`() {
        // Карта клеток по умолчанию — из DIGameGlobalContainer (тянет Gdx): клетки берём из реестра ниже.
        val base = GlobalSimulationSettings(cellsSettings = emptyMap())
        val standard = mapOf(
            WorldRule.GRAVITY to 0f,
            WorldRule.PRODUCER_PAUSE to base.producerRestoreTimeTickCoefficient
        )
        for (rule in WorldRule.entries) {
            // Доля стандарта: сам стандарт — 100%. Значение: стандарт как есть.
            val slider = if (rule.format == RuleFormat.PERCENT) 100f else standard.getValue(rule)
            assertTrue(slider in rule.min..rule.max, "${rule.key}: стандарт $slider вне ${rule.min}..${rule.max}")
        }
        for (cell in CellTypes.createAll()) {
            val s = cell.defaultCellSettings
            assertTrue(s.maxEnergy in CellRule.MAX_ENERGY.min..CellRule.MAX_ENERGY.max, "${cell.key}: запас ${s.maxEnergy}")
            val softness = WorldPhysics.standardSoftness(cell.key)
            assertTrue(softness in CellRule.SOFTNESS.min..CellRule.SOFTNESS.max, "${cell.key}: мягкость $softness")
        }
        assertTrue(100f in CellRule.ACTION_COST.min..CellRule.ACTION_COST.max)
    }

    @Test
    fun `настройка клетки стоит у живого типа на вкладке «Клетки»`() {
        // Иначе её не показала бы ни одна вкладка: «Клетки» показывают только типы из палитры.
        val live = CellTypes.createAll().map { it.key }.filter { it !in CellPalette.RETIRED_CELLS }.toSet()
        val bound = WorldRule.entries.filter { it.cellKey != null }
        assertTrue(bound.isNotEmpty())
        for (rule in WorldRule.entries) {
            val key = rule.cellKey
            if (key == null) {
                assertTrue(rule.section != RuleSection.CELLS, "${rule.key}: на «Клетках» без типа клетки")
            } else {
                assertEquals(RuleSection.CELLS, rule.section, "${rule.key}: у типа $key, а вкладка ${rule.section}")
                assertTrue(key in live, "${rule.key}: типа $key нет среди живых клеток")
            }
        }
    }

    @Test
    fun `проценты ползунка переводятся в значение и обратно`() {
        val standard = 0.06f
        assertEquals(0.12f, RuleFormat.PERCENT.fromSlider(200f, standard), 1e-7f)
        assertEquals(200f, RuleFormat.PERCENT.toSlider(0.12f, standard), 1e-3f)
        // Стандарт 0 — доля всегда 0, деления на ноль нет.
        assertEquals(0f, RuleFormat.PERCENT.toSlider(0.5f, 0f))
        assertEquals(0f, RuleFormat.PERCENT.fromSlider(300f, 0f))
        // Значение и множитель — ползунок и есть значение.
        assertEquals(6f, RuleFormat.NUMBER.fromSlider(6f, 0f))
        assertEquals(2.5f, RuleFormat.TIMES.toSlider(2.5f, 5f))
        // Множитель 1 переводится в 1.0 точно: при стандарте физика считает теми же битами.
        assertEquals(1.0, RuleFormat.PERCENT.fromSlider(100f, 1f).toDouble())
    }

    @Test
    fun `подписи и подсказки есть на всех языках и печатаются шрифтом игры`() {
        val keys = listOf(
            "button.worldSettings", "worldRules.title", "worldRules.subtitle", "worldRules.close",
            "worldRules.resetAll", "worldRules.resetAll.hint", "worldRules.resetOne", "worldRules.standard",
            "worldRules.footer"
        ) + RuleSection.entries.flatMap { listOf("worldRules.tab.${it.key}", "worldRules.tab.${it.key}.hint") } +
            (WorldRule.entries.map { it.key } + CellRule.entries.map { it.key })
                .flatMap { listOf("worldRules.$it", "worldRules.$it.hint") }

        // Тесты :core запускаются из каталога core.
        val files = File("../assets/ui/i18n").listFiles { f -> f.name.startsWith("MyBundle") && f.extension == "properties" }.orEmpty()
        assertEquals(4, files.size, "ожидались четыре языка")
        for (file in files) {
            val bundle = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
            for (key in keys) {
                val text = bundle.getProperty(key)
                assertTrue(!text.isNullOrBlank(), "${file.name}: нет $key")
                // Шрифт игры — латиница-1 и кириллица (MyGame): длинного тире, стрелок и т. п. в нём нет.
                val missing = text.filter { c -> c.code > 0xFF && c.code !in 0x400..0x45F }
                assertTrue(missing.isEmpty(), "${file.name}: $key — знаков нет в шрифте: $missing")
            }
        }
    }
}
