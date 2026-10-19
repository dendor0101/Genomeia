package io.github.some_example_name.old.systems.genomics

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.base.Formulas
import io.github.some_example_name.old.cells.Muscle
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DISimulationContainer.cellsSettings
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.systems.genomics.genome.Action
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
    val organEntity: OrganEntity
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
            val newCellType = action.cellType?.let(cellList::requireIndex)
            val newCell = newCellType?.let { cellList[it] } ?: lastCell

            newCellType?.let {
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
                    val activationFuncType: Int = action.funActivation?.let(Formulas::requireIndex) ?: Formulas.defaultIndex
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.ADD_NEURAL,
                        ints = intArrayOf(index, cellType, activationFuncType),
                        floats = floatArrayOf(a, b, c),
                        booleans = booleanArrayOf(isSum)
                    )
                }
                cellType[index] = it.toByte()
                maxEnergy[index] = cellsSettings[it].maxEnergy
                setDragCoefficient(index, substrateSettings.data.viscosityOfTheEnvironment)
                setEffectOnContact(index, newCell.effectOnContact)
                setIsCollidable(index, newCell.isCollidable)
                setCellStiffness(index, cellsSettings[it].cellStiffness)
                isNeural[index] = newCell.isNeural

                val genomeIndex = organEntity.genomeIndex[organIndex[index]]
                worldCommandsManager.worldCommandBuffer[threadId].push(
                    type = WorldCommandType.MUTATE_ON_START,
                    ints = intArrayOf(index, threadId, genomeIndex)
                )
            }

            // Мутация в зиготу делает клетку зиготой-пустышкой (CellEntity.isDormantZygote): в
            // теле она остаётся и ничего не растит — новое тело на старом в арену организма
            // не помещается. Действий следующих стадий она не получит (CellSystem), а деление
            // ЭТОЙ стадии, если оно у неё было, засчитывается как сделанное: стадия ждёт его
            // по счётчику, и без этого организм застрял бы на ней навсегда.
            if (!isDividedInThisStage[index] && isDormantZygote(index)) {
                isDividedInThisStage[index] = true
                worldCommandsManager.worldCommandBuffer[threadId].push(
                    type = WorldCommandType.DECREMENT_DIVIDE_COUNTER,
                    ints = intArrayOf(organIndex[index])
                )
            }

            mutateSpecialData(index, threadId, action, typeChanged = newCellType != null && newCellType != lastCellType, newCell)

            if (isFromMuscleToAnother) {
                degreeOfShortening[index] = 1f
            }

            action.color?.let { setColor(index, it.toIntBits()) }

            action.radius?.let { setRadius(index, it) }

            if (lastCell.isNeural && newCell.isNeural) {
                action.funActivation?.let { setActivationFuncType(index, Formulas.requireIndex(it).toByte()) }
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

            action.pheromoneType?.let {
                pheromoneType[index] = it
            }

            if (action.physicalLink.isNotEmpty()) {
                action.physicalLink.forEach { (cellGenomeIdToConnectWith, linkData) ->

                    val linkedCellIndex = organToIdToIndex.get(organIndex[index], cellGenomeIdToConnectWith)
                    if (linkedCellIndex != -1) {
                        val neuralLinkIndex = neuralLinkEntity.linkIndexMap.get(index, linkedCellIndex)
                        if (linkData != null && linkData.isNeuronal) {
                            val isLink1NeuralDirected: Boolean = linkData.directedNeuronLink == cellGenomeId[index]
                            val linkColor = (linkData.color ?: Color.CYAN).toIntBits()

                            if (neuralLinkIndex == -1) {
                                val cellIndex: Int = index
                                val otherCellIndex: Int = linkedCellIndex

                                worldCommandsManager.worldCommandBuffer[threadId].push(
                                    type = WorldCommandType.ADD_NEURAL_LINK,
                                    booleans = booleanArrayOf(isLink1NeuralDirected),
                                    ints = intArrayOf(
                                        cellIndex,
                                        otherCellIndex,
                                        linkColor,
                                        getGeneration(cellIndex),
                                        getGeneration(otherCellIndex)
                                    )
                                )
                            } else {
                                neuralLinkEntity.isLink1NeuralDirected[neuralLinkIndex] = isLink1NeuralDirected
                                neuralLinkEntity.color[neuralLinkIndex] = linkColor
                            }
                        } else {
                            //Удаление нейро-линка
                            if (neuralLinkIndex != -1) {
                                worldCommandsManager.worldCommandBuffer[threadId].push(
                                    type = WorldCommandType.DELETE_NEURAL_LINK,
                                    ints = intArrayOf(neuralLinkIndex, neuralLinkEntity.getGeneration(neuralLinkIndex))
                                )
                            }
                        }
                    }
                }
            }

            energy[index] -= energyNecessaryToMutate[index] - 0.7f
        }
    }

    /**
     * Свои данные клетки (SpecialModData: глаз, хвост, продюсер, контроллер…) при мутации.
     *
     * Тип сменился — старый слот удаляется, новый заводится командой: слоты выдаёт общий
     * аллокатор SpecialModDataEntity, из параллельной фазы его трогать нельзя. Какого вида
     * были данные, неважно — слот один на клетку. Тип тот же — клетка сама решает, что
     * поменять (Cell.mutateSpecialData), и новое значение пишется в её собственный слот.
     */
    private fun mutateSpecialData(index: Int, threadId: Int, action: Action, typeChanged: Boolean, newCell: Cell) {
        // -1 — своих данных нет или их команда ADD_SPECIAL_MOD_DATA ещё не применилась.
        val slot = specialEntity.specialTypeIndexes[index]
        if (typeChanged) {
            if (slot != -1) {
                worldCommandsManager.worldCommandBuffer[threadId].push(
                    type = WorldCommandType.DELETE_SPECIAL_MOD_DATA,
                    ints = intArrayOf(index, specialEntity.getSpecialModDataGeneration(index), cellEntity.getGeneration(index))
                )
            }
            val data = newCell.createSpecialData(action) ?: return
            worldCommandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.ADD_SPECIAL_MOD_DATA,
                ints = intArrayOf(index, worldCommandsManager.pushSpecialModData(threadId, data), cellEntity.getGeneration(index))
            )
        } else if (slot != -1) {
            val current = specialEntity.getSpecialData(index) ?: return
            newCell.mutateSpecialData(current, action)?.let { specialEntity.setSpecialData(index, it) }
        }
    }

    override fun dispose() {

    }

}
