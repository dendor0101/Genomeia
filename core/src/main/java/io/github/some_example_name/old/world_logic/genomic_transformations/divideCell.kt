package io.github.some_example_name.old.world_logic.genomic_transformations

import com.badlogic.gdx.math.MathUtils
import io.github.some_example_name.old.world_logic.cells.base.createCellType
import io.github.some_example_name.old.screens.GlobalSettings.SAFE_DIVISION_MODE
import io.github.some_example_name.old.good_one.isEqualLinks
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import io.github.some_example_name.old.organisms.AddCell
import kotlin.math.PI

fun CellManager.divideCell(index: Int, threadId: Int) {

    if (!isDividedInThisStage[index] && energy[index] >= energyNecessaryToDivide[index]) {
        isDividedInThisStage[index] = true

        val action = cellActions[index]?.divide ?: return

        addCells[threadId].add(
            AddCell(
                action = action,
                parentX = x[index],
                parentY = y[index],
                parentAngle = angle[index],
                parentId = cellGenomeId[index],
                parentOrganismId = organismIndex[index],
                parentIndex = index
            )
        )

        if (parentIndex[index] == -1) {
            angleDiff[index] = angle[index] + PI.toFloat() - (action.angle ?: return)
        }

        energy[index] -= energyNecessaryToDivide[index]
    }
    return
}

fun CellManager.collectCells(gridX: Int, gridY: Int, radius: Int = 3): IntArray {
    val list = ArrayList<Int>()
    for (dy in -radius..radius) {
        for (dx in -radius..radius) {
            val arr = gridManager.getCells(gridX + dx, gridY + dy)
            for (v in arr) list.add(v)
        }
    }
    return list.toIntArray()
}

fun CellManager.addCell(addCell: AddCell) {
    val action = addCell.action

    val addCellIndex =
        if (deadCellsStackAmount >= 0) deadCellsStack[deadCellsStackAmount--]
        else ++cellLastId

    isAliveCell[addCellIndex] = true
    cellGeneration[addCellIndex]++

    cellGenomeId[addCellIndex] = action.id
    cellType[addCellIndex] = action.cellType ?: throw Exception("Forgot cellType")
    createCellType(
        cellType = cellType[addCellIndex],
        cellId = addCellIndex,
        genomeIndex = getOrganism(addCell.parentOrganismId).genomeIndex
    )
    action.color?.let {
        colorR[addCellIndex] = it.r
        colorG[addCellIndex] = it.g
        colorB[addCellIndex] = it.b
    }

    var parentLinkLength = 1f

    if (action.physicalLink.isNotEmpty()) {
        //TODO With the new command system, take this out into multithreading
        val gridX = (addCell.parentX / CELL_SIZE).toInt()
        val gridY = (addCell.parentY / CELL_SIZE).toInt()
        val closestCells = collectCells(gridX, gridY)
        val idToIndexAssociation = closestCells.filter { organismIndex[it] == addCell.parentOrganismId }
            .associateBy { cellGenomeId[it] }

        if (SAFE_DIVISION_MODE || isGenomeEditor) {
            parentLinkLength = action.physicalLink[addCell.parentId]?.length ?: 1f
        }

        action.physicalLink.forEach { (cellGenomeIdToConnectWith, linkData) ->
            val otherCellIndex = idToIndexAssociation[cellGenomeIdToConnectWith]
            if (linkData != null && otherCellIndex != null) {
                if (linksAmount[addCellIndex] < MAX_LINK_AMOUNT && linksAmount[otherCellIndex] < MAX_LINK_AMOUNT && linkData.length != null) {
                    val addLinkId =
                        if (deadLinksStackAmount >= 0) deadLinksStack[deadLinksStackAmount--]
                        else ++linksLastId

                    isAliveLink[addLinkId] = true
                    linkGeneration[addLinkId]++

                    linksNaturalLength[addLinkId] = if (isEqualLinks) 30f else linkData.length
                    degreeOfShortening[addLinkId] = 1f
                    isStickyLink[addLinkId] = false
                    isNeuronLink[addLinkId] = linkData.isNeuronal

                    if (linkData.isNeuronal && linkData.directedNeuronLink != action.id
                        && linkData.directedNeuronLink != cellGenomeIdToConnectWith) {
                        throw Exception("Incorrect logic in the neural-link")
                    }
                    isLink1NeuralDirected[addLinkId] = linkData.directedNeuronLink == action.id

                    links1[addLinkId] = addCellIndex
                    links2[addLinkId] = otherCellIndex
                    linkIndexMap.put(addCellIndex, otherCellIndex, addLinkId)
                    addLink(addCellIndex, addLinkId)
                    addLink(otherCellIndex, addLinkId)
                }
            }
        }
    }
    action.angleDirected?.let { angleDiff[addCellIndex] = it }
    action.colorRecognition?.let { colorDifferentiation[addCellIndex] = it }
    action.lengthDirected?.let { visibilityRange[addCellIndex] = it }
    action.funActivation?.let { activationFuncType[addCellIndex] = it }
    action.a?.let { a[addCellIndex] = it }
    action.b?.let { b[addCellIndex] = it }
    action.c?.let { c[addCellIndex] = it }
    action.isSum?.let { isSum[addCellIndex] = it }

    val genomeAngle = action.angle ?: throw Exception("Forgot angle")

    val divideAngle = genomeAngle + addCell.parentAngle
    x[addCellIndex] = addCell.parentX + MathUtils.cos(divideAngle) * parentLinkLength
    y[addCellIndex] = addCell.parentY + MathUtils.sin(divideAngle) * parentLinkLength
    angle[addCellIndex] = divideAngle

    parentIndex[addCellIndex] = addCell.parentIndex
    if (parentIndex[addCell.parentIndex] == -1) {
        parentIndex[addCell.parentIndex] = addCellIndex
    }

    if (action.physicalLink.isEmpty()) {
        parentIndex[addCellIndex] = -1
    }

    gridId[addCellIndex] = gridManager.addCell(
        (x[addCellIndex] / CELL_SIZE).toInt(),
        (y[addCellIndex] / CELL_SIZE).toInt(),
        addCellIndex
    ) {
        killCell(addCellIndex, 0)
    }
    //Получается если спавним зиготу, то тогда создается новый организм
    //TODO Подумать также для одноклеточных или с зацикленным делением, чтобы не создавать новы организм
    // It turns out that if we spawn a zygote, then a new organism is created.
    // TODO: Consider the same for single-celled organisms or those with a cycle of division, so as not to create a new organism.
    if (cellType[addCellIndex] != 18) {
        organismIndex[addCellIndex] = addCell.parentOrganismId
    }
}
