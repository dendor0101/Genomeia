package io.github.some_example_name.old.world_logic.genomic_transformations

import io.github.some_example_name.old.world_logic.cells.base.createCellType
import io.github.some_example_name.old.world_logic.cells.controllerIndexesLol
import io.github.some_example_name.old.good_one.isEqualLinks
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import io.github.some_example_name.old.organisms.AddLink
import kotlin.collections.forEach
import kotlin.collections.set

fun CellManager.mutateCell(index: Int, threadId: Int) {

    if (!isMutateInThisStage[index] && energy[index] >= energyNecessaryToMutate[index]) {
        isMutateInThisStage[index] = true

        val action = cellActions[index]?.mutate ?: return

        decrementMutationCounter[threadId].add(organismIndex[index])

        action.color?.let {
            colorR[index] = it.r
            colorG[index] = it.g
            colorB[index] = it.b
        }
        action.funActivation?.let { activationFuncType[index] = it }
        action.a?.let { a[index] = it }
        action.b?.let { b[index] = it }
        action.c?.let { c[index] = it }
        action.isSum?.let { isSum[index] = it }
        action.angleDirected?.let {
            angleDiff[index] = it
        }
        action.colorRecognition?.let { colorDifferentiation[index] = it }
        action.lengthDirected?.let { visibilityRange[index] = it }
        var isFromMuscleToAnother = false
        action.cellType?.let {
            if (cellType[index] == 16 && it != 16) {
                controllerIndexesLol.remove(cellGenomeId[index])
            }
            if (cellType[index] != 16 && it == 16) {
                controllerIndexesLol[cellGenomeId[index]] = false
            }
            isFromMuscleToAnother = cellType[index] == 5 && it != 5
            cellType[index] = it
            createCellType(cellType[index], index, false, threadId)
        }

        if (isFromMuscleToAnother) {
            val base = index * MAX_LINK_AMOUNT
            val amount = linksAmount[index]

            for (i in 0 until amount) {
                val idx = base + i
                val linkId = links[idx]
                degreeOfShortening[linkId] = 1f
            }
        }

        if (action.physicalLink.isNotEmpty()) {
            //TODO With the new command system, take this out into multithreading
            val gridX = (x[index] / CELL_SIZE).toInt()
            val gridY = (y[index] / CELL_SIZE).toInt()
            val closestCells = collectCells(gridX, gridY)
            val idToIndexAssociation = closestCells.filter { organismIndex[it] == organismIndex[index] && it != index}
                .associateBy { cellGenomeId[it] }

            action.physicalLink.forEach { (cellGenomeIdToConnectWith, linkData) ->
                val linkedCellIndex = idToIndexAssociation[cellGenomeIdToConnectWith]
                if (linkedCellIndex != null) {
                    val linkId = linkIndexMap.get(index, linkedCellIndex)
                    if (linkData != null) {
                        if (linkId == -1) {
                            if (linksAmount[index] < MAX_LINK_AMOUNT && linksAmount[linkedCellIndex] < MAX_LINK_AMOUNT && linkData.length != null) {
                                if (linkData.isNeuronal && linkData.directedNeuronLink != cellGenomeId[index]
                                    && linkData.directedNeuronLink != cellGenomeIdToConnectWith) {
                                    throw Exception("Incorrect logic in the neural-link")
                                }

                                addLinks[threadId].add(
                                    AddLink(
                                        cellIndex = index,
                                        otherCellIndex = linkedCellIndex,
                                        linksLength = if (isEqualLinks) 30f else linkData.length,
                                        degreeOfShortening = 1f,
                                        isStickyLink = false,
                                        isNeuronLink = linkData.isNeuronal,
                                        isLink1NeuralDirected = linkData.directedNeuronLink == cellGenomeId[index]
                                    )
                                )
                            }
                        } else {
                            if (!linkData.isNeuronal) {
                                val cellIndex = if (isLink1NeuralDirected[linkId]) links1[linkId] else links2[linkId]
                                neuronImpulseInput[cellIndex] = 0f
                                neuronImpulseOutput[cellIndex] = 0f
                            } else {
                                val cellLink1Index = links1[linkId]
                                val cellLink2Index = links2[linkId]
                                val cellLink1Id = cellGenomeId[cellLink1Index]
                                val cellLink2Id = cellGenomeId[cellLink2Index]

                                isLink1NeuralDirected[linkId] = linkData.directedNeuronLink == cellLink1Id

                                if (linkData.directedNeuronLink != cellLink1Id && linkData.directedNeuronLink != cellLink2Id) {
                                    throw Exception("Incorrect logic in the neural-link ${linkData.directedNeuronLink} ${cellGenomeId[index]} ${cellGenomeIdToConnectWith}")
                                }
                            }

                            isNeuronLink[linkId] = linkData.isNeuronal
                        }
                    } else {
                        if (linkId != -1) {
                            addToDeleteList(threadId, linkId)
                        }
                    }
                }
            }
        }

        energy[index] -= energyNecessaryToMutate[index]
    }
}
