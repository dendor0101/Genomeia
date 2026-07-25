package io.github.some_example_name.old.features.menu

import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.core.DISingleThreadSimulationContainer
import io.github.some_example_name.old.features.worldeditor.WorldGenerator
import io.github.some_example_name.old.features.worldeditor.WorldTerrainManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.render.RenderSystem
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.systems.simulation.SingleThreadSimulationSystem
import kotlin.random.Random

class MenuViewModel(
    val simEntity: SimulationData = DISingleThreadSimulationContainer.simulationData,
    val simulationSystem: SingleThreadSimulationSystem = DISingleThreadSimulationContainer.simulationSystem,
    val renderSystem: RenderSystem = DISingleThreadSimulationContainer.renderSystem,
    val userCommandManager: UserCommandManager = DISingleThreadSimulationContainer.userCommandManager,
    val worldTerrainManager: WorldTerrainManager = DISingleThreadSimulationContainer.worldTerrainManager,
    val worldGenerator: WorldGenerator = WorldGenerator(),
    val genomeManager: GenomeManager = DISingleThreadSimulationContainer.genomeManager
) {

    private val rng = java.util.Random()

    fun startMenuSimulation() {
        val map = worldGenerator.generateWorld(
            width = DISingleThreadSimulationContainer.gridWidth,
            height = DISingleThreadSimulationContainer.gridHeight,
            seed = rng.nextLong()
        )
        worldTerrainManager.map = map
        worldTerrainManager.initWorld(
            gridWith = DISingleThreadSimulationContainer.gridWidth,
            gridHeight = DISingleThreadSimulationContainer.gridHeight,
        )

        genomeManager.loadGenomes()

        repeat(5) {
            genomeManager.genomes.forEachIndexed { index, _ ->
                userCommandManager.push(
                    cmd = PlayerCommand.Tap(
                        x = rng.nextFloat() * DISingleThreadSimulationContainer.gridWidth,
                        y = rng.nextFloat() * DISingleThreadSimulationContainer.gridHeight,
                        isLeftButton = true,
                        genomeIndex = index
                    )
                )
            }
        }
    }

    fun updateFrame() {
        simulationSystem.updateTick()
        renderSystem.render()
    }
}
