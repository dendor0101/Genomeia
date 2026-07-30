package io.github.some_example_name.old.entities

import io.github.some_example_name.old.core.utils.UnorderedIntPairMap

class NeuralLinkEntity(
    linksStartMaxAmount: Int,
    val cellEntity: CellEntity,
    val isEditor: Boolean
) : Entity(linksStartMaxAmount) {

    var links1 = IntArray(maxAmount) { -1 }
    var links2 = IntArray(maxAmount) { -1 }
    var linksGeneration1 = IntArray(maxAmount) { -1 }
    var linksGeneration2 = IntArray(maxAmount) { -1 }
    var isLink1NeuralDirected = BooleanArray(maxAmount)
    var color = IntArray(maxAmount)
    val linkIndexMap = UnorderedIntPairMap(10_000)

    fun addNeuralLink(
        cellIndex: Int,
        otherCellIndex: Int,
        isLink1NeuralDirected: Boolean,
        color: Int
    ): Int {
        val addLinkIndex = add()

        links1[addLinkIndex] = cellIndex
        links2[addLinkIndex] = otherCellIndex
        linksGeneration1[addLinkIndex] = cellEntity.getGeneration(cellIndex)
        linksGeneration2[addLinkIndex] = cellEntity.getGeneration(otherCellIndex)
        this.isLink1NeuralDirected[addLinkIndex] = isLink1NeuralDirected
        this.color[addLinkIndex] = color

        cellEntity.linkAmount[cellIndex] ++
        cellEntity.linkAmount[otherCellIndex] ++

        linkIndexMap.put(cellIndex, otherCellIndex, addLinkIndex)

        with(cellEntity) {
            val cellA = cellList[cellType[cellIndex].toInt()]
            val cellB = cellList[cellType[otherCellIndex].toInt()]
            if (cellA.doesNeedNeuralConnections) {
                addNeuralConnection(cellIndex, addLinkIndex)
            }
            if (cellB.doesNeedNeuralConnections) {
                addNeuralConnection(otherCellIndex, addLinkIndex)
            }
        }

        return addLinkIndex
    }

    fun deleteNeuralLink(linkIndex: Int, linkGeneration: Int? = null) {
        if (isAlive[linkIndex] && (linkGeneration == null || getGeneration(linkIndex) == linkGeneration)) {
            delete(linkIndex)

            val cellA = links1[linkIndex]
            val cellB = links2[linkIndex]

            if (!isEditor) {
                linkIndexMap.remove(cellA, cellB)
            }

            links1[linkIndex] = -1
            links2[linkIndex] = -1
            linksGeneration1[linkIndex] = -1
            linksGeneration2[linkIndex] = -1
            isLink1NeuralDirected[linkIndex] = false
            color[linkIndex] = 0

            cellEntity.removeNeuralConnection(cellA, linkIndex)
            cellEntity.removeNeuralConnection(cellB, linkIndex)
        }
    }

    override fun onCopy() {
        // TODO
    }

    override fun onPaste() {
        // TODO
    }

    override fun onClear(bound: Int) {
        links1.clear(-1)
        links2.clear(-1)
        linksGeneration1.clear(-1)
        linksGeneration2.clear(-1)
        isLink1NeuralDirected.clear(false)
        color.clear()
        linkIndexMap.clear()
    }

    override fun onResize(oldMax: Int) {
        links1 = links1.resize(-1)
        links2 = links2.resize(-1)
        linksGeneration1 = linksGeneration1.resize(-1)
        linksGeneration2 = linksGeneration2.resize(-1)
        isLink1NeuralDirected = isLink1NeuralDirected.resize(false)
        color = color.resize()
    }
}
