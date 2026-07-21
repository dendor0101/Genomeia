package io.github.some_example_name.old.commands

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.RandomXS128
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.core.DISimulationContainer.worldCommandsManager
import io.github.some_example_name.old.core.utils.collectParticles
import io.github.some_example_name.old.core.utils.distanceTo
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem.Companion.PARTICLE_MAX_RADIUS
import io.github.some_example_name.old.ui.screens.GlobalSettings.DEBUG_MODE
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.forEach
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class UserCommandManager(
    val organEntity: OrganEntity,
    val cellEntity: CellEntity,
    val genomeManager: GenomeManager,
    val cellList: List<Cell>,
    val simulationData: SimulationData,
    val gridManager: GridManager,
    val particleEntity: ParticleEntity,
    val zygote: Zygote
): Disposable {

    private val commandQueue = ConcurrentLinkedQueue<PlayerCommand>()

    @Volatile var grabbedParticleIndex = -1
    @Volatile var tapX = 0f
    @Volatile var tapY = 0f
    @Volatile var isDragging = false

    val random: RandomXS128 = RandomXS128()

    fun push(cmd: PlayerCommand) {
        commandQueue.offer(cmd)
    }

    fun processingCommandsFromUser() {
        var isAlreadyDragged = false

        while (true) {
            val cmd = commandQueue.poll() ?: break

            if (DEBUG_MODE) {
                DISimulationContainer.logSaver.saveUserCommand(cmd)
            }

            var floats = FloatArray(4)

            when (cmd) {
                PlayerCommand.StopDrag -> {
                    grabbedParticleIndex = -1
                    simulationData.selectedCellIndex = -1
                    isDragging = false
                }

                is PlayerCommand.TouchDown -> {
//                    floats = FloatArray(2) {cmd.x; cmd.y}
//                    bools = BooleanArray(1) {cmd.isLeftButton}

                    val neighborsCellIndexes =
                        gridManager.collectParticles(cmd.x.toInt(), cmd.y.toInt(), radius = 1)
                    grabbedParticleIndex = neighborsCellIndexes
                        .minByOrNull {
                            distanceTo(cmd.x, cmd.y, particleEntity.x[it], particleEntity.y[it])
                        }?.takeIf {
                            isDragging = false
                            distanceTo(
                                cmd.x,
                                cmd.y,
                                particleEntity.x[it],
                                particleEntity.y[it]
                            ) < particleEntity.radius[it]
                        } ?: -1

                    tapX = cmd.x
                    tapY = cmd.y
                    simulationData.selectedCellIndex = -1
                }

                is PlayerCommand.Drag -> {
                    if (grabbedParticleIndex != -1) {
                        // floats = FloatArray(4) {cmd.x; cmd.y; particleEntity.x[grabbedParticleIndex]; particleEntity.y[grabbedParticleIndex]}

                        isAlreadyDragged = true
                        val grabDrag = 0.5f // To reduce oscillations

                        with(particleEntity) {
                            vx[grabbedParticleIndex] =
                                vx[grabbedParticleIndex] * grabDrag + (cmd.x - x[grabbedParticleIndex]) * 0.02f
                            vy[grabbedParticleIndex] =
                                vy[grabbedParticleIndex] * grabDrag + (cmd.y - y[grabbedParticleIndex]) * 0.02f
                        }
                        isDragging = true

                        tapX = cmd.x
                        tapY = cmd.y
                        simulationData.selectedCellIndex = -1
                    }
                }

                is PlayerCommand.Tap -> {
//                    floats = FloatArray(2) {cmd.x; cmd.y}
//                    bools = BooleanArray(1) {cmd.isLeftButton}

                    val neighborsCellIndexes =
                        gridManager.collectParticles(cmd.x.toInt(), cmd.y.toInt(), radius = 1)
                    val grabbedParticleIndexTap = neighborsCellIndexes
                        .minByOrNull {
                            distanceTo(cmd.x, cmd.y, particleEntity.x[it], particleEntity.y[it])
                        }?.takeIf {
                            distanceTo(
                                cmd.x,
                                cmd.y,
                                particleEntity.x[it],
                                particleEntity.y[it]
                            ) < particleEntity.radius[it]
                        } ?: -1

                    simulationData.selectedCellIndex = if (grabbedParticleIndexTap != -1) {
                        if (particleEntity.isCell[grabbedParticleIndexTap]) {
                            isDragging = false
                            particleEntity.holderEntityIndex[grabbedParticleIndexTap]
                        } else -1
                    } else -1

                    if (simulationData.selectedCellIndex == -1) {
                        if (cmd.isLeftButton) {
                            if (cmd.x > 0 && cmd.x < gridManager.gridWidth && cmd.y > 0 && cmd.y < gridManager.gridHeight) {
                                val genomeIndex = cmd.genomeIndex//simulationData.currentGenomeIndex
                                val genome = genomeManager.genomes[genomeIndex]
                                val organIndex = organEntity.addOrgan(
                                    genomeIndex = genomeIndex,
                                    genomeSize = genome.genomeStageInstruction.size,
                                    dividedTimes = genome.dividedTimes[0],
                                    mutatedTimes = genome.mutatedTimes[0]
                                )
                                val randomAngle = random.nextFloat(0f, MathUtils.PI2)//cmd.angle//if ( cmd.replay ) MathUtils.random(0f, MathUtils.PI2) else 1f//Вот тут хз, ты это закоментировал но я что бы протестить раскомментирую.
                                floats[0] = randomAngle
                                cellEntity.addCell(
                                    x = cmd.x,
                                    y = cmd.y,
                                    color = zygote.defaultColor.toIntBits(),
                                    radius = PARTICLE_MAX_RADIUS,
                                    cellType = zygote.cellTypeId,
                                    organIndex = organIndex,
                                    angleCos = cos(randomAngle),
                                    angleSin = sin(randomAngle)
                                )

                            }
                        } else {
                            val radius = 7.0f
                            repeat(10) {
                                val angle = random.nextFloat(0f, MathUtils.PI2)//MathUtils.random(0f, MathUtils.PI2)
                                println("now angle: ${angle}")

                                val r = radius * sqrt(random.nextFloat())//MathUtils.random())

                                val x = cmd.x + MathUtils.cos(angle) * r
                                val y = cmd.y + MathUtils.sin(angle) * r

                                if (x > 0 && x < gridManager.gridWidth && y > 0 && y < gridManager.gridHeight) {

                                    val neighborsCellIndexes = gridManager.collectParticles(
                                        x.toInt(),
                                        y.toInt(),
                                        radius = 1
                                    )
                                    val neighborIndex = neighborsCellIndexes.firstOrNull {
                                        distanceTo(
                                            x,
                                            y,
                                            particleEntity.x[it],
                                            particleEntity.y[it]
                                        ) < particleEntity.radius[it]
                                    }

                                    if (neighborIndex == null) {
                                        val color = Color.RED.toIntBits()
                                        val radius = random.nextFloat() * (0.5f - 0.1f) + 0.1f

//                                        worldCommandsManager.worldCommandBuffer[Random.nextInt(
//                                            0,
//                                            threadCount
//                                        )].push(
//                                            type = WorldCommandType.ADD_SUBSTANCE,
//                                            floats = floatArrayOf(x, y, radius),
//                                            ints = intArrayOf(color, 0)
//                                        )
                                        worldCommandsManager.worldCommandBuffer[random.nextInt(
                                            0,
                                            threadCount
                                        )].push(
                                            type = WorldCommandType.ADD_SUBSTANCE,
                                            floats = floatArrayOf(x, y, radius),
                                            ints = intArrayOf(color, 0)
                                        )
                                    }
                                }
                            }
                        }
                    }

//                    println("Tap (${cmd.x}, ${cmd.y} ${cmd.isLeftButton} ${grabbedParticleIndex})")
                }
            }
        }


        if (grabbedParticleIndex != -1 && !isAlreadyDragged && isDragging) {
            val grabDrag = 0.5f // To reduce oscillations

            with(particleEntity) {
                vx[grabbedParticleIndex] = vx[grabbedParticleIndex] * grabDrag + (tapX - x[grabbedParticleIndex]) * 0.02f
                vy[grabbedParticleIndex] = vy[grabbedParticleIndex] * grabDrag + (tapY - y[grabbedParticleIndex]) * 0.02f
            }
        }
    }

    override fun dispose() {
        commandQueue.clear()
    }
}
