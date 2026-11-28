package io.github.some_example_name.old.features.worldeditor

import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.SessionJournal
import kotlinx.serialization.Serializable

/** Что игрок хочет сделать в редакторе мира. Один тип на все действия экрана. */
@Serializable
sealed interface WorldEditorIntent : GameCommand {

    /**
     * Кнопка «Новый сид». Сам сид выбирается при создании команды ([newSeed]) и едет в ней:
     * случайность внутри обработчика при повторе по журналу дала бы другую карту.
     */
    @Serializable
    data class NewSeed(val seed: String) : WorldEditorIntent {
        override val name get() = "NewSeed"
        override val detail get() = seed
    }

    @Serializable
    data class SetSeed(val seed: String) : WorldEditorIntent {
        override val name get() = "SetSeed"
        override val detail get() = seed
    }

    @Serializable
    data class SetDayNight(val value: Int) : WorldEditorIntent {
        override val name get() = "SetDayNight"
        override val detail get() = value.toString()
    }

    @Serializable
    data class SetSmoothing(val value: Int) : WorldEditorIntent {
        override val name get() = "SetSmoothing"
        override val detail get() = value.toString()
    }

    /** Ширина мира в клетках сетки симуляции, не в ячейках карты: см. WorldSpec.worldWidth. */
    @Serializable
    data class SetWorldWidth(val value: Int) : WorldEditorIntent {
        override val name get() = "SetWorldWidth"
        override val detail get() = value.toString()
    }

    @Serializable
    data class SetWorldHeight(val value: Int) : WorldEditorIntent {
        override val name get() = "SetWorldHeight"
        override val detail get() = value.toString()
    }

    @Serializable
    data class SetBrushSize(val value: Int) : WorldEditorIntent {
        override val name get() = "SetBrushSize"
        override val detail get() = value.toString()
    }

    @Serializable
    data class SetErasing(val value: Boolean) : WorldEditorIntent {
        override val name get() = "SetErasing"
        override val detail get() = value.toString()
    }

    @Serializable
    data class SetCircleBrush(val value: Boolean) : WorldEditorIntent {
        override val name get() = "SetCircleBrush"
        override val detail get() = value.toString()
    }

    @Serializable
    data class Paint(val gridX: Int, val gridY: Int) : WorldEditorIntent {
        override val name get() = "Paint"
        override val detail get() = "$gridX,$gridY"
    }

    @Serializable
    data object ClearMap : WorldEditorIntent {
        override val name get() = "ClearMap"
    }

    /** Показать или спрятать панель настроек генератора рядом с холстом. */
    @Serializable
    data class SetSettingsOpen(val open: Boolean) : WorldEditorIntent {
        override val name get() = "SetSettingsOpen"
        override val detail get() = open.toString()
    }

    companion object {
        private const val SEED_LENGTH = 8

        fun newSeed(): NewSeed = NewSeed((1..SEED_LENGTH).map { ('0'..'9').random() }.joinToString(""))
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
    /** Стартовый сид — из команды навигации GoWorldEditor, см. её описание. */
    initialSeed: String,
    private val worldGenerator: WorldGenerator = WorldGenerator()
) {
    companion object {
        /**
         * Размер мира по умолчанию — тот, что редактор показывал всегда (карта 192×192).
         * Мир в симуляции раньше был 256×256 при той же карте, то есть пустовал на 3/4.
         */
        const val DEFAULT_WORLD_SIZE = 128

        /**
         * Границы размера мира в клетках мира (единицах координат игры). Шаг — 16 клеток: высота
         * сетки обязана делиться на chunkHeight * 2 (см. DISimulationContainer.resizeWorld),
         * ширина — для единообразия.
         *
         * Сверху 1024 (решение 21.11, было 384): начало координат физики в центре мира
         * (PhysicsBridge.originX), и даже в углу мира 1024 координата физики не дальше от нуля,
         * чем в углу прежнего мира 384 с началом в углу, — шаг float тот же.
         *
         * Цена (замер 22.11, docs/physics/reference.md, находка 36): у мира 1024 с рельефом 1.17 млн
         * частиц и пик 5.9 ГБ, карта 1536×1536 строится ~1 с на каждый шаг ползунка, а таблица
         * микроскопа (render GpuOpticalInput) при 1.2 млн частиц больше предела текстуры — отрисовка
         * падает уже на ПК (TODO.md, «Мир 1024 роняет микроскоп»).
         */
        const val WORLD_SIZE_MIN = 64
        const val WORLD_SIZE_MAX = 1024
        const val WORLD_SIZE_STEP = 16

        const val DAY_NIGHT_MIN = 0f
        const val DAY_NIGHT_MAX = 25f
        const val SMOOTHING_MIN = 0f
        const val SMOOTHING_MAX = 50f
        const val BRUSH_SIZE_MIN = 0f
        const val BRUSH_SIZE_MAX = 20f

        /**
         * Редактор из рецепта сохранённого уровня (LevelInfo.world).
         *
         * Размер карты в рецепте — производная от размера мира (см. WorldSpec), поэтому
         * worldWidth восстанавливается делением, а не хранится вторым полем: иначе два
         * размера могли бы разойтись. Правки повторяются тем же applyEdit, которым их
         * наносила кисть: toSpec() отдаст рецепт той же карты, без пустых мазков.
         */
        fun fromSpec(spec: WorldSpec, worldGenerator: WorldGenerator = WorldGenerator()): WorldEditorViewModel {
            // Параметры генератора живут в companion и читаются при генерации — их надо
            // выставить до первого generate(), иначе редактор откроется с чужим рельефом.
            WorldGenerator.GENERATOR_DAY_NIGHT = spec.dayNight
            WorldGenerator.GENERATOR_INTERPOLATE = spec.smoothing

            val world = WorldEditorViewModel(spec.seed, worldGenerator)
            world.worldWidth = world.clampWorldSize(spec.gridWidth * WorldSpec.MAP_SCALE_DEN / WorldSpec.MAP_SCALE_NUM)
            world.worldHeight = world.clampWorldSize(spec.gridHeight * WorldSpec.MAP_SCALE_DEN / WorldSpec.MAP_SCALE_NUM)
            world.regenerate()
            spec.edits.forEach { world.applyEdit(it) }
            return world
        }
    }

    var seed: String = initialSeed
        private set

    /** Размер мира в клетках сетки симуляции. Карта под него — [gridWidth] × [gridHeight]. */
    var worldWidth: Int = DEFAULT_WORLD_SIZE
        private set

    var worldHeight: Int = DEFAULT_WORLD_SIZE
        private set

    /** Размер карты в её ячейках — то, что рисует холст и правит кисть. */
    val gridWidth: Int get() = WorldSpec.mapCells(worldWidth)

    val gridHeight: Int get() = WorldSpec.mapCells(worldHeight)

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

    var isSettingsOpen: Boolean = false
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

    fun handle(intent: WorldEditorIntent) = SessionJournal.execute(CommandSource.WORLD_EDITOR, intent) { apply(it) }

    private fun apply(intent: WorldEditorIntent) {
        when (intent) {
            is WorldEditorIntent.NewSeed -> {
                seed = intent.seed
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

            is WorldEditorIntent.SetWorldWidth -> {
                val value = clampWorldSize(intent.value)
                if (value != worldWidth) {
                    worldWidth = value
                    regenerate()
                }
            }

            is WorldEditorIntent.SetWorldHeight -> {
                val value = clampWorldSize(intent.value)
                if (value != worldHeight) {
                    worldHeight = value
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

            is WorldEditorIntent.SetSettingsOpen -> isSettingsOpen = intent.open
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

    /**
     * В рецепт идут только правки, которые меняют карту. Мазок, не тронувший ни одной ячейки,
     * при воспроизведении тоже ничего не тронет: все промежуточные карты, а значит и итог,
     * побитово те же. Clear стирает всё до себя, и правки перед ним больше ни на что не влияют.
     *
     * Зачем: кисть пишет мазок на каждое событие мыши, и в уровнях PR 38 из ~4700 мазков
     * карты большая часть повторяла предыдущий. Рецепт едет в журнал (GoSimulation, предел
     * записи 1 МБ, JournalFormat.MAX_PAYLOAD) и в файл уровня.
     */
    private fun applyEdit(edit: WorldEdit) {
        if (!applyWorldEdit(map, edit, gridWidth, gridHeight)) return
        if (edit is WorldEdit.Clear) edits.clear()
        edits += edit
        mapVersion++
    }

    private fun regenerate() {
        // Новая генерация обнуляет карту целиком, значит прошлые правки к ней уже
        // неприменимы — рецепт должен описывать ровно то, что игрок видит сейчас.
        edits.clear()
        map = generate()
        mapVersion++
    }

    /**
     * Размер приходит из ползунка, но команда — это данные из журнала: кривое значение
     * не должно уронить симуляцию (высота не кратна чанку) уже после выхода из редактора.
     */
    private fun clampWorldSize(value: Int): Int =
        (value / WORLD_SIZE_STEP * WORLD_SIZE_STEP).coerceIn(WORLD_SIZE_MIN, WORLD_SIZE_MAX)

    private fun generate(): Array<BooleanArray> =
        worldGenerator.generateWorld(gridWidth, gridHeight, seed.hashCode().toLong())
}
