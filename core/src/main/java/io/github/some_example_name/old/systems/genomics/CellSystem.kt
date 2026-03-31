package io.github.some_example_name.old.systems.genomics

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DIContainer.energyTransportRate
import io.github.some_example_name.old.core.DIContainer.threadCount
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.simulation.ThreadManager
import kotlin.math.atan2

class CellSystem(
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val organEntity: OrganEntity,
    val genomeManager: GenomeManager,
    val worldCommandsManager: WorldCommandsManager,
    val gridManager: GridManager,
    val divideManager: DivideManager,
    val mutateManager: MutateManager,
    val threadManager: ThreadManager
) {

    fun iterateCell() = with(cellEntity) {
        val size = aliveList.size

        if (size == 0) return

        val chunkSize = (size + threadCount - 1) / threadCount

        for (threadId in 0 until threadCount) {
            val start = threadId * chunkSize
            val end = minOf(start + chunkSize, size)

            if (start >= end) break

            val future = threadManager.executor.submit {
                for (i in start until end) {
                    val cellIndex = aliveList.getInt(i)
                    processCell(cellIndex, threadId)
                }
            }
            threadManager.futures.add(future)
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()
    }

    private fun processCell(cellIndex: Int, threadId: Int) = with(cellEntity) {
        if (!isAlive[cellIndex]) return

        val isNeural = isNeural[cellIndex]

        if (isNeural) {
            if (getIsNeuronTransportable(cellIndex)) {
                val impulse = activation(cellIndex, neuronImpulseInput[cellIndex])
                neuronImpulseOutput[cellIndex] = impulse
            }
        } else {
            neuronImpulseOutput[cellIndex] = neuronImpulseInput[cellIndex]
        }

        cellList[cellType[cellIndex].toInt()].doOnTick(cellIndex = cellIndex, threadId = threadId)

        if (isNeural) {
            neuronImpulseInput[cellIndex] = if (getIsSum(cellIndex)) 0f else 1f
        } else {
            neuronImpulseInput[cellIndex] = 0f
        }

        if (energy[cellIndex] < 0f) {
            worldCommandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.DELETE_CELL,
                ints = intArrayOf(cellIndex)
            )
        }

        genomicTransformations(cellIndex, threadId)
        processCellAngle(cellIndex)
    }

    //TODO возможно это можно через сами линки обрабатывать
    private fun processCellAngle(cellIndex: Int) = with(cellEntity) {
        if (parentIndex[cellIndex] != -1) {
            val linkId = linkEntity.linkIndexMap.get(cellIndex, parentIndex[cellIndex])
            if (linkId == -1) return //TODO потетсить всякие варинаты без этой защиты
            val c1 = linkEntity.links1[linkId]
            val c2 = linkEntity.links2[linkId]
            val childCellIndex = if (cellIndex != c2) c2 else c1

            val dx = getX(cellIndex) - getX(childCellIndex)
            val dy = getY(cellIndex) - getY(childCellIndex)
            val angleToChild = atan2(dy, dx)

            angle[cellIndex] = angleToChild + angleDiff[cellIndex]
        }
    }

    private fun genomicTransformations(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val organIndex = organIndex[cellIndex]
        if (!organEntity.alreadyGrownUp[organIndex]) {
            if (organEntity.justChangedStage[organIndex]) {
                val currentStage = genomeManager.genomes[organEntity.genomeIndex[organIndex]]
                    .genomeStageInstruction[organEntity.stage[organIndex]]
                val action = currentStage.cellActions[cellGenomeId[cellIndex]]
                val isDivideNotNull = action?.divide != null
                val isMutateNotNull = action?.mutate != null

                cellActions[cellIndex] = action

                isDividedInThisStage[cellIndex] = !isDivideNotNull
                isMutateInThisStage[cellIndex] = !isMutateNotNull

                if (isDivideNotNull) {
                    //TODO Make a more accurate energy calculation
                    energyNecessaryToDivide[cellIndex] = 3.0f
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DIVIDE_ALIVE_CELL_ACTION_COUNTER,
                        intArrayOf(organIndex)
                    )
                }

                if (isMutateNotNull) {
                    //TODO Make a more accurate energy calculation
                    energyNecessaryToMutate[cellIndex] = 2.0f
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.MUTATE_ALIVE_CELL_ACTION_COUNTER,
                        intArrayOf(organIndex)
                    )
                }
            }
            mutateManager.mutateCell(cellIndex, threadId)
            divideManager.divideCell(cellIndex, threadId)
        }
    }

    fun transportEnergy(linkCell1: Int, linkCell2: Int) = with(cellEntity) {
        val cell1maxEnergy = maxEnergy[linkCell1]
        val cell2maxEnergy = maxEnergy[linkCell2]
        if (energy[linkCell1] / cell1maxEnergy < energy[linkCell2] / cell2maxEnergy) {
            energy[linkCell1] += energyTransportRate
            energy[linkCell2] -= energyTransportRate
        } else if (energy[linkCell1] / cell1maxEnergy != energy[linkCell2] / cell2maxEnergy) {
            energy[linkCell1] -= energyTransportRate
            energy[linkCell2] += energyTransportRate
        }
    }

    fun transportNeuralSignal(linkId: Int, linkCell1: Int, linkCell2: Int) = with(cellEntity) {
        if (linkEntity.isNeuronLink[linkId]) {
            val directed = linkEntity.isLink1NeuralDirected[linkId]
            val signalToCellIndex = if (directed) linkCell1 else linkCell2
            val signalFromCellIndex = if (directed) linkCell2 else linkCell1

            val neuronImpulseInputSum = neuronImpulseInput[signalToCellIndex]
            val neuronImpulseOutput = neuronImpulseOutput[signalFromCellIndex]

            if (isNeural[signalToCellIndex]) {
                if (getIsSum(signalToCellIndex)) {
                    neuronImpulseInput[signalToCellIndex] = neuronImpulseInputSum + neuronImpulseOutput
                } else {
                    neuronImpulseInput[signalToCellIndex] = neuronImpulseInputSum * neuronImpulseOutput
                }
            } else {
                neuronImpulseInput[signalToCellIndex] = neuronImpulseInputSum + neuronImpulseOutput
            }
        }
    }

}
