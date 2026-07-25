package com.example.concurrent

import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.WorldResizable
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.features.worldeditor.WorldTerrainManager
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem
import io.github.some_example_name.old.systems.physics.MovementManager
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem
import io.github.some_example_name.old.systems.render.RenderBufferManager
import io.github.some_example_name.old.systems.simulation.Simulation
import io.github.some_example_name.old.systems.simulation.SimulationData
import it.unimi.dsi.fastutil.ints.IntArrayList

class SimulationSystem(
    val gridManager: GridManager,
    val worldCommandsManager: WorldCommandsManager,
    val organManager: OrganManager,
    val cellEntity: CellEntity,
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
): Simulation, WorldResizable {

    private val threadManager: ThreadManager = ThreadManager(
        simulationData = simulationData
    )

    private var simulationThread: Thread? = null

    override fun start() {
        worldTerrainManager.initWorld()
        if (!threadManager.isRunning) {
            threadManager.isRunning = true

            simulationThread = Thread { threadManager.runUpdateLoop { updateTick() } }.apply {
                isDaemon = true
                name = "Simulation-Main-Thread"
            }
            simulationThread?.start()
        }
    }

    override fun updateTick() {
        if (simulationData.isFinish) {
            dispose()
        }
        if (simulationData.isRestart) {
            restartSim()
        }

        simulationData.tickCounter++
        simulationData.timeSimulation += Simulation.DELTA_SIM_TICK_TIME

        iterateLinksInParallel()
        processParticleCollision()
        iterateCellInParallel()
        pheromonesManager.iterate()
        arrangementOfPositionsInTheGrid()

        worldCommandsManager.executingCommandsFromTheWorld()
        organManager.performOrgansNextStage()
        userCommandManager.processingCommandsFromUser()
        worldCommandsManager.executingLastCommandsFromTheWorld()

        renderBufferManager.updateBuffer()
    }

    private fun iterateLinksInParallel() {
        processPhase(worldCommandsManager.oddLinkLists)
        processPhase(worldCommandsManager.evenLinkLists)
    }

    private fun processPhase(lists: Array<IntArrayList>) {
        threadManager.futures.clear()
        for (t in 0 until DISimulationContainer.threadCount) {
            threadManager.futures.add(
                threadManager.executor.submit {
                    val list = lists[t]
                    for (i in list.indices) {
                        val linkIndex = list.getInt(i)
                        linkPhysicsSystem.processLink(linkIndex,t)
                    }
                }
            )
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()
    }

    private fun iterateCellInParallel() = with(cellEntity) {
        val size = aliveList.size
        if (size == 0) return@with
        val chunkSize = (size + DISimulationContainer.threadCount - 1) / DISimulationContainer.threadCount
        for (threadId in 0 until DISimulationContainer.threadCount) {
            val start = threadId * chunkSize
            val end = minOf(start + chunkSize, size)

            if (start >= end) break

            val future = threadManager.executor.submit {
                for (i in start until end) {
                    val cellIndex = aliveList.getInt(i)
                    cellSystem.processCell(cellIndex, threadId)
                }
            }
            threadManager.futures.add(future)
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()
    }

    private fun processParticleCollision() {
        threadManager.runChunkStage(isOdd = true) { start, end, threadId ->
            particlePhysicsSystem.processGridChunkPhysics(start, end, threadId, isOdd = true)
        }
        threadManager.runChunkStage(isOdd = false) { start, end, threadId ->
            particlePhysicsSystem.processGridChunkPhysics(start, end, threadId, isOdd = false)
        }
    }

    private fun arrangementOfPositionsInTheGrid() {
        for (chunk in 0..<DISimulationContainer.threadCount) {
            threadManager.futures.add(threadManager.executor.submit {
                for (i in 0..<worldCommandsManager.oddCellCounter[chunk]) {
                    movementManager.moveParticle(worldCommandsManager.oddCellChunkPositionStack[chunk][i], chunk)
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        for (chunk in 0..<DISimulationContainer.threadCount) {
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

    override fun stop() {
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

    override fun dispose() {
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

    override fun resize() {
        threadManager.resize()
    }
}
