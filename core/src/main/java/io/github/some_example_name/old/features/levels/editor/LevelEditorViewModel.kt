package io.github.some_example_name.old.features.levels.editor

import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.commands.GoSimulation
import io.github.some_example_name.old.commands.GoWorldEditor
import io.github.some_example_name.old.commands.NavigationCommands
import io.github.some_example_name.old.features.worldeditor.WorldEditorViewModel
import io.github.some_example_name.old.features.worldeditor.WorldGenerator
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.old.journal.CommandRegistry
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.SessionJournal
import io.github.some_example_name.old.systems.logic.ScriptError
import kotlinx.serialization.Serializable

/** Текстовые поля свойств уровня: значение — как игрок его набрал, разбор — при сборке уровня. */
@Serializable
enum class LevelField { NAME, CELLS_NEED, DEST_X, DEST_Y, SPAWN_X, SPAWN_Y, SPAWN_RADIUS }

/**
 * Действия игрока в редакторе уровней. Как у остальных экранов (GameCommand): экран только
 * собирает команду из жеста, состояние меняет [LevelEditorViewModel]. Внешний ввод — файл
 * уровня или скрипта с диска — едет в команде данными ([LoadLevel], [SetScript]).
 */
@Serializable
sealed interface LevelEditorIntent : GameCommand {

    /** Набранный текст поля свойств. */
    @Serializable
    data class SetField(val field: LevelField, val value: String) : LevelEditorIntent {
        override val name get() = "SetField"
        override val detail get() = "$field=$value"
    }

    /**
     * Текст скрипта. Экран шлёт его не на каждую клавишу (скрипт целиком на каждую букву
     * раздул бы журнал квадратично), а когда игрок уходит из поля или жмёт кнопку.
     */
    @Serializable
    data class SetScript(val text: String) : LevelEditorIntent {
        override val name get() = "SetScript"
        override val detail get() = "${text.length} симв."
    }

    @Serializable
    data class SetCellAllowed(val cellKey: String, val allowed: Boolean) : LevelEditorIntent {
        override val name get() = "SetCellAllowed"
        override val detail get() = "$cellKey=$allowed"
    }

    /** Добавить ([index] = -1) или заменить организм, который уровень ставит сам. */
    @Serializable
    data class PutOrganism(val index: Int, val organism: ScriptedOrganism) : LevelEditorIntent {
        override val name get() = "PutOrganism"
        override val detail get() = "$index ${organism.genomeName}"
    }

    @Serializable
    data class RemoveOrganism(val index: Int) : LevelEditorIntent {
        override val name get() = "RemoveOrganism"
        override val detail get() = "$index"
    }

    /** Карта вернулась из редактора мира. */
    @Serializable
    data class SetMap(val map: WorldSpec) : LevelEditorIntent {
        override val name get() = "SetMap"
        override val detail get() = map.detail
    }

    /** Уровень, прочитанный из файла: данными, чтобы повтор не зависел от диска разработчика. */
    @Serializable
    data class LoadLevel(val level: LevelInfo) : LevelEditorIntent {
        override val name get() = "LoadLevel"
        override val detail get() = level.nameKey
    }

    @Serializable
    data object SaveLevel : LevelEditorIntent {
        override val name get() = "SaveLevel"
    }

    @Serializable
    data object TestLevel : LevelEditorIntent {
        override val name get() = "TestLevel"
    }

    @Serializable
    data object OpenMapEditor : LevelEditorIntent {
        override val name get() = "OpenMapEditor"
    }

    @Serializable
    data object Exit : LevelEditorIntent {
        override val name get() = "Exit"
    }
}

/** Сообщение под скриптом: ключ бандла и подстановки. Текст собирает экран. */
data class LevelMessage(val key: String, val args: List<String> = emptyList())

/**
 * Состояние редактора уровней и всё, что с ним делают. Без Gdx: файлы, навигация и
 * справочники (клетки, геномы, проверка скрипта) приходят снаружи, поэтому модель
 * проверяется в тестах :core.
 */
class LevelEditorViewModel(
    /** Ключи клеток, доступных в игре (без выведенных): палитра нового уровня — все они. */
    private val cellKeys: List<String>,
    /** Имена геномов (GenomeCatalog.all): организм уровня ссылается на один из них. */
    private val genomeNames: () -> List<String>,
    private val checkScript: (String) -> ScriptError?,
    private val writeLevel: (LevelInfo) -> Unit,
    private val navigate: (NavigationCommands) -> Unit
) {
    private val fields = Array(LevelField.entries.size) { "" }

    /**
     * Скрипт нового уровня пустой. Заготовку на языке игрока экран показывает подсказкой в
     * пустом поле: текст из бандла в данных уровня сделал бы уровень зависящим от языка, а
     * повтор .bug идёт на языке разработчика.
     */
    var script: String = ""
        private set

    /** Палитра уровня по ключу клетки; порядок — порядок клеток в игре. */
    val allowedCells = LinkedHashMap<String, Boolean>()

    val organisms = ArrayList<ScriptedOrganism>()

    /**
     * Карта нового уровня — рецептом из констант. Не через WorldEditorViewModel: тот берёт
     * параметры генератора из общих полей WorldGenerator, которые меняет любой открытый
     * редактор мира, и повтор .bug в свежем процессе получил бы другую карту.
     */
    var map: WorldSpec = WorldSpec(
        gridWidth = WorldSpec.mapCells(WorldEditorViewModel.DEFAULT_WORLD_SIZE),
        gridHeight = WorldSpec.mapCells(WorldEditorViewModel.DEFAULT_WORLD_SIZE),
        seed = NEW_LEVEL_SEED,
        dayNight = WorldGenerator.DEFAULT_DAY_NIGHT,
        smoothing = WorldGenerator.DEFAULT_INTERPOLATE
    )
        private set

    /** Сообщение под скриптом. Экран подписывается и показывает текст. */
    var onMessage: ((LevelMessage) -> Unit)? = null

    /** Состояние поменялось не из поля ввода: экран пересобирает вёрстку. */
    var onChanged: (() -> Unit)? = null

    init {
        resetFields()
        for (key in cellKeys) allowedCells[key] = true
    }

    fun field(field: LevelField): String = fields[field.ordinal]

    fun handle(intent: LevelEditorIntent) =
        SessionJournal.execute(CommandSource.LEVEL_EDITOR, intent) { apply(it) }

    /** Разбор скрипта без запуска. Запрос, а не команда: состояние он не меняет. */
    fun check(text: String): LevelMessage =
        checkScript(text)?.let(::scriptMessage) ?: LevelMessage("level.editor.noErrors")

    private fun apply(intent: LevelEditorIntent) {
        when (intent) {
            is LevelEditorIntent.SetField -> fields[intent.field.ordinal] = intent.value
            is LevelEditorIntent.SetScript -> script = intent.text
            is LevelEditorIntent.SetCellAllowed -> allowedCells[intent.cellKey] = intent.allowed
            is LevelEditorIntent.PutOrganism -> {
                if (intent.index in organisms.indices) organisms[intent.index] = intent.organism
                else organisms += intent.organism
                onChanged?.invoke()
            }
            is LevelEditorIntent.RemoveOrganism -> {
                if (intent.index in organisms.indices) organisms.removeAt(intent.index)
                onChanged?.invoke()
            }
            is LevelEditorIntent.SetMap -> {
                map = intent.map
                onChanged?.invoke()
            }
            is LevelEditorIntent.LoadLevel -> {
                load(intent.level)
                onMessage?.invoke(LevelMessage("level.editor.loaded"))
                onChanged?.invoke()
            }
            LevelEditorIntent.SaveLevel -> save()
            LevelEditorIntent.TestLevel -> build()?.let { navigate(GoSimulation(world = null, genomeName = null, level = it)) }
            LevelEditorIntent.OpenMapEditor -> navigate(GoWorldEditor(map.seed, levelMap = map))
            LevelEditorIntent.Exit -> navigate(GoBack)
        }
    }

    private fun save() {
        val level = build() ?: return
        val ok = runCatching { writeLevel(level) }.isSuccess
        onMessage?.invoke(LevelMessage(if (ok) "level.editor.saved" else "level.editor.saveFailed"))
    }

    private fun resetFields() {
        fields[LevelField.NAME.ordinal] = NEW_LEVEL_NAME
        fields[LevelField.CELLS_NEED.ordinal] = "-1"
        fields[LevelField.DEST_X.ordinal] = "-1"
        fields[LevelField.DEST_Y.ordinal] = "-1"
        // Радиус 0 — без зоны старта: старые уровни читаются как раньше.
        fields[LevelField.SPAWN_X.ordinal] = "-1"
        fields[LevelField.SPAWN_Y.ordinal] = "-1"
        fields[LevelField.SPAWN_RADIUS.ordinal] = "0"
    }

    private fun load(level: LevelInfo) {
        fields[LevelField.NAME.ordinal] = level.nameKey
        fields[LevelField.CELLS_NEED.ordinal] = level.cellsNeed.toString()
        fields[LevelField.DEST_X.ordinal] = if (level.needDestination) level.destination.first.toString() else "-1"
        fields[LevelField.DEST_Y.ordinal] = if (level.needDestination) level.destination.second.toString() else "-1"
        // Радиус — то, что отличает «зона есть» от «зоны нет»: без него X и Y ни на что не влияют.
        val zone = level.spawnRadius > 0f
        fields[LevelField.SPAWN_X.ordinal] = if (zone) level.spawnX.toString() else "-1"
        fields[LevelField.SPAWN_Y.ordinal] = if (zone) level.spawnY.toString() else "-1"
        fields[LevelField.SPAWN_RADIUS.ordinal] = if (zone) level.spawnRadius.toString() else "0"
        script = level.logic

        allowedCells.clear()
        // Пустая карта — старая запись «разрешено всё». Раскрываем её в явный список, иначе
        // первый же клик в диалоге оставил бы в файле одну клетку и спрятал все остальные.
        for (key in cellKeys) allowedCells[key] = level.allowedCells.isEmpty() || level.allowedCells[key] == true

        organisms.clear()
        organisms.addAll(level.scriptedOrganisms)
        map = level.world
    }

    /**
     * Уровень из текущего состояния — общий путь для «Сохранить» и «Протестировать»:
     * числа, согласованность финиша и зоны старта, хотя бы одна клетка, организмы, скрипт
     * и размер записи журнала. null — что-то не так, сообщение уже ушло на экран.
     */
    fun build(): LevelInfo? {
        val hasLua = script.isNotEmpty()
        if (hasLua) checkScript(script)?.let { return fail(scriptMessage(it)) }

        if (fields.any { it.isEmpty() }) return fail(LevelMessage("level.editor.emptyField"))

        val cellsNeed = field(LevelField.CELLS_NEED).toIntOrNull()
        val destX = field(LevelField.DEST_X).toFloatOrNull()
        val destY = field(LevelField.DEST_Y).toFloatOrNull()
        val spawnX = field(LevelField.SPAWN_X).toFloatOrNull()
        val spawnY = field(LevelField.SPAWN_Y).toFloatOrNull()
        val spawnRadius = field(LevelField.SPAWN_RADIUS).toFloatOrNull()
        if (cellsNeed == null || destX == null || destY == null || spawnX == null || spawnY == null || spawnRadius == null) {
            return fail(LevelMessage("level.editor.wrongType"))
        }
        if (!destX.isFinite() || !destY.isFinite() || !spawnX.isFinite() || !spawnY.isFinite() || !spawnRadius.isFinite()) {
            return fail(LevelMessage("level.editor.notFinite"))
        }

        // Финиш задан либо обеими координатами, либо ни одной. Одна -1 и вторая 64 —
        // это не «нет финиша», а финиш в (-1, 64), до которого нельзя дойти.
        val needDestination = !(destX == -1f && destY == -1f)
        if (needDestination && (destX == -1f || destY == -1f)) return fail(LevelMessage("level.editor.destinationBoth"))

        if (allowedCells.none { (key, allowed) -> allowed && key in cellKeys }) return fail(LevelMessage("level.editor.noCells"))

        val catalog = genomeNames()
        for (organism in organisms) {
            if (organism.genomeName !in catalog) {
                return fail(LevelMessage("level.editor.genomeNotFound", listOf(organism.genomeName)))
            }
            if (!organism.x.isFinite() || !organism.y.isFinite() || !organism.angleDegrees.isFinite()) {
                return fail(LevelMessage("level.editor.organismNotNumbers"))
            }
            if (organism.x < 0f || organism.y < 0f || organism.x > map.worldWidth || organism.y > map.worldHeight) {
                return fail(LevelMessage("level.editor.organismOutside", listOf("${map.worldWidth}", "${map.worldHeight}")))
            }
        }

        val hasSpawnZone = spawnRadius > 0f
        val name = field(LevelField.NAME)
        val level = LevelInfo(
            id = name.hashCode(),
            nameKey = name,
            hasLua = hasLua,
            logic = script,
            allowedCells = LinkedHashMap(allowedCells),
            cellsNeed = cellsNeed,
            needDestination = needDestination,
            destination = destX to destY,
            world = map,
            spawnX = if (hasSpawnZone) spawnX else -1f,
            spawnY = if (hasSpawnZone) spawnY else -1f,
            spawnRadius = if (hasSpawnZone) spawnRadius else 0f,
            scriptedOrganisms = organisms.toList()
        )

        // Уровень целиком едет в команде GoSimulation, а запись журнала больше
        // JournalFormat.MAX_PAYLOAD читатель считает порчей и обрезает всё после неё.
        val size = encodedSize(level)
        if (size > MAX_LEVEL_BYTES) return fail(LevelMessage("level.editor.tooBig", listOf("${size / 1024}", "${MAX_LEVEL_BYTES / 1024}")))
        return level
    }

    private fun fail(message: LevelMessage): LevelInfo? {
        onMessage?.invoke(message)
        return null
    }

    private fun scriptMessage(error: ScriptError): LevelMessage =
        if (error.line >= 0) LevelMessage("level.editor.errorAtLine", listOf(error.message, error.line.toString()))
        else LevelMessage("level.editor.error", listOf(error.message))

    companion object {
        const val NEW_LEVEL_NAME = "Level"
        const val NEW_LEVEL_SEED = "level"

        /**
         * Предел уровня в записи журнала: половина JournalFormat.MAX_PAYLOAD (1 МБ), с
         * запасом на остальные поля команды. Встроенные уровни после сжатия рецепта карты
         * (WorldEditorViewModel.applyEdit) — десятки КБ, см. LevelAssetsCheck.
         */
        const val MAX_LEVEL_BYTES = 512 * 1024

        fun encodedSize(level: LevelInfo): Int =
            CommandRegistry.encode(GoSimulation(world = null, genomeName = null, level = level))?.second?.size ?: 0
    }
}
