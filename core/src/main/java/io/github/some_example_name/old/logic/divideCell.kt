package io.github.some_example_name.old.logic

import com.badlogic.gdx.math.MathUtils
import io.github.some_example_name.old.good_one.genomeStage
import io.github.some_example_name.old.good_one.genomeStageInstruction
import io.github.some_example_name.old.good_one.pikSounds
import io.github.some_example_name.old.logic.GridManager.Companion.CELL_SIZE

var WALL_AMOUNT = 200

fun CellManager.divideCell(index: Int) {
    if (genomeStage == 0) return
    if (!isDividedInThisStage[index] && energy[index] >= energyNecessaryToDivide[index]) {
        isDividedInThisStage[index] = true
//        println("divideCell ${id[index-WALL_AMOUNT]}")
        val action = genomeStageInstruction[genomeStage - 1].cellActions[id[index-WALL_AMOUNT]]?.divide ?: return
//        println()
//        if (action.id == null) return
        val i = action.indexOfNew + WALL_AMOUNT

        isPhantom[i] = false
        x[i] = x[index] + MathUtils.cos(action.angle ?: return)
        y[i] = y[index] + MathUtils.sin(action.angle ?: return)
//        pikSounds.random().play()
        gridId[i] = gridManager.addCell(
            (x[i] / CELL_SIZE).toInt(),
            (y[i] / CELL_SIZE).toInt(),
            i
        )

        energy[index] -= energyNecessaryToDivide[index]
        return
    }
}

fun CellManager.genomeUpdate() {
    val changes = genomeStageInstruction[genomeStage - 1]
    changes.cellActions.forEach { (k, v) ->
        v.divide?.let { action ->
            cellLastId ++
            val index = action.indexOfNew + WALL_AMOUNT
            id[index] = action.id ?: ""
            isPhantom[index] = true
            cellType[index] = action.cellType ?: 0
            createCellType(cellType[index], index)
            action.color?.let {
                colorR[index] = it.r
                colorG[index] = it.g
                colorB[index] = it.b
            }
            action.physicalLink.forEach { (linkedCellId_without_wall_cells, linkData) ->
                val linkedCellId = linkedCellId_without_wall_cells + WALL_AMOUNT
                if (linkData != null) {
                    val linkId = linkIdMap.get(index, linkedCellId)
                    if (linkId == -1) {
                        linksLastId++
                        linksLength[linksLastId] = linkData.length
                        degreeOfShortening[linksLastId] = 1f
                        isStickyLink[linksLastId] = false
                        isNeuronLink[linksLastId] = linkData.isNeuronal
                        directedNeuronLink[linksLastId] = (linkData.directedNeuronLink ?: -1) + WALL_AMOUNT
                        links1[linksLastId] = index
                        links2[linksLastId] = linkedCellId
//                        println("directedNeuronLink ${directedNeuronLink[linksLastId]}")
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
                }
            }
            action.angleDirected?.let { angle[index] = it }
            action.startDirectionId?.let { startAngleId[index] = it + WALL_AMOUNT }
            action.funActivation?.let { activationFuncType[index] = it }
            action.a?.let { a[index] = it }
            action.b?.let { b[index] = it }
            action.c?.let { c[index] = it }
        }
    }
}
