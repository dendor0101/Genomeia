package io.github.some_example_name.old.systems.genomics

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Eye
import io.github.some_example_name.old.cells.Muscle
import io.github.some_example_name.old.cells.PheromoneEmitter
import io.github.some_example_name.old.cells.Producer
import io.github.some_example_name.old.cells.Tail
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DISimulationContainer.cellsSettings
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.systems.physics.GridManager
import kotlin.math.cos
import kotlin.math.sin

class MutateManager(
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val neuralLinkEntity: NeuralLinkEntity,
    val particleEntity: ParticleEntity,
    val worldCommandsManager: WorldCommandsManager,
    val gridManager: GridManager,
    val specialEntity: SpecialEntity,
    val organEntity: OrganEntity,
    val isEditor: Boolean
): Disposable {

    fun mutateCell(index: Int, threadId: Int) = with(cellEntity) {
        //TODO очень легко запутсаться и потерять какие-то значения при мутации, нужно либо перепроверить, либо менять все параметры разом или сделать общий метод с addCell
        if (!isMutateInThisStage[index] && energy[index] >= energyNecessaryToMutate[index]) {
            isMutateInThisStage[index] = true

            val action = cellActions[index]?.mutate ?: return

            worldCommandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.DECREMENT_MUTATION_COUNTER,
                ints = intArrayOf(organIndex[index])
            )

            var isFromMuscleToAnother = false

            val lastCellType = cellType[index].toInt()
            val lastCell = cellList[lastCellType]
            val newCell = action.cellType?.let { cellList[it] } ?: lastCell

            action.cellType?.let {
                isFromMuscleToAnother = lastCell is Muscle && newCell !is Muscle

                if (lastCell.isNeural && !newCell.isNeural) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_NEURAL,
                        ints = intArrayOf(index, getNeuralGeneration(index))
                    )
                }
                if (!lastCell.isNeural && newCell.isNeural) {
                    val cellType: Int = it
                    val a: Float = action.a ?: 1f
                    val b: Float = action.b ?: 0f
                    val c: Float = action.c ?: 0f
                    val isSum: Boolean = action.isSum ?: true
                    val activationFuncType: Int = action.funActivation ?: 0
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.ADD_NEURAL,
                        ints = intArrayOf(index, cellType, activationFuncType),
                        floats = floatArrayOf(a, b, c),
                        booleans = booleanArrayOf(isSum)
                    )
                }
                if (lastCell.doesNeedNeuralConnections && !newCell.doesNeedNeuralConnections) {
                    neuralConnections.remove(index)
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_NEURAL_CONNECTIONS,
                        ints = intArrayOf(index)
                    )
                }
                if (!lastCell.doesNeedNeuralConnections && newCell.doesNeedNeuralConnections) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.ADD_NEURAL_CONNECTIONS_EMPTY_LIST,
                        ints = intArrayOf(index)
                    )
                    command[index] = 0
                }
                if (lastCell is Eye && newCell !is Eye) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_EYE,
                        ints = intArrayOf(index, specialEntity.getEyeGeneration(index))
                    )
                }
                if (lastCell !is Eye && newCell is Eye) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.ADD_EYE,
                        ints = intArrayOf(index, action.colorRecognition ?: 7),
                        floats = floatArrayOf(action.lengthDirected ?: 4.25f)
                    )
                }
                if (lastCell is Tail && newCell !is Tail) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_TAIL,
                        ints = intArrayOf(index, specialEntity.getTailGeneration(index))
                    )
                }
                if (lastCell !is Tail && newCell is Tail) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.ADD_TAIL,
                        ints = intArrayOf(index)
                    )
                }
                if (lastCell is Producer && newCell !is Producer) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_PRODUCER,
                        ints = intArrayOf(index, specialEntity.getProducerGeneration(index))
                    )
                }
                if (lastCell !is Producer && newCell is Producer) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.ADD_PRODUCER,
                        ints = intArrayOf(index)
                    )
                }
                if (lastCell is PheromoneEmitter && newCell !is PheromoneEmitter) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_PHEROMONE_EMITTER,
                        ints = intArrayOf(index, specialEntity.getPheromoneEmitterGeneration(index))
                    )
                }
                if (lastCell !is PheromoneEmitter && newCell is PheromoneEmitter) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.ADD_PHEROMONE_EMITTER,
                        ints = intArrayOf(index)
                    )
                }
                cellType[index] = it.toByte()
                maxEnergy[index] = cellsSettings[it].maxEnergy
                setDragCoefficient(index, substrateSettings.data.viscosityOfTheEnvironment)
                setEffectOnContact(index, newCell.effectOnContact)
                setIsCollidable(index, newCell.isCollidable)
                setCellStiffness(index, cellsSettings[it].cellStiffness)
                isNeural[index] = newCell.isNeural

                if (newCell is Zygote && !isEditor) {
                    cellGenomeId[index] = 0
                }

                val genomeIndex = organEntity.genomeIndex[organIndex[index]]
                worldCommandsManager.worldCommandBuffer[threadId].push(
                    type = WorldCommandType.MUTATE_ON_START,
                    ints = intArrayOf(index, threadId, genomeIndex)
                )
            }

            if (isFromMuscleToAnother) {
                degreeOfShortening[index] = 1f
            }

            action.color?.let { setColor(index, it.toIntBits()) }

            action.radius?.let { setRadius(index, it) }

            if (lastCell.isNeural && newCell.isNeural) {
                action.funActivation?.let { setActivationFuncType(index, it.toByte()) }
                action.a?.let { setA(index, it) }
                action.b?.let { setB(index, it) }
                action.c?.let { setC(index, it) }
                action.isSum?.let { setIsSum(index, it) }
                setIsNeuronTransportable(index, newCell.isNeuronTransportable)
            }

            action.angleDirected?.let {
                val cosA = angleCos[index]
                val sinA = angleSin[index]
                val cosB = angleDirectedCos[index]
                val sinB = angleDirectedSin[index]

                angleCos[index] = cosA * cosB + sinA * sinB
                angleSin[index] = sinA * cosB - cosA * sinB

                angleDirectedCos[index] = cos(it)
                angleDirectedSin[index] = sin(it)

                val cosA2 = angleCos[index]
                val sinA2 = angleSin[index]
                val cosB2 = angleDirectedCos[index]
                val sinB2 = angleDirectedSin[index]

                angleCos[index] = cosA2 * cosB2 - sinA2 * sinB2
                angleSin[index] = sinA2 * cosB2 + cosA2 * sinB2
            }

            if (lastCell is Eye && newCell is Eye) {
                action.colorRecognition?.let { specialEntity.setColorDifferentiation(index, it.toByte()) }
                action.lengthDirected?.let { specialEntity.setVisibilityRange(index, it) }
            }

            action.pheromoneType?.let {
                pheromoneType[index] = it
            }

            if (action.physicalLink.isNotEmpty()) {
                action.physicalLink.forEach { (cellGenomeIdToConnectWith, linkData) ->

                    val linkedCellIndex = organToIdToIndex.get(organIndex[index], cellGenomeIdToConnectWith)
                    if (linkedCellIndex != -1) {
                        if (linkData != null) {
                            val neuralLinkIndex = neuralLinkEntity.linkIndexMap.get(index, linkedCellIndex)
                            val isLink1NeuralDirected: Boolean = linkData.directedNeuronLink == cellGenomeId[index]
                            val linkColor = (linkData.color ?: Color.CYAN).toIntBits()

                            if (neuralLinkIndex == -1) {
                                val cellIndex: Int = index
                                val otherCellIndex: Int = linkedCellIndex

                                worldCommandsManager.worldCommandBuffer[threadId].push(
                                    type = WorldCommandType.ADD_NEURAL_LINK,
                                    booleans = booleanArrayOf(isLink1NeuralDirected),
                                    ints = intArrayOf(cellIndex, otherCellIndex, linkColor)
                                )
                            } else {
                                neuralLinkEntity.isLink1NeuralDirected[neuralLinkIndex] = isLink1NeuralDirected
                                neuralLinkEntity.color[neuralLinkIndex] = linkColor
                            }
                        } else {
                            //Удаление нейро-линка
                            val linkIndex = neuralLinkEntity.linkIndexMap.get(index, linkedCellIndex)
                            if (linkIndex != -1) {
                                worldCommandsManager.worldCommandBuffer[threadId].push(
                                    type = WorldCommandType.DELETE_NEURAL_LINK,
                                    ints = intArrayOf(linkIndex, neuralLinkEntity.getGeneration(linkIndex))
                                )
                            }
                        }
                    }
                }
            }

            energy[index] -= energyNecessaryToMutate[index] - 0.7f
        }
    }

    override fun dispose() {

    }

}
