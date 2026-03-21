package io.github.some_example_name.old.entities

import io.github.some_example_name.old.core.utils.UnorderedIntPairMap
import java.util.BitSet

class LinkEntity (
    linksStartMaxAmount: Int,
    val cellEntity: CellEntity
): Entity(linksStartMaxAmount) {
    var links1 = IntArray(maxAmount) { -1 }
    var links2 = IntArray(maxAmount) { -1 }
    var linksNaturalLength = FloatArray(maxAmount) { -10f }
    var isNeuronLink = BitSet(maxAmount)
    var isLink1NeuralDirected = BitSet(maxAmount)
    var degreeOfShortening = FloatArray(maxAmount) { 1f }
    var isStickyLink = BooleanArray(maxAmount) { false }
    val linkIndexMap = UnorderedIntPairMap(1_000_000)

    fun addLink(
        cellIndex: Int,
        otherCellIndex: Int,
        linksLength: Float,
        degreeOfShortening: Float,
        isStickyLink: Boolean,
        isNeuronLink: Boolean,
        isLink1NeuralDirected: Boolean,
    ) {
        val addLinkId = add()

        links1[addLinkId] = cellIndex
        links2[addLinkId] = otherCellIndex
        this.linksNaturalLength[addLinkId] = linksLength
        this.isNeuronLink[addLinkId] = isNeuronLink
        this.isLink1NeuralDirected[addLinkId] = isLink1NeuralDirected
        this.degreeOfShortening[addLinkId] = degreeOfShortening
        this.isStickyLink[addLinkId] = isStickyLink
        linkIndexMap.put(cellIndex, otherCellIndex, addLinkId)
    }

    fun deleteLink(linkIndex: Int) {
        delete(linkIndex)

        val cellA = links1[linkIndex]
        val cellB = links2[linkIndex]
        linkIndexMap.remove(cellA, cellB)

        if (isNeuronLink[linkIndex]) {
            val cellIndex = if (isLink1NeuralDirected[linkIndex]) cellA else cellB
            cellEntity.neuronImpulseInput[cellIndex] = 0f
            cellEntity.neuronImpulseOutput[cellIndex] = 0f
        }

        links1[linkIndex] = -1
        links2[linkIndex] = -1

        linksNaturalLength[linkIndex] = -10f
        isNeuronLink[linkIndex] = false
        isLink1NeuralDirected[linkIndex] = false
        degreeOfShortening[linkIndex] = 1f
        isStickyLink[linkIndex] = false

        if (cellEntity.parentIndex[cellA] == cellB) {
            //TODO придумать как переназначать parentIndex при отрывании связи, потому что у клетки сейчас нет списка связок для итерирования
            cellEntity.parentIndex[cellA] = -1
            /*

    private fun reinitParentIndex(cellId: Int) {
        cellEntity.parentIndex[cellId] = -1
        TODO()
//        TODO придумать как переназначать parentIndex при отрывании связи, потому что у клетки сейчас нет списка связок для итерирования
//        val base = cellId * MAX_LINK_AMOUNT
//        val amount = linksAmount[cellId]
//        if (amount == 0) {
//            parentIndex[cellId] = -1
//            return
//        } else {
//            val idx = base + 0
//            val linkId = links[idx]
//            val c1 = links1[linkId]
//            val c2 = links2[linkId]
//            val otherCellId = if (c1 != cellId) c1 else if (c2 != cellId) c2 else {
//                parentIndex[cellId] = -1
//                return
//            }
//
//            val dx = x[cellId] - x[otherCellId]
//            val dy = y[cellId] - y[otherCellId]
//            val angleToChild = atan2(dy, dx)
//
//            parentIndex[cellId] = otherCellId
//            angleDiff[cellId] = angle[cellId] - angleToChild
//        }
    }
*/
        }

        if (cellEntity.parentIndex[cellB] == cellA) {
            cellEntity.parentIndex[cellB] = -1
        }
    }

    override fun onCopy() {
        TODO("Not yet implemented")
    }

    override fun onPaste() {
        TODO("Not yet implemented")
    }

    override fun onClear(bound: Int) {
        links1.fill(-1, 0, bound)
        links2.fill(-1, 0, bound)
        linksNaturalLength.fill(-10f, 0, bound)
        isNeuronLink.clear()
        isLink1NeuralDirected.clear()
        degreeOfShortening.fill(1f, 0, bound)
        isStickyLink.fill(false, 0, bound)
        linkIndexMap.clear()
    }

    override fun onResize(oldMax: Int) {
        run {
            val old = links1
            links1 = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, links1, 0, oldMax)
        }
        run {
            val old = links2
            links2 = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, links2, 0, oldMax)
        }
        run {
            val old = linksNaturalLength
            linksNaturalLength = FloatArray(maxAmount) { -10f }
            System.arraycopy(old, 0, linksNaturalLength, 0, oldMax)
        }
        run {
            val old = isNeuronLink
            isNeuronLink = BitSet(maxAmount)
            isNeuronLink.or(old)
        }
        run {
            val old = isLink1NeuralDirected
            isLink1NeuralDirected = BitSet(maxAmount)
            isLink1NeuralDirected.or(old)
        }
        run {
            val old = degreeOfShortening
            degreeOfShortening = FloatArray(maxAmount) { 1f }
            System.arraycopy(old, 0, degreeOfShortening, 0, oldMax)
        }
        run {
            val old = isStickyLink
            isStickyLink = BooleanArray(maxAmount) { false }
            System.arraycopy(old, 0, isStickyLink, 0, oldMax)
        }
    }
}
