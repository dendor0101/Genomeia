package io.github.some_example_name.old.features.worldeditor

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Загрузка уровня в редактор и рецепт карты: из рецепта должна получиться та же карта. */
class WorldEditorFromSpecTest {

    @AfterTest
    fun resetGenerator() {
        // Companion генератора — общий на процесс: не оставляем его в состоянии теста.
        WorldGenerator.GENERATOR_DAY_NIGHT = WorldGenerator.DEFAULT_DAY_NIGHT
        WorldGenerator.GENERATOR_INTERPOLATE = WorldGenerator.DEFAULT_INTERPOLATE
    }

    @Test
    fun `карта из рецепта совпадает с нарисованной`() {
        val original = WorldEditorViewModel("12345678")
        original.handle(WorldEditorIntent.SetDayNight(20))
        original.handle(WorldEditorIntent.SetSmoothing(7))
        original.handle(WorldEditorIntent.SetWorldWidth(64))
        original.handle(WorldEditorIntent.Paint(gridX = 10, gridY = 20))
        original.handle(WorldEditorIntent.ClearMap)

        val spec = original.toSpec()
        val restored = WorldEditorViewModel.fromSpec(spec)

        // toSpec() сверяет и размер, и сид, и параметры генератора, и правки.
        assertEquals(spec, restored.toSpec())
    }

    @Test
    fun `мазок без изменений в рецепт не попадает, а карта та же`() {
        val editor = WorldEditorViewModel("12345678")
        editor.handle(WorldEditorIntent.SetErasing(false))
        repeat(5) { editor.handle(WorldEditorIntent.Paint(gridX = 30, gridY = 30)) }
        editor.handle(WorldEditorIntent.Paint(gridX = 31, gridY = 30))

        val spec = editor.toSpec()
        assertTrue(spec.edits.size <= 2, "повторы мазка остались в рецепте: ${spec.edits.size}")
        assertTrue(spec.buildMap().contentDeepEquals(editor.map))
    }

    @Test
    fun `очистка стирает из рецепта всё, что было до неё`() {
        val editor = WorldEditorViewModel("12345678")
        editor.handle(WorldEditorIntent.Paint(gridX = 10, gridY = 10))
        editor.handle(WorldEditorIntent.Paint(gridX = 40, gridY = 40))
        editor.handle(WorldEditorIntent.ClearMap)
        editor.handle(WorldEditorIntent.Paint(gridX = 20, gridY = 20))

        val spec = editor.toSpec()
        assertEquals(WorldEdit.Clear, spec.edits.first())
        assertEquals(2, spec.edits.size)
        assertTrue(spec.buildMap().contentDeepEquals(editor.map))
    }
}
