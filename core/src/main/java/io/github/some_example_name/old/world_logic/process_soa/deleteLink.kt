package io.github.some_example_name.old.world_logic.process_soa

import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import kotlin.math.atan2

fun CellManager.deleteLink(linkId: Int) {
    if (!isAliveLink[linkId]) return
    val c1 = links1[linkId]
    val c2 = links2[linkId]

    linkIndexMap.remove(c1, c2)

    deleteLinkedCellLink(c1, linkId)
    deleteLinkedCellLink(c2, linkId)

    isAliveLink[linkId] = false

    deadLinksStackAmount ++
    deadLinksStack[deadLinksStackAmount] = linkId

    if (isNeuronLink[linkId]) {
        val cellIndex = if (isLink1NeuralDirected[linkId]) c1 else c2
        neuronImpulseInput[cellIndex] = 0f
        neuronImpulseOutput[cellIndex] = 0f
    }

    links1[linkId] = -1
    links2[linkId] = -1

    linksNaturalLength[linkId] = -10f
    isNeuronLink[linkId] = false
    isLink1NeuralDirected[linkId] = false
    degreeOfShortening[linkId] = 1f
    isStickyLink[linkId] = false

    if (parentIndex[c1] == c2) {
        reinitParentIndex(c1)
    }

    if (parentIndex[c2] == c1) {
        reinitParentIndex(c2)
    }
}

private fun CellManager.reinitParentIndex(cellId: Int) {
    val base = cellId * MAX_LINK_AMOUNT
    val amount = linksAmount[cellId]
    if (amount == 0) {
        parentIndex[cellId] = -1
        return
    } else {
        val idx = base + 0
        val linkId = links[idx]
        val c1 = links1[linkId]
        val c2 = links2[linkId]
        val otherCellId = if (c1 != cellId) c1 else if (c2 != cellId) c2 else {
            parentIndex[cellId] = -1
            return
        }

        val dx = x[cellId] - x[otherCellId]
        val dy = y[cellId] - y[otherCellId]
        val angleToChild = atan2(dy, dx)

        parentIndex[cellId] = otherCellId
        angleDiff[cellId] = angle[cellId] - angleToChild
    }
}
