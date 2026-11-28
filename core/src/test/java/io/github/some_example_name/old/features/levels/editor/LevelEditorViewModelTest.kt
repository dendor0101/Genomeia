package io.github.some_example_name.old.features.levels.editor

import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.commands.GoWorldEditor
import io.github.some_example_name.old.commands.NavigationCommands
import io.github.some_example_name.old.features.worldeditor.WorldEdit
import io.github.some_example_name.old.systems.logic.ScriptError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Редактор уровней без экрана: команды меняют состояние, сборка уровня проверяет поля. */
class LevelEditorViewModelTest {

    private val navigated = ArrayList<NavigationCommands>()
    private val written = ArrayList<LevelInfo>()
    private val messages = ArrayList<LevelMessage>()

    private fun model(checkScript: (String) -> ScriptError? = { null }) = LevelEditorViewModel(
        cellKeys = listOf("leaf", "muscle", "tail"),
        genomeNames = { listOf("bundled/Glist") },
        checkScript = checkScript,
        writeLevel = { written += it },
        navigate = { navigated += it }
    ).also { it.onMessage = { m -> messages += m } }

    @Test
    fun `новый уровень собирается сразу и со всеми клетками`() {
        val level = assertNotNull(model().build())
        assertEquals(LevelEditorViewModel.NEW_LEVEL_NAME, level.nameKey)
        assertEquals(false, level.hasLua)
        assertEquals(mapOf("leaf" to true, "muscle" to true, "tail" to true), level.allowedCells)
        assertEquals(false, level.needDestination)
    }

    @Test
    fun `протестировать — уровень в команде запуска мира`() {
        val vm = model()
        vm.handle(LevelEditorIntent.SetField(LevelField.DEST_X, "40"))
        vm.handle(LevelEditorIntent.SetField(LevelField.DEST_Y, "50"))
        vm.handle(LevelEditorIntent.SetScript("function terrain() win() end"))
        vm.handle(LevelEditorIntent.TestLevel)

        val level = assertIs<GoSimulation>(navigated.single()).level
        assertNotNull(level)
        assertEquals(40f to 50f, level.destination)
        assertEquals(true, level.hasLua)
    }

    @Test
    fun `финиш с одной координатой не собирается`() {
        val vm = model()
        vm.handle(LevelEditorIntent.SetField(LevelField.DEST_X, "40"))
        assertNull(vm.build())
        assertEquals("level.editor.destinationBoth", messages.last().key)
    }

    @Test
    fun `без единой клетки уровень не собрать`() {
        val vm = model()
        for (key in listOf("leaf", "muscle", "tail")) vm.handle(LevelEditorIntent.SetCellAllowed(key, false))
        assertNull(vm.build())
        assertEquals("level.editor.noCells", messages.last().key)
    }

    @Test
    fun `ошибка скрипта — со строкой`() {
        val vm = model(checkScript = { ScriptError("unexpected", 3) })
        vm.handle(LevelEditorIntent.SetScript("function ("))
        vm.handle(LevelEditorIntent.SaveLevel)
        assertTrue(written.isEmpty())
        assertEquals(LevelMessage("level.editor.errorAtLine", listOf("unexpected", "3")), messages.last())
    }

    @Test
    fun `загруженный уровень сохраняется таким же`() {
        val source = assertNotNull(model().build()).copy(
            nameKey = "Bone",
            id = "Bone".hashCode(),
            allowedCells = mapOf("leaf" to true, "muscle" to false, "tail" to true),
            scriptedOrganisms = listOf(ScriptedOrganism("bundled/Glist", 10f, 20f, 45f))
        )
        val vm = model()
        vm.handle(LevelEditorIntent.LoadLevel(source))
        vm.handle(LevelEditorIntent.SaveLevel)

        assertEquals(source, written.single())
        assertEquals("level.editor.saved", messages.last().key)
    }

    @Test
    fun `организм с неизвестным геномом не пропускается`() {
        val vm = model()
        vm.handle(LevelEditorIntent.PutOrganism(-1, ScriptedOrganism("нет такого", 1f, 1f)))
        assertNull(vm.build())
        assertEquals("level.editor.genomeNotFound", messages.last().key)
    }

    @Test
    fun `редактор карты открывается с картой уровня`() {
        val vm = model()
        vm.handle(LevelEditorIntent.OpenMapEditor)
        assertEquals(vm.map, assertIs<GoWorldEditor>(navigated.single()).levelMap)
    }

    @Test
    fun `уровень больше предела записи журнала не собирается`() {
        val vm = model()
        val strokes = List(9000) { WorldEdit.Stroke(it % 150, it / 150, 1, erase = it % 2 == 0, circle = true) }
        vm.handle(LevelEditorIntent.SetMap(vm.map.copy(edits = strokes)))
        assertNull(vm.build())
        assertEquals("level.editor.tooBig", messages.last().key)
    }
}
