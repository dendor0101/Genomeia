package io.github.some_example_name.old.world_logic.process_soa

import io.github.some_example_name.old.world_logic.CellManager
import java.util.*

class LinkDeletionBuffer(threadAmount: Int, perThreadCapacity: Int, private val cellManager: CellManager) {
    private val totalCapacity = threadAmount * perThreadCapacity
    private val deleteBuffer = IntArray(totalCapacity)
    private var deleteBufferSize = 0

    fun collect(deletedLinkLists: Array<IntArray>, deletedLinkSizes: IntArray) {
        deleteBufferSize = 0
        for (i in deletedLinkSizes.indices) {
            val size = deletedLinkSizes[i]
            if (size < 0) continue
            for (j in 0..size) {
                deleteBuffer[deleteBufferSize++] = deletedLinkLists[i][j]
            }
            deletedLinkSizes[i] = -1
        }
    }

    fun flush() {
        if (deleteBufferSize == 0) return

        Arrays.sort(deleteBuffer, 0, deleteBufferSize)

        var lastId = -1
        for (i in deleteBufferSize - 1 downTo 0) {
            val id = deleteBuffer[i]
            if (id != lastId) {
                cellManager.deleteLink(id)
                lastId = id
            }
        }

        deleteBufferSize = 0
    }
}

private fun CellManager.deleteLink(linkId: Int) {
    linkIndexMap.remove(links1[linkId], links2[linkId])
    getOrganism(organismId[links1[linkId]]).linkIdMap.remove(id[links1[linkId]], id[links2[linkId]])
    deleteLinkedCellLink(links1[linkId], linkId)
    deleteLinkedCellLink(links2[linkId], linkId)

    if (linkId != linksLastId) {
        if (isNeuronLink[linkId]) {
            neuronImpulseInput[directedNeuronLink[linkId]] = 0f
        }
        deleteLinkedCellLink(links1[linksLastId], linksLastId)
        deleteLinkedCellLink(links2[linksLastId], linksLastId)
        addLink(links1[linksLastId], linkId)
        addLink(links2[linksLastId], linkId)

        links1[linkId] = links1[linksLastId]
        links2[linkId] = links2[linksLastId]
        linksNaturalLength[linkId] = linksNaturalLength[linksLastId]
        isNeuronLink[linkId] = isNeuronLink[linksLastId]
        directedNeuronLink[linkId] = directedNeuronLink[linksLastId]
        degreeOfShortening[linkId] = degreeOfShortening[linksLastId]
        isStickyLink[linkId] = isStickyLink[linksLastId]

        linkIndexMap.put(links1[linkId], links2[linkId], linkId)
        getOrganism(organismId[links1[linkId]]).linkIdMap.put(id[links1[linkId]], id[links2[linkId]], linkId)
    } else {
        if (isNeuronLink[linksLastId]) {
            neuronImpulseInput[directedNeuronLink[linksLastId]] = 0f
        }
    }

    links1[linksLastId] = -1
    links2[linksLastId] = -1

    linksNaturalLength[linksLastId] = -10f
    isNeuronLink[linksLastId] = false
    directedNeuronLink[linksLastId] = -1
    degreeOfShortening[linksLastId] = 1f
    isStickyLink[linksLastId] = false

    linksLastId -= 1
}
