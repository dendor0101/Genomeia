package io.github.some_example_name.old.features.menu

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.math.MathUtils
import io.github.some_example_name.old.commands.GoExit
import io.github.some_example_name.old.commands.GoGenomeEditor
import io.github.some_example_name.old.commands.GoSettings
import io.github.some_example_name.old.commands.GoSupport
import io.github.some_example_name.old.commands.GoWorldEditor
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DISingleThreadSimulationContainer
import io.github.some_example_name.old.core.log.ActionLog
import io.github.some_example_name.old.features.worldeditor.WorldGenerator
import io.github.some_example_name.old.features.worldeditor.WorldTerrainManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.render.RenderSystem
import io.github.some_example_name.old.systems.simulation.SingleThreadSimulationSystem

/** Что игрок хочет сделать в главном меню. */
sealed interface MenuIntent {
    val name: String
    val detail: String get() = ""

    data object OpenWorldEditor : MenuIntent {
        override val name get() = "OpenWorldEditor"
    }

    /** Нажали «Редактор» — дальше или список геномов, или сразу пустой редактор. */
    data object BrowseGenomes : MenuIntent {
        override val name get() = "BrowseGenomes"
    }

    data class OpenGenomeEditor(val genomeName: String?) : MenuIntent {
        override val name get() = "OpenGenomeEditor"
        override val detail get() = genomeName ?: "new"
    }

    data object OpenSettings : MenuIntent {
        override val name get() = "OpenSettings"
    }

    data object OpenSupport : MenuIntent {
        override val name get() = "OpenSupport"
    }

    data object Exit : MenuIntent {
        override val name get() = "Exit"
    }
}

/** Что экран должен сделать со своей стороны в ответ на намерение. */
sealed interface MenuEffect {
    data object None : MenuEffect

    /** Показать диалог выбора генома — это чистая вёрстка, ViewModel её не открывает. */
    data class ShowGenomeList(val genomes: List<String>) : MenuEffect
}

class MenuViewModel(
    val simulationSystem: SingleThreadSimulationSystem = DISingleThreadSimulationContainer.simulationSystem,
    val renderSystem: RenderSystem = DISingleThreadSimulationContainer.renderSystem,
    val userCommandManager: UserCommandManager = DISingleThreadSimulationContainer.userCommandManager,
    val worldTerrainManager: WorldTerrainManager = DISingleThreadSimulationContainer.worldTerrainManager,
    val worldGenerator: WorldGenerator = WorldGenerator(),
    val genomeManager: GenomeManager = DISingleThreadSimulationContainer.genomeManager
) {

    private val navigation = DIGameGlobalContainer.navigationCommandsManager

    private var tiltX = 0f
    private var tiltY = 0f

    private val maxTilt = 1.2f // максимальный «угол» в условных единицах
    private val sensitivity = 3.8f // насколько быстро набирается наклон
    private val damping = 0.92f // затухание (чтобы возвращалось в центр)

    /**
     * Единственный вход для действий игрока в меню.
     *
     * В журнале получается две строки на нажатие: намерение ("нажали Настройки") и переход
     * ("экран стал GoSettings"). Это не дубль — это разные слои, и когда переход когда-нибудь
     * окажется заблокирован или перенаправлен, они разойдутся, и в логе это будет видно.
     */
    fun handle(intent: MenuIntent): MenuEffect {
        ActionLog.record(LOG_SOURCE, intent.name, intent.detail)

        return when (intent) {
            is MenuIntent.OpenWorldEditor -> {
                navigation.performCommand(GoWorldEditor)
                MenuEffect.None
            }

            is MenuIntent.BrowseGenomes -> {
                val genomes = DIGameGlobalContainer.genomeJsonReader.getGenomeFileNamesFromFolder()
                if (genomes.isEmpty()) {
                    handle(MenuIntent.OpenGenomeEditor(null))
                } else {
                    MenuEffect.ShowGenomeList(genomes)
                }
            }

            is MenuIntent.OpenGenomeEditor -> {
                navigation.performCommand(GoGenomeEditor(intent.genomeName))
                MenuEffect.None
            }

            is MenuIntent.OpenSettings -> {
                navigation.performCommand(GoSettings)
                MenuEffect.None
            }

            is MenuIntent.OpenSupport -> {
                navigation.performCommand(GoSupport)
                MenuEffect.None
            }

            is MenuIntent.Exit -> {
                navigation.performCommand(GoExit)
                MenuEffect.None
            }
        }
    }

    fun startMenuSimulation() {
//        val map = worldGenerator.generateWorld(
//            width = DISingleThreadSimulationContainer.gridWidth,
//            height = DISingleThreadSimulationContainer.gridHeight,
//            seed = Random.nextLong()
//        )
//        worldTerrainManager.map = map
//        worldTerrainManager.initWorld(
//            gridWith = DISingleThreadSimulationContainer.gridWidth,
//            gridHeight = DISingleThreadSimulationContainer.gridHeight,
//        )

        genomeManager.loadGenomes()

//        repeat(5) {
//            genomeManager.genomes.forEachIndexed { index, _ ->
//                userCommandManager.push(
//                    cmd = PlayerCommand.Tap(
//                        x = Random.nextFloat() * DISingleThreadSimulationContainer.gridWidth,
//                        y = Random.nextFloat() * DISingleThreadSimulationContainer.gridHeight,
//                        isLeftButton = true,
//                        genomeIndex = index
//                    )
//                )
//            }
//        }

//        userCommandManager.push(
//            cmd = PlayerCommand.Tap(
//                x = /*Random.nextFloat()*/0.5f * DISingleThreadSimulationContainer.gridWidth,
//                y = /*Random.nextFloat()*/0.5f * DISingleThreadSimulationContainer.gridHeight,
//                isLeftButton = true,
//                genomeIndex = 0
//            )
//        )
    }

    fun updateFrame() {
        simulationSystem.updateTick()
        renderSystem.render()
    }

    fun moveCamera(camera: Camera, delta: Float) {
        if (Gdx.input.isPeripheralAvailable(Input.Peripheral.Gyroscope)) {
            val rawX = Gdx.input.gyroscopeX
            val rawY = Gdx.input.gyroscopeY

            // Переназначаем оси в зависимости от текущей ориентации экрана
            val (gyroX, gyroY) = when (Gdx.input.rotation) {
                0 -> rawY to rawX          // Portrait (нормально)
                90 -> -rawX to rawY         // Landscape
                180 -> -rawY to -rawX        // Portrait перевёрнутый
                270 -> rawX to -rawY         // Landscape в другую сторону
                else -> rawY to rawX
            }

            // Интегрируем
            tiltX += gyroX * sensitivity * delta
            tiltY += gyroY * sensitivity * delta

            // Затухание
            tiltX *= damping
            tiltY *= damping

            // Ограничение
            tiltX = MathUtils.clamp(tiltX, -maxTilt, maxTilt)
            tiltY = MathUtils.clamp(tiltY, -maxTilt, maxTilt)

            camera.position.set(24f + tiltX, 24f - tiltY, 0f)
        } else {
            // fallback на мышь
            val x = (Gdx.input.x.toFloat() / Gdx.graphics.width - 0.5f) * 2f
            val y = (Gdx.input.y.toFloat() / Gdx.graphics.height - 0.5f) * 2f
            camera.position.set(24f + x, 24f - y, 0f)
        }
        camera.update()
    }

    private companion object {
        const val LOG_SOURCE = "Menu"
    }
}
