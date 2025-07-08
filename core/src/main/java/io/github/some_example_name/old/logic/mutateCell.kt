package io.github.some_example_name.old.logic

import io.github.some_example_name.old.good_one.genomeStage
import io.github.some_example_name.old.good_one.genomeStageInstruction

fun CellManager.mutateCell(index: Int, threadId: Int) {
    if (genomeStage == 0) return
    if (!isMutateInThisStage[index] && energy[index] >= energyNecessaryToMutate[index]) {
        isMutateInThisStage[index] = true
        val action = genomeStageInstruction[genomeStage - 1].cellActions[id[index-WALL_AMOUNT]]?.mutate ?: return

        action.color?.let {
            colorR[index] = it.r
            colorG[index] = it.g
            colorB[index] = it.b
        }
        action.funActivation?.let { activationFuncType[index] = it }
        action.a?.let { a[index] = it }
        action.b?.let { b[index] = it }
        action.c?.let { c[index] = it }
        action.angleDirected?.let { angle[index] = it }
        action.startDirectionId?.let { startAngleId[index] = it + WALL_AMOUNT }
        action.cellType?.let {
            cellType[index] = it
            createCellType(cellType[index], index, false)
        }

        action.physicalLink.forEach { (linkedCellId_without_wall_cells, linkData) ->
            val linkedCellId = linkedCellId_without_wall_cells + WALL_AMOUNT
            val linkId = linkIdMap.get(index, linkedCellId)
            if (linkData != null) {
                if (linkId == -1) {
                    linksLastId++
                    linksLength[linksLastId] = linkData.length
                    degreeOfShortening[linksLastId] = 1f
                    isStickyLink[linksLastId] = false
                    isNeuronLink[linksLastId] = linkData.isNeuronal
                    directedNeuronLink[linksLastId] = (linkData.directedNeuronLink ?: -1) + WALL_AMOUNT
                    println("lol ${(linkData.directedNeuronLink ?: -1) + WALL_AMOUNT}")
                    links1[linksLastId] = index
                    links2[linksLastId] = linkedCellId
                    linkIdMap.put(index, linkedCellId, linksLastId)
                    addLink(index, linksLastId)
                    addLink(linkedCellId, linksLastId)
                } else {
                    if (!linkData.isNeuronal && directedNeuronLink[linkId] != -1) {
                        neuronImpulseImport[directedNeuronLink[linkId]] = 0f
                    }
                    isNeuronLink[linkId] = linkData.isNeuronal
                    directedNeuronLink[linkId] = (linkData.directedNeuronLink ?: -1) + WALL_AMOUNT
                }
            } else {
                addToDeleteList(threadId, linkId)
            }
        }
        energy[index] -= energyNecessaryToMutate[index]
    }
}
