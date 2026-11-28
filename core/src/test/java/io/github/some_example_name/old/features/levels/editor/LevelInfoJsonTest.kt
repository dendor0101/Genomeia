package io.github.some_example_name.old.features.levels.editor

import io.github.some_example_name.old.features.worldeditor.WorldEdit
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Файл уровня: то, что редактор записал, должно прочитаться обратно без потерь. */
class LevelInfoJsonTest {

    private val level = LevelInfo(
        id = 42,
        nameKey = "Тестовый уровень",
        hasLua = true,
        logic = "function tick() end",
        allowedCells = mapOf("leaf" to true, "muscle" to false),
        cellsNeed = 10,
        needDestination = true,
        destination = 12.5f to 30f,
        scriptedOrganisms = listOf(
            ScriptedOrganism(genomeName = "bundled/testEye", x = 10f, y = 20f, angleDegrees = 90f),
            ScriptedOrganism(genomeName = "test chameleon", x = 30f, y = 40f, angleDegrees = -45f),
        ),
        world = WorldSpec(
            gridWidth = 192,
            gridHeight = 192,
            seed = "12345678",
            dayNight = 15,
            smoothing = 12,
            edits = listOf(
                WorldEdit.Clear,
                WorldEdit.Stroke(gridX = 10, gridY = 20, brushSize = 3, erase = false, circle = true),
                WorldEdit.Stroke(gridX = 11, gridY = 20, brushSize = 0, erase = true, circle = false)
            )
        )
    )

    @Test
    fun `уровень переживает запись и чтение`() {
        assertEquals(level, LevelFiles.decode(LevelFiles.encode(level)))
    }

    @Test
    fun `мазки в файле — строкой, без имён классов`() {
        val text = LevelFiles.encode(level)
        assertTrue(text.contains("\"strokes\": \"C;10,20,3,0,1;11,20,0,1,0\""), text)
        assertFalse(text.contains("WorldEdit"), "формат файла зависит от имени пакета")
    }

    @Test
    fun `файл старого вида с мазками-объектами читается`() {
        val old = Json.encodeToString(LevelInfo.serializer(), level)
        assertTrue(old.contains("WorldEdit.Stroke"))
        assertEquals(level, LevelFiles.decode(old))
    }

    @Test
    fun `убранное поле isBuiltin не мешает читать старые файлы`() {
        val old = Json.encodeToString(LevelInfo.serializer(), level).replaceFirst("{", "{\"isBuiltin\":true,")
        assertEquals(level, LevelFiles.decode(old))
    }
}
