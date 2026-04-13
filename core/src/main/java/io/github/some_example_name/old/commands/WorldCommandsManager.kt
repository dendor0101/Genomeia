package io.github.some_example_name.old.commands

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.core.utils.OrderedIntPairMap
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem.Companion.MAX_LINK_AMOUNT
import kotlin.random.Random

class WorldCommandsManager(
    val gridManager: GridManager,
    val organManager: OrganManager,
    val organEntity: OrganEntity,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val specialEntity: SpecialEntity,
    val particleEntity: ParticleEntity,
    val substrateSettings: SubstrateSettings,
    val genomeManager: GenomeManager,
    val simulationData: SimulationData,
    val cellList: List<Cell>,
    val substancesEntity: SubstancesEntity,
    threadCount: Int
) {
    val worldCommandBuffer = Array(threadCount) { WorldCommandBuffer() }
    val worldCommandEndBuffer = Array(threadCount) { WorldCommandBuffer(100) }
    private val lastAddedCellIndexBuffer = IntArray(threadCount) { -1 }
    private val organIndexCellIdMapIndex = OrderedIntPairMap()

    val evenChunkPositionStack = Array(threadCount) { IntArray(5_000) }
    val oddChunkPositionStack = Array(threadCount) { IntArray(5_000) }
    val evenCounter = IntArray(threadCount)
    val oddCounter = IntArray(threadCount)

    fun executingCommandsFromTheWorld() {
        worldCommandBuffer.forEachIndexed { threadId, worldCommandBuffer ->
            worldCommandBuffer.consume { type, ints, floats, booleans ->
                when (type) {
                    WorldCommandType.DIVIDE_ALIVE_CELL_ACTION_COUNTER -> {
                        organEntity.divideCounterThisStage[ints[0]]++
                    }
                    WorldCommandType.MUTATE_ALIVE_CELL_ACTION_COUNTER -> {
                        organEntity.mutateCounterThisStage[ints[0]]++
                    }
                    WorldCommandType.ADD_PARTICLE -> {
                        particleEntity.addParticle(
                            x = floats[0],
                            y = floats[1],
                            radius = floats[2],
                            color = ints[0],
                            isCell = false,
                            holderEntityIndex = -1
                        )
                    }
                    WorldCommandType.ADD_LINK -> {
                        val cellIndex = if (ints[0] == -1) {
                            lastAddedCellIndexBuffer[threadId]
                        } else ints[0]

                        val otherCellIndex = ints[1]

                        if (cellEntity.linksAmount[cellIndex] < MAX_LINK_AMOUNT
                            && cellEntity.linksAmount[otherCellIndex] < MAX_LINK_AMOUNT) {
                            linkEntity.addLink(
                                cellIndex = cellIndex,
                                otherCellIndex = otherCellIndex,
                                linksLength = floats[0],
                                degreeOfShortening = floats[1],
                                isStickyLink = booleans[0],
                                isNeuronLink = booleans[1],
                                isLink1NeuralDirected = booleans[2]
                            )
                        }
                    }
                    WorldCommandType.DELETE_LINK -> {
                        println("DELETE_LINK ${ints[0]}")
                        linkEntity.deleteLink(linkIndex = ints[0], linkGeneration = ints[1])
                    }
                    WorldCommandType.ADD_CELL -> {
                        val cellType = ints[2]
                        val newCell = cellList[cellType]
                        val cellGenomeId = ints[1]
                        val parentOrganIndex = ints[3]
                        val organIndex = if (newCell is Zygote) {
                            val genomeIndex = organEntity.genomeIndex[parentOrganIndex]
                            val genome = genomeManager.genomes[genomeIndex]
                            organEntity.addOrgan(
                                genomeIndex = genomeIndex,
                                genomeSize = genome.genomeStageInstruction.size,
                                dividedTimes = genome.dividedTimes[0],
                                mutatedTimes = genome.mutatedTimes[0]
                            )
                        } else {
                            parentOrganIndex
                        }

                        val cellIndex = cellEntity.addCell(
                            x = floats[0],
                            y = floats[1],
                            color = ints[0],
                            radius = floats[2],
                            cellGenomeId = cellGenomeId,
                            cellType = cellType,
                            organIndex = organIndex,
                            parentIndex = ints[4],
                            angle = floats[3],
                            angleDiff = floats[4],
                            colorDifferentiation = ints[5],
                            visibilityRange = floats[5],
                            a = floats[6],
                            b = floats[7],
                            c = floats[8],
                            isSum = booleans[0],
                            activationFuncType = ints[6].toByte()
                        )
                        lastAddedCellIndexBuffer[threadId] = cellIndex
                        organIndexCellIdMapIndex.put(organIndex, cellGenomeId, cellIndex)
                        if (newCell !is Zygote) {
                            newCell.onStart(cellIndex, 0)
                        } else {
                            cellEntity.energy[cellIndex] = 4.3f
                        }
                    }
                    WorldCommandType.DECREMENT_DIVIDE_COUNTER -> {
                        organEntity.dividedTimes[ints[0]]--
                    }
                    WorldCommandType.DECREMENT_MUTATION_COUNTER -> {
                        organEntity.mutatedTimes[ints[0]]--
                    }
                    WorldCommandType.DELETE_CELL -> {
                        val cellIndex = ints[0]
                        val cellGeneration = ints[1]
                        println("DELETE_CELL $cellIndex")
                        if (cellEntity.isAlive[cellIndex] && cellEntity.getGeneration(cellIndex) == cellGeneration) {
                            val r = Random.nextInt(255)
                            val g = Random.nextInt(255)
                            val b = Random.nextInt(255)
                            val a = 255

                            val color = (a shl 24) or (r shl 16) or (g shl 8) or b
                            substancesEntity.addSubstance(
                                x = cellEntity.getX(cellIndex),
                                y = cellEntity.getY(cellIndex),
                                color = color,
                                radius = 0.1f,
                                subType = 0,
                            )
                            organManager.cellDeleted(cellIndex)

                            while (cellEntity.linksAmount[cellIndex] > 0) {
                                val base = cellIndex * MAX_LINK_AMOUNT
                                val linkIndex = cellEntity.links[base]
                                if (linkIndex != -1) {
                                    linkEntity.deleteLink(linkIndex)
                                }
                            }

                            cellEntity.deleteCell(cellIndex)
                            cellList[cellEntity.cellType[cellIndex].toInt()].onDie(cellIndex)
                        }
                    }
                    WorldCommandType.DELETE_NEURAL -> {
                        cellEntity.deleteNeural(cellIndex = ints[0], neuralGeneration = ints[1])
                    }
                    WorldCommandType.ADD_NEURAL -> {
                        cellEntity.addNeural(
                            index = ints[0],
                            cellType = ints[1],
                            a = floats[0],
                            b = floats[1],
                            c = floats[2],
                            isSum = booleans[0],
                            activationFuncType = ints[2].toByte()
                        )
                    }
                    WorldCommandType.DELETE_EYE -> {
                        specialEntity.deleteEye(cellIndex = ints[0], eyeGeneration = ints[1])
                    }
                    WorldCommandType.ADD_EYE -> {
                        specialEntity.addEye(
                            index = ints[0],
                            colorDifferentiation = ints[1],
                            visibilityRange = floats[0]
                        )
                    }
                    WorldCommandType.DELETE_TAIL -> {
                        specialEntity.deleteTail(cellIndex = ints[0], tailGeneration = ints[1])
                    }
                    WorldCommandType.ADD_TAIL -> {
                        specialEntity.addTail(index = ints[0])
                    }
                    WorldCommandType.ADD_ORGAN -> {
                        val organStartCellOrganIndex = ints[0]
                        cellEntity.organIndex[organStartCellOrganIndex] = organEntity.addOrgan(
                            genomeIndex = ints[1],
                            genomeSize = ints[2],
                            dividedTimes = ints[3],
                            mutatedTimes = ints[4]
                        )
                    }
                    WorldCommandType.DELETE_ORGAN -> {
                        val organIndex = ints[0]
                        val organGeneration = ints[1]
                        //TODO DELETE_ORGAN
                        //TODO подумать все ли нормально будет с organIndexCellIdMapIndex
                    }
                    WorldCommandType.ADD_SUBSTANCE -> {
                        substancesEntity.addSubstance(
                            x = floats[0],
                            y = floats[1],
                            color = ints[0],
                            radius = floats[2],
                            subType = ints[1].toByte(),
                        )
                    }
                    WorldCommandType.DELETE_SUBSTANCE -> {
                        substancesEntity.deleteSubstance(subIndex = ints[0], subGeneration = ints[1])
                    }
                    else -> {}
                }
            }
        }

        worldCommandEndBuffer.forEachIndexed { threadId, worldCommandBuffer ->
            worldCommandBuffer.consume { type, ints, floats, booleans ->
                when (type) {
                    WorldCommandType.ADD_LINK_BY_ID -> {
                        val cellId = ints[0]
                        val otherCellId = ints[1]
                        val organIndex = ints[2]
                        val linksLength = floats[0]
                        val isNeuronLink = booleans[0]
                        val isLink1NeuralDirected = booleans[1]

                        val cellIndex = organIndexCellIdMapIndex.get(organIndex, cellId)
                        val otherCellIndex = organIndexCellIdMapIndex.get(organIndex, otherCellId)

                        if (cellIndex != -1 && otherCellIndex != -1 && linkEntity.linkIndexMap.get(cellIndex, otherCellIndex) == -1) {
                            linkEntity.addLink(
                                cellIndex = cellIndex,
                                otherCellIndex = otherCellIndex,
                                linksLength = linksLength,
                                degreeOfShortening = 1f,
                                isStickyLink = false,
                                isNeuronLink = isNeuronLink,
                                isLink1NeuralDirected = isLink1NeuralDirected
                            )
                        }
                    }

                    else -> {}
                }
            }
        }

        organIndexCellIdMapIndex.clear()
    }
}
