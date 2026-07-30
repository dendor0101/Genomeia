package io.github.some_example_name.old.systems.genomics

import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DISimulationContainer.energyTransportRate
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.simulation.ThreadManager
import kotlin.math.sqrt

class CellSystem(
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val organEntity: OrganEntity,
    val genomeManager: GenomeManager,
    val worldCommandsManager: WorldCommandsManager,
    val gridManager: GridManager,
    val divideManager: DivideManager,
    val mutateManager: MutateManager,
    val threadManager: ThreadManager?
): Disposable {

    fun iterateCellInParallel() = with(cellEntity) {
        if (threadManager == null) return@with
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

    fun processCell(cellIndex: Int, threadId: Int = 0) = with(cellEntity) {
        if (!isAlive[cellIndex]) return

        val isNeural = isNeural[cellIndex]

        if (neuronImpulseInput[cellIndex].isNaN() || neuronImpulseOutput[cellIndex].isNaN()) {
            throw Exception("neuronImpulseInput $cellIndex is Nan ${cellList[cellType[cellIndex].toInt()].name} ${neuronImpulseInput[cellIndex]} ${neuronImpulseOutput[cellIndex]}")
        }

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
                ints = intArrayOf(cellIndex, getGeneration(cellIndex))
            )
        }

        genomicTransformations(cellIndex, threadId)
    }

    fun processCellAngle(cellIndex: Int, parentCellIndex: Int) = with(cellEntity) {
        val dx = getX(cellIndex) - getX(parentCellIndex)
        val dy = getY(cellIndex) - getY(parentCellIndex)

        val len = sqrt(dx * dx + dy * dy)
        val toChildCos = dx / len
        val toChildSin = dy / len

        val cd = angleCompensationCos[cellIndex]
        val sd = angleCompensationSin[cellIndex]

        val parentCos = toChildCos * cd - toChildSin * sd
        val parentSin = toChildSin * cd + toChildCos * sd

        val directedCos = angleDirectedCos[cellIndex]
        val directedSin = angleDirectedSin[cellIndex]

        angleCos[cellIndex] = parentCos * directedCos - parentSin * directedSin
        angleSin[cellIndex] = parentSin * directedCos + parentCos * directedSin
    }

    fun genomicTransformations(cellIndex: Int, threadId: Int = 0) = with(cellEntity) {
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

    override fun dispose() {

    }
}
