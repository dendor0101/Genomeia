package io.github.some_example_name.old.entities

import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.utils.UnorderedIntPairMap
import io.github.some_example_name.old.systems.physics.GridManager
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlin.math.sqrt

class LinkEntity(
    linksStartMaxAmount: Int,
    val cellEntity: CellEntity,
    val gridManager: GridManager,
    val particleEntity: ParticleEntity,
    val diContext: DIContext
) : Entity(linksStartMaxAmount) {
    var links1 = IntArray(maxAmount) { -1 }
    var links2 = IntArray(maxAmount) { -1 }
    var linksGeneration1 = IntArray(maxAmount) { -1 }
    var linksGeneration2 = IntArray(maxAmount) { -1 }
    var linksNaturalLength = FloatArray(maxAmount) { -10f }
    val linkIndexMap = UnorderedIntPairMap(100_000)

    var linkPhase = BooleanArray(maxAmount)
    var assignedThread = ByteArray(maxAmount) { -1 }
    var linkToListPosition = IntArray(maxAmount) { -1 }

    fun registerNewLink(
        linkIndex: Int,
        evenLinkLists: Array<IntArrayList>,
        oddLinkLists: Array<IntArrayList>
    ) {
        val cellIndex = links1[linkIndex]
        //TODO тут есть проблема которая при особых условиях приведет к состоянию гонки
        val chunk = cellEntity.getGridId(cellIndex) / diContext.chunkSize
        val phase = chunk % 2
        val threadId = (chunk - phase) / 2

        if (threadId !in 0 until diContext.threadCount) throw Exception("threadId out of threadCount")

        linkPhase[linkIndex] = phase == 0
        assignedThread[linkIndex] = threadId.toByte()

        val lists = if (phase == 0) evenLinkLists else oddLinkLists
        val list = lists[threadId]

        val position = list.size
        list.add(linkIndex)
        linkToListPosition[linkIndex] = position
    }

    // === НОВЫЙ МЕТОД ДЛЯ БЫСТРОГО УДАЛЕНИЯ ===
    fun removeLinkFromLists(
        linkIndex: Int,
        evenLinkLists: Array<IntArrayList>,
        oddLinkLists: Array<IntArrayList>
    ) {
        val phase = linkPhase[linkIndex]
        val threadId = assignedThread[linkIndex].toInt()

        val list = if (phase) evenLinkLists[threadId] else oddLinkLists[threadId]
        val pos = linkToListPosition[linkIndex]

        // защита
        if (pos < 0 || pos >= list.size || list.getInt(pos) != linkIndex) {
            linkToListPosition[linkIndex] = -1
            return
        }

        // === O(1) удаление: swap with last ===
        val lastPos = list.size - 1
        if (pos != lastPos) {
            val lastLinkIndex = list.getInt(lastPos)
            list.set(pos, lastLinkIndex)
            linkToListPosition[lastLinkIndex] = pos
        }
        list.removeInt(lastPos)

        // очистка
        linkToListPosition[linkIndex] = -1
        linkPhase[linkIndex] = false
        assignedThread[linkIndex] = -1
    }

    fun addLink(
        cellIndex: Int,
        otherCellIndex: Int,
        linksLength: Float,
    ): Int {
        val addLinkIndex = add()

        links1[addLinkIndex] = cellIndex
        links2[addLinkIndex] = otherCellIndex
        linksGeneration1[addLinkIndex] = cellEntity.getGeneration(cellIndex)
        linksGeneration2[addLinkIndex] = cellEntity.getGeneration(otherCellIndex)

        this.linksNaturalLength[addLinkIndex] = linksLength

        cellEntity.linkAmount[cellIndex] ++
        cellEntity.linkAmount[otherCellIndex] ++

        linkIndexMap.put(cellIndex, otherCellIndex, addLinkIndex)

        return addLinkIndex
    }

    fun deleteLink(linkIndex: Int, linkGeneration: Int? = null) {
        if (isAlive[linkIndex] && (linkGeneration == null || getGeneration(linkIndex) == linkGeneration)) {
            delete(linkIndex)

            val cellA = links1[linkIndex]
            val cellB = links2[linkIndex]

            linkIndexMap.remove(cellA, cellB)

            cellEntity.linkAmount[cellA] --
            cellEntity.linkAmount[cellB] --

            links1[linkIndex] = -1
            links2[linkIndex] = -1
            linksGeneration1[linkIndex] = -1
            linksGeneration2[linkIndex] = -1

            linksNaturalLength[linkIndex] = -10f
        }
    }

    fun reinitParentLink(linkIndex: Int) {
        val cellA = links1[linkIndex]
        val cellB = links2[linkIndex]

        if (cellEntity.parentIndex[cellA] == cellB) {
            cellEntity.parentIndex[cellA] = -1
        }

        if (cellEntity.parentIndex[cellB] == cellA) {
            cellEntity.parentIndex[cellB] = -1
        }
    }

    fun reinitParentIndex(cellIndex: Int, newParentIndex: Int) = with(cellEntity) {
        parentIndex[cellIndex] = newParentIndex
        val otherCellIndex = parentIndex[cellIndex]

        val dx = getX(cellIndex) - getX(otherCellIndex)
        val dy = getY(cellIndex) - getY(otherCellIndex)
        val len = sqrt(dx * dx + dy * dy)
        val dirCos = dx / len
        val dirSin = dy / len

        parentIndex[cellIndex] = otherCellIndex

        angleCompensationCos[cellIndex] = angleCos[cellIndex] * dirCos + angleSin[cellIndex] * dirSin
        angleCompensationSin[cellIndex] = angleSin[cellIndex] * dirCos - angleCos[cellIndex] * dirSin
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
        linksGeneration1.clear(-1)
        linksGeneration2.clear(-1)
        linksNaturalLength.clear(-10f)
        linkIndexMap.clear()
        linkPhase.clear(false)
        assignedThread.clear(-1)
        linkToListPosition.clear(-1)
    }

    override fun onResize(oldMax: Int) {
        links1 = links1.resize(-1)
        links2 = links2.resize(-1)
        linksGeneration1 = linksGeneration1.resize(-1)
        linksGeneration2 = linksGeneration2.resize(-1)
        linksNaturalLength = linksNaturalLength.resize(-10f)
        linkPhase = linkPhase.resize(false)
        assignedThread = assignedThread.resize(-1)
        linkToListPosition = linkToListPosition.resize(-1)
    }
}
