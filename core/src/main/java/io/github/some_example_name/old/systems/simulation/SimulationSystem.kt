package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.core.DIContainer.threadCount
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SimEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem
import io.github.some_example_name.old.systems.render.TripleBufferManager

class SimulationSystem(
    val gridManager: GridManager,
    val worldCommandsManager: WorldCommandsManager,
    val organManager: OrganManager,
    val organEntity: OrganEntity,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val pheromoneEntity: PheromoneEntity,
    val substancesEntity: SubstancesEntity,
    val substrateSettings: SubstrateSettings,
    val threadManager: ThreadManager,
    val genomeManager: GenomeManager,
    val particlePhysicsSystem: ParticlePhysicsSystem,
    val linkPhysicsSystem: LinkPhysicsSystem,
    val simEntity: SimEntity,
    val tripleBufferManager: TripleBufferManager,
    val cellSystem: CellSystem,
    val userCommandManager: UserCommandManager
) {

    val simulationThread = Thread { threadManager.runUpdateLoop { updateTick() } }

    fun startThread() {
        if (!threadManager.isRunning) {
            threadManager.isRunning = true
            simulationThread.start()
        }
    }

    fun updateTick() {
        if (simEntity.isFinish) {
            dispose()
        }
        if (simEntity.isRestart) {
            restartSim()
        }

        simEntity.tickCounter++
        simEntity.timeSimulation += DELTA_SIM_TICK_TIME

        processParticleCollision()
        linkPhysicsSystem.iterateLinks()
        cellSystem.iterateCell()

        arrangementOfPositionsInTheGrid()
        tripleBufferManager.updateAndCommitProducer()
        worldCommandsManager.executingCommandsFromTheWorld()
        organManager.performOrgansNextStage()
        userCommandManager.processingCommandsFromUser()
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
                for (i in 0..<worldCommandsManager.oddCounter[chunk]) {
                    particlePhysicsSystem.moveParticle(worldCommandsManager.oddChunkPositionStack[chunk][i])
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        for (chunk in 0..<threadCount) {
            threadManager.futures.add(threadManager.executor.submit {
                for (i in 0..<worldCommandsManager.evenCounter[chunk]) {
                    particlePhysicsSystem.moveParticle(worldCommandsManager.evenChunkPositionStack[chunk][i])
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        worldCommandsManager.oddCounter.fill(0)
        worldCommandsManager.evenCounter.fill(0)
    }

    fun stopUpdateThread() {
        simulationThread.interrupt()
        try {
            simulationThread.join(1000) // ждём до 1 секунды // wait up to 1 second
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        threadManager.dispose()
    }

    fun dispose() {
        gridManager.clearAll()
        cellEntity.clear()
        linkEntity.clear()
        organEntity.clear()
        particleEntity.clear()
        substancesEntity.clear()
        simEntity.clear()
        organManager.clear()
    }

    private fun restartSim() {
        dispose()
        simEntity.isRestart = false
    }

    companion object {
        const val DELTA_SIM_TICK_TIME = 0.016666666f
    }
}
