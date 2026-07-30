package io.github.some_example_name.old.commands

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.features.devsupport.SupportScreen
import io.github.some_example_name.old.features.ecosystem.EcoSystemScreen
import io.github.some_example_name.old.features.editor.GenomeEditorScreen
import io.github.some_example_name.old.features.settings.SettingsScreen
import io.github.some_example_name.old.features.simulation.SimulationScreen
import io.github.some_example_name.old.features.worldeditor.WorldEditorScreen

class NavigationCommandsManager {
    private val navigationStack = ArrayDeque<Screen>()

    fun performCommand(navigationCommands: NavigationCommands) {
        when (navigationCommands) {
            is Menu -> {
                val old = game.screen
                if (old != null) {
                    navigationStack.addLast(old)
                }

                game.screen = when (navigationCommands) {
                    GoEcoSystem -> EcoSystemScreen()
                    is GoGenomeEditor -> GenomeEditorScreen(navigationCommands.genomeName)
                    GoSettings -> SettingsScreen()
                    GoWorldEditor -> WorldEditorScreen()
                    GoSupport -> SupportScreen()
                    EcoSystemScreenCellsSettings -> TODO()
                    EcoSystemScreenGlobalSettings -> TODO()
                    is GoSimulation -> SimulationScreen(navigationCommands.map, navigationCommands.genomeName)
                }
            }

            GoBack -> {
                if (navigationStack.isNotEmpty()) {
                    val current = game.screen
                    game.screen = navigationStack.removeLast()
                    current?.dispose()
                } else {
                    navigationStack.forEach { it.dispose() }
                    navigationStack.clear()
                    game.screen?.dispose()
                    Gdx.app.exit()
                }
            }

            GoExit -> {
                navigationStack.forEach { it.dispose() }
                navigationStack.clear()
                Gdx.app.exit()
            }
        }
    }
}
