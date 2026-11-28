package io.github.some_example_name.old.features.levels.editor

import io.github.some_example_name.old.features.worldeditor.WorldSpec
import kotlinx.serialization.Serializable

/**
 * Уровень целиком: скрипт, палитра разрешённых клеток, цель, зона старта, карта и
 * организмы, которых уровень ставит сам. Один и тот же объект — файл уровня (LevelFiles,
 * JSON) и данные команд GoSimulation и LevelEditorIntent.LoadLevel (журнал, protobuf):
 * повтор .bug поднимает ровно тот уровень, который играли.
 *
 * Поля — формат файла и журнала: новые только с значением по умолчанию.
 */
@Serializable
data class LevelInfo(
    val id: Int,
    val nameKey: String,
    val hasLua: Boolean,
    val logic: String,
    val allowedCells: Map<String, Boolean>,
    val cellsNeed: Int,
    val needDestination: Boolean,
    val destination: Pair<Float, Float>,
    val world: WorldSpec,
    /** Центр зоны старта: уровням, созданным до её появления, ставить организм можно везде. */
    val spawnX: Float = -1f,
    val spawnY: Float = -1f,
    /** Радиус зоны старта; 0 — ограничения нет. */
    val spawnRadius: Float = 0f,
    /**
     * Организмы, которые уровень создаёт сам. Это не скриптовые клетки: полноценный
     * организм из генома, с фиксированными координатой и углом. Одинаковые при каждом
     * запуске — в этом и смысл: игрок меняет только свои действия.
     */
    val scriptedOrganisms: List<ScriptedOrganism> = emptyList(),
    /**
     * Место в списке кампании: меньше — раньше. Встроенные уровни идут курсом — клетки в
     * порядке реестра CellTypes (Leaf, Fat, Bone, …), уровни формул — после Neuron в порядке
     * формул (Formulas.kt). 0 — без места (свои уровни игрока): они идут по названию.
     */
    val order: Int = 0
)
