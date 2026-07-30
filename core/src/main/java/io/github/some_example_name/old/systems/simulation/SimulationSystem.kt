package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem
import io.github.some_example_name.old.systems.render.RenderBufferManager
import io.github.some_example_name.old.features.worldeditor.WorldTerrainManager
import io.github.some_example_name.old.systems.genomics.NeuralLinkManager
import io.github.some_example_name.old.systems.physics.MovementManager

class SimulationSystem(
    val gridManager: GridManager,
    val worldCommandsManager: WorldCommandsManager,
    val organManager: OrganManager,
    val organEntity: OrganEntity,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val neuralLinkEntity: NeuralLinkEntity,
    val neuralLinkManager: NeuralLinkManager,
    val particleEntity: ParticleEntity,
    val pheromoneEntity: PheromoneEntity,
    val substrateSettings: SubstrateSettings,
    val threadManager: ThreadManager,
    val genomeManager: GenomeManager,
    val particlePhysicsSystem: ParticlePhysicsSystem,
    val linkPhysicsSystem: LinkPhysicsSystem,
    val simulationData: SimulationData,
    val cellSystem: CellSystem,
    val userCommandManager: UserCommandManager,
    val entityList: List<Entity>,
    val renderBufferManager: RenderBufferManager,
    val pheromonesManager: PheromonesManager,
    val movementManager: MovementManager,
    val worldTerrainManager: WorldTerrainManager
) {

    private var simulationThread: Thread? = null

    fun startThread() {
        if (!threadManager.isRunning) {
            threadManager.isRunning = true

            simulationThread = Thread { threadManager.runUpdateLoop { updateTick() } }.apply {
                isDaemon = true
                name = "Simulation-Main-Thread"
            }
            simulationThread?.start()
        }
    }

    fun updateTick() {
        if (simulationData.isFinish) {
            dispose()
        }
        if (simulationData.isRestart) {
            restartSim()
        }

        simulationData.tickCounter++
        simulationData.timeSimulation += DELTA_SIM_TICK_TIME

        linkPhysicsSystem.iterateLinksInParallel()

        neuralLinkManager.iterate()

        processParticleCollision()
        cellSystem.iterateCellInParallel()
        pheromonesManager.iterate()
        arrangementOfPositionsInTheGrid()

        worldCommandsManager.executingCommandsFromTheWorld()
        organManager.performOrgansNextStage()
        userCommandManager.processingCommandsFromUser()
        worldCommandsManager.executingLastCommandsFromTheWorld()

        renderBufferManager.updateBuffer()
    }

    fun processParticleCollision() {
        threadManager.runChunkStage(isOdd = true) { start, end, threadId ->
            particlePhysicsSystem.processGridChunkPhysics(start, end, threadId, isOdd = true)
        }
        threadManager.runChunkStage(isOdd = false) { start, end, threadId ->
            particlePhysicsSystem.processGridChunkPhysics(start, end, threadId, isOdd = false)
        }
    }

    fun arrangementOfPositionsInTheGrid() {
        for (chunk in 0..<threadCount) {
            threadManager.futures.add(threadManager.executor.submit {
                for (i in 0..<worldCommandsManager.oddCellCounter[chunk]) {
                    movementManager.moveParticle(worldCommandsManager.oddCellChunkPositionStack[chunk][i], chunk)
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        for (chunk in 0..<threadCount) {
            threadManager.futures.add(threadManager.executor.submit {
                for (i in 0..<worldCommandsManager.evenCellCounter[chunk]) {
                    movementManager.moveParticle(worldCommandsManager.evenCellChunkPositionStack[chunk][i], chunk)
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        worldCommandsManager.oddCellCounter.fill(0)
        worldCommandsManager.evenCellCounter.fill(0)
    }

    fun stopUpdateThread() {
        threadManager.stopSimulationLoop()

        simulationThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }

        threadManager.futures.clear()
    }

    fun dispose() {
        gridManager.clearAll()
        entityList.forEach { it.clear() }
        simulationData.clear()
        worldCommandsManager.dispose()
    }

    private fun restartSim() {
        dispose()
        simulationData.isRestart = false
        worldTerrainManager.initWorld()
    }

    fun initMap() {
        worldTerrainManager.initWorld()
    }

    companion object {
        const val DELTA_SIM_TICK_TIME = 0.016666666f
    }
}
