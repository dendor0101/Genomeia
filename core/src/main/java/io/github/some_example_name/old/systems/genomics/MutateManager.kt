package io.github.some_example_name.old.systems.genomics

import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Eye
import io.github.some_example_name.old.cells.Muscle
import io.github.some_example_name.old.cells.Producer
import io.github.some_example_name.old.cells.Tail
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DISimulationContainer.cellsSettings
import io.github.some_example_name.old.core.utils.collectParticles
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.systems.physics.GridManager

class MutateManager(
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val worldCommandsManager: WorldCommandsManager,
    val gridManager: GridManager,
    val specialEntity: SpecialEntity
): Disposable {

    fun mutateCell(index: Int, threadId: Int) = with(cellEntity) {
        //TODO очень легко запутсаться и потерять какие-то значения при мутации, нужно либо перепроверить, либо менять все параметры разом
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
                cellType[index] = it.toByte()
                setDragCoefficient(index, substrateSettings.data.viscosityOfTheEnvironment)
                setEffectOnContact(index, newCell.effectOnContact)
                setIsCollidable(index, newCell.isCollidable)
                setCellStiffness(index, cellsSettings[it].cellStiffness)
                isNeural[index] = newCell.isNeural

                newCell.onStart(index, threadId)
            }

            if (isFromMuscleToAnother) {
                //TODO придумать как выприямить все линки, если мышца превратиалсь в дргую клетку
            }

            action.color?.let { setColor(index, it.toIntBits()) }

            if (lastCell.isNeural && newCell.isNeural) {
                action.funActivation?.let { setActivationFuncType(index, it.toByte()) }
                action.a?.let { setA(index, it) }
                action.b?.let { setB(index, it) }
                action.c?.let { setC(index, it) }
                action.isSum?.let { setIsSum(index, it) }
                setIsNeuronTransportable(index, newCell.isNeuronTransportable)
            }

            action.angleDirected?.let { angleDiff[index] = it }

            if (lastCell is Eye && newCell is Eye) {
                action.colorRecognition?.let { specialEntity.setColorDifferentiation(index, it.toByte()) }
                action.lengthDirected?.let { specialEntity.setVisibilityRange(index, it) }
            }

            if (action.physicalLink.isNotEmpty()) {
                val gridX = getX(index).toInt()
                val gridY = getY(index).toInt()
                val closestCells = gridManager.collectParticles(gridX, gridY)
                val idToIndexAssociation = closestCells
                    .filter { particleEntity.isCell[it] }
                    .map { particleEntity.holderEntityIndex[it] }
                    .filter { organIndex[it] == organIndex[index] && it != index }
                    .associateBy { cellGenomeId[it] }

                action.physicalLink.forEach { (cellGenomeIdToConnectWith, linkData) ->
                    val linkedCellIndex = idToIndexAssociation[cellGenomeIdToConnectWith]
                    if (linkedCellIndex != null) {
                        val linkIndex = linkEntity.linkIndexMap.get(index, linkedCellIndex)
                        if (linkData != null) {
                            if (linkIndex == -1) {
                                if (linkData.length != null) {
                                    if (linkData.isNeuronal && linkData.directedNeuronLink != cellGenomeId[index]
                                        && linkData.directedNeuronLink != cellGenomeIdToConnectWith
                                    ) {
                                        throw Exception("Incorrect logic in the neural-link")
                                    }

                                    val cellIndex: Int = index
                                    val otherCellIndex: Int = linkedCellIndex
                                    val linksLength: Float = linkData.length
                                    val degreeOfShortening: Float = 1f
                                    val isStickyLink: Boolean = false
                                    val isNeuronLink: Boolean = linkData.isNeuronal
                                    val isLink1NeuralDirected: Boolean = linkData.directedNeuronLink == cellGenomeId[index]

                                    if (otherCellIndex == -1) throw Exception("otherCellIndex == -1 in Mutate")
                                    worldCommandsManager.worldCommandBuffer[threadId].push(
                                        type = WorldCommandType.ADD_LINK,
                                        booleans = booleanArrayOf(
                                            isStickyLink,
                                            isNeuronLink,
                                            isLink1NeuralDirected
                                        ),
                                        floats = floatArrayOf(linksLength, degreeOfShortening),
                                        ints = intArrayOf(cellIndex, otherCellIndex)
                                    )
                                }
                            } else {
                                with(linkEntity) {
                                    if (!linkData.isNeuronal) {
                                        val cellIndex = if (isLink1NeuralDirected[linkIndex]) links1[linkIndex] else links2[linkIndex]
                                        if (isNeural[cellIndex]) {
                                            neuronImpulseInput[cellIndex] = 0f
                                            neuronImpulseOutput[cellIndex] = 0f
                                        }
                                    } else {
                                        val cellLink1Index = links1[linkIndex]
                                        val cellLink2Index = links2[linkIndex]
                                        val cellLink1Id = cellGenomeId[cellLink1Index]
                                        val cellLink2Id = cellGenomeId[cellLink2Index]

                                        isLink1NeuralDirected[linkIndex] =
                                            linkData.directedNeuronLink == cellLink1Id

                                        if (linkData.directedNeuronLink != cellLink1Id && linkData.directedNeuronLink != cellLink2Id) {
                                            throw Exception("Incorrect logic in the neural-link ${linkData.directedNeuronLink} ${cellGenomeId[index]} $cellGenomeIdToConnectWith")
                                        }
                                    }

                                    isNeuronLink[linkIndex] = linkData.isNeuronal
                                }
                            }
                        } else {
                            if (linkIndex != -1) {
                                worldCommandsManager.worldCommandBuffer[threadId].push(
                                    type = WorldCommandType.DELETE_LINK,
                                    ints = intArrayOf(linkIndex, linkEntity.getGeneration(linkIndex))
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
