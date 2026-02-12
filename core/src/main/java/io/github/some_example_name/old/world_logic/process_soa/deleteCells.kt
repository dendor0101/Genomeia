package io.github.some_example_name.old.world_logic.process_soa

import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import java.util.*

class CellDeletionBuffer(threadAmount: Int, perThreadCapacity: Int, private val cellManager: CellManager) {
    private val totalCapacity = threadAmount * perThreadCapacity
    private val deleteBuffer = IntArray(totalCapacity)
    private var deleteBufferSize = 0

    fun collect(deletedCellsLists: Array<IntArray>, deletedCellsSizes: IntArray) {
        deleteBufferSize = 0
        for (i in deletedCellsSizes.indices) {
            val size = deletedCellsSizes[i]
            if (size < 0) continue
            for (j in 0..size) {
                deleteBuffer[deleteBufferSize++] = deletedCellsLists[i][j]
            }
            deletedCellsSizes[i] = -1
        }
    }

    fun flush() {
        if (deleteBufferSize == 0) return

        Arrays.sort(deleteBuffer, 0, deleteBufferSize)

        var lastId = -1
        for (i in deleteBufferSize - 1 downTo 0) {
            val id = deleteBuffer[i]
            if (id != lastId) {
                cellManager.deleteCell(id)
                lastId = id
            }
        }

        deleteBufferSize = 0
    }
}


private fun CellManager.deleteCell(cellId: Int) {
    subManager.addCell(
        x[cellId],
        y[cellId],
        0f,
        0f
    )

    val gridX = (x[cellId] / CELL_SIZE).toInt()
    val gridY = (y[cellId] / CELL_SIZE).toInt()
    gridManager.removeCell(gridX, gridY, cellId)

    if (cellId != cellLastId) {

        val gridXDelete = (x[cellLastId] / CELL_SIZE).toInt()
        val gridYDelete = (y[cellLastId] / CELL_SIZE).toInt()
        gridManager.removeCell(gridXDelete, gridYDelete, cellLastId)
        gridManager.addCell(gridXDelete, gridYDelete, cellId)

        id[cellId] = id[cellLastId]
        parentId[cellId] = parentId[cellLastId]
        firstChildId[cellId] = firstChildId[cellLastId]
        organismId[cellId] = organismId[cellLastId]
        gridId[cellId] = gridId[cellLastId]
        x[cellId] = x[cellLastId]
        y[cellId] = y[cellLastId]
        angle[cellId] = angle[cellLastId]
        vx[cellId] = vx[cellLastId]
        vy[cellId] = vy[cellLastId]
        vxOld[cellId] = vxOld[cellLastId]
        vyOld[cellId] = vyOld[cellLastId]
        ax[cellId] = ax[cellLastId]
        ay[cellId] = ay[cellLastId]
        colorR[cellId] = colorR[cellLastId]
        colorG[cellId] = colorG[cellLastId]
        colorB[cellId] = colorB[cellLastId]
        energyNecessaryToDivide[cellId] = energyNecessaryToDivide[cellLastId]
        energyNecessaryToMutate[cellId] = energyNecessaryToMutate[cellLastId]
        neuronImpulseInput[cellId] = neuronImpulseInput[cellLastId]
        dragCoefficient[cellId] = dragCoefficient[cellLastId]
        isAliveWithoutEnergy[cellId] = isAliveWithoutEnergy[cellLastId]
        isNeuronTransportable[cellId] = isNeuronTransportable[cellLastId]
        effectOnContact[cellId] = effectOnContact[cellLastId]
        isDividedInThisStage[cellId] = isDividedInThisStage[cellLastId]
        isMutateInThisStage[cellId] = isMutateInThisStage[cellLastId]
        cellType[cellId] = cellType[cellLastId]
        energy[cellId] = energy[cellLastId]
        tickRestriction[cellId] = tickRestriction[cellLastId]

        val base = cellId * MAX_LINK_AMOUNT
        val baseLast = cellLastId * MAX_LINK_AMOUNT
        val amountLast = linksAmount[cellLastId]
        for (j in 0 until amountLast) {
            val idxLast = baseLast + j
            val idx = base + j
            links[idx] = links[idxLast]
            linkIndexMap.remove(links1[links[idx]], links2[links[idx]])
            getOrganism(organismId[links1[links[idx]]]).linkIdMap.remove(id[links1[links[idx]]], id[links2[links[idx]]])
            if (cellLastId == links1[links[idx]]) links1[links[idx]] = cellId
            if (cellLastId == links2[links[idx]]) links2[links[idx]] = cellId
            linkIndexMap.put(links1[links[idx]], links2[links[idx]], links[idx])
            getOrganism(organismId[links1[links[idx]]]).linkIdMap.put(id[links1[links[idx]]], id[links2[links[idx]]], links[idx])
        }
        linksAmount[cellId] = linksAmount[cellLastId]


        activationFuncType[cellId] = activationFuncType[cellLastId]
        a[cellId] = a[cellLastId]
        b[cellId] = b[cellLastId]
        c[cellId] = c[cellLastId]
        dTime[cellId] = dTime[cellLastId]
        remember[cellId] = remember[cellLastId]
        isSum[cellId] = isSum[cellLastId]
        angleDiff[cellId] = angleDiff[cellLastId]
        colorDifferentiation[cellId] = colorDifferentiation[cellLastId]
        visibilityRange[cellId] = visibilityRange[cellLastId]
        speed[cellId] = speed[cellLastId]
    }

    id[cellLastId] = -1
    parentId[cellLastId] = -1
    firstChildId[cellLastId] = -1
    organismId[cellLastId] = -1
    gridId[cellLastId] = -1
    x[cellLastId] = 0f
    y[cellLastId] = 0f
    angle[cellLastId] = 0f
    vx[cellLastId] = 0f
    vy[cellLastId] = 0f
    vxOld[cellLastId] = 0f
    vyOld[cellLastId] = 0f
    ax[cellLastId] = 0f
    ay[cellLastId] = 0f
    colorR[cellLastId] = 1f
    colorG[cellLastId] = 1f
    colorB[cellLastId] = 1f
    energyNecessaryToDivide[cellLastId] = 2f
    energyNecessaryToMutate[cellLastId] = 2f
    neuronImpulseInput[cellLastId] = 0f
    dragCoefficient[cellLastId] = 0.93f
    isAliveWithoutEnergy[cellLastId] = 200
    isNeuronTransportable[cellLastId] = true
    effectOnContact[cellLastId] = false
    isDividedInThisStage[cellLastId] = false
    isMutateInThisStage[cellLastId] = false
    cellType[cellLastId] = 0
    energy[cellLastId] = 0f
    tickRestriction[cellLastId] = 0

    val baseLast = cellLastId * MAX_LINK_AMOUNT
    val amountLast = linksAmount[cellLastId]
    for (j in 0 until amountLast) {
        val idxLast = baseLast + j
        links[idxLast] = -1
    }
    linksAmount[cellLastId] = 0

    activationFuncType[cellLastId] = 0
    a[cellLastId] = 0f
    b[cellLastId] = 0f
    c[cellLastId] = 0f
    dTime[cellLastId] = -1f
    remember[cellLastId] = 0f
    isSum[cellLastId] = true
    angleDiff[cellLastId] = 0f
    colorDifferentiation[cellLastId] = 7
    visibilityRange[cellLastId] = 170f
    speed[cellLastId] = 0f

    cellLastId--
}
