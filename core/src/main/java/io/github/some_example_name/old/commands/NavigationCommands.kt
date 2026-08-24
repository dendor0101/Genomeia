package io.github.some_example_name.old.commands

import io.github.some_example_name.old.features.worldeditor.WorldSpec

sealed class NavigationCommands {
    /**
     * Стабильное имя команды.
     *
     * Именно оно уходит в журнал и понимается [navigationCommandByName] — поэтому это
     * не `this::class.simpleName`: имена классов режет минификатор, а лог краша должен
     * читаться и в релизной сборке.
     */
    abstract val name: String

    /** Короткая сводка параметров для журнала. Сводка, а не дамп. */
    open val detail: String get() = ""
}

val NavigationCommands.logName: String
    get() = if (detail.isEmpty()) name else "$name($detail)"

object GoExit : NavigationCommands() {
    override val name get() = "GoExit"
}

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
class GoReplace(val target: Menu) : NavigationCommands() {
    override val name get() = "GoReplace"
    override val detail get() = target.logName
}

sealed class Menu : NavigationCommands()

object GoMenu : Menu() {
    override val name get() = "GoMenu"
}

object GoWorldEditor : Menu() {
    override val name get() = "GoWorldEditor"
}

class GoGenomeEditor(val genomeName: String?) : Menu() {
    override val name get() = "GoGenomeEditor"
    override val detail get() = genomeName ?: "new"
}

object GoSettings : Menu() {
    override val name get() = "GoSettings"
}

object GoEcoSystem : Menu() {
    override val name get() = "GoEcoSystem"
}

object GoSupport : Menu() {
    override val name get() = "GoSupport"
}

class GoSimulation(
    /** Рецепт мира, а не готовая карта: см. [WorldSpec]. null — мир из редактора генома. */
    val world: WorldSpec?,
    val genomeName: String?
) : Menu() {
    override val name get() = "GoSimulation"
    override val detail
        get() = listOfNotNull(world?.detail, genomeName).joinToString(", ").ifEmpty { "-" }
}

object EcoSystemScreenGlobalSettings : Menu() {
    override val name get() = "EcoSystemScreenGlobalSettings"
}

object EcoSystemScreenCellsSettings : Menu() {
    override val name get() = "EcoSystemScreenCellsSettings"
}

/**
 * Команда навигации по имени.
 *
 * Нужна там, где команда приходит строкой, а не из кода: параметр запуска для скриншотов
 * вёрстки, а в перспективе — воспроизведение записанного журнала действий.
 * Команды с обязательными аргументами (GoSimulation) сюда намеренно не попадают.
 */
fun navigationCommandByName(name: String): NavigationCommands? = when (name.trim()) {
    GoMenu.name -> GoMenu
    GoWorldEditor.name -> GoWorldEditor
    GoSettings.name -> GoSettings
    GoEcoSystem.name -> GoEcoSystem
    GoSupport.name -> GoSupport
    "GoGenomeEditor" -> GoGenomeEditor(null)
    GoBack.name -> GoBack
    GoExit.name -> GoExit
    else -> null
}
