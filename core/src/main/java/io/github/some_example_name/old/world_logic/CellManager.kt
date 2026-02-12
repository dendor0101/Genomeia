package io.github.some_example_name.old.world_logic

import io.github.some_example_name.old.genome.GenomeManager
import io.github.some_example_name.old.good_one.utils.distanceTo
import io.github.some_example_name.old.good_one.utils.invSqrt
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import io.github.some_example_name.old.world_logic.ThreadManager.Companion.THREAD_COUNT
import io.github.some_example_name.old.world_logic.commands.GameCommand
import io.github.some_example_name.old.world_logic.commands.UserCommandBuffer
import io.github.some_example_name.old.world_logic.genomic_transformations.addCell
import io.github.some_example_name.old.world_logic.genomic_transformations.divideCell
import io.github.some_example_name.old.world_logic.genomic_transformations.mutateCell
import io.github.some_example_name.old.world_logic.process_soa.CellDeletionBuffer
import io.github.some_example_name.old.world_logic.process_soa.LinkDeletionBuffer
import io.github.some_example_name.old.organisms.Organism
import io.github.some_example_name.old.organisms.OrganismManager
import io.github.some_example_name.old.screens.GlobalSettings.HYDRODYNAMIC_DRAG
import io.github.some_example_name.old.substances.SubstanceManager
import io.github.some_example_name.old.substances.SubstancePlug
import io.github.some_example_name.old.world_logic.cells.Pumper
import io.github.some_example_name.old.world_logic.cells.Punisher
import io.github.some_example_name.old.world_logic.cells.Sticky
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.world_logic.cells.base.createCellType
import io.github.some_example_name.old.world_logic.cells.base.doSpecific
import io.github.some_example_name.old.world_logic.cells.currentGenomeIndex
import java.lang.Float.max
import kotlin.BooleanArray
import kotlin.math.atan2

//TODO разнести этот класс на сущности, которые потом отдельно будет проще переделывать, физика графика и тд
//TODO: split this class into entities that will be easier to rework separately later, physics graphics, etc.
class CellManager(
    val map: Array<BooleanArray>?,
    cellsStartMaxAmount: Int = 120_000,
    linksStartMaxAmount: Int = 120_000,
    val isGenomeEditor: Boolean = false,
    val gridManager: GridManager = GridManager(),
    val subManager: SubstancePlug = SubstanceManager(),
    genomeName: String? = null,
) : CrossThreadEditable(cellsStartMaxAmount, linksStartMaxAmount, isGenomeEditor) {
    val deletionLinksBuffer = if (!isGenomeEditor) {
        LinkDeletionBuffer(THREAD_COUNT, 300, this)
    } else {
        LinkDeletionBuffer(1, 300, this)
    }
    val deletionCellsBuffer = if (!isGenomeEditor) {
        CellDeletionBuffer(THREAD_COUNT, 300, this)
    } else {
        CellDeletionBuffer(1, 300, this)
    }

    val threadManager: ThreadManagerPlug = if (!isGenomeEditor) {
        ThreadManager(gridManager, this)
    } else {
        ThreadManagerPlugImpl()
    }
    val genomeManager = GenomeManager(isGenomeEditor = isGenomeEditor, genomeName = genomeName)
    val organismManager = OrganismManager()

    fun getOrganism(index: Int) = organismManager.organisms[index]

    val commandBuffer = UserCommandBuffer()
    var grabbedXLocal = 0f
    var grabbedYLocal = 0f
    var grabbedCellLocal = -1

    var isRestart = false
    var isFinish = false

    val globalSettings = readSettings()
    private val rateOfEnergyTransferInLinks = globalSettings.rateOfEnergyTransferInLinks
    val cellsSettings: List<CellSettings> = globalSettings.cellsSettings.values.toList()
//    val shaderBuffer = if (!isGenomeEditor) {
//        Array(THREAD_COUNT) { ShaderBuffer(20_000) }
//    } else {
//        Array(0) { ShaderBuffer(0) }
//    }
//
//    val shaderBufferCounter = if (!isGenomeEditor) {
//        IntArray(THREAD_COUNT)
//    } else {
//        IntArray(0)
//    }

    init {
        if (!isGenomeEditor) {
            worldInit()
        }
    }

    fun startThread() {
        threadManager.startUpdateThread()
    }

    /*
    * PhysicsSystem
    * GameLogicSystem
    * */

    private fun repulseNeighbors(cellId: Int, gridX: Int, gridY: Int, threadId: Int) {
        //TODO Это можно превратить в 2 цикла, через два горизонтальных среза
        //TODO This can be turned into 2 cycles, through two horizontal slices
        gridManager.getCells(gridX - 1, gridY + 1).also { ids ->
            for (id in ids) repulse(cellId, id, false, threadId)
        }
        gridManager.getCells(gridX, gridY + 1).also { ids ->
            for (id in ids) repulse(cellId, id, false, threadId)
        }
        gridManager.getCells(gridX + 1, gridY + 1).also { ids ->
            for (id in ids) repulse(cellId, id, false, threadId)
        }
        gridManager.getCells(gridX + 1, gridY).also { ids ->
            for (id in ids) repulse(cellId, id, false, threadId)
        }
    }

    fun processCellClosest(cells: IntArray, threadId: Int) {
        for (i in cells.indices) {
            for (j in i + 1 until cells.size) {
                repulse(cells[i], cells[j], true, threadId)
            }
        }
    }

    fun killCell(cellId: Int, threadId: Int) {
        deletedCellSizes[threadId] += 1
        deleteCellLists[threadId][
            deletedCellSizes[threadId]
        ] = cellId

        if (cellId == grabbedCell) {
            grabbedCell = -1
        }

        val base = cellId * MAX_LINK_AMOUNT
        val amount = linksAmount[cellId]

        for (j in 0 until amount) {
            val idx = base + j
            val linkId = links[idx]
            addToDeleteList(threadId, linkId)
        }
    }

    fun processPhysics(cellId: Int, gridX: Int, gridY: Int, threadId: Int) {
        stretchLinks(cellId, threadId)
        repulseNeighbors(cellId, gridX, gridY, threadId)
    }

    private fun processWorldBorders(cellId: Int) {
        if (cellType[cellId] == -1) return
        if (x[cellId] < CELL_RADIUS) {
            x[cellId] = CELL_RADIUS
            vx[cellId] = 0f
        } else if (x[cellId] > gridManager.WORLD_WIDTH_MINUS_CELL_RADIUS) {
            x[cellId] = gridManager.WORLD_WIDTH_MINUS_CELL_RADIUS
            vx[cellId] = 0f
        } else if (y[cellId] < CELL_RADIUS) {
            y[cellId] = CELL_RADIUS
            vy[cellId] = 0f
        } else if (y[cellId] > gridManager.WORLD_HEIGHT_MINUS_CELL_RADIUS) {
            y[cellId] = gridManager.WORLD_HEIGHT_MINUS_CELL_RADIUS
            vy[cellId] = 0f
        }
    }

    private fun processCellEnergy(cellId: Int, threadId: Int) {
        if (energy[cellId] <= 0f) isAliveWithoutEnergy[cellId] -= 1
        if (energy[cellId] > 0) isAliveWithoutEnergy[cellId] = globalSettings.theNumberOfTicksHungryCellDies
        if (isAliveWithoutEnergy[cellId] < 0) {
            killCell(cellId, threadId)
            return
        }
    }

    private fun processCellFrictionOld(cellId: Int) {
        vx[cellId] *= dragCoefficient[cellId]
        vy[cellId] *= dragCoefficient[cellId]
    }

    private fun processCellFrictionSimpler(cellId: Int) {
        if (cellType[cellId] == 10) {
            vx[cellId] *= 1f-dragCoefficient[cellId]
            vy[cellId] *= 1f-dragCoefficient[cellId]
        } else {
            // Much more simplified version of the hydrodynamic drag equation
            // Drag is inversely proportional to the number of links a cell has
            // So cells on the surface of the organism have less drag than cells on the inside
            val amount = linksAmount[cellId]+1
            vx[cellId] *= 1f-(1f-dragCoefficient[cellId])/amount
            vy[cellId] *= 1f-(1f-dragCoefficient[cellId])/amount
        }
    }

    private fun processCellFriction(cellId: Int) {
        // Approximate hydrodynamic drag using surface normal calculated from neighboring cells
        // TODO: Use collision neighbors instead of linked neighbors
        // TODO: Fix phantom forces
        val base = cellId * MAX_LINK_AMOUNT
        val amount = linksAmount[cellId]
        if (amount == 0) {
            vx[cellId] *= dragCoefficient[cellId]
            vy[cellId] *= dragCoefficient[cellId]
            return
        }
        // Initialise the sum totals
        var vectorSumX = 0f;
        var vectorSumY = 0f;
        for (i in 0 until amount) {
            val idx = base + i
            val linkId = links[idx]
            val c1 = links1[linkId]
            val c2 = links2[linkId]
            val otherCellId = if (c1 != cellId) c1 else if (c2 != cellId) c2 else continue
            vectorSumX -= x[otherCellId]
            vectorSumY -= y[otherCellId]
        }
        // Normalise
        vectorSumX /= amount
        vectorSumY /= amount
        // Make relative to the cell
        vectorSumX += x[cellId]
        vectorSumY += y[cellId]
        // The friction proportional to alignment with the surface normal
        val frictionMultiplier = max(vx[cellId]*vectorSumX + vy[cellId]*vectorSumY, 0f)
        // Alternatively, we could project the friction force onto the surface normal like a more accurate hydrofoil

        vx[cellId] *= 1f-(1f-dragCoefficient[cellId])*frictionMultiplier*0.05f
        vy[cellId] *= 1f-(1f-dragCoefficient[cellId])*frictionMultiplier*0.05f
    }

    private fun processCellAngle(cellId: Int) {
        val organism = getOrganism(organismId[cellId])
        //Самая первая клетка // The very first cell
        if (parentId[cellId] == -1) {
            if (firstChildId[cellId] != -1) {
                val linkId = organism.linkIdMap.get(id[cellId], firstChildId[cellId])
                if (linkId == -1) return // TODO сделать, что бы у клетки менялся firstChildId и parentId при отрыви связи
                // TODO: make sure that the cell's firstChildId and parentId change when the connection is broken
                val c1 = links1[linkId]
                val c2 = links2[linkId]
                val childCellId = if (cellId != c2) c2 else c1

                val dx = x[childCellId] - x[cellId]
                val dy = y[childCellId] - y[cellId]
                val angleToChild = atan2(dy, dx)

                angle[cellId] = angleToChild// + angleDiff[cellId]
            }
        } else { //Клетка с родителем и ребенком, промежуточная // Cell with parent and child, intermediate
            val linkId = organism.linkIdMap.get(id[cellId], parentId[cellId])
            if (linkId == -1) return // TODO сделать, что бы у клетки менялся firstChildId и parentId при отрыви связи
            // TODO: make sure that the cell's firstChildId and parentId change when the connection is broken
            val c1 = links1[linkId]
            val c2 = links2[linkId]
            val parentCellId = if (cellId != c2) c2 else c1

            //TODO десь падает с ошибкой -1
            //TODO crashes here with error -1
            if (parentCellId == -1) {
                /*throw Exception*/println("Error with angle parentCellId: $parentCellId cellId $cellId genome name: ${genomeManager.genomes[organismManager.organisms[organismId[cellId]].genomeIndex].name}")
                return
            }
            val dx = x[parentCellId] - x[cellId]
            val dy = y[parentCellId] - y[cellId]
            val angleToParent = atan2(dy, dx)
            angle[cellId] = angleToParent + 3.141592f// + angleDiff[cellId]
        }
    }

    fun resetNeuralImpulseInput(cellId: Int) {
        neuronImpulseInput[cellId] = if (isSum[cellId]) 0f else 1f
    }

    fun processCell(
        cellId: Int,
        gridX: Int,
        gridY: Int,
        threadId: Int,
        isOdd: Boolean,
        isVisibleOnScreen: Boolean = true
    ) {
//        processWorldBorders(cellId, threadId)
//        processPhysics(cellId, gridX, gridY, threadId)
        processCellEnergy(cellId, threadId)

//        if (isVisibleOnScreen) {
//            val buf = shaderBuffer[threadId]
//            buf.x[shaderBufferCounter[threadId]] = x[cellId]
//            buf.y[shaderBufferCounter[threadId]] = y[cellId]
//            buf.colorRGBA[shaderBufferCounter[threadId]] = 1234123
//            shaderBufferCounter[threadId]++
//        }

        if (cellType[cellId] != -1) {
            if (HYDRODYNAMIC_DRAG)
                processCellFriction(cellId)
            else
                processCellFrictionSimpler(cellId)
        }
        if (isNeuronTransportable[cellId]) {
            neuronImpulseOutput[cellId] = activation(this, cellId, neuronImpulseInput[cellId])
        }
        doSpecific(cellType[cellId], cellId, threadId) // This can override neuronImpulseOutput
        resetNeuralImpulseInput(cellId)

        if (cellType[cellId] != -1) {
            mutateCell(cellId, threadId)
            divideCell(cellId, threadId)
            processCellAngle(cellId)

            if (isOdd) {
                oddChunkPositionStack[threadId][oddCounter[threadId]] = cellId
                oddCounter[threadId]++
            } else {
                evenChunkPositionStack[threadId][evenCounter[threadId]] = cellId
                evenCounter[threadId]++
            }
        }
    }

    private fun stretchLinks(cellId: Int, threadId: Int) {
        val base = cellId * MAX_LINK_AMOUNT
        val amount = linksAmount[cellId]
        if (amount == 0) return

        for (i in 0 until amount) {
            val idx = base + i
            val linkId = links[idx]
            val c1 = links1[linkId]
            val c2 = links2[linkId]
            val otherCellId = if (c1 != cellId) c1 else if (c2 != cellId) c2 else continue
            if (gridId[cellId] < gridId[otherCellId]) {
                processLink(linkId, threadId)
            }
        }
    }

    private fun processLink(linkId: Int, threadId: Int) {
        val linkCell1 = links1[linkId]
        val linkCell2 = links2[linkId]

        if (cellType[linkCell2] == -1 && cellType[linkCell1] == -1) return
        val dx = x[linkCell1] - x[linkCell2]
        val dy = y[linkCell1] - y[linkCell2]

        //transport energy
        val energyTransportRate = rateOfEnergyTransferInLinks // made this a variable instead of a magic number
        if (energy[linkCell1] / cellsSettings[cellType[linkCell1] + 1].maxEnergy < energy[linkCell2] / cellsSettings[cellType[linkCell2] + 1].maxEnergy) {
            energy[linkCell1] += energyTransportRate
            energy[linkCell2] -= energyTransportRate
        } else if (energy[linkCell1] / cellsSettings[cellType[linkCell1] + 1].maxEnergy != energy[linkCell2] / cellsSettings[cellType[linkCell2] + 1].maxEnergy) {
            energy[linkCell1] -= energyTransportRate
            energy[linkCell2] += energyTransportRate
        }

        //transport neuronImpulse
        if (isNeuronLink[linkId] && directedNeuronLink[linkId] != -1) {
            if (this.id[linkCell1] == directedNeuronLink[linkId]) { // linkCell1 is the receiver
                //if (isNeuronTransportable[linkCell1]) {
                    if (isSum[linkCell1]) {
                        neuronImpulseInput[linkCell1] += neuronImpulseOutput[linkCell2]
                    } else {
                        neuronImpulseInput[linkCell1] *= neuronImpulseOutput[linkCell2]
                    }
                //}
            } else if (this.id[linkCell2] == directedNeuronLink[linkId]) { // linkCell2 is the receiver
                //if (isNeuronTransportable[linkCell2]) {
                    if (isSum[linkCell2]) {
                        neuronImpulseInput[linkCell2] += neuronImpulseOutput[linkCell1]
                    } else {
                        neuronImpulseInput[linkCell2] *= neuronImpulseOutput[linkCell1]
                    }
                //}
            }
        }
        if (isStickyLink[linkId] && !isNeuronLink[linkId]) {
            if (cellType[linkCell1] == 11 && activation(this, linkCell2, neuronImpulseOutput[linkCell2]) >= 1) {
                deletedLinkSizes[threadId] += 1
                deleteLinkLists[threadId][deletedLinkSizes[threadId]] = linkId
            } else if (cellType[linkCell2] == 11 && activation(this, linkCell2, neuronImpulseOutput[linkCell2]) >= 1) {
                deletedLinkSizes[threadId] += 1
                deleteLinkLists[threadId][deletedLinkSizes[threadId]] = linkId
            }
        }

        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared > 25_600) {
            addToDeleteList(threadId, linkId)
            return
        }
        // TODO: for physical accuracy this should be changed to a harmonic mean
        val stiffness = (cellsSettings[cellType[linkCell1] + 1].linkStiffness + cellsSettings[cellType[linkCell2] + 1].linkStiffness) / 2
        if (distanceSquared <= 0) return
        val dist = 1.0f / invSqrt(distanceSquared)

        val force = (dist - linksNaturalLength[linkId] * degreeOfShortening[linkId]) * stiffness

        val dirX = dx / dist
        val dirY = dy / dist

        // Spring dampening
        val dvx = vx[linkCell1] - vx[linkCell2]
        val dvy = vy[linkCell1] - vy[linkCell2]

        val dampeningConstant = 0.3f
        val dampeningForce = dampeningConstant*(dvx * dirX + dvy * dirY)

        val fx = (force + dampeningForce) * dirX
        val fy = (force + dampeningForce) * dirY

        vx[linkCell2] += fx
        vy[linkCell2] += fy
        vx[linkCell1] -= fx
        vy[linkCell1] -= fy
    }

    private fun repulse(cellAId: Int, cellBId: Int, isSameCell: Boolean = false, threadId: Int) {
        if (cellType[cellBId] == -1 && cellType[cellAId] == -1) return

        val linkId = linkIndexMap.get(cellAId, cellBId)
        if (isSameCell && linkId != -1) {
            processLink(linkId, threadId)
            return
        }
        if (linkId != -1) return
        val dx = x[cellAId] - x[cellBId]
        val dy = y[cellAId] - y[cellBId]
        val dx2 = dx * dx
        val radiusSquared = 1600
        if (dx2 > radiusSquared) return
        val dy2 = dy * dy
        if (dy2 > radiusSquared) return
        val distanceSquared = dx2 + dy2
        if (distanceSquared < radiusSquared) {
            val distance = 1.0f / invSqrt(distanceSquared)
            if (distance.isNaN()) throw Exception("TODO потом убрать")

            if (effectOnContact[cellAId] || effectOnContact[cellBId]) {
                if (cellType[cellBId] == 11) {
                    Sticky.specificToThisType(this, cellBId, cellAId, threadId, distance)
                } else if (cellType[cellAId] == 11) {
                    Sticky.specificToThisType(this, cellAId, cellBId, threadId, distance)
                }

                if (cellType[cellBId] == 12) {
                    Pumper.specificToThisType(this, cellBId, cellAId)
                }
                if (cellType[cellAId] == 12) {
                    Pumper.specificToThisType(this, cellAId, cellBId)
                }

                if (cellType[cellBId] == 24) {
                    Punisher.specificToThisType(this, cellBId, cellAId, threadId)
                }
                if (cellType[cellAId] == 24) {
                    Punisher.specificToThisType(this, cellAId, cellBId, threadId)
                }
            }

            // Квадратичная зависимость силы
            val cellStrengthAverage = (cellsSettings[cellType[cellAId] + 1].cellStiffness + cellsSettings[cellType[cellBId] + 1].cellStiffness) / 2f
            val force = cellStrengthAverage - cellStrengthAverage * distanceSquared / radiusSquared
            // Нормализация вектора расстояния
            val normX = dx / distance
            val normY = dy / distance
            val vectorX = normX * force
            val vectorY = normY * force
            vx[cellAId] += vectorX
            vy[cellAId] += vectorY
            vx[cellBId] -= vectorX
            vy[cellBId] -= vectorY
        }
    }

    fun stopUpdateThread() {
        threadManager.dispose()
    }

    //TODO сделать перетягивание тоже через команды
    // TODO: Make drag and drop also via commands
    // TODO: Fix oscillation and failing to ungrab
    fun grabbed(px: Float, py: Float): Boolean {
        if (grabbedCell == -1) {
            val x = (px / CELL_SIZE).toInt()
            val y = (py / CELL_SIZE).toInt()
            val allCells = mutableListOf<Int>()
            for (i in -1..1) {
                for (j in -1..1) {
                    allCells.addAll(gridManager.getCells(x + i, y + j).toList())
                }
            }
            grabbedCell = allCells.minByOrNull { distanceTo(px, py, this.x[it], this.y[it]) }
                ?.takeIf { distanceTo(px, py, this.x[it], this.y[it]) < CELL_RADIUS }
                ?: return false

            try {
                if (cellType[grabbedCell] == -1) {
                    grabbedCell = -1
                    commandBuffer.push(GameCommand.MovePlayer(px, py, grabbedCell))
                    return false
                }
            } catch (e: Exception) {
                //TODO пока не понятно как grabbedCell может быть -1
                //Ведь в другом потоке только killCell и dispose (и они оба по идее не могут случиться)
                // TODO: It's not yet clear how grabbedCell can be -1
                // After all, in the other thread there are only killCell and dispose (and both of them, in theory, can't happen)
                println("grabbedCell == -1 $e")
            }


            commandBuffer.push(GameCommand.MovePlayer(px, py, grabbedCell))
        } else return false
        return true
    }

    fun moveTo(px: Float, py: Float) {
        commandBuffer.push(GameCommand.MovePlayer(px, py, grabbedCell))
    }

    fun addCell(px: Float, py: Float, type: Int, isWall: Boolean = true, genomeIndex: Int = -1) {
        cellLastId++
        createCellType(type, cellLastId, true, genomeIndex)
        if (!isWall) {
            energy[cellLastId] = 0.1f
            id[cellLastId] = 0
        } else {
            id[cellLastId] = -1
        }
        x[cellLastId] = px
        y[cellLastId] = py
        cellType[cellLastId] = type
        gridId[cellLastId] = gridManager.addCell(
            (x[cellLastId] / CELL_SIZE).toInt(),
            (y[cellLastId] / CELL_SIZE).toInt(),
            cellLastId
        )
    }

    fun onMouseClick(x: Float, y: Float) {
        commandBuffer.push(GameCommand.Spawn(x, y))
    }

    fun moveToCell(i: Int) {
        val oldX = (x[i] / CELL_SIZE).toInt()
        val oldY = (y[i] / CELL_SIZE).toInt()
        x[i] += vx[i]
        y[i] += vy[i]
        val rawAx = (vxOld[i] - vx[i]) / 3f
        val rawAy = (vyOld[i] - vy[i]) / 3f
        ax[i] = 0.3f * rawAx + 0.7f * ax[i]
        ay[i] = 0.3f * rawAy + 0.7f * ay[i]
        vxOld[i] = vx[i]
        vyOld[i] = vy[i]
        processWorldBorders(i)
        val newX = (x[i] / CELL_SIZE).toInt()
        val newY = (y[i] / CELL_SIZE).toInt()
        if (newX != oldX || newY != oldY) {
            gridManager.removeCell(oldX, oldY, i)
            gridId[i] = gridManager.addCell(newX, newY, i)
        }
    }

    /*
    * Метод который выполняется после всех паралельных обновлений чанков
    * И здесь обрабатываются все команды (от мира и от игрока)
    * The method that runs after all parallel chunk updates
    * All commands (from the world and from the player) are processed here.
    * */
    fun updateAfterCycle() {

        if (isFinish) {
            dispose()
        }

        /*
        * Зануление счетчиков буфера для шейдера
        * Zeroing out buffer counters for shader
        * */
//        shaderBufferCounter.fill(0)

        /*
        * Обработка команд от User
        * Processing commands from User
        * */
        commandBuffer.swapAndConsume { cmd ->
            when (cmd) {
                is GameCommand.Spawn -> {
                    addCell(cmd.x, cmd.y, 18, false, genomeIndex = currentGenomeIndex)
                }

                is GameCommand.MovePlayer -> {
                    grabbedXLocal = cmd.dx
                    grabbedYLocal = cmd.dy
                    grabbedCellLocal = cmd.cellId
                }
                else -> {

                }
            }
        }

        if (grabbedCellLocal != -1) {
            val grabDrag = 0.5f // To reduce oscillations
            vx[grabbedCellLocal] = vx[grabbedCellLocal]*grabDrag + (grabbedXLocal - x[grabbedCellLocal]) * 0.02f
            vy[grabbedCellLocal] = vy[grabbedCellLocal]*grabDrag + (grabbedYLocal - y[grabbedCellLocal]) * 0.02f
        }

        /*
        * Растоновка позиций в сетке
        * Arrangement of positions in the grid
        * */
        for (chunk in 0..<THREAD_COUNT) {
            threadManager.futures.add(executor.submit {
                for (i in 0..<oddCounter[chunk]) {
                    moveToCell(oddChunkPositionStack[chunk][i])
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        for (chunk in 0..<THREAD_COUNT) {
            threadManager.futures.add(executor.submit {
                for (i in 0..<evenCounter[chunk]) {
                    moveToCell(evenChunkPositionStack[chunk][i])
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        oddCounter.fill(0)
        evenCounter.fill(0)

        /*
        * Выполнение команд от мира
        * Executing commands from the world
        * */
        performWorldCommands()

        /*
        * Переход на следющую стадию генома в каждом организме
        * Transition to the next stage of the genome in each organism
        * */
        organismManager.organisms.forEach {
            performOrganismNextStage(it)
        }

        //Полное дерьмо, переделать
        subManager.updateCells()

        if (isRestart) {
            restartSim()
        }

        if (cellMaxAmount * 0.9 < cellLastId) {
            resizeCells()
        }

        if (linksMaxAmount * 0.9 < linksLastId) {
            resizeLinks()
        }
    }

    fun performOrganismNextStage(it: Organism): Boolean? {
        it.justChangedStage = false
        it.timerToGrowAfterStage--
        if (it.dividedTimes[it.stage] <= 0 && it.mutatedTimes[it.stage] <= 0) {
            if (it.genomeSize > it.stage + 1) {
                it.stage++
                it.justChangedStage = true
                it.timerToGrowAfterStage = 5
                return true
            } else {
                return null
            }
        }
        return false
    }

    fun performWorldCommands() {
        /*
        * Добавление связей
        * Adding connections
        * */
        addLinks.forEach {
            it.forEach { link ->
                linksLastId++
                linksNaturalLength[linksLastId] = link.linksLength
                degreeOfShortening[linksLastId] = link.degreeOfShortening
                isStickyLink[linksLastId] = link.isStickyLink
                isNeuronLink[linksLastId] = link.isNeuronLink
                directedNeuronLink[linksLastId] = link.directedNeuronLink
                links1[linksLastId] = link.cellId
                links2[linksLastId] = link.otherCellId
                linkIndexMap.put(link.cellId, link.otherCellId, linksLastId)
                getOrganism(organismId[link.cellId]).linkIdMap.put(
                    id[link.cellId],
                    id[link.otherCellId],
                    linksLastId
                )
                addLink(link.cellId, linksLastId)
                addLink(link.otherCellId, linksLastId)
            }
            it.clear()
        }

        /*
        * Удаление клеток и связок
        * Removal of cells and ligaments
        * */
        deletionLinksBuffer.collect(deleteLinkLists, deletedLinkSizes)
        deletionLinksBuffer.flush()
        deletionCellsBuffer.collect(deleteCellLists, deletedCellSizes)
        deletionCellsBuffer.flush()

        /*
        * Добавление клеток
        * Adding cells
        * */
        addCells.forEach {
            it.forEach { addCell ->
                val organism = organismManager.organisms[addCell.parentOrganismId]
                organism.dividedTimes[organism.stage]--
                addCell(addCell)
            }
            it.clear()
        }

        /*
        * Добавление еды
        * Adding food
        * */
        addSubstances.forEach {
            it.forEach { addSub ->
                subManager.addCell(addSub.x, addSub.y, addSub.vx, addSub.vy)
            }
            it.clear()
        }

        /*
        * Добавление организмов из мутаций Zygote
        * Adding organisms from Zygote mutations
        * */
        addOrganisms.forEach {
            it.forEach { addOrganism ->
                organismManager.organisms.add(addOrganism)
            }
            it.clear()
        }

        /*
        * Инкремент счетчика стадий для мутаиций
        * Increment the stage counter for mutations
        * */
        decrementMutationCounter.forEach {
            it.forEach { organismId ->
                val organism = organismManager.organisms[organismId]
                organism.mutatedTimes[organism.stage]--
            }
            it.clear()
        }
    }

    fun dispose() {
        grabbedXLocal = 0f
        grabbedYLocal = 0f
        grabbedCellLocal = -1
        grabbedX = 0f
        grabbedY = 0f
        grabbedCell = -1
        gridManager.clearAll()
        clear()
        linkIndexMap.clear()
        organismManager.organisms.forEach {
            it.linkIdMap.clear()
        }
        organismManager.organisms.clear()
        subManager.clear()
    }

    private fun restartSim() {
        dispose()
        if (!isGenomeEditor) {
            worldInit()
        }
        isRestart = false
    }

    companion object {
        const val CELL_RADIUS = 20f
        const val MAX_LINK_AMOUNT = 15
        const val MAX_REPULSE_CELLS = 8
    }
}
