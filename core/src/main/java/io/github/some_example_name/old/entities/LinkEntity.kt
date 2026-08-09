package io.github.some_example_name.old.entities

import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.utils.UnorderedIntPairMap
import io.github.some_example_name.old.systems.physics.GridManager
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.math.sqrt

@Serializable
class LinkEntity(
    @Transient val linksStartMaxAmount: Int = 0
) : Entity(linksStartMaxAmount) {

    @Transient lateinit var cellEntity: CellEntity
    @Transient lateinit var gridManager: GridManager
    @Transient lateinit var particleEntity: ParticleEntity
    @Transient lateinit var diContext: DIContext

    constructor(
        linksStartMaxAmount: Int,
        cellEntity: CellEntity,
        gridManager: GridManager,
        particleEntity: ParticleEntity,
        diContext: DIContext
    ) : this(linksStartMaxAmount) {
        loadEntity(cellEntity, gridManager, particleEntity, diContext)
    }

    @ProtoNumber(1) var links1 = IntArray(maxAmount) { -1 }
    @ProtoNumber(2) var links2 = IntArray(maxAmount) { -1 }
    @ProtoNumber(3) var linksGeneration1 = IntArray(maxAmount) { -1 }
    @ProtoNumber(4) var linksGeneration2 = IntArray(maxAmount) { -1 }
    @ProtoNumber(5) var linksNaturalLength = FloatArray(maxAmount) { -10f }
    @ProtoNumber(6) var isNeuronLink = BooleanArray(maxAmount)
    @ProtoNumber(7) var isLink1NeuralDirected = BooleanArray(maxAmount)
    @ProtoNumber(8) var isStickyLink = BooleanArray(maxAmount) { false }
    @ProtoNumber(9) var isLongNeuralLink = BooleanArray(maxAmount) { false }
    @ProtoNumber(10) var color = IntArray(maxAmount)

    @Transient val linkIndexMap = UnorderedIntPairMap(100_000)

    @Transient var linkPhase = BooleanArray(maxAmount)
    @Transient var assignedThread = ByteArray(maxAmount) { -1 }
    @Transient var linkToListPosition = IntArray(maxAmount) { -1 }

    fun registerNewLink(
        linkIndex: Int,
        evenLinkLists: Array<IntArrayList>,
        oddLinkLists: Array<IntArrayList>
    ) {
        val cellIndex = links1[linkIndex]
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

    fun removeLinkFromLists(
        linkIndex: Int,
        evenLinkLists: Array<IntArrayList>,
        oddLinkLists: Array<IntArrayList>
    ) {
        val phase = linkPhase[linkIndex]
        val threadId = assignedThread[linkIndex].toInt()

        val list = if (phase) evenLinkLists[threadId] else oddLinkLists[threadId]
        val pos = linkToListPosition[linkIndex]

        if (pos < 0 || pos >= list.size || list.getInt(pos) != linkIndex) {
            linkToListPosition[linkIndex] = -1
            return
        }

        val lastPos = list.size - 1
        if (pos != lastPos) {
            val lastLinkIndex = list.getInt(lastPos)
            list.set(pos, lastLinkIndex)
            linkToListPosition[lastLinkIndex] = pos
        }
        list.removeInt(lastPos)

        linkToListPosition[linkIndex] = -1
        linkPhase[linkIndex] = false
        assignedThread[linkIndex] = -1
    }

    fun addLink(
        cellIndex: Int,
        otherCellIndex: Int,
        linksLength: Float,
        isStickyLink: Boolean,
        isNeuronLink: Boolean,
        isLink1NeuralDirected: Boolean,
        color: Int
    ): Int {
        val addLinkIndex = add()

        links1[addLinkIndex] = cellIndex
        links2[addLinkIndex] = otherCellIndex
        linksGeneration1[addLinkIndex] = cellEntity.getGeneration(cellIndex)
        linksGeneration2[addLinkIndex] = cellEntity.getGeneration(otherCellIndex)

        this.linksNaturalLength[addLinkIndex] = linksLength
        this.isNeuronLink[addLinkIndex] = isNeuronLink
        this.isLink1NeuralDirected[addLinkIndex] = isLink1NeuralDirected
        this.isStickyLink[addLinkIndex] = isStickyLink
        this.color[addLinkIndex] = color

        isLongNeuralLink[addLinkIndex] = linksLength <= 0

        cellEntity.linkAmount[cellIndex] ++
        cellEntity.linkAmount[otherCellIndex] ++

        linkIndexMap.put(cellIndex, otherCellIndex, addLinkIndex)

        if (isNeuronLink) {
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
        }

        return addLinkIndex
    }

    fun deleteLink(linkIndex: Int, linkGeneration: Int? = null) {
        if (isAlive[linkIndex] && (linkGeneration == null || getGeneration(linkIndex) == linkGeneration)) {
            delete(linkIndex)

            val cellA = links1[linkIndex]
            val cellB = links2[linkIndex]

            linkIndexMap.remove(cellA, cellB)

            if (isNeuronLink[linkIndex]) {
                val cellIndex = if (isLink1NeuralDirected[linkIndex]) cellA else cellB
                cellEntity.neuronImpulseInput[cellIndex] = 0f
                cellEntity.neuronImpulseOutput[cellIndex] = 0f
            }

            cellEntity.linkAmount[cellA] --
            cellEntity.linkAmount[cellB] --

            links1[linkIndex] = -1
            links2[linkIndex] = -1
            linksGeneration1[linkIndex] = -1
            linksGeneration2[linkIndex] = -1

            linksNaturalLength[linkIndex] = -10f
            isNeuronLink[linkIndex] = false
            isLink1NeuralDirected[linkIndex] = false
            isStickyLink[linkIndex] = false
            isLongNeuralLink[linkIndex] = false
            color[linkIndex] = 0

            cellEntity.removeNeuralConnection(cellA, linkIndex)
            cellEntity.removeNeuralConnection(cellB, linkIndex)
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

    fun loadEntity(
        cellEntity: CellEntity,
        gridManager: GridManager,
        particleEntity: ParticleEntity,
        diContext: DIContext
    ) {
        this.cellEntity = cellEntity
        this.gridManager = gridManager
        this.particleEntity = particleEntity
        this.diContext = diContext
    }

    fun serializeEntity() {
        super.saveSerialize()
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
        rebuildTransient()
    }

    fun copyFrom(other: LinkEntity) {
        copyBaseFrom(other)
        copyInto(other.links1, links1)
        copyInto(other.links2, links2)
        copyInto(other.linksGeneration1, linksGeneration1)
        copyInto(other.linksGeneration2, linksGeneration2)
        copyInto(other.linksNaturalLength, linksNaturalLength)
        copyInto(other.isNeuronLink, isNeuronLink)
        copyInto(other.isLink1NeuralDirected, isLink1NeuralDirected)
        copyInto(other.isStickyLink, isStickyLink)
        copyInto(other.isLongNeuralLink, isLongNeuralLink)
        copyInto(other.color, color)
        rebuildTransient()
    }

    private fun rebuildTransient() {
        linkIndexMap.clear()
        for (i in 0..lastId) {
            if (isAlive[i]) {
                linkIndexMap.put(links1[i], links2[i], i)
            }
        }
        linkPhase = BooleanArray(maxAmount)
        assignedThread = ByteArray(maxAmount) { -1 }
        linkToListPosition = IntArray(maxAmount) { -1 }
    }

    /**
     * Раскладывает загруженные связи по потоковым спискам.
     *
     * Без этого связи не попадают в LinkPhysicsSystem.iterateLinksInParallel и организм после
     * загрузки просто рассыпается: клетки есть, а пружины между ними не считаются.
     *
     * Вызывать строго после particleEntity.restoreGridManager(): registerNewLink читает
     * gridId клетки, чтобы определить чанк и поток.
     *
     * @return количество связей, которые не удалось разложить (ссылаются на мёртвые клетки)
     */
    fun restoreLinkLists(
        evenLinkLists: Array<IntArrayList>,
        oddLinkLists: Array<IntArrayList>
    ): Int {
        var skipped = 0
        for (i in 0..lastId) {
            if (!isAlive[i]) continue

            val cellIndex = links1[i]
            val otherCellIndex = links2[i]
            val isBroken = cellIndex < 0 || otherCellIndex < 0 ||
                !cellEntity.isAliveAndSameGen(cellIndex, linksGeneration1[i]) ||
                !cellEntity.isAliveAndSameGen(otherCellIndex, linksGeneration2[i]) ||
                // registerNewLink читает gridId клетки через её частицу
                cellEntity.getParticleIndex(cellIndex) < 0

            if (isBroken) {
                skipped++
                continue
            }

            registerNewLink(i, evenLinkLists, oddLinkLists)
        }
        return skipped
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
        isNeuronLink.clear(false)
        isLink1NeuralDirected.clear(false)
        isStickyLink.clear(false)
        isLongNeuralLink.clear(false)
        color.clear()
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
        isNeuronLink = isNeuronLink.resize(false)
        isLink1NeuralDirected = isLink1NeuralDirected.resize(false)
        isStickyLink = isStickyLink.resize(false)
        isLongNeuralLink = isLongNeuralLink.resize(false)
        color = color.resize()
        linkPhase = linkPhase.resize(false)
        assignedThread = assignedThread.resize(-1)
        linkToListPosition = linkToListPosition.resize(-1)
    }
}
