package io.github.some_example_name.old.features.worldeditor

import io.github.some_example_name.old.core.log.ActionLog

/** Что игрок хочет сделать в редакторе мира. Один тип на все действия экрана. */
sealed interface WorldEditorIntent {
    val name: String
    val detail: String get() = ""

    data object NewSeed : WorldEditorIntent {
        override val name get() = "NewSeed"
    }

    data class SetSeed(val seed: String) : WorldEditorIntent {
        override val name get() = "SetSeed"
        override val detail get() = seed
    }

    data class SetDayNight(val value: Int) : WorldEditorIntent {
        override val name get() = "SetDayNight"
        override val detail get() = value.toString()
    }

    data class SetSmoothing(val value: Int) : WorldEditorIntent {
        override val name get() = "SetSmoothing"
        override val detail get() = value.toString()
    }

    data class SetBrushSize(val value: Int) : WorldEditorIntent {
        override val name get() = "SetBrushSize"
        override val detail get() = value.toString()
    }

    data class SetErasing(val value: Boolean) : WorldEditorIntent {
        override val name get() = "SetErasing"
        override val detail get() = value.toString()
    }

    data class SetCircleBrush(val value: Boolean) : WorldEditorIntent {
        override val name get() = "SetCircleBrush"
        override val detail get() = value.toString()
    }

    data class Paint(val gridX: Int, val gridY: Int) : WorldEditorIntent {
        override val name get() = "Paint"
        override val detail get() = "$gridX,$gridY"
    }

    data object ClearMap : WorldEditorIntent {
        override val name get() = "ClearMap"
    }
}

/**
 * Вся логика редактора мира: генерация карты, кисть, состояние настроек.
 *
 * Единственный вход — [handle]. Экран не трогает поля напрямую, поэтому любое действие
 * игрока проходит ровно через одну строку и там же попадает в журнал: краш в редакторе
 * воспроизводится по логу, а не по пересказу.
 *
 * Ничего не знает ни про scene2d, ни про Pixmap/Texture — оперирует только
 * `map: Array<BooleanArray>`, поэтому её можно дёргать из тестов без окна и GL-контекста.
 */
class WorldEditorViewModel(
    private val worldGenerator: WorldGenerator = WorldGenerator(),
    val gridWidth: Int = DEFAULT_GRID_WIDTH,
    val gridHeight: Int = DEFAULT_GRID_HEIGHT
) {
    companion object {
        val DEFAULT_GRID_WIDTH = (128 * 1.5f).toInt()
        val DEFAULT_GRID_HEIGHT = (128 * 1.5f).toInt()

        const val DAY_NIGHT_MIN = 0f
        const val DAY_NIGHT_MAX = 25f
        const val SMOOTHING_MIN = 0f
        const val SMOOTHING_MAX = 50f
        const val BRUSH_SIZE_MIN = 0f
        const val BRUSH_SIZE_MAX = 20f

        private const val SEED_LENGTH = 8
        private const val LOG_SOURCE = "WorldEditor"
    }

    var seed: String = randomSeed()
        private set

    /**
     * Оба параметра генератора живут в companion WorldGenerator (их читает generateWorld),
     * поэтому здесь они не дублируются, а проксируются — иначе состояние экрана и генератора
     * разъезжается при повторном заходе в редактор.
     */
    val dayNight: Int get() = WorldGenerator.GENERATOR_DAY_NIGHT

    val smoothing: Int get() = WorldGenerator.GENERATOR_INTERPOLATE

    var brushSize: Int = 9
        private set

    var isErasing: Boolean = false
        private set

    var useCircleBrush: Boolean = true
        private set

    /** Правки поверх сгенерированной карты — то, из чего собирается [WorldSpec]. */
    private val edits = mutableListOf<WorldEdit>()

    var map: Array<BooleanArray> = generate()
        private set

    /**
     * Растёт при каждом изменении карты. Вьюха сравнивает его со своим последним значением
     * и перезаливает текстуру только когда есть что заливать — мазок кистью, не меняющий
     * ни одной ячейки, не стоит ни одной загрузки в GPU.
     */
    var mapVersion: Int = 0
        private set

    fun handle(intent: WorldEditorIntent) {
        ActionLog.record(LOG_SOURCE, intent.name, intent.detail)

        when (intent) {
            is WorldEditorIntent.NewSeed -> {
                seed = randomSeed()
                regenerate()
            }

            is WorldEditorIntent.SetSeed -> {
                if (intent.seed != seed) {
                    seed = intent.seed
                    regenerate()
                }
            }

            is WorldEditorIntent.SetDayNight -> {
                if (intent.value != dayNight) {
                    WorldGenerator.GENERATOR_DAY_NIGHT = intent.value
                    regenerate()
                }
            }

            is WorldEditorIntent.SetSmoothing -> {
                if (intent.value != smoothing) {
                    WorldGenerator.GENERATOR_INTERPOLATE = intent.value
                    regenerate()
                }
            }

            is WorldEditorIntent.SetBrushSize -> brushSize = intent.value

            is WorldEditorIntent.SetErasing -> isErasing = intent.value

            is WorldEditorIntent.SetCircleBrush -> useCircleBrush = intent.value

            is WorldEditorIntent.Paint -> applyEdit(
                WorldEdit.Stroke(
                    gridX = intent.gridX,
                    gridY = intent.gridY,
                    brushSize = brushSize,
                    erase = isErasing,
                    circle = useCircleBrush
                )
            )

            is WorldEditorIntent.ClearMap -> applyEdit(WorldEdit.Clear)
        }
    }

    /** Рецепт текущего мира: по нему карта восстанавливается побайтово. */
    fun toSpec(): WorldSpec = WorldSpec(
        gridWidth = gridWidth,
        gridHeight = gridHeight,
        seed = seed,
        dayNight = dayNight,
        smoothing = smoothing,
        edits = edits.toList()
    )

    private fun applyEdit(edit: WorldEdit) {
        edits += edit
        if (applyWorldEdit(map, edit, gridWidth, gridHeight)) {
            mapVersion++
        }
    }

    private fun regenerate() {
        // Новая генерация обнуляет карту целиком, значит прошлые правки к ней уже
        // неприменимы — рецепт должен описывать ровно то, что игрок видит сейчас.
        edits.clear()
        map = generate()
        mapVersion++
    }

    private fun generate(): Array<BooleanArray> =
        worldGenerator.generateWorld(gridWidth, gridHeight, seed.hashCode().toLong())

    private fun randomSeed(): String =
        (1..SEED_LENGTH).map { ('0'..'9').random() }.joinToString("")
}
