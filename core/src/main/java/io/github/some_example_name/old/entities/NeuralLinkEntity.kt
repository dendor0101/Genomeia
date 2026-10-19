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
    val linkEditorIndexMap = UnorderedIntPairMap(100)

    /**
     * Живых нейросвязей вне арен. Нейрофаза обходит их отдельным однопоточным проходом и
     * пропускает его целиком, пока их нет. Выводится из arenaOwner, не сохраняется.
     */
    var poolLinkCount = 0
        private set

    /**
     * Сколько нейросвязей создано с последней очистки. Только для пика нейросвязей при
     * запекании (EditorSimulationSystem): на состояние мира не влияет и не сохраняется.
     */
    var createdCount = 0L
        private set

    fun addNeuralLink(
        cellIndex: Int,
        otherCellIndex: Int,
        isLink1NeuralDirected: Boolean,
        color: Int,
        cellGeneration: Int = LinkEntity.NO_GENERATION_CHECK,
        otherCellGeneration: Int = LinkEntity.NO_GENERATION_CHECK,
    ): Int {
        if (!isCellUsable(cellIndex, cellGeneration) ||
            !isCellUsable(otherCellIndex, otherCellGeneration)
        ) {
            return -1
        }

        // Слот — из арены нейросвязей организма (OrganEntity.takeNeuralSlot): так нейрофазу
        // можно считать по организмам параллельно, а освободившийся слот достаётся следующей
        // нейросвязи того же тела. Арены нет (редактор, геном старше пика нейросвязей) или
        // она полна — общий пул; такие связи нейрофаза досчитывает одним потоком.
        //
        // Полна она быть не должна: ёмкость — пик одновременно живых за весь рост, снятый
        // с запасом на любой порядок действий внутри стадии (EditorSimulationSystem). Пул —
        // страховка, а не штатный путь, поэтому без исключения.
        val organEntity = cellEntity.organEntity
        val organIndex = cellEntity.organIndex[cellIndex]
        val arenaSlot = if (organEntity.hasNeuralArena(organIndex)) organEntity.takeNeuralSlot(organIndex) else -1
        val addLinkIndex = if (arenaSlot == -1) add() else addAt(arenaSlot, arenaOwner = organIndex)
        if (arenaSlot == -1) poolLinkCount++
        createdCount++

        links1[addLinkIndex] = cellIndex
        links2[addLinkIndex] = otherCellIndex
        linksGeneration1[addLinkIndex] = cellEntity.getGeneration(cellIndex)
        linksGeneration2[addLinkIndex] = cellEntity.getGeneration(otherCellIndex)
        this.isLink1NeuralDirected[addLinkIndex] = isLink1NeuralDirected
        this.color[addLinkIndex] = color

        linkIndexMap.put(cellIndex, otherCellIndex, addLinkIndex)
        linkEditorIndexMap.put(cellIndex, otherCellIndex, addLinkIndex)

        cellEntity.addNeuralConnection(cellIndex, addLinkIndex)
        cellEntity.addNeuralConnection(otherCellIndex, addLinkIndex)

        return addLinkIndex
    }

    fun deleteNeuralLink(linkIndex: Int, linkGeneration: Int? = null) {
        if (isAlive[linkIndex] && (linkGeneration == null || getGeneration(linkIndex) == linkGeneration)) {
            // Владелец — запомненный при выдаче: клетки связи к этому моменту могли умереть.
            val owner = arenaOwnerOf(linkIndex)
            if (owner == -1) poolLinkCount-- else cellEntity.organEntity.releaseNeuralSlot(owner, linkIndex)
            delete(linkIndex)

            val cellA = links1[linkIndex]
            val cellB = links2[linkIndex]

            linkIndexMap.remove(cellA, cellB)
            if (!isEditor) {
                linkEditorIndexMap.remove(cellA, cellB)
            }

            cellEntity.removeNeuralConnection(cellA, linkIndex)
            cellEntity.removeNeuralConnection(cellB, linkIndex)

            links1[linkIndex] = -1
            links2[linkIndex] = -1
            linksGeneration1[linkIndex] = -1
            linksGeneration2[linkIndex] = -1
            isLink1NeuralDirected[linkIndex] = false
            color[linkIndex] = 0
        }
    }

    fun detachAllNeuralLinks(cellIndex: Int) {
        val links = cellEntity.neuralConnections.get(cellIndex) ?: return

        while (!links.isEmpty) {
            val linkIndex = links.getInt(0)

            if (!isAlive[linkIndex]) {
                cellEntity.removeNeuralConnection(cellIndex, linkIndex)
                continue
            }

            deleteNeuralLink(linkIndex)
        }
    }

    private fun isCellUsable(cellIndex: Int, expectedGeneration: Int): Boolean {
        if (cellIndex < 0 || !cellEntity.isAlive[cellIndex]) return false
        if (expectedGeneration == LinkEntity.NO_GENERATION_CHECK) return true
        return cellEntity.getGeneration(cellIndex) == expectedGeneration
    }

    override fun persistFields(state: EntityState) {
        state.ints(::links1)
        state.ints(::links2)
        state.ints(::linksGeneration1)
        state.ints(::linksGeneration2)
        state.booleans(::isLink1NeuralDirected)
        state.ints(::color)
    }

    /** Карты по паре клеток выводятся из самих связей. Списки нейросвязей клеток сохраняет CellEntity. */
    override fun restoreAfterLoad() {
        linkIndexMap.clear()
        linkEditorIndexMap.clear()
        poolLinkCount = 0
        for (i in 0 until aliveList.size) {
            val linkIndex = aliveList.getInt(i)
            linkIndexMap.put(links1[linkIndex], links2[linkIndex], linkIndex)
            linkEditorIndexMap.put(links1[linkIndex], links2[linkIndex], linkIndex)
            if (arenaOwnerOf(linkIndex) == -1) poolLinkCount++
        }
    }

    override fun onCopy() {
        // TODO
    }

    override fun onPaste() {
        // TODO
    }

    override fun onClear(bound: Int) {
        poolLinkCount = 0
        createdCount = 0
        links1.clear(-1)
        links2.clear(-1)
        linksGeneration1.clear(-1)
        linksGeneration2.clear(-1)
        isLink1NeuralDirected.clear(false)
        color.clear()
        linkIndexMap.clear()
        linkEditorIndexMap.clear()
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
