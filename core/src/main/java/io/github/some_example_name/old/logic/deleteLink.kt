package io.github.some_example_name.old.logic

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
//    if (linkId > linksLastId) {
//        println("OMG it's bigger $linkId $linksLastId")
//        return
//    }
//    println("deleteLink $linkId $linksLastId")
    linkIdMap.remove(links1[linkId], links2[linkId])
    deleteLinkedCellLink(links1[linkId], linkId)
    deleteLinkedCellLink(links2[linkId], linkId)

    if (linkId != linksLastId) {
        if (isNeuronLink[linkId]) {
            neuronImpulseImport[directedNeuronLink[linkId]] = 0f
        }
        deleteLinkedCellLink(links1[linksLastId], linksLastId)
        deleteLinkedCellLink(links2[linksLastId], linksLastId)
        addLink(links1[linksLastId], linkId)
        addLink(links2[linksLastId], linkId)

        links1[linkId] = links1[linksLastId]
        links2[linkId] = links2[linksLastId]
        linksLength[linkId] = linksLength[linksLastId]
        isNeuronLink[linkId] = isNeuronLink[linksLastId]
        directedNeuronLink[linkId] = directedNeuronLink[linksLastId]
        degreeOfShortening[linkId] = degreeOfShortening[linksLastId]
        isStickyLink[linkId] = isStickyLink[linksLastId]

        linkIdMap.put(links1[linkId], links2[linkId], linkId)
    } else {
        if (isNeuronLink[linksLastId]) {
            neuronImpulseImport[directedNeuronLink[linksLastId]] = 0f
        }
    }

    //TODO разобраться где менять у startAngleId
    startAngleId[links1[linksLastId]] = -1
    startAngleId[links2[linksLastId]] = -1

    links1[linksLastId] = -1
    links2[linksLastId] = -1
    linksLength[linksLastId] = -10f
    isNeuronLink[linksLastId] = false
    directedNeuronLink[linksLastId] = -1
    degreeOfShortening[linksLastId] = 1f
    isStickyLink[linksLastId] = false


    linksLastId -= 1
//    println("deleteLink $linkId $linksLastId")
}
