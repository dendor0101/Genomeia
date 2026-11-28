package io.github.some_example_name.old.commands

import io.github.some_example_name.old.features.worldeditor.WorldEditorIntent
import io.github.some_example_name.old.features.worldeditor.WorldSpec
import io.github.some_example_name.old.features.levels.editor.LevelInfo
import kotlinx.serialization.Serializable

@Serializable
sealed class NavigationCommands : GameCommand {
    /** Именно это имя понимает [navigationCommandByName]. */
    abstract override val name: String

    override val detail: String get() = ""
}

@Serializable
object GoExit : NavigationCommands() {
    override val name get() = "GoExit"
}

@Serializable
object GoBack : NavigationCommands() {
    override val name get() = "GoBack"
}

/**
 * Заменяет текущий экран и очищает стек — «дальше начинаем отсюда».
 *
 * Нужна там, где раньше стояло прямое `game.screen = SomeScreen()`. Такое присваивание
 * меняло экран мимо менеджера, поэтому стек оставался от прошлого маршрута: после выхода
 * из симуляции в меню «Назад» уводило обратно в редактор мира, которого игрок уже не видел,
 * а сами отложенные экраны никто не освобождал.
 */
@Serializable
class GoReplace(val target: Menu) : NavigationCommands() {
    override val name get() = "GoReplace"
    override val detail get() = target.logName
}

@Serializable
sealed class Menu : NavigationCommands()

@Serializable
object GoMenu : Menu() {
    override val name get() = "GoMenu"
}

/**
 * Редактор мира. Стартовый сид едет в команде: случайность в конструкторе экрана при
 * повторе дала бы другую карту, и все мазки кистью легли бы не туда.
 */
@Serializable
class GoWorldEditor(
    val seed: String,
    /**
     * Карта уровня из редактора уровней: редактор мира открывается с неё, а «Сохранить»
     * возвращает правленую карту командой LevelEditorIntent.SetMap. null — обычный мир.
     * Данными в команде, а не общей ссылкой на вьюху: повтор .bug поднимет ту же карту.
     */
    val levelMap: WorldSpec? = null
) : Menu() {
    override val name get() = "GoWorldEditor"
    override val detail get() = if (levelMap != null) "$seed, level" else seed
}

@Serializable
class GoGenomeEditor(val genomeName: String?) : Menu() {
    override val name get() = "GoGenomeEditor"
    override val detail get() = genomeName ?: "new"
}

/** Экран «Геномы»: свои и встроенные геномы с превью, переход в редактор, импорт. */
@Serializable
object GoGenomes : Menu() {
    override val name get() = "GoGenomes"
}

/** Гайд игрока: обучение редактору генома и описание клеток. */
@Serializable
object GoGuide : Menu() {
    override val name get() = "GoGuide"
}

/** Обучение: редактор генома с новым геномом и шагами обучения (EditorTutorial). */
@Serializable
object GoTutorial : Menu() {
    override val name get() = "GoTutorial"
}

@Serializable
object GoSettings : Menu() {
    override val name get() = "GoSettings"
}

/** Кампания: встроенные уровни и уровни игрока (CampaignScreen). */
@Serializable
object GoCampaign : Menu() {
    override val name get() = "GoCampaign"
}

/** Редактор уровней: скрипт, свойства, карта (LevelEditorScreen). */
@Serializable
object GoLevelEditor : Menu() {
    override val name get() = "GoLevelEditor"
}

@Serializable
object GoEcoSystem : Menu() {
    override val name get() = "GoEcoSystem"
}

@Serializable
object GoSupport : Menu() {
    override val name get() = "GoSupport"
}

/**
 * Запуск мира. Либо обычная симуляция ([world] или [savedWorld]), либо уровень: тогда
 * карта, lua и цель едут в [level] одной командой.
 */
@Serializable
class GoSimulation(
    /**
     * Рецепт мира, а не готовая карта: см. [WorldSpec]. null — мир не из редактора карт:
     * тест генома, сохранение или уровень (у уровня карта лежит в [level]).
     */
    val world: WorldSpec?,
    val genomeName: String?,
    /** Слот сохранённого мира (MapSave): тогда мир загружается из него, а [world] не нужен. */
    val savedWorld: String? = null,
    /**
     * Уровень целиком: карта, lua, палитра разрешённых клеток, цель. Едет в команде, а не
     * ставится экраном до её вызова, иначе в журнале остаётся «запустить мир», а какой
     * именно уровень — нет, и повтор .bug поднимает уровень обычной симуляцией.
     * null — обычная симуляция.
     */
    val level: LevelInfo? = null
) : Menu() {
    override val name get() = "GoSimulation"
    override val detail
        get() = listOfNotNull(world?.detail, genomeName, savedWorld?.let { "save=$it" },
            level?.let { "level=${it.nameKey}" })
            .joinToString(", ").ifEmpty { "-" }
}

@Serializable
object EcoSystemScreenGlobalSettings : Menu() {
    override val name get() = "EcoSystemScreenGlobalSettings"
}

@Serializable
object EcoSystemScreenCellsSettings : Menu() {
    override val name get() = "EcoSystemScreenCellsSettings"
}

/**
 * Команда навигации по имени.
 *
 * Нужна там, где команда приходит строкой, а не из кода: параметр запуска для скриншотов
 * вёрстки. Команды с аргументами берутся с аргументами по умолчанию: редактор мира — со
 * случайным сидом, симуляция — пустой мир без рельефа и без теста генома.
 */
fun navigationCommandByName(name: String): NavigationCommands? = when (name.trim()) {
    GoMenu.name -> GoMenu
    "GoWorldEditor" -> GoWorldEditor(WorldEditorIntent.newSeed().seed)
    GoSettings.name -> GoSettings
    GoEcoSystem.name -> GoEcoSystem
    GoSupport.name -> GoSupport
    "GoGenomeEditor" -> GoGenomeEditor(null)
    GoGenomes.name -> GoGenomes
    GoGuide.name -> GoGuide
    GoTutorial.name -> GoTutorial
    "GoSimulation" -> GoSimulation(world = null, genomeName = null)
    GoLevelEditor.name -> GoLevelEditor
    GoCampaign.name -> GoCampaign
    GoBack.name -> GoBack
    GoExit.name -> GoExit
    else -> null
}
