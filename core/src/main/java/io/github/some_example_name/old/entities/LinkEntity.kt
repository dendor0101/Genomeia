package io.github.some_example_name.old.entities

import io.github.some_example_name.old.core.utils.UnorderedIntPairMap
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem.Companion.MAX_LINK_AMOUNT
import kotlin.math.atan2

class LinkEntity(
    linksStartMaxAmount: Int,
    val cellEntity: CellEntity
) : Entity(linksStartMaxAmount) {
    var links1 = IntArray(maxAmount) { -1 }
    var links2 = IntArray(maxAmount) { -1 }
    var linksNaturalLength = FloatArray(maxAmount) { -10f }
    var isNeuronLink = BooleanArray(maxAmount)
    var isLink1NeuralDirected = BooleanArray(maxAmount)
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
        cellEntity.addLink(cellIndex, addLinkId)
        cellEntity.addLink(otherCellIndex, addLinkId)
    }

    fun deleteLink(linkIndex: Int, linkGeneration: Int? = null) {
        if (isAlive[linkIndex] && (linkGeneration == null
                || getGeneration(linkIndex) == linkGeneration)) {
            delete(linkIndex)

            val cellA = links1[linkIndex]
            val cellB = links2[linkIndex]
            linkIndexMap.remove(cellA, cellB)
            cellEntity.deleteLinkedCellLink(cellA, linkIndex)
            cellEntity.deleteLinkedCellLink(cellB, linkIndex)

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
                reinitParentIndex(cellA)
            }

            if (cellEntity.parentIndex[cellB] == cellA) {
                reinitParentIndex(cellB)
            }
        }
    }

    private fun reinitParentIndex(cellId: Int) = with(cellEntity) {
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

            val dx = getX(cellId) - getX(otherCellId)
            val dy = getY(cellId) - getY(otherCellId)
            val angleToChild = atan2(dy, dx)

            parentIndex[cellId] = otherCellId
            angleDiff[cellId] = angle[cellId] - angleToChild
        }
    }

    override fun onCopy() {
        TODO("Not yet implemented")
    }

    override fun onPaste() {
        TODO("Not yet implemented")
    }

    override fun onClear(bound: Int) {
        links1.clear(-1)
        links2.clear(-1)
        linksNaturalLength.clear(-10f)
        isNeuronLink.clear(false)
        isLink1NeuralDirected.clear(false)
        degreeOfShortening.clear(1f)
        isStickyLink.clear(false)
        linkIndexMap.clear()
    }

    override fun onResize(oldMax: Int) {
        links1 = links1.resize(-1)
        links2 = links2.resize(-1)
        linksNaturalLength = linksNaturalLength.resize(-10f)
        isNeuronLink = isNeuronLink.resize(false)
        isLink1NeuralDirected = isLink1NeuralDirected.resize(false)
        degreeOfShortening = degreeOfShortening.resize(1f)
        isStickyLink = isStickyLink.resize(false)
    }
}
