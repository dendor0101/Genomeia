package io.github.some_example_name.old.commands

sealed class NavigationCommands


object GoExit: NavigationCommands()
object GoBack: NavigationCommands()

sealed class Menu: NavigationCommands()

object GoWorldEditor: Menu()
class GoGenomeEditor(val genomeName: String?): Menu()
object GoSettings: Menu()
object GoEcoSystem: Menu()
object GoSupport: Menu()
class GoSimulation(
    val map: Array<BooleanArray>?,
    val genomeName: String?
): Menu()
object EcoSystemScreenGlobalSettings: Menu()
object EcoSystemScreenCellsSettings: Menu()

/**
 * Команда навигации по имени, как она пишется в коде: "GoWorldEditor", "GoSettings".
 *
 * Нужна там, где команда приходит строкой, а не из кода: параметр запуска для скриншотов
 * вёрстки, а в перспективе — воспроизведение записанного лога действий игрока.
 * Команды с обязательными аргументами (GoSimulation) сюда намеренно не попадают.
 */
fun navigationCommandByName(name: String): NavigationCommands? = when (name.trim()) {
    "GoWorldEditor" -> GoWorldEditor
    "GoSettings" -> GoSettings
    "GoEcoSystem" -> GoEcoSystem
    "GoSupport" -> GoSupport
    "GoGenomeEditor" -> GoGenomeEditor(null)
    "GoBack" -> GoBack
    "GoExit" -> GoExit
    else -> null
}
