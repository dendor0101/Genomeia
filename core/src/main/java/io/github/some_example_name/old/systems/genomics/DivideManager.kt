package io.github.some_example_name.old.systems.genomics

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.utils.collectParticles
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem.Companion.PARTICLE_MAX_RADIUS
import kotlin.math.PI

class DivideManager(
    val cellEntity: CellEntity,
    val particleEntity: ParticleEntity,
    val worldCommandsManager: WorldCommandsManager,
    val gridManager: GridManager
) {

    fun divideCell(index: Int, threadId: Int) = with(cellEntity) {
        if (!isDividedInThisStage[index] && energy[index] >= energyNecessaryToDivide[index]) {
            isDividedInThisStage[index] = true

            val action = cellActions[index]?.divide ?: return

            val parentLinkLength = action.physicalLink[cellGenomeId[index]]?.length ?: 0.025f
            val genomeAngle = action.angle ?: throw Exception("Forgot angle")
            val divideAngle = genomeAngle + angle[index]
            var x: Float = getX(index) + MathUtils.cos(divideAngle) * parentLinkLength
            var y: Float = getY(index) + MathUtils.sin(divideAngle) * parentLinkLength

            if (x < 0) {
                x = 0.1f
            }
            if (x > DISimulationContainer.gridManager.gridWidth) {
                x = DISimulationContainer.gridManager.gridWidth - 0.1f
            }
            if (y < 0) {
                y = 0.1f
            }
            if (y > DISimulationContainer.gridManager.gridHeight) {
                y = DISimulationContainer.gridManager.gridHeight - 0.1f
            }

            val cellGenomeId: Int = action.id
            val parentOrganIndex: Int = organIndex[index]
            run {
                val color: Int = (action.color ?: Color.WHITE).toIntBits()
                val radius: Float = PARTICLE_MAX_RADIUS
                val cellType: Int = action.cellType ?: throw Exception("Forgot cellType")
                val parentIndex: Int = index
                val angle: Float = divideAngle
                val angleDiff: Float = action.angleDirected ?: 0f
                val colorDifferentiation: Int = action.colorRecognition ?: 7
                val visibilityRange: Float = action.lengthDirected ?: 4.25f
                val a: Float = action.a ?: 1f
                val b: Float = action.b ?: 0f
                val c: Float = action.c ?: 0f
                val isSum: Boolean = action.isSum ?: true
                val activationFuncType: Int = action.funActivation ?: 0

                worldCommandsManager.worldCommandBuffer[threadId].push(
                    type = WorldCommandType.ADD_CELL,
                    booleans = booleanArrayOf(isSum),
                    floats = floatArrayOf(x, y, radius, angle, angleDiff, visibilityRange, a, b, c),
                    ints = intArrayOf(
                        color,
                        cellGenomeId,
                        cellType,
                        parentOrganIndex,
                        parentIndex,
                        colorDifferentiation,
                        activationFuncType
                    )
                )

                worldCommandsManager.worldCommandBuffer[threadId].push(
                    type = WorldCommandType.DECREMENT_DIVIDE_COUNTER,
                    ints = intArrayOf(parentOrganIndex)
                )
            }

            if (action.physicalLink.isNotEmpty()) {
                val gridX = x.toInt()
                val gridY = y.toInt()
                val closestCells = gridManager.collectParticles(gridX, gridY)
                val idToIndexAssociation = closestCells
                        .filter { particleEntity.isCell[it] }
                        .map { particleEntity.holderEntityIndex[it] }
                        .filter { organIndex[it] == organIndex[index]}
                        .associateBy { this.cellGenomeId[it] }

                action.physicalLink.forEach { (cellGenomeIdToConnectWith, linkData) ->
                    val otherCellIndex = idToIndexAssociation[cellGenomeIdToConnectWith]
                    if (linkData != null && linkData.length != null) {

                        val cellIndex: Int = -1
                        val linksLength: Float = linkData.length
                        val degreeOfShortening: Float = 1f
                        val isStickyLink: Boolean = false
                        val isNeuronLink: Boolean = linkData.isNeuronal
                        val isLink1NeuralDirected: Boolean = linkData.directedNeuronLink == action.id

                        if (otherCellIndex != null) {
                            if (linkData.isNeuronal && linkData.directedNeuronLink != action.id
                                && linkData.directedNeuronLink != cellGenomeIdToConnectWith
                            ) {
                                throw Exception("Incorrect logic in the neural-link")
                            }

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
                        } else {
                            val cellId: Int = cellGenomeId
                            val otherCellId: Int = cellGenomeIdToConnectWith

                            worldCommandsManager.worldCommandEndBuffer[threadId].push(
                                type = WorldCommandType.ADD_LINK_BY_ID,
                                booleans = booleanArrayOf(isNeuronLink, isLink1NeuralDirected),
                                floats = floatArrayOf(linksLength),
                                ints = intArrayOf(cellId, otherCellId, parentOrganIndex)
                            )
                        }
                    }
                }
            }

            if (parentIndex[index] == -1) {
                angleDiff[index] = angle[index] + PI.toFloat() - (action.angle ?: return)
            }

            energy[index] -= energyNecessaryToDivide[index] - 0.7f
        }
    }

}
