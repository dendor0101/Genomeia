package io.github.some_example_name.old.world_logic.genomic_transformations

import com.badlogic.gdx.math.MathUtils
import io.github.some_example_name.old.world_logic.cells.base.createCellType
import io.github.some_example_name.old.screens.GlobalSettings.SAFE_DIVISION_MODE
import io.github.some_example_name.old.good_one.isEqualLinks
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import io.github.some_example_name.old.organisms.AddCell

fun CellManager.divideCell(index: Int, threadId: Int) {

    val organism = organismManager.organisms[organismId[index]]
    if (organism.justChangedStage) {
        isDividedInThisStage[index] = false
    }

    if (!isDividedInThisStage[index]) {
        if (energy[index] >= energyNecessaryToDivide[index] && organism.timerToGrowAfterStage < 0) {
            isDividedInThisStage[index] = true
            //TODO Разобраться с этим, но это видимо пока только зацикливанием нужно будет
            // TODO We need to figure this out, but for now it will probably only be possible to loop it.
            if (organism.genomeIndex == -1) return
            val currentStage =
                genomeManager.genomes[organism.genomeIndex].genomeStageInstruction[organism.stage]
            val action = currentStage.cellActions[id[index]]?.divide ?: return

            addCells[threadId].add(
                AddCell(
                    action = action,
                    parentX = x[index],
                    parentY = y[index],
                    parentAngle = angle[index],
                    parentId = id[index],
                    parentOrganismId = organismId[index],
                    parentIndex = index
                )
            )

            //TODO Костыль с углами
            //TODO Есть баг если клетке, которая является по сути второй клеткой организма поменять angleDiff на
            //любой другой угол, то система с делением из ломается
            // TODO: Angle crutch
            // TODO: There's a bug: If you change the angleDiff of a cell, which is essentially the second cell in the body, to
            // any other angle, the division system breaks down.
            if (parentId[index] == -1 && firstChildId[index] == -1) {
                angleDiff[index] = angle[index] - (action.angle ?: return)
            }

            energy[index] -= energyNecessaryToDivide[index]
        }
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
    cellLastId++
    id[cellLastId] = action.id
    cellType[cellLastId] = action.cellType ?: throw Exception("Forgot cellType")
    createCellType(
        cellType = cellType[cellLastId],
        cellId = cellLastId,
        genomeIndex = getOrganism(addCell.parentOrganismId).genomeIndex
    )
    action.color?.let {
        colorR[cellLastId] = it.r
        colorG[cellLastId] = it.g
        colorB[cellLastId] = it.b
    }

    var parentLinkLength = 1f

    if (action.physicalLink.isNotEmpty()) {
        val gridX = (addCell.parentX / CELL_SIZE).toInt()
        val gridY = (addCell.parentY / CELL_SIZE).toInt()

        val cells = collectCells(gridX, gridY)

        val mapIndexLinkId =
            cells.filter { organismId[it] == addCell.parentOrganismId }.associateBy { id[it] }

        if (SAFE_DIVISION_MODE || isGenomeEditor) {
            parentLinkLength = action.physicalLink[addCell.parentId]?.length ?: 1f
        }

        action.physicalLink.forEach { (linkToConnectWith, linkData) ->
            val otherCellIndex = mapIndexLinkId[linkToConnectWith]
            if (linkData != null && otherCellIndex != null) {
                val linkId = linkIndexMap.get(cellLastId, otherCellIndex)
                if (linkId == -1) {
                    if (linksAmount[cellLastId] < MAX_LINK_AMOUNT && linksAmount[otherCellIndex] < MAX_LINK_AMOUNT && linkData.length != null) {
                        linksLastId++
                        linksNaturalLength[linksLastId] = if (isEqualLinks) 30f else linkData.length
                        degreeOfShortening[linksLastId] = 1f
                        isStickyLink[linksLastId] = false
                        isNeuronLink[linksLastId] = linkData.isNeuronal
                        directedNeuronLink[linksLastId] = linkData.directedNeuronLink ?: -1

                        links1[linksLastId] = cellLastId
                        links2[linksLastId] = otherCellIndex
                        linkIndexMap.put(cellLastId, otherCellIndex, linksLastId)
                        getOrganism(addCell.parentOrganismId).linkIdMap.put(id[cellLastId], id[otherCellIndex], linksLastId)
                        addLink(cellLastId, linksLastId)
                        addLink(otherCellIndex, linksLastId)
                    }
                } else {
                    if (!linkData.isNeuronal && directedNeuronLink[linkId] != -1) {
                        neuronImpulseInput[directedNeuronLink[linkId]] = 0f
                    }

                    isNeuronLink[linkId] = linkData.isNeuronal
                    directedNeuronLink[linkId] = linkData.directedNeuronLink ?: -1
                }
            }
        }
    }
    action.angleDirected?.let { angleDiff[cellLastId] = it }
    action.colorRecognition?.let { colorDifferentiation[cellLastId] = it }
    action.lengthDirected?.let { visibilityRange[cellLastId] = it }
    action.funActivation?.let { activationFuncType[cellLastId] = it }
    action.a?.let { a[cellLastId] = it }
    action.b?.let { b[cellLastId] = it }
    action.c?.let { c[cellLastId] = it }
    action.isSum?.let { isSum[cellLastId] = it }

    val genomeAngle = action.angle ?: throw Exception("Forgot angle")

    val divideAngle = genomeAngle + addCell.parentAngle
    x[cellLastId] = addCell.parentX + MathUtils.cos(divideAngle) * parentLinkLength
    y[cellLastId] = addCell.parentY + MathUtils.sin(divideAngle) * parentLinkLength
    angle[cellLastId] = divideAngle

    //TODO Костыль с углами
    // TODO Crutch with corners
    parentId[cellLastId] = addCell.parentId

    if (firstChildId[addCell.parentIndex] == -1) {
        firstChildId[addCell.parentIndex] = action.id
    }

//    pikSounds.random().play(GlobalSettings.SOUND_VOLUME / 100f)
    gridId[cellLastId] = gridManager.addCell(
        (x[cellLastId] / CELL_SIZE).toInt(),
        (y[cellLastId] / CELL_SIZE).toInt(),
        cellLastId
    )
    //Получается если спавним зиготу, то тогда создается новый организм
    //TODO Подумать также для одноклеточных или с зацикленным делением, чтобы не создавать новы организм
    // It turns out that if we spawn a zygote, then a new organism is created.
    // TODO: Consider the same for single-celled organisms or those with a cycle of division, so as not to create a new organism.
    if (cellType[cellLastId] != 18) {
        organismId[cellLastId] = addCell.parentOrganismId
    }
}
