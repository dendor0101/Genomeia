package io.github.some_example_name.old.systems.genomics

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS
import io.github.some_example_name.old.systems.physics.GridManager
import kotlin.math.cos
import kotlin.math.sin

class DivideManager(
    val cellEntity: CellEntity,
    val particleEntity: ParticleEntity,
    val worldCommandsManager: WorldCommandsManager,
    val gridManager: GridManager,
    val cellList: List<Cell>
) {

    fun divideCell(index: Int, threadId: Int) = with(cellEntity) {
        if (!isDividedInThisStage[index] && energy[index] >= energyNecessaryToDivide[index]) {
            isDividedInThisStage[index] = true

            val action = cellActions[index]?.divide ?: return

            val parentLinkLength = action.physicalLink[cellGenomeId[index]]?.length ?: 0.025f
            val genomeAngle = action.angle ?: throw Exception("Forgot angle")
            val divideAngleCos = cos(genomeAngle)
            val divideAngleSin = sin(genomeAngle)

            val parentCos = angleCos[index] * angleDirectedCos[index] + angleSin[index] * angleDirectedSin[index]
            val parentSin = angleSin[index] * angleDirectedCos[index] - angleCos[index] * angleDirectedSin[index]

            var finalCos = parentCos * divideAngleCos - parentSin * divideAngleSin
            var finalSin = parentSin * divideAngleCos + parentCos * divideAngleSin

            if (cellList[cellType[index].toInt()] is Zygote) {
                val finalZygoteCos = finalCos * angleDirectedCos[index] - finalSin * angleDirectedSin[index]
                val finalZygoteSin = finalSin * angleDirectedCos[index] + finalCos * angleDirectedSin[index]

                finalCos = finalZygoteCos
                finalSin = finalZygoteSin
            }

            var x = getX(index) + finalCos * parentLinkLength
            var y = getY(index) + finalSin * parentLinkLength

            if (cellEntity.parentIndex[index] == -1) {
                val aCos = -1f
                val aSin = 0f
                val bCos = divideAngleCos
                val bSin = divideAngleSin
                cellEntity.angleCompensationCos[index] = aCos * bCos + aSin * bSin
                cellEntity.angleCompensationSin[index] = aSin * bCos - aCos * bSin
            }

            if (x < 0) {
                x = 0.1f
            }
            if (x > gridManager.gridWidth) {
                x = gridManager.gridWidth - 0.1f
            }
            if (y < 0) {
                y = 0.1f
            }
            if (y > gridManager.gridHeight) {
                y = gridManager.gridHeight - 0.1f
            }

            val cellGenomeId: Int = action.id
            val parentOrganIndex: Int = organIndex[index]
            run {
                val color: Int = (action.color ?: Color.WHITE).toIntBits()
                val radius: Float = action.radius ?: PARTICLE_MAX_RADIUS
                val cellType: Int = action.cellType ?: throw Exception("Forgot cellType")
                val parentIndex: Int = index
                val angleDiff: Float = action.angleDirected ?: 0f
                val angleDiffCos: Float = cos(angleDiff)
                val angleDiffSin: Float = sin(angleDiff)

                val colorDifferentiation: Int = action.colorRecognition ?: 7
                val visibilityRange: Float = action.lengthDirected ?: 4.25f
                val a: Float = action.a ?: 1f
                val b: Float = action.b ?: 0f
                val c: Float = action.c ?: 0f
                val isSum: Boolean = action.isSum ?: true
                val activationFuncType: Int = action.funActivation ?: 0

                val isMorphogenesis = false
                val pheromoneType = action.pheromoneType ?: -1

//                val cell = cellList[cellType]
//                val specialModDataIndex = when(cell) {
//                    is NonWorkingCell1 -> {
//                        if (action.specialData?.attachedKey != null) {
//                            worldCommandsManager.worldCommandSpecialModDataBuffer[threadId].add(
//                                ControllerData(action.specialData.attachedKey)
//                            )
//                            worldCommandsManager.worldCommandSpecialModDataBuffer[threadId].size - 1
//                        } else -1
//                    }
//                    else -> -1
//                }


                worldCommandsManager.worldCommandBuffer[threadId].push(
                    type = WorldCommandType.ADD_CELL,
                    booleans = booleanArrayOf(isSum, isMorphogenesis),
                    floats = floatArrayOf(x, y, radius, finalCos, finalSin, angleDiffCos, angleDiffSin, visibilityRange, a, b, c),
                    ints = intArrayOf(
                        color,
                        cellGenomeId,
                        cellType,
                        parentOrganIndex,
                        parentIndex,
                        colorDifferentiation,
                        activationFuncType,
                        pheromoneType,
                        -1
//                        specialModDataIndex
                    )
                )

                worldCommandsManager.worldCommandBuffer[threadId].push(
                    type = WorldCommandType.DECREMENT_DIVIDE_COUNTER,
                    ints = intArrayOf(parentOrganIndex)
                )
            }

            if (action.physicalLinkMirroredForCell.isNotEmpty()) {
                action.physicalLinkMirroredForCell.forEach { (cellGenomeIdToConnectWith, linkData) ->
                    val otherCellIndex = organToIdToIndex.get(organIndex[index], cellGenomeIdToConnectWith)
                    if (linkData != null) {
                        val cellIndex: Int = -1
                        val linksLength: Float = linkData.length ?: -1f
                        val isNeuronLink: Boolean = linkData.isNeuronal
                        val isLink1NeuralDirected: Boolean = linkData.directedNeuronLink == action.id
                        val linkColor = (linkData.color ?: if (linkData.isNeuronal) Color.CYAN else Color.RED).toIntBits()

                        if (otherCellIndex != -1) {
                            if (!isNeuronLink) {
                                worldCommandsManager.worldCommandBuffer[threadId].push(
                                    type = WorldCommandType.ADD_LINK,
                                    floats = floatArrayOf(linksLength),
                                    ints = intArrayOf(cellIndex, otherCellIndex)
                                )
                            } else {
                                worldCommandsManager.worldCommandBuffer[threadId].push(
                                    type = WorldCommandType.ADD_NEURAL_LINK,
                                    booleans = booleanArrayOf(isLink1NeuralDirected),
                                    ints = intArrayOf(cellIndex, otherCellIndex, linkColor)
                                )
                            }
                        } else {
                            val cellId: Int = cellGenomeId
                            val otherCellId: Int = cellGenomeIdToConnectWith

                            if (!isNeuronLink) {
                                worldCommandsManager.worldCommandSecondBuffer[threadId].push(
                                    type = WorldCommandType.ADD_LINK_BY_ID,
                                    floats = floatArrayOf(linksLength),
                                    ints = intArrayOf(cellId, otherCellId, parentOrganIndex)
                                )
                            } else {
                                worldCommandsManager.worldCommandSecondBuffer[threadId].push(
                                    type = WorldCommandType.ADD_NEURAL_LINK_BY_ID,
                                    booleans = booleanArrayOf(isLink1NeuralDirected),
                                    ints = intArrayOf(cellId, otherCellId, parentOrganIndex, linkColor)
                                )
                            }
                        }
                    }
                }
            }

            energy[index] -= energyNecessaryToDivide[index] - 0.7f
        }
    }

}
