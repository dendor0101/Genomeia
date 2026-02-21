package io.github.some_example_name.old.world_logic

import io.github.some_example_name.old.genome.CellAction
import io.github.some_example_name.old.good_one.shader.ShaderManager
import io.github.some_example_name.old.world_logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.world_logic.ThreadManager.Companion.THREAD_COUNT
import io.github.some_example_name.old.good_one.utils.primitive_hash_map.UnorderedIntPairMap
import io.github.some_example_name.old.organisms.AddCell
import io.github.some_example_name.old.organisms.AddLink
import io.github.some_example_name.old.organisms.Organism
import io.github.some_example_name.old.substances.SubstanceAdd
import io.github.some_example_name.old.world_logic.GridManager.Companion.GRID_SIZE
import java.util.BitSet

open class CrossThreadEditable(
    cellsStartMaxAmount: Int,
    linksStartMaxAmount: Int,
    isGenomeEditor: Boolean
) : ShaderManager() {

    var grabbedCell = -1
    var grabbedX = 0f
    var grabbedY = 0f

    @Volatile
    var cellMaxAmount = cellsStartMaxAmount

    @Volatile
    var linksMaxAmount = linksStartMaxAmount

    @Volatile
    var cellLastId = -1

    @Volatile
    var linksLastId = -1

    var deadCellsStackAmount = -1
    var deadCellsStack = IntArray(250_000) { -1 }

    var deadLinksStackAmount = -1
    var deadLinksStack = IntArray(250_000) { -1 }

    //Cell
    var isAliveCell = BitSet(cellMaxAmount)
    var cellGeneration = IntArray(cellMaxAmount)
    var cellGenomeId = IntArray(cellMaxAmount) { -1 }
    var cellActions: Array<CellAction?> = arrayOfNulls(cellMaxAmount)
    var organismIndex = IntArray(cellMaxAmount) { -1 }
    var parentIndex = IntArray(cellMaxAmount) { -1 }
    var gridId = IntArray(cellMaxAmount) { -1 }
    var x = FloatArray(cellMaxAmount) //Рисовать
    var y = FloatArray(cellMaxAmount) //Рисовать
    var angle = FloatArray(cellMaxAmount) //не забыть добавлять в удаление
    var vx = FloatArray(cellMaxAmount)
    var vy = FloatArray(cellMaxAmount)
    var vxOld = FloatArray(cellMaxAmount)
    var vyOld = FloatArray(cellMaxAmount)
    var ax = FloatArray(cellMaxAmount)
    var ay = FloatArray(cellMaxAmount)
    var colorR = FloatArray(cellMaxAmount) { 1f } //Рисовать
    var colorG = FloatArray(cellMaxAmount) { 1f } //Рисовать
    var colorB = FloatArray(cellMaxAmount) { 1f } //Рисовать
    var energyNecessaryToDivide = FloatArray(cellMaxAmount) { 2f }  // TODO: Refer to cell type rather than cell
    var energyNecessaryToMutate = FloatArray(cellMaxAmount) { 1f }  // TODO: Refer to cell type rather than cell
    var neuronImpulseInput = FloatArray(cellMaxAmount)
    var neuronImpulseOutput = FloatArray(cellMaxAmount)
    var dragCoefficient = FloatArray(cellMaxAmount) { 0.93f }
    var isAliveWithoutEnergy = IntArray(cellMaxAmount) { 200 }//TODO перевести в ByteArray возможно будет более оптимизированная проверка
    var isNeuronTransportable = BooleanArray(cellMaxAmount) { false }// TODO: Refer to cell type rather than cell
    var effectOnContact = BooleanArray(cellMaxAmount) { false }// TODO: Refer to cell type rather than cell
    var isDividedInThisStage = BooleanArray(cellMaxAmount) { true }
    var isMutateInThisStage = BooleanArray(cellMaxAmount) { true }
    var cellType = IntArray(cellMaxAmount) //TODO перевести в ByteArray возможно будет более оптимизированная проверка
    var energy = FloatArray(cellMaxAmount) //Рисовать
    var tickRestriction = // TODO: Replace with something like time % 4
        IntArray(cellMaxAmount) //TODO перевести в ByteArray возможно будет более оптимизированная проверка
    var linksAmount =
        IntArray(cellMaxAmount) //TODO перевести в ByteArray возможно будет более оптимизированная проверка
    var links = IntArray(cellMaxAmount * MAX_LINK_AMOUNT) { -1 }

    //Neural
    var activationFuncType = IntArray(cellMaxAmount)
    var a = FloatArray(cellMaxAmount) { 1f }
    var b = FloatArray(cellMaxAmount)
    var c = FloatArray(cellMaxAmount)
    var dTime = FloatArray(cellMaxAmount) { -1f }
    var remember = FloatArray(cellMaxAmount)
    var isSum = BooleanArray(cellMaxAmount) { true }

    //Directed
    var angleDiff = FloatArray(cellMaxAmount)

    //Eye
    var colorDifferentiation = IntArray(cellMaxAmount) { 7 }
    var visibilityRange = FloatArray(cellMaxAmount) { 170f }

    //Tail
    var speed = FloatArray(cellMaxAmount)

    //Links
    var isAliveLink = BitSet(linksMaxAmount)
    var linkGeneration = IntArray(linksMaxAmount)
    var links1 = IntArray(linksMaxAmount) { -1 }
    var links2 = IntArray(linksMaxAmount) { -1 }
    var linksNaturalLength = FloatArray(linksMaxAmount) { -10f }
    var isNeuronLink = BitSet(linksMaxAmount)
    var isLink1NeuralDirected = BitSet(linksMaxAmount)
    var degreeOfShortening = FloatArray(linksMaxAmount) { 1f }
    var isStickyLink = BooleanArray(linksMaxAmount) { false }
    val linkIndexMap = UnorderedIntPairMap(if (!isGenomeEditor) 1_000_000 else 300)//TODO заменить на fastutil если это имеет смысл

    fun addLink(cellId: Int, linkId: Int) {
        val base = cellId * MAX_LINK_AMOUNT
        if (cellId < 0) return
        val amount = linksAmount[cellId]
        if (amount >= MAX_LINK_AMOUNT) {
            // перезаписываем последний
            links[base + MAX_LINK_AMOUNT - 1] = linkId
        } else {
            links[base + amount] = linkId
            linksAmount[cellId] += 1
        }
    }

    fun deleteLinkedCellLink(cellId: Int, linkId: Int) {
        val base = cellId * MAX_LINK_AMOUNT
        val amount = linksAmount[cellId]
        if (amount == 0) return

        for (i in 0 until amount) {
            val idx = base + i
            if (links[idx] == linkId) {
                // Заменяем на последний элемент
                links[idx] = links[base + amount - 1]
                links[base + amount - 1] = -1 // не обязательно, но может быть полезно
                linksAmount[cellId] -= 1
                return
            }
        }
    }

    // Pheromone system
    // Could easily be expanded to support other substances dissolved in the substrate
    var pheromoneR = FloatArray(GRID_SIZE) { 0f }
    var pheromoneG = FloatArray(GRID_SIZE) { 0f }
    var pheromoneB = FloatArray(GRID_SIZE) { 0f }

    /*
    * Команды которые поступают от Мира и Игрока, выполняются только в updateAfterCycle
    * Commands that come from the World and the Player are executed only in updateAfterCycle
    * */
    val threadCount = if (!isGenomeEditor) THREAD_COUNT else 1

    val deletedCellSizes = IntArray(threadCount) { -1 }
    val deleteCellLists = Array(threadCount) { IntArray(1301) { -1 } }

    val deletedLinkSizes = IntArray(threadCount) { -1 }
    val deleteLinkLists = Array(threadCount) { IntArray(1300) { -1 } }
    fun addToDeleteList(threadId: Int, linkId: Int) {
        deletedLinkSizes[threadId] += 1
        deleteLinkLists[threadId][deletedLinkSizes[threadId]] = linkId
    }

    //TODO переделать в SoA
    val addCells = Array(threadCount) { mutableListOf<AddCell>() }
    val addLinks = Array(threadCount) { mutableListOf<AddLink>() }
    val addSubstances = Array(threadCount) { mutableListOf<SubstanceAdd>() }

    val addOrganisms = Array(threadCount) { mutableListOf<Organism>() }

    val decrementMutationCounter = Array(threadCount) { mutableListOf<Int>() }

    val evenCounter = IntArray(threadCount)
    val oddCounter = IntArray(threadCount)
    val evenChunkPositionStack = if (!isGenomeEditor) {
        Array(THREAD_COUNT) { IntArray(30_000) }
    } else {
        Array(0) { IntArray(0) }
    }
    val oddChunkPositionStack = if (!isGenomeEditor) {
        Array(THREAD_COUNT) { IntArray(30_000) }
    } else {
        Array(0) { IntArray(0) }
    }
//TODO сделать нормальный буфер под клетки которым нужно отталкивание (это нужно будет для модов)
// TODO: Make a proper buffer for cells that need repulsion (this will be needed for mods)
//    val repulseCounter = IntArray(threadCount)
//    val repulseIndexOtherCell = IntArray(threadCount * MAX_REPULSE_CELLS)


    //TODO перепроверить наличее всех нужных характеристик
    // TODO: Double-check the presence of all required characteristics
    fun clear() {
        // Store the bounds for resetting arrays
        val cellBound = (cellLastId + 1).coerceAtLeast(0)
        val linkBound = (linksLastId + 1).coerceAtLeast(0)

        // Reset last ID counters
        cellLastId = -1
        linksLastId = -1

        deadCellsStack.fill(-1, 0, (deadCellsStackAmount + 1).coerceAtLeast(0))
        deadCellsStackAmount = -1
        deadLinksStack.fill(-1, 0, (deadLinksStackAmount + 1).coerceAtLeast(0))
        deadLinksStackAmount = -1

        // Reset cell-related arrays (0 to cellLastId + 1)
        cellGeneration.fill(0, 0, cellBound)
        isAliveCell.clear()
        cellGenomeId.fill(-1, 0, cellBound)
        cellActions.fill(null, 0, cellBound)
        organismIndex.fill(-1, 0, cellBound)
        parentIndex.fill(-1, 0, cellBound)
        gridId.fill(-1, 0, cellBound)
        x.fill(0f, 0, cellBound)
        y.fill(0f, 0, cellBound)
        angle.fill(0f, 0, cellBound)
        vx.fill(0f, 0, cellBound)
        vy.fill(0f, 0, cellBound)
        vxOld.fill(0f, 0, cellBound)
        vyOld.fill(0f, 0, cellBound)
        ax.fill(0f, 0, cellBound)
        ay.fill(0f, 0, cellBound)
        colorR.fill(1f, 0, cellBound)
        colorG.fill(1f, 0, cellBound)
        colorB.fill(1f, 0, cellBound)
        energyNecessaryToDivide.fill(2f, 0, cellBound)
        energyNecessaryToMutate.fill(1f, 0, cellBound)
        neuronImpulseInput.fill(0f, 0, cellBound)
        neuronImpulseOutput.fill(0f, 0, cellBound)
        dragCoefficient.fill(0.93f, 0, cellBound)
        isAliveWithoutEnergy.fill(200, 0, cellBound)
        isNeuronTransportable.fill(true, 0, cellBound)
        effectOnContact.fill(false, 0, cellBound)
        isDividedInThisStage.fill(true, 0, cellBound)
        isMutateInThisStage.fill(true, 0, cellBound)
        cellType.fill(0, 0, cellBound)
        energy.fill(0f, 0, cellBound)
        tickRestriction.fill(0, 0, cellBound)
        linksAmount.fill(0, 0, cellBound)
        links.fill(-1, 0, cellBound * MAX_LINK_AMOUNT)

        // Reset neural-related arrays (0 to cellLastId + 1)
        activationFuncType.fill(0, 0, cellBound)
        a.fill(1f, 0, cellBound)
        b.fill(0f, 0, cellBound)
        c.fill(0f, 0, cellBound)
        dTime.fill(-1f, 0, cellBound)
        remember.fill(0f, 0, cellBound)
        isSum.fill(true, 0, cellBound)

        // Reset directed-related arrays (0 to cellLastId + 1)
        angleDiff.fill(0f, 0, cellBound)

        // Reset eye-related arrays (0 to cellLastId + 1)
        colorDifferentiation.fill(7, 0, cellBound)
        visibilityRange.fill(170f, 0, cellBound)

        // Reset tail-related arrays (0 to cellLastId + 1)
        speed.fill(0f, 0, cellBound)

        // Reset link-related arrays (0 to linksLastId + 1)
        linkGeneration.fill(0, 0, linkBound)
        isAliveLink.clear()
        links1.fill(-1, 0, linkBound)
        links2.fill(-1, 0, linkBound)
        linksNaturalLength.fill(-10f, 0, linkBound)
        isNeuronLink.clear()
        isLink1NeuralDirected.clear()
        degreeOfShortening.fill(1f, 0, linkBound)
        isStickyLink.fill(false, 0, linkBound)

        pheromoneR.fill(0f, 0, GRID_SIZE)
        pheromoneG.fill(0f, 0, GRID_SIZE)
        pheromoneB.fill(0f, 0, GRID_SIZE)
    }


    fun resizeCells() {
        val oldMax = cellMaxAmount
        cellMaxAmount = (oldMax * 5 / 4).coerceAtLeast(oldMax + 1)

        // Resize cell arrays
        run {
            val old = cellGeneration
            cellGeneration = IntArray(cellMaxAmount)
            System.arraycopy(old, 0, cellGeneration, 0, oldMax)
        }
        run {
            val old = isAliveCell
            isAliveCell = BitSet(cellMaxAmount)
            isAliveCell.or(old)
        }
        run {
            val old = cellGenomeId
            cellGenomeId = IntArray(cellMaxAmount) { -1 }
            System.arraycopy(old, 0, cellGenomeId, 0, oldMax)
        }
        run {
            val old = cellActions
            cellActions = arrayOfNulls(cellMaxAmount)
            System.arraycopy(old, 0, cellActions, 0, oldMax)
        }
        run {
            val old = organismIndex
            organismIndex = IntArray(cellMaxAmount) { -1 }
            System.arraycopy(old, 0, organismIndex, 0, oldMax)
        }
        run {
            val old = parentIndex
            parentIndex = IntArray(cellMaxAmount) { -1 }
            System.arraycopy(old, 0, parentIndex, 0, oldMax)
        }
        run {
            val old = gridId
            gridId = IntArray(cellMaxAmount) { -1 }
            System.arraycopy(old, 0, gridId, 0, oldMax)
        }
        run {
            val old = x
            x = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, x, 0, oldMax)
        }
        run {
            val old = y
            y = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, y, 0, oldMax)
        }
        run {
            val old = angle
            angle = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, angle, 0, oldMax)
        }
        run {
            val old = vx
            vx = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, vx, 0, oldMax)
        }
        run {
            val old = vy
            vy = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, vy, 0, oldMax)
        }
        run {
            val old = vxOld
            vxOld = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, vxOld, 0, oldMax)
        }
        run {
            val old = vyOld
            vyOld = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, vyOld, 0, oldMax)
        }
        run {
            val old = ax
            ax = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, ax, 0, oldMax)
        }
        run {
            val old = ay
            ay = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, ay, 0, oldMax)
        }
        run {
            val old = colorR
            colorR = FloatArray(cellMaxAmount) { 1f }
            System.arraycopy(old, 0, colorR, 0, oldMax)
        }
        run {
            val old = colorG
            colorG = FloatArray(cellMaxAmount) { 1f }
            System.arraycopy(old, 0, colorG, 0, oldMax)
        }
        run {
            val old = colorB
            colorB = FloatArray(cellMaxAmount) { 1f }
            System.arraycopy(old, 0, colorB, 0, oldMax)
        }
        run {
            val old = energyNecessaryToDivide
            energyNecessaryToDivide = FloatArray(cellMaxAmount) { 2f }
            System.arraycopy(old, 0, energyNecessaryToDivide, 0, oldMax)
        }
        run {
            val old = energyNecessaryToMutate
            energyNecessaryToMutate = FloatArray(cellMaxAmount) { 1f }
            System.arraycopy(old, 0, energyNecessaryToMutate, 0, oldMax)
        }
        run {
            val old = neuronImpulseInput
            neuronImpulseInput = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, neuronImpulseInput, 0, oldMax)
        }
        run {
            val old = neuronImpulseOutput
            neuronImpulseOutput = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, neuronImpulseOutput, 0, oldMax)
        }
        run {
            val old = dragCoefficient
            dragCoefficient = FloatArray(cellMaxAmount) { 0.93f }
            System.arraycopy(old, 0, dragCoefficient, 0, oldMax)
        }
        run {
            val old = isAliveWithoutEnergy
            isAliveWithoutEnergy = IntArray(cellMaxAmount) { 200 }
            System.arraycopy(old, 0, isAliveWithoutEnergy, 0, oldMax)
        }
        run {
            val old = isNeuronTransportable
            isNeuronTransportable = BooleanArray(cellMaxAmount) { true }
            System.arraycopy(old, 0, isNeuronTransportable, 0, oldMax)
        }
        run {
            val old = effectOnContact
            effectOnContact = BooleanArray(cellMaxAmount) { false }
            System.arraycopy(old, 0, effectOnContact, 0, oldMax)
        }
        run {
            val old = isDividedInThisStage
            isDividedInThisStage = BooleanArray(cellMaxAmount) { true }
            System.arraycopy(old, 0, isDividedInThisStage, 0, oldMax)
        }
        run {
            val old = isMutateInThisStage
            isMutateInThisStage = BooleanArray(cellMaxAmount) { true }
            System.arraycopy(old, 0, isMutateInThisStage, 0, oldMax)
        }
        run {
            val old = cellType
            cellType = IntArray(cellMaxAmount)
            System.arraycopy(old, 0, cellType, 0, oldMax)
        }
        run {
            val old = energy
            energy = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, energy, 0, oldMax)
        }
        run {
            val old = tickRestriction
            tickRestriction = IntArray(cellMaxAmount)
            System.arraycopy(old, 0, tickRestriction, 0, oldMax)
        }
        run {
            val old = linksAmount
            linksAmount = IntArray(cellMaxAmount)
            System.arraycopy(old, 0, linksAmount, 0, oldMax)
        }
        run {
            val old = activationFuncType
            activationFuncType = IntArray(cellMaxAmount)
            System.arraycopy(old, 0, activationFuncType, 0, oldMax)
        }
        run {
            val old = a
            a = FloatArray(cellMaxAmount) { 1f }
            System.arraycopy(old, 0, a, 0, oldMax)
        }
        run {
            val old = b
            b = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, b, 0, oldMax)
        }
        run {
            val old = c
            c = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, c, 0, oldMax)
        }
        run {
            val old = dTime
            dTime = FloatArray(cellMaxAmount) { -1f }
            System.arraycopy(old, 0, dTime, 0, oldMax)
        }
        run {
            val old = remember
            remember = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, remember, 0, oldMax)
        }
        run {
            val old = isSum
            isSum = BooleanArray(cellMaxAmount) { true }
            System.arraycopy(old, 0, isSum, 0, oldMax)
        }
        run {
            val old = angleDiff
            angleDiff = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, angleDiff, 0, oldMax)
        }
        run {
            val old = colorDifferentiation
            colorDifferentiation = IntArray(cellMaxAmount) { 7 }
            System.arraycopy(old, 0, colorDifferentiation, 0, oldMax)
        }
        run {
            val old = visibilityRange
            visibilityRange = FloatArray(cellMaxAmount) { 170f }
            System.arraycopy(old, 0, visibilityRange, 0, oldMax)
        }
        run {
            val old = speed
            speed = FloatArray(cellMaxAmount)
            System.arraycopy(old, 0, speed, 0, oldMax)
        }

        // Special handling for links array
        run {
            val oldLinks = links
            links = IntArray(cellMaxAmount * MAX_LINK_AMOUNT) { -1 }
            for (i in 0 until oldMax) {
                System.arraycopy(oldLinks, i * MAX_LINK_AMOUNT, links, i * MAX_LINK_AMOUNT, MAX_LINK_AMOUNT)
            }
        }
    }

    fun resizeLinks() {
        val oldMax = linksMaxAmount
        linksMaxAmount = (oldMax * 5 / 4).coerceAtLeast(oldMax + 1)

        // Resize link arrays
        run {
            val old = isAliveLink
            isAliveLink = BitSet(linksMaxAmount)
            isAliveLink.or(old)
        }
        run {
            val old = linkGeneration
            linkGeneration = IntArray(linksMaxAmount)
            System.arraycopy(old, 0, linkGeneration, 0, oldMax)
        }
        run {
            val old = links1
            links1 = IntArray(linksMaxAmount) { -1 }
            System.arraycopy(old, 0, links1, 0, oldMax)
        }
        run {
            val old = links2
            links2 = IntArray(linksMaxAmount) { -1 }
            System.arraycopy(old, 0, links2, 0, oldMax)
        }
        run {
            val old = linksNaturalLength
            linksNaturalLength = FloatArray(linksMaxAmount) { -10f }
            System.arraycopy(old, 0, linksNaturalLength, 0, oldMax)
        }
        run {
            val old = isNeuronLink
            isNeuronLink = BitSet(linksMaxAmount)
            isNeuronLink.or(old)
        }
        run {
            val old = isLink1NeuralDirected
            isLink1NeuralDirected = BitSet(linksMaxAmount)
            isLink1NeuralDirected.or(old)
        }
        run {
            val old = degreeOfShortening
            degreeOfShortening = FloatArray(linksMaxAmount) { 1f }
            System.arraycopy(old, 0, degreeOfShortening, 0, oldMax)
        }
        run {
            val old = isStickyLink
            isStickyLink = BooleanArray(linksMaxAmount) { false }
            System.arraycopy(old, 0, isStickyLink, 0, oldMax)
        }
    }
}
