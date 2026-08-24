package io.github.some_example_name.old.commands

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.core.DISimulationContainer.worldCommandsManager
import io.github.some_example_name.old.core.log.ActionLog
import io.github.some_example_name.old.core.utils.collectParticles
import io.github.some_example_name.old.core.utils.distanceTo
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS
import io.github.some_example_name.old.systems.physics.GridManager
import java.util.concurrent.ConcurrentLinkedQueue
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
    val zygote: Zygote,
    val isEditor: Boolean
): Disposable {

    private val commandQueue = ConcurrentLinkedQueue<PlayerCommand>()

    @Volatile var grabbedParticleIndex = -1
    @Volatile var tapX = 0f
    @Volatile var tapY = 0f
    @Volatile var isDragging = false

    fun push(cmd: PlayerCommand) {
        // Единственный вход для действий игрока в мире — значит и единственное место,
        // где их надо журналировать. Drag сыпется на каждое движение мыши, но подряд
        // идущие однотипные записи ActionLog схлопывает в одну со счётчиком.
        ActionLog.record(LOG_SOURCE, cmd.name, cmd.detail)
        commandQueue.offer(cmd)
    }

    fun processingCommandsFromUser() {
        var isAlreadyDragged = false

        while (true) {
            val cmd = commandQueue.poll() ?: break

            when (cmd) {
                PlayerCommand.StopDrag -> {
                    grabbedParticleIndex = -1
                    simulationData.selectedCellIndex = -1
                    isDragging = false
                }

                is PlayerCommand.TouchDown -> {
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
                                val genomeIndex = cmd.genomeIndex ?: simulationData.currentGenomeIndex
                                val genome = genomeManager.genomes[genomeIndex]
                                val organIndex = organEntity.addOrgan(
                                    genomeIndex = genomeIndex,
                                    genomeSize = genome.genomeStageInstruction.size,
                                    dividedTimes = genome.dividedTimes[0],
                                    mutatedTimes = genome.mutatedTimes[0]
                                )
                                // Строго до первой addCell: зигота уже должна брать слот
                                // из арены, иначе тело начнётся вне своего диапазона.
                                //
                                // Размеры берутся из генома, снятые при запекании, а не из
                                // констант: константа на все геномы сразу — это либо
                                // перерасход на мелких телах, либо падение на крупных.
                                // У незапечённого генома там нули, и тогда работают
                                // прежние значения по умолчанию.
                                if (genome.cellsAmount > 0) {
                                    organEntity.allocateArenas(
                                        organIndex = organIndex,
                                        layout = genome.sortedGraph,
                                        maxCells = genome.cellsAmount,
                                        maxLinks = genome.linksAmount
                                    )
                                } else {
                                    organEntity.allocateArenas(
                                        organIndex = organIndex,
                                        layout = genome.sortedGraph,
                                        maxCells = genome.cellsAmount,
                                        maxLinks = genome.linksAmount
                                    )
//                                    throw Exception("Пустой геном без клеток")
                                }
                                val randomAngle = if (isEditor) 0f else 0f//MathUtils.random(0f, MathUtils.PI2)
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
                                val angle = MathUtils.random(0f, MathUtils.PI2)

                                val r = radius * sqrt(MathUtils.random())

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
                                        val radius = Random.nextFloat() * (0.5f - 0.1f) + 0.1f

                                        worldCommandsManager.worldCommandBuffer[Random.nextInt(
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

    private companion object {
        const val LOG_SOURCE = "Player"
    }

    override fun dispose() {
        commandQueue.clear()
    }
}
