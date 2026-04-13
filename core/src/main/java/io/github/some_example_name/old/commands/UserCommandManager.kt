package io.github.some_example_name.old.commands

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Zygote
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
import io.github.some_example_name.old.systems.render.RenderSystem
import kotlin.collections.forEach
import kotlin.collections.get
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.times

class UserCommandManager(
    val organEntity: OrganEntity,
    val cellEntity: CellEntity,
    val genomeManager: GenomeManager,
    val cellList: List<Cell>,
    val simulationData: SimulationData,
    val gridManager: GridManager,
    val particleEntity: ParticleEntity,
    val zygote: Zygote,
    val renderSystem: RenderSystem
): Disposable {
    private val bufferA = mutableListOf<PlayerCommand>()
    private val bufferB = mutableListOf<PlayerCommand>()

    var writeBuffer = bufferA
    var readBuffer = bufferB

    var grabbedParticleIndex = -1
    var tapX = 0f
    var tapY = 0f

    fun push(cmd: PlayerCommand) {
        writeBuffer.add(cmd)
    }

    inline fun swapAndConsume(consumer: (PlayerCommand) -> Unit) {
        val tmp = writeBuffer
        writeBuffer = readBuffer
        readBuffer = tmp

        if (readBuffer.isNotEmpty()) {
            readBuffer.forEach(consumer)
            readBuffer.clear()
        }
    }

    fun processingCommandsFromUser() {
        var isAlreadyDragged = false
        swapAndConsume { cmd ->
            when (cmd) {
                PlayerCommand.StopDrag -> {
                    grabbedParticleIndex = -1
                    simulationData.selectedCellIndex = -1
                }
                is PlayerCommand.TouchDown -> {
                    val neighborsCellIndexes = gridManager.collectParticles(cmd.x.toInt(), cmd.y.toInt(), radius = 1)
                    grabbedParticleIndex = neighborsCellIndexes
                        .minByOrNull {
                            distanceTo(cmd.x, cmd.y, particleEntity.x[it], particleEntity.y[it])
                        }?.takeIf {
                            distanceTo(cmd.x, cmd.y, particleEntity.x[it], particleEntity.y[it]) < particleEntity.radius[it]
                        } ?: -1

                    tapX = cmd.x
                    tapY = cmd.y
                    simulationData.selectedCellIndex = -1
                }

                is PlayerCommand.Drag -> {
                    if (grabbedParticleIndex != -1) {
                        isAlreadyDragged = true
                        val grabDrag = 0.5f // To reduce oscillations

                        with(particleEntity) {
                            vx[grabbedParticleIndex] = vx[grabbedParticleIndex] * grabDrag + (cmd.x - x[grabbedParticleIndex]) * 0.02f
                            vy[grabbedParticleIndex] = vy[grabbedParticleIndex] * grabDrag + (cmd.y - y[grabbedParticleIndex]) * 0.02f
                        }

                        tapX = cmd.x
                        tapY = cmd.y
                        simulationData.selectedCellIndex = -1
                    }
                }

                is PlayerCommand.Tap -> {
                    val neighborsCellIndexes = gridManager.collectParticles(cmd.x.toInt(), cmd.y.toInt(), radius = 1)
                    val grabbedParticleIndex = neighborsCellIndexes
                        .minByOrNull {
                            distanceTo(cmd.x, cmd.y, particleEntity.x[it], particleEntity.y[it])
                        }?.takeIf {
                            distanceTo(cmd.x, cmd.y, particleEntity.x[it], particleEntity.y[it]) < particleEntity.radius[it]
                        } ?: -1

                    simulationData.selectedCellIndex = if (grabbedParticleIndex != -1) {
                        if (particleEntity.isCell[grabbedParticleIndex]) {
                            particleEntity.holderEntityIndex[grabbedParticleIndex]
                        } else -1
                    } else -1

                    if (simulationData.selectedCellIndex == -1) {
                        if (cmd.isLeftButton) {
                            if (cmd.x > 0 && cmd.x < gridManager.gridWidth && cmd.y > 0 && cmd.y < gridManager.gridHeight) {
                                val genomeIndex = simulationData.currentGenomeIndex
                                val genome = genomeManager.genomes[genomeIndex]
                                val organIndex = organEntity.addOrgan(
                                    genomeIndex = genomeIndex,
                                    genomeSize = genome.genomeStageInstruction.size,
                                    dividedTimes = genome.dividedTimes[0],
                                    mutatedTimes = genome.mutatedTimes[0]
                                )
                                cellEntity.addCell(
                                    x = cmd.x,
                                    y = cmd.y,
                                    color = zygote.defaultColor.toIntBits(),
                                    radius = PARTICLE_MAX_RADIUS,
                                    cellType = zygote.cellTypeId,
                                    organIndex = organIndex,
                                    angle = Random.nextFloat() * 3.1415f
                                )
                            }
                        } else {
                            val radius = 170.0f

                            repeat(15_000) {
                                val angle = MathUtils.random(0f, MathUtils.PI2)

                                val r = radius * sqrt(MathUtils.random())

                                val x = cmd.x + MathUtils.cos(angle) * r
                                val y = cmd.y + MathUtils.sin(angle) * r

                                if (x > 0 && x < gridManager.gridWidth && y > 0 && y < gridManager.gridHeight) {
                                    val r = Random.nextInt(255)
                                    val g = Random.nextInt(255)
                                    val b = Random.nextInt(255)
                                    val a = 255

                                    val color = (a shl 24) or (r shl 16) or (g shl 8) or b
                                    val radius = Random.nextFloat() * (0.5f - 0.1f) + 0.1f

                                    worldCommandsManager.worldCommandBuffer[Random.nextInt(
                                        0,
                                        threadCount
                                    )].push(
                                        type = WorldCommandType.ADD_PARTICLE,
                                        floats = floatArrayOf(x, y, radius),
                                        ints = intArrayOf(color)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }


        if (grabbedParticleIndex != -1 && !isAlreadyDragged) {
            val grabDrag = 0.5f // To reduce oscillations

            with(particleEntity) {
                vx[grabbedParticleIndex] = vx[grabbedParticleIndex] * grabDrag + (tapX - x[grabbedParticleIndex]) * 0.02f
                vy[grabbedParticleIndex] = vy[grabbedParticleIndex] * grabDrag + (tapY - y[grabbedParticleIndex]) * 0.02f
            }
        }
    }

    override fun dispose() {

    }
}
