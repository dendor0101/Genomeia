package io.github.some_example_name.old.world_logic.genomic_transformations

import io.github.some_example_name.old.world_logic.cells.base.createCellType
import io.github.some_example_name.old.world_logic.cells.controllerIndexesLol
import io.github.some_example_name.old.good_one.isEqualLinks
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import io.github.some_example_name.old.organisms.AddLink
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.collections.set

fun CellManager.mutateCell(index: Int, threadId: Int) {

    val organism = organismManager.organisms[organismId[index]]
    if (organism.justChangedStage) {
        isMutateInThisStage[index] = false
    }
    if (!isMutateInThisStage[index] && energy[index] >= energyNecessaryToMutate[index]) {
        isMutateInThisStage[index] = true
        //Разобраться с этим, но это видимо пока только зацикливанием нужно будет
        // We need to figure this out, but for now it will probably only be possible to loop it.
        if (organism.genomeIndex == -1) return
        val currentStage = genomeManager.genomes[organism.genomeIndex].genomeStageInstruction[organism.stage]
        val action = currentStage.cellActions[id[index]]?.mutate ?: return

        decrementMutationCounter[threadId].add(organismId[index])

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
                controllerIndexesLol.remove(id[index])
            }
            if (cellType[index] != 16 && it == 16) {
                controllerIndexesLol[id[index]] = false
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
            val gridX = (x[index] / CELL_SIZE).toInt()
            val gridY = (y[index] / CELL_SIZE).toInt()


            val cells = collectCells(gridX, gridY)

            val mapIndexLinkId =
                cells.filter { organismId[it] == organismId[index] && it != index}.associateBy { id[it] }

            action.physicalLink.forEach { (linkToConnectWith, linkData) ->
                val linkedCellId = mapIndexLinkId[linkToConnectWith]
                if (linkedCellId != null) {
                    val linkId = linkIndexMap.get(index, linkedCellId)
                    if (linkData != null) {
                        if (linkId == -1) {
                            if (linksAmount[index] < MAX_LINK_AMOUNT && linksAmount[linkedCellId] < MAX_LINK_AMOUNT && linkData.length != null) {
                                addLinks[threadId].add(
                                    AddLink(
                                        cellId = index,
                                        otherCellId = linkedCellId,
                                        linksLength = if (isEqualLinks) 30f else linkData.length,
                                        degreeOfShortening = 1f,
                                        isStickyLink = false,
                                        isNeuronLink = linkData.isNeuronal,
                                        directedNeuronLink = linkData.directedNeuronLink ?: -1,
                                    )
                                )
                            }
                        } else {
                            if (!linkData.isNeuronal && directedNeuronLink[linkId] != -1) {
                                neuronImpulseInput[directedNeuronLink[linkId]] = 0f
                            }

                            isNeuronLink[linkId] = linkData.isNeuronal

                            if (linkData.isNeuronal) {
                                directedNeuronLink[linkId] = linkData.directedNeuronLink ?: -1
                            }
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
