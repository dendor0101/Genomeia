package io.github.some_example_name.old.commands

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.SessionJournal
import io.github.some_example_name.old.features.devsupport.SupportScreen
import io.github.some_example_name.old.features.ecosystem.EcoSystemScreen
import io.github.some_example_name.old.features.editor.GenomeEditorScreen
import io.github.some_example_name.old.features.guide.GuideScreen
import io.github.some_example_name.old.features.menu.MenuScreen
import io.github.some_example_name.old.features.settings.SettingsScreen
import io.github.some_example_name.old.features.simulation.SimulationScreen
import io.github.some_example_name.old.features.worldeditor.WorldEditorScreen

class NavigationCommandsManager {

    private val screenStack = ArrayDeque<Screen>()

    /**
     * Стек КОМАНД параллельно стеку экранов.
     *
     * Экраны — живые объекты, в краш-репорт их не положишь. Команды — это ровно тот
     * маршрут, которым игрок сюда пришёл, и стоит он один указатель на переход.
     */
    private val commandStack = ArrayDeque<NavigationCommands>()

    /** Команда, которой открыт текущий экран. null — стартовый экран, выставленный игрой. */
    private var currentCommand: NavigationCommands? = null

    /**
     * Побочный канал: сюда уходит каждая выполненная команда вместе с ИМЕНЕМ экрана, на котором
     * в итоге оказались. Второй параметр важен для [GoBack]: сама команда не говорит, куда
     * ушли, — это знает только стек, а без назначения запись в журнале не проверяема.
     */
    var onCommand: ((command: NavigationCommands, destination: String) -> Unit)? = null

    /** Маршрут игрока по экранам снизу вверх — для краш-репорта. */
    fun currentPath(): List<String> =
        commandStack.map { it.logName } + listOfNotNull(currentCommand?.logName)

    fun performCommand(command: NavigationCommands) =
        // В ActionLog переход пишет onCommand — уже с тем, куда в итоге попали.
        SessionJournal.execute(CommandSource.NAVIGATION, command, logAction = false) { perform(it) }

    private fun perform(navigationCommands: NavigationCommands) {
        val destination = when (navigationCommands) {
            is Menu -> {
                val old = game.screen
                if (old != null) {
                    screenStack.addLast(old)
                    // Стартовый экран игры выставлен мимо менеджера, поэтому у него нет
                    // своей команды — записываем его как GoMenu, чем он и является.
                    commandStack.addLast(currentCommand ?: GoMenu)
                }

                currentCommand = navigationCommands
                game.screen = createScreen(navigationCommands)
                navigationCommands.name
            }

            is GoReplace -> {
                val old = game.screen
                clearStack()
                currentCommand = navigationCommands.target
                game.screen = createScreen(navigationCommands.target)
                old?.dispose()
                navigationCommands.target.name
            }

            GoBack -> {
                if (screenStack.isNotEmpty()) {
                    val current = game.screen
                    game.screen = screenStack.removeLast()
                    currentCommand = commandStack.removeLastOrNull()
                    current?.dispose()
                    currentCommand?.name ?: GoMenu.name
                } else {
                    exitGame()
                    EXIT
                }
            }

            GoExit -> {
                exitGame()
                EXIT
            }
        }

        onCommand?.invoke(navigationCommands, destination)
    }

    private fun createScreen(command: Menu): Screen = when (command) {
        GoMenu -> MenuScreen()
        GoEcoSystem -> EcoSystemScreen()
        is GoGenomeEditor -> GenomeEditorScreen(command.genomeName)
        GoGuide -> GuideScreen()
        GoTutorial -> GenomeEditorScreen(genomeName = null, isTutorial = true)
        GoSettings -> SettingsScreen()
        is GoWorldEditor -> WorldEditorScreen(command.seed)
        GoSupport -> SupportScreen()
        EcoSystemScreenCellsSettings -> TODO()
        EcoSystemScreenGlobalSettings -> TODO()
        is GoSimulation -> SimulationScreen(command.world, command.genomeName, command.savedWorld)
    }

    /**
     * Выход из игры. При повторе бага не выходим: игрок вышел, а разработчику надо
     * рассмотреть, чем всё кончилось.
     */
    private fun exitGame() {
        if (BugReplay.isActive) {
            BugReplay.note("игрок вышел из игры — здесь запись кончается")
            return
        }
        clearStack()
        // Текущий экран здесь НЕ освобождается: Gdx.app.exit() на всех платформах доходит
        // до MyGame.dispose(), а тот освобождает экран сам. Раньше он освобождался дважды,
        // и кнопка «Выход» в меню падала на втором Stage.dispose (буфер батча уже отдан).
        Gdx.app.exit()
    }

    private fun clearStack() {
        screenStack.forEach { it.dispose() }
        screenStack.clear()
        commandStack.clear()
    }

    private companion object {
        const val EXIT = "exit"
    }
}
