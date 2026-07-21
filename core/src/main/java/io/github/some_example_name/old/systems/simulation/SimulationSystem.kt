package io.github.some_example_name.old.systems.simulation

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem
import io.github.some_example_name.old.systems.render.RenderBufferManager
import io.github.some_example_name.old.systems.render.RenderSystem
import io.github.some_example_name.old.systems.render.ShaderManager
import io.github.some_example_name.old.ui.screens.GlobalSettings.DEBUG_MODE
import io.github.some_example_name.old.ui.screens.GlobalSettings.GRID_HEIGHT
import io.github.some_example_name.old.ui.screens.GlobalSettings.GRID_WIDTH
import kotlin.random.Random

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
    val simulationData: SimulationData,
    val cellSystem: CellSystem,
    val userCommandManager: UserCommandManager,
    val shaderManager: ShaderManager,
    val renderSystem: RenderSystem,
    val entityList: List<Entity>,
    val renderBufferManager: RenderBufferManager,
    val pheromonesManager: PheromonesManager
) {

    private var simulationThread: Thread? = null
    private var map: Array<BooleanArray>? = null

    private var logPlay = true //специальная переменная для запуска логов, изначально false

    fun startThread() {
        if (!threadManager.isRunning) {
            threadManager.isRunning = true

            if(!DEBUG_MODE && logPlay) {
                DISimulationContainer.logReplay.play()
            }

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

//        if (DEBUG_MODE) { уже не нужно
//            try {
//                val x = cellEntity.getX(0) //для particle заменить на particleEntity
//                val y = cellEntity.getY(0)
//                DISimulationContainer.logSaver.saveDebug(x, y, DISimulationContainer.simulationData.tickCounter)
//            }
//            catch (e: Exception) {
//
//            }
//        }

//        try { не нужно
//            val x = cellEntity.getX(0)
//            val y = cellEntity.getY(0)
//
//            if (DISimulationContainer.logReplay.startReplayTick != -1) {
//                var currentTick = DISimulationContainer.simulationData.tickCounter
//                val (logX, logY) = DISimulationContainer.logReplay.coordList[currentTick] ?: Pair(0f,0f)
//                println("Tick: ${currentTick}, x compare: ${x==logX}, x: ${x}, logX: ${logX}")
//                print("y compare: ${y==logY}, y: ${y}, logY: ${logY}")
//            }
//        }
//        catch (e: Throwable) {
//            println("nah")
//        }

        simulationData.tickCounter++
        simulationData.timeSimulation += DELTA_SIM_TICK_TIME

        if (!DEBUG_MODE && logPlay) {
            DISimulationContainer.logReplay.updateReplay()
        }

        linkPhysicsSystem.iterateLinks()
        processParticleCollision()
        cellSystem.iterateCell()
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
                    particlePhysicsSystem.moveParticle(worldCommandsManager.oddCellChunkPositionStack[chunk][i], chunk)
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        for (chunk in 0..<threadCount) {
            threadManager.futures.add(threadManager.executor.submit {
                for (i in 0..<worldCommandsManager.evenCellCounter[chunk]) {
                    particlePhysicsSystem.moveParticle(worldCommandsManager.evenCellChunkPositionStack[chunk][i], chunk)
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
        DISimulationContainer.logSaver.close()
    }

    private fun restartSim() {
        dispose()
        simulationData.isRestart = false
        initWorld(map)
    }

    fun getColor(random: Random): Int {
        val r = random.nextFloat() * 0.25f + 0.1f   // 0.1 - 0.35
        val g = random.nextFloat() * 0.5f + 0.5f    // 0.5 - 1.0
        val b = random.nextFloat() * 0.2f + 0.05f   // 0.05 - 0.25

        return Color(r, g, b, 1f).toIntBits()
    }
//    fun getColor(random: Random) = leafColors[random.nextInt(6)].toIntBits()

    fun initWorld(map: Array<BooleanArray>?) {
        this.map = map
        val random = Random(3)
        if (map != null) {
            for (y in 0 until map.size) {
                for (x in 0 until map[y].size) {

                    val scalex = x.toFloat() / 1.5f
                    val scaley = y.toFloat() / 1.5f
                    if (x == 0 || x == map.size - 1 || y == 0 || y == map[y].size - 1) continue
                    if (map[y][x]) {
                        if (scalex < GRID_WIDTH && scaley < GRID_HEIGHT) {
                            particleEntity.addParticle(
                                x = scalex,
                                y = scaley,
                                radius = 0.5f,
                                color = getColor(random),
                                isCell = false,
                                isSub = false,
                                holderEntityIndex = -1,
                                dragCoefficient = 0.4f
                            )
                        }

                    } else {
//                    if (random.nextInt(60) == 1) {
////                        subManager.addCell(
////                            x * WorldEditorScreen.SCALE_FACTOR + WorldEditorScreen.OFFSET + random.nextDouble(-10.0, 10.0).toFloat(),
////                            y * WorldEditorScreen.SCALE_FACTOR + WorldEditorScreen.OFFSET + random.nextDouble(-10.0, 10.0).toFloat(),
////                            0f, 0f
////                        )
//
//                        substancesEntity.addSubstance(
//                            x = scalex,
//                            y = scaley,
//                            color = Color.RED.toIntBits(),
//                            radius = 0.25f,
//                            subType = 0.toByte(),
//                        )
//                    }
                    }
                }
            }
        }
        for (i in 1..<(GRID_WIDTH / 2 * 3)) {
            particleEntity.addParticle(
                x = i.toFloat() / 1.5f,
                y = 0.5f,
                radius = 0.5f,
                color = getColor(random),
                isCell = false,
                isSub = false,
                holderEntityIndex = -1,
                dragCoefficient = 1.0f
            )
        }
        for (i in 1..<(GRID_HEIGHT / 2 * 3)) {
            particleEntity.addParticle(
                x = 0.5f,
                y = i.toFloat() / 1.5f,
                radius = 0.5f,
                color = getColor(random),
                isCell = false,
                isSub = false,
                holderEntityIndex = -1,
                dragCoefficient = 1.0f
            )
        }

        for (i in 1..<(GRID_HEIGHT / 2 * 3)) {
            particleEntity.addParticle(
                x = GRID_WIDTH - 0.5f,
                y = i.toFloat() / 1.5f,
                radius = 0.5f,
                color = getColor(random),
                isCell = false,
                isSub = false,
                holderEntityIndex = -1,
                dragCoefficient = 1.0f
            )
        }
        for (i in 1..<(GRID_WIDTH / 2 * 3)) {
            particleEntity.addParticle(
                x = i.toFloat() / 1.5f,
                y = GRID_HEIGHT - 0.5f,
                radius = 0.5f,
                color = getColor(random),
                isCell = false,
                isSub = false,
                holderEntityIndex = -1,
                dragCoefficient = 1.0f
            )
        }
    }


    companion object {
        const val DELTA_SIM_TICK_TIME = 0.016666666f
    }
}
