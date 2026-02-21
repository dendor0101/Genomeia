package io.github.some_example_name.old.world_logic.process_soa

import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE

fun CellManager.deleteCell(cellIndex: Int) {
    if (!isAliveCell[cellIndex]) return

    if (organismIndex[cellIndex] != -1) {

        if (!isDividedInThisStage[cellIndex]) {
            val organism = organismManager.organisms[organismIndex[cellIndex]]
            organism.divideCounterThisStage--
        }

        if (!isMutateInThisStage[cellIndex]) {
            val organism = organismManager.organisms[organismIndex[cellIndex]]
            organism.mutateCounterThisStage--
        }
    }

    val gridX = (x[cellIndex] / CELL_SIZE).toInt()
    val gridY = (y[cellIndex] / CELL_SIZE).toInt()
    gridManager.removeCell(gridX, gridY, cellIndex)

    isAliveCell[cellIndex] = false

    deadCellsStackAmount ++
    deadCellsStack[deadCellsStackAmount] = cellIndex

    cellGenomeId[cellIndex] = -1
    parentIndex[cellIndex] = -1
    cellActions[cellIndex] = null
    organismIndex[cellIndex] = -1
    gridId[cellIndex] = -1
    x[cellIndex] = 0f
    y[cellIndex] = 0f
    angle[cellIndex] = 0f
    vx[cellIndex] = 0f
    vy[cellIndex] = 0f
    vxOld[cellIndex] = 0f
    vyOld[cellIndex] = 0f
    ax[cellIndex] = 0f
    ay[cellIndex] = 0f
    colorR[cellIndex] = 1f
    colorG[cellIndex] = 1f
    colorB[cellIndex] = 1f
    energyNecessaryToDivide[cellIndex] = 2f
    energyNecessaryToMutate[cellIndex] = 1f
    neuronImpulseInput[cellIndex] = 0f
    neuronImpulseOutput[cellIndex] = 0f
    dragCoefficient[cellIndex] = 0.93f
    isAliveWithoutEnergy[cellIndex] = 200
    isNeuronTransportable[cellIndex] = true
    effectOnContact[cellIndex] = false
    isDividedInThisStage[cellIndex] = true
    isMutateInThisStage[cellIndex] = true
    cellType[cellIndex] = 0
    energy[cellIndex] = 0f
    tickRestriction[cellIndex] = 0

    while (linksAmount[cellIndex] > 0) {
        val base = cellIndex * MAX_LINK_AMOUNT
        val linkId = links[base + 0]
        if (linkId != -1) {
            deleteLink(linkId)
        }
    }

    linksAmount[cellIndex] = 0
    val base = cellIndex * MAX_LINK_AMOUNT
    links.fill(-1, base, base + MAX_LINK_AMOUNT)

    activationFuncType[cellIndex] = 0
    a[cellIndex] = 1f
    b[cellIndex] = 0f
    c[cellIndex] = 0f
    dTime[cellIndex] = -1f
    remember[cellIndex] = 0f
    isSum[cellIndex] = true
    angleDiff[cellIndex] = 0f
    colorDifferentiation[cellIndex] = 7
    visibilityRange[cellIndex] = 170f
    speed[cellIndex] = 0f
}
