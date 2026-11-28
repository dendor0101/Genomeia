package io.github.some_example_name.old.commands

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.features.levels.editor.LevelInfo
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.SessionJournal
import io.github.some_example_name.old.features.devsupport.SupportScreen
import io.github.some_example_name.old.features.ecosystem.EcoSystemScreen
import io.github.some_example_name.old.features.editor.GenomeEditorScreen
import io.github.some_example_name.old.features.genomes.GenomesScreen
import io.github.some_example_name.old.features.guide.GuideScreen
import io.github.some_example_name.old.features.levels.CampaignScreen
import io.github.some_example_name.old.features.levels.editor.LevelEditorScreen
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

    /**
     * Команда экрана под текущим — туда вернёт [GoBack]. null — под текущим ничего нет.
     * Так редактор генома узнаёт, что открыт из мира и после сохранения вернётся в него.
     */
    fun previousCommand(): NavigationCommands? = commandStack.lastOrNull()

    fun performCommand(command: NavigationCommands) =
        // В ActionLog переход пишет onCommand — уже с тем, куда в итоге попали.
        SessionJournal.execute(CommandSource.NAVIGATION, command, logAction = false) { perform(it) }

    private fun perform(navigationCommands: NavigationCommands) {
        val target = when (navigationCommands) {
            is Menu -> navigationCommands
            is GoReplace -> navigationCommands.target
            else -> null
        }
        val factory = target?.let { screenFactory(it) }
        if (target != null && factory == null) return
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
                game.screen = factory!!.invoke()
                navigationCommands.name
            }

            is GoReplace -> {
                val old = game.screen
                clearStack()
                currentCommand = navigationCommands.target
                game.screen = factory!!.invoke()
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
                } else if (game.screen is SimulationScreen) {
                    // Уровень мог быть запущен через GoReplace (например, «Сохранить и
                    // протестировать» из редактора генома). Стека в этом случае нет, но
                    // «Назад» в симуляции не должен закрывать игру — уводим в меню.
                    val old = game.screen
                    clearStack()
                    currentCommand = GoMenu
                    game.screen = MenuScreen()
                    old?.dispose()
                    GoMenu.name
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

    private fun screenFactory(command: Menu): (() -> Screen)? = when (command) {
        GoMenu -> ::MenuScreen
        GoEcoSystem -> ::EcoSystemScreen
        is GoGenomeEditor -> ({ GenomeEditorScreen(command.genomeName) })
        GoGenomes -> ::GenomesScreen
        GoGuide -> ::GuideScreen
        GoTutorial -> ({ GenomeEditorScreen(genomeName = null, isTutorial = true) })
        GoSettings -> ::SettingsScreen
        is GoWorldEditor -> ({ WorldEditorScreen(command.seed, levelMap = command.levelMap) })
        GoSupport -> ::SupportScreen
        EcoSystemScreenCellsSettings, EcoSystemScreenGlobalSettings -> null
        GoCampaign -> ::CampaignScreen
        GoLevelEditor -> ({
            // Редактор уровней открывается из кампании, где мог только что идти уровень:
            // ни его настройки, ни урезанная палитра клеток не переезжают в редактор.
            DISimulationContainer.simulationData.resetLevel()
            DIGenomeEditorContainer.cellPalette = CellPalette.FULL
            LevelEditorScreen()
        })
        is GoSimulation -> ({
            applyLevel(command.level)
            // Карта уровня — внутри LevelInfo, в GoSimulation.world её не дублируем.
            SimulationScreen(command.level?.world ?: command.world, command.genomeName, command.savedWorld)
        })
    }

    /**
     * Состояние уровня перед сборкой экрана симуляции.
     *
     * Раньше это делал сам экран уровня до [performCommand], поэтому в журнал попадала
     * только команда «запустить мир»: повтор .bug из уровня поднимал обычный мир, без lua
     * и цели, и хэши расходились. Теперь уровень едет в [GoSimulation], а состояние
     * ставится здесь — одинаково для живой игры и для повтора.
     */
    private fun applyLevel(level: LevelInfo?) {
        val data = DISimulationContainer.simulationData
        // Мир без уровня сбрасывает флаг: иначе следующий запуск после уровня остался бы
        // уровнем со старым LevelInfo.
        data.isLevel = level != null
        if (level == null) return
        data.level = level

        if (level.hasLua) {
            DIGameGlobalContainer.luaEnv.setScript(level.logic)
        }

        val allowed = level.allowedCells.filterValues { it }.keys
        // Пустая карта — старая запись «разрешено всё». Карта, в которой все значения
        // false, — это явное «ни одной клетки»; её нельзя путать с FULL, иначе редактор
        // уровня с выключенными галочками внезапно разрешал бы все клетки.
        DIGenomeEditorContainer.cellPalette = if (level.allowedCells.isEmpty()) {
            CellPalette.FULL
        } else {
            // Уровень ограничивает клетки, а не формулы: рецепты гайдов («time» → «sin»,
            // осциллятор для присоски) иначе в уровне не собрать. Выведенные формулы всё
            // равно отсекает сам CellPalette, а сама палитра на симуляцию не влияет.
            CellPalette.only(allowed, Formulas.all().map { it.key }.toSet())
        }
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
