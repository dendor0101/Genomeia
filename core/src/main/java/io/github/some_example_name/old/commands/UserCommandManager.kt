package io.github.some_example_name.old.commands

import com.badlogic.gdx.math.MathUtils
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.core.DIContainer.gridManager
import io.github.some_example_name.old.core.DIContainer.simEntity
import io.github.some_example_name.old.core.DIContainer.worldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import kotlin.collections.forEach
import kotlin.math.sqrt
import kotlin.random.Random


// Командный буфер с двойной очередью
class UserCommandManager(
    val organEntity: OrganEntity,
    val cellEntity: CellEntity,
    val genomeManager: GenomeManager,
    val cellList: List<Cell>
) {
    private val bufferA = mutableListOf<PlayerCommand>()
    private val bufferB = mutableListOf<PlayerCommand>()

    var writeBuffer = bufferA
    var readBuffer = bufferB

    fun push(cmd: PlayerCommand) {
        writeBuffer.add(cmd)
    }

    inline fun swapAndConsume(consumer: (PlayerCommand) -> Unit) {
        // Меняем местами очереди
        val tmp = writeBuffer
        writeBuffer = readBuffer
        readBuffer = tmp

        // Теперь readBuffer содержит все команды с прошлого кадра
        if (readBuffer.isNotEmpty()) {
            readBuffer.forEach(consumer)
            readBuffer.clear()
        }
    }

    fun processingCommandsFromUser() {
        swapAndConsume { cmd ->
            when (cmd) {
                is PlayerCommand.SpawnCell -> {

                    if (cmd.x > 0 && cmd.x < gridManager.gridWidth && cmd.y > 0 && cmd.y < gridManager.gridHeight) {
                        val cellType = 17
                        val genomeIndex = simEntity.currentGenomeIndex
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
                            color = cellList[cellType].defaultColor.toIntBits(),
                            radius = 0.5f,
                            cellType = cellType,
                            organIndex = organIndex,
                        )
                    }
                }

                is PlayerCommand.SpawnParticles -> {
                    val radius = 150.0f

                    repeat(1_000/*0_000*/) {
                        val angle = MathUtils.random(0f, MathUtils.PI2)

                        // больше частиц ближе к центру
                        val r = radius * sqrt(MathUtils.random())

                        val x = cmd.x + MathUtils.cos(angle) * r
                        val y = cmd.y + MathUtils.sin(angle) * r

                        if (x > 0 && x < gridManager.gridWidth && y > 0 && y < gridManager.gridHeight) {
                            val r = 128 + Random.nextInt(128)
                            val g = 128 + Random.nextInt(128)
                            val b = 128 + Random.nextInt(128)
                            val a = 255

                            val color = (r shl 24) or (g shl 16) or (b shl 8) or a

                            worldCommandsManager.worldCommandBuffer[0].push(
                                type = WorldCommandType.ADD_PARTICLE,
                                floats = floatArrayOf(x, y, 0.5f),
                                ints = intArrayOf(color)
                            )
                        }
                    }
                }

                is PlayerCommand.DragCell -> {
//                    TODO()
//                    grabbedXLocal = cmd.dx
//                    grabbedYLocal = cmd.dy
//                    grabbedCellLocal = cmd.cellId
                }

                else -> {

                }
            }
        }
    }
}
