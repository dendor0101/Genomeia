package io.github.some_example_name.old.features.levels.editor

import io.github.some_example_name.old.features.worldeditor.WorldEdit
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Файл уровня: то, что редактор записал, должно прочитаться обратно без потерь. */
class LevelInfoJsonTest {

    @Test
    fun `уровень переживает запись и чтение`() {
        val level = LevelInfo(
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
                    WorldEdit.Stroke(gridX = 10, gridY = 20, brushSize = 3, erase = false, circle = true),
                    WorldEdit.Clear
                )
            )
        )

        val text = Json.encodeToString(LevelInfo.serializer(), level)
        assertEquals(level, Json.decodeFromString(LevelInfo.serializer(), text))
    }
}
