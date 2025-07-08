package io.github.some_example_name.old.logic

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.attempts.game.gameabstraction.entity.ParticleType
import io.github.some_example_name.attempts.game.physics.WorldGenerator
import io.github.some_example_name.attempts.game.physics.invSqrt
import io.github.some_example_name.old.good_one.genomeStage
import io.github.some_example_name.old.good_one.genomeStageInstruction
import io.github.some_example_name.old.good_one.shader.ShaderManager.Companion.CELLS_FLOAT_COUNT
import io.github.some_example_name.old.good_one.shader.ShaderManager.Companion.LINKS_FLOAT_COUNT
import io.github.some_example_name.old.logic.GridManager.Companion.CELL_SIZE
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_CELL_HEIGHT
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_CELL_WIDTH
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_HEIGHT
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_WIDTH
import io.github.some_example_name.old.logic.LinksDrawGridStructure.Companion.SCREEN_CELL_WIDTH_LINK
import io.github.some_example_name.old.logic.ThreadManager.Companion.THREAD_COUNT
import kotlin.math.sqrt
import kotlin.random.Random

class CellManager : CrossThreadEditable() {
    val gridManager = GridManager()
    val threadManager = ThreadManager(gridManager, this)
    val subManager = SubstanceManager()
    val deletionLinksBuffer = LinkDeletionBuffer(THREAD_COUNT, 300, this)
    val deletionCellsBuffer = CellDeletionBuffer(THREAD_COUNT, 300, this)
    val zoomManager = ZoomManager(this)
    val linksGrid = LinksDrawGridStructure()

    fun addLink(cellId: Int, linkId: Int) {
        val base = cellId * MAX_LINK_AMOUNT
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

    val specialCellsMaxSize = 1000
    @Volatile
    var specialCellsId = 0
    var drawSpecialCells = IntArray(1000) { -1 }

    fun showInfo() {
        println("linksSize: ${linksLastId} cellsSize: ${cellLastId}")
        for (j in MAX_LINK_AMOUNT * 2..<MAX_LINK_AMOUNT * 2 + linksAmount[2]) {
            println(links[j])
            println("${links1[links[j]]} ${links2[links[j]]}")
        }
    }

    private val worldGenerator = WorldGenerator()

    init {
        val walls = worldGenerator.generateWorld()

        walls.forEachIndexed { index, it ->
            val random = Random(index)
            if (it.type == ParticleType.WALL) {
                addCell(
                    it.x * 4f - 1000f + random.nextDouble(-10.0, 10.0).toFloat(),
                    it.y * 4f - 1000f + random.nextDouble(-10.0, 10.0).toFloat(),
                    type = 16
                )
            } else {
                subManager.addCell(
                    it.x * 4f - 1000f + random.nextDouble(-10.0, 10.0).toFloat(),
                    it.y * 4f - 1000f + random.nextDouble(-10.0, 10.0).toFloat(),
                    0f, 0f
                )
            }
        }

        for (i in 1..<384) {
            addCell(0f, (i * 20).toFloat(), 16)
        }
        for (i in 0..<384) {
            addCell((i * 20).toFloat(), 0f,  16)
        }
        for (i in 0..<384) {
            addCell(7679f, (i * 20).toFloat(), 16)
        }
        for (i in 0..<384) {
            addCell((i * 20).toFloat(), 7679f,  16)
        }

//        for (i in 0..<cellLastId+1) {
//            val cx = (x[i] / CELL_SIZE).toInt()
//            val cy = (y[i] / CELL_SIZE).toInt()
//            val cells  = gridManager.getCells(cx, cy) + gridManager.getCells(cx - 1, cy + 1) +
//                gridManager.getCells(cx, cy + 1) + gridManager.getCells(cx + 1, cy + 1) +
//                gridManager.getCells(cx - 1, cy) + gridManager.getCells(cx + 1, cy) +
//                gridManager.getCells(cx - 1, cy - 1) + gridManager.getCells(cx + 1, cy - 1) +
//                gridManager.getCells(cx, cy - 1)
//
//            for (otherCellId in cells) {
//                val linkId = linkIdMap.get(i, otherCellId)
//                if (linkId == -1 && Random.nextDouble() > 0.75f) {
//                    val dx = x[i] - x[otherCellId]
//                    val dy = y[i] - y[otherCellId]
//                    val dx2 = dx * dx
//                    val dy2 = dy * dy
//                    val distanceSquared = dx2 + dy2
//                    val distance = 1.0f / invSqrt(distanceSquared)
//                    if (distance < 40f) {
//                        addStickyLink(i, otherCellId, distance)
//                    }
//                }
//            }
//        }
//        println("links amount ${linksLastId}")

        WALL_AMOUNT = cellLastId + 1
        addCell(270f, 200f, 0, true)
//        addCell(480f, 220f, 0)
        threadManager.startUpdateThread()
    }


    //Cell
    val floatArraySize = 256 * 256 * 4
    val intArraySize = 256 * 256 * 2
    val cellsMaxAmount = floatArraySize - floatArraySize % CELLS_FLOAT_COUNT - CELLS_FLOAT_COUNT
    val drawStrokeCellCounts = IntArray(256) { 0 }
    val drawIntGrid = IntArray(256 * 256 * 2) { 0 }
    val bufferFloat = BufferUtils.newFloatBuffer(floatArraySize)
    val bufferInt = BufferUtils.newIntBuffer(intArraySize) // 2 int на пиксель

    fun setGridValue(base: Int, total: Int, x: Int, y: Int) {
        val index = ((y shl 8) + x) shl 1 // (y * 256 + x) * 2
        if (index < 0 || index + 1 >= intArraySize) return
        drawIntGrid[index] = base
        drawIntGrid[index + 1] = total
    }

    private fun updateDrawChunk(
        start: Int,
        end: Int,
        startX: Int,
        endX: Int,
        startY: Int,
        cameraX: Int,
        cameraY: Int,
        zoom: Float,
        aspectRatio: Float
    ) {
        var counterThread = drawStrokeCellCounts[start]
        for (cy in start + startY..end + startY) {
            for (cx in startX..endX) {
                val cellsCount = gridManager.getCellsCount(cx, cy)
                val linksCount = linksGrid.getLinksCount(cx, cy)

                val batchSize = cellsCount * CELLS_FLOAT_COUNT + linksCount * LINKS_FLOAT_COUNT

                val base = if (cellsCount == 0 && linksCount == 0) {
                    val hasNeighbor =
                        gridManager.getCellsCount(cx + 1, cy - 1) == 0 &&
                            gridManager.getCellsCount(cx + 1, cy) == 0 &&
                            gridManager.getCellsCount(cx + 1, cy + 1) == 0 &&
                            gridManager.getCellsCount(cx - 1, cy - 1) == 0 &&
                            gridManager.getCellsCount(cx - 1, cy) == 0 &&
                            gridManager.getCellsCount(cx - 1, cy + 1) == 0 &&
                            gridManager.getCellsCount(cx, cy + 1) == 0 &&
                            gridManager.getCellsCount(cx, cy - 1) == 0
                    if (hasNeighbor) 2 else 1
                } else counterThread

                setGridValue(base, batchSize, cx - cameraX, cy - cameraY)
                if (base == 2) continue

                val cells = gridManager.getCells(cx, cy)
                for (id in cells) {
                    if (cellType[id] == 6 || cellType[id] == 14 || cellType[id] == 3) {
                        if (specialCellsId < specialCellsMaxSize) {
                            drawSpecialCells[specialCellsId++] = id
                        }
                    }
                    if (counterThread < cellsMaxAmount) {
                        bufferFloat.put(counterThread++, (1f))
                        bufferFloat.put(counterThread++, ((x[id] - zoomManager.screenOffsetX) * zoom) / Gdx.graphics.width)
                        bufferFloat.put(counterThread++, (((y[id] - zoomManager.screenOffsetY) * zoom) / Gdx.graphics.height) * aspectRatio)
                        bufferFloat.put(counterThread++, (ax[id]))
                        bufferFloat.put(counterThread++, (ay[id]))
                        bufferFloat.put(counterThread++, /*energy[id]*/(if (cellType[id] != 16) energy[id] else 0f))
                        bufferFloat.put(counterThread++, (colorR[id]))
                        bufferFloat.put(counterThread++, (colorG[id]))
                        bufferFloat.put(counterThread++, (colorB[id]))
                        bufferFloat.put(counterThread++, (0f))
                    }
                }

                val links = linksGrid.getLinks(cx, cy)
                for (id in links) {
                    if (counterThread < cellsMaxAmount) {
                        val c1 = links1[id]
                        val c2 = links2[id]
                        bufferFloat.put(counterThread++, (2f))
                        bufferFloat.put(counterThread++,((x[c1] - zoomManager.screenOffsetX) * zoom) / Gdx.graphics.width)
                        bufferFloat.put(counterThread++,(((y[c1] - zoomManager.screenOffsetY) * zoom) / Gdx.graphics.height) * aspectRatio)
                        bufferFloat.put(counterThread++, ((x[c2] - zoomManager.screenOffsetX) * zoom) / Gdx.graphics.width)
                        bufferFloat.put(counterThread++, (((y[c2] - zoomManager.screenOffsetY) * zoom) / Gdx.graphics.height) * aspectRatio)
                        bufferFloat.put(counterThread++, (colorR[c1]))
                        bufferFloat.put(counterThread++, (colorG[c1]))
                        bufferFloat.put(counterThread++, (colorB[c1]))
                        bufferFloat.put(counterThread++, (colorR[c2]))
                        bufferFloat.put(counterThread++, (colorG[c2]))
                        bufferFloat.put(counterThread++, (colorB[c2]))
                    }
                }
            }
        }
    }

    private fun putLinksToGrid(endCameraX: Float, endCameraY: Float) {
        val linksAmount = linksLastId + 1
        val threadAmount = THREAD_COUNT * 4
        val batchSize = (linksAmount + threadAmount - 1) / threadAmount

        for (i in 0 until threadAmount) {
            val start = i * batchSize
            val end = minOf(start + batchSize, linksAmount)

            if (start >= end) break

            threadManager.futures.add(threadManager.executor.submit {
                for (linkId in start until end) {
                    val c1 = links1[linkId]
                    val c2 = links2[linkId]
                    if (y[c1] > endCameraY) continue
                    if (x[c1] > endCameraX) continue
                    if (x[c1] < zoomManager.screenOffsetX) continue
                    if (y[c1] < zoomManager.screenOffsetY) continue

                    val cx = (((x[c1] + x[c2]) / 2f) / CELL_SIZE).toInt()
                    val cy = (((y[c1] + y[c2]) / 2f) / CELL_SIZE).toInt()
                    linksGrid.addLink(cx, cy, linkId)
                }
            })
        }

        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()
    }

    fun updateDraw() {

//        val startTime = System.nanoTime()
        specialCellsId = 0
        val aspectRatio = Gdx.graphics.height.toFloat() / Gdx.graphics.width.toFloat()
        val zoom = zoomManager.zoomScale * zoomManager.shaderCellSize
        val endCameraX = zoomManager.screenOffsetX + Gdx.graphics.width / zoom
        val endCameraY = zoomManager.screenOffsetY + Gdx.graphics.width / zoom
        val startX = ((zoomManager.screenOffsetX) / CELL_SIZE).toInt().coerceIn(0, WORLD_CELL_WIDTH)
        val startY = ((zoomManager.screenOffsetY) / CELL_SIZE).toInt().coerceIn(0, WORLD_CELL_HEIGHT)
        val endX = ((zoomManager.screenOffsetX + Gdx.graphics.width / zoom) / CELL_SIZE).toInt().coerceIn(0, WORLD_CELL_WIDTH - 1)
        val endY = ((zoomManager.screenOffsetY + Gdx.graphics.height / zoom) / CELL_SIZE).toInt().coerceIn(0, WORLD_CELL_HEIGHT - 1)
        val cameraX = ((zoomManager.screenOffsetX) / CELL_SIZE).toInt()
        val cameraY = ((zoomManager.screenOffsetY) / CELL_SIZE).toInt()

        putLinksToGrid(endCameraX, endCameraY)

        val strokeCountsSize = endY - startY
        for (cy in startY..endY) {
            for (cx in startX..endX) {
                val cellIndex = cy * WORLD_CELL_WIDTH + cx
                val linkIndex = cy * SCREEN_CELL_WIDTH_LINK + cx
                drawStrokeCellCounts[cy - startY] += gridManager.cellCounts[cellIndex] * CELLS_FLOAT_COUNT + linksGrid.linkCounts[linkIndex] * LINKS_FLOAT_COUNT
            }
        }

        //0.22 мс на сам сбор данных
        var sum = 0
        for (i in 0..strokeCountsSize) {
            val cellsCountInStroke = drawStrokeCellCounts[i]
            drawStrokeCellCounts[i] = sum
            sum += cellsCountInStroke
        }

        val threadAmount = THREAD_COUNT * 4
        val total = strokeCountsSize + 1
        val chunkSize = (total + threadAmount - 1) / threadAmount // округление вверх
        for (threadIndex in 0 until threadAmount) {
            val start = threadIndex * chunkSize
            val end = minOf((threadIndex + 1) * chunkSize, total)
            if (start >= end) break
            threadManager.futures.add(threadManager.executor.submit {
                updateDrawChunk(start, end, startX, endX, startY, cameraX, cameraY, zoom, aspectRatio)
            })
        }

        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        bufferFloat.position(0)

        bufferInt.clear()
        bufferInt.put(drawIntGrid)
        bufferInt.position(0)

        threadManager.futures.add(threadManager.executor.submit {
            drawIntGrid.fill(2)
            drawStrokeCellCounts.fill(0)
            linksGrid.clear()
        })

//        val elapsedTime = (System.nanoTime() - startTime) / 1_000_000.0 // в миллисекундах
        //0.43 - на весь updateDraw метод
        //С линками теперь 0.62 - не так плохо, но и не хорошо
//        println("$elapsedTime")
    }

    private fun repulseNeighbors(cellId: Int, gridX: Int, gridY: Int, threadId: Int) {
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

    private fun killCell(cellId: Int, threadId: Int) {
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

    fun processCell(cellId: Int, gridX: Int, gridY: Int, threadId: Int) {
        stretchLinks(cellId, threadId)
        repulseNeighbors(cellId, gridX, gridY, threadId)

        if (energy[cellId] > 0) isAliveWithoutEnergy[cellId] = 200
        if (isAliveWithoutEnergy[cellId] < 0) {
            killCell(cellId, threadId)
            return
        }

        if (cellType[cellId] != 16) {
            if (x[cellId] < CELL_RADIUS && vx[cellId] < 0f) {
                x[cellId] = CELL_RADIUS
                vx[cellId] = -vx[cellId] * 0.1f
            }
            if (x[cellId] > WORLD_WIDTH - CELL_RADIUS && vx[cellId] > 0f) {
                x[cellId] = WORLD_WIDTH - CELL_RADIUS
                vx[cellId] = -vx[cellId] * 0.1f
            }
            if (y[cellId] < CELL_RADIUS && vy[cellId] < 0f) {
                y[cellId] = CELL_RADIUS
                vy[cellId] = -vy[cellId] * 0.1f
            }
            if (y[cellId] > WORLD_HEIGHT - CELL_RADIUS && vy[cellId] > 0f) {
                y[cellId] = WORLD_HEIGHT - CELL_RADIUS
                vy[cellId] = -vy[cellId] * 0.1f
            }
            vx[cellId] *= frictionLevel[cellId]
            vy[cellId] *= frictionLevel[cellId]

            if (energy[cellId] <= 0f) isAliveWithoutEnergy[cellId] -= 1
            if (isLooseEnergy[cellId]) energy[cellId] -= 0.001f
//
//            if (!isPhantom[cellId]) {
//                val rawAx = (vxOld[cellId] - vx[cellId]) / 36f
//                val rawAy = (vyOld[cellId] - vy[cellId]) / 36f
//                ax[cellId] = 0.3f * rawAx + 0.7f * ax[cellId]
//                ay[cellId] = 0.3f * rawAy + 0.7f * ay[cellId]
//                vxOld[cellId] = vx[cellId]
//                vyOld[cellId] = vy[cellId]
//            }
        }
        doSpecific(cellType[cellId], cellId)

        if (cellType[cellId] != 16) {
            divideCell(cellId)
            mutateCell(cellId, threadId)
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
                processLink(cellId, otherCellId, linkId, threadId)
            }
        }
    }

    private fun processLink(cellId: Int, id: Int, linkId: Int, threadId: Int) {
        if (cellType[id] == 16 && cellType[cellId] == 16) return
        val dx = x[cellId] - x[id]
        val dy = y[cellId] - y[id]

        //transportEnergy
        if (energy[cellId] / maxEnergy[cellId] < energy[id] / maxEnergy[id]) {
            energy[cellId] += 0.01f
            energy[id] -= 0.01f
        } else if (energy[cellId] / maxEnergy[cellId] != energy[id] / maxEnergy[id]) {
            energy[cellId] -= 0.01f
            energy[id] += 0.01f
        }

        //transportNeuronImpulse
        if (isNeuronLink[linkId] && directedNeuronLink[linkId] != -1) {
            if (links1[linkId] == directedNeuronLink[linkId]) {
                if (cellType[links1[linkId]] != 4 && cellType[links1[linkId]] != 13) {
                    if (neuronImpulseImport[links2[linkId]] != neuronImpulseImport[links1[linkId]]) {
                        neuronImpulseImport[links1[linkId]] = neuronImpulseImport[links2[linkId]]
                    }
                }
            } else if (links2[linkId] == directedNeuronLink[linkId]) {
                if (cellType[links2[linkId]] != 4 && cellType[links2[linkId]] != 13) {
                    if (neuronImpulseImport[links1[linkId]] != neuronImpulseImport[links2[linkId]]) {
                        neuronImpulseImport[links2[linkId]] = neuronImpulseImport[links1[linkId]]
                    }
                }
            }
        }
        if (isStickyLink[linkId]) {
            if (cellType[cellId] == 11 && neuronImpulseImport[cellId] >= 1) {
                deletedLinkSizes[threadId] += 1
                deleteLinkLists[threadId][deletedLinkSizes[threadId]] = linkId
            } else if (cellType[id] == 11 && neuronImpulseImport[id] >= 1) {
                deletedLinkSizes[threadId] += 1
                deleteLinkLists[threadId][deletedLinkSizes[threadId]] = linkId
            }
        }

        val sqrt = dx * dx + dy * dy
        if (sqrt > 6400) {
            addToDeleteList(threadId, linkId)
            return
        }
        val stiffness = (linkStrength[id] + linkStrength[cellId]) / 2
        if (sqrt <= 0) return
        val dist = 1.0f / invSqrt(sqrt)

        val force = (dist - linksLength[linkId] * degreeOfShortening[linkId]) * stiffness

        val fx = force * dx / dist
        val fy = force * dy / dist
        vx[id] += fx
        vy[id] += fy
        vx[cellId] -= fx
        vy[cellId] -= fy
    }

    private fun repulse(cellId: Int, id: Int, isSameCell: Boolean = false, threadId: Int) {
        if (cellType[id] == 16 && cellType[cellId] == 16) return

        //TODO нужно будет сделать оценку что выгоднее каждый раз запрашивать linkIdMap.get или перебором, в общем зависит от количества коллизй в linkIdMap
        val linkId = linkIdMap.get(cellId, id)
        if (isSameCell && linkId != -1) {
            processLink(cellId, id, linkId, threadId)
            return
        }
        if (linkId != -1) return
        val dx = x[cellId] - x[id]
        val dy = y[cellId] - y[id]
        val dx2 = dx * dx
        val radiusSquared = 1600
        if (dx2 > radiusSquared) return
        val dy2 = dy * dy
        if (dy2 > radiusSquared) return
        val distanceSquared = dx2 + dy2
        if (distanceSquared < radiusSquared) {
            val distance = 1.0f / invSqrt(distanceSquared)
            if (distance.isNaN()) throw Exception("TODO потом убрать")
            if (cellType[id] == 11 && neuronImpulseImport[id] < 1f) {
                addStickyLink(id, cellId, distance)
                return
            } else if (cellType[cellId] == 11 && neuronImpulseImport[cellId] < 1f) {
                addStickyLink(cellId, id, distance)
                return
            }
            // Квадратичная зависимость силы
            val cellStrengthAverage = (cellStrength[cellId] + cellStrength[id]) / 2f
            val force = cellStrengthAverage - cellStrengthAverage * distanceSquared / radiusSquared
            // Нормализация вектора расстояния
            val normX = dx / distance
            val normY = dy / distance
            if (normX.isNaN() || normY.isNaN()) throw Exception("TODO потом убрать")
            val vectorX = normX * force
            val vectorY = normY * force
            vx[cellId] += vectorX
            vy[cellId] += vectorY
            vx[id] -= vectorX
            vy[id] -= vectorY
            if (cellType[id] == 12) {
                if (energy[id] < maxEnergy[id] && energy[cellId] >= 0.1f) {
                    energy[id] += 0.1f
                    energy[cellId] -= 0.1f
                }
            }
            if (cellType[cellId] == 12) {
                if (energy[cellId] < maxEnergy[cellId] && energy[id] >= 0.1f) {
                    energy[cellId] += 0.1f
                    energy[id] -= 0.1f
                }
                return
            }
        }
    }

    fun updateSize() {
        cellMaxAmount += 1000
        x = x.copyOf(cellMaxAmount)
        y = y.copyOf(cellMaxAmount)
        vx = vx.copyOf(cellMaxAmount)
        vy = vy.copyOf(cellMaxAmount)
        colorR = colorR.copyOf(cellMaxAmount)
        colorG = colorG.copyOf(cellMaxAmount)
        colorB = colorB.copyOf(cellMaxAmount)
        cellType = cellType.copyOf(cellMaxAmount)
        energy = energy.copyOf(cellMaxAmount)
        maxEnergy = maxEnergy.copyOf(cellMaxAmount)
        //TODO остальные
        //System.arraycopy(this.x, 0, newX, 0, this.x.size) было бы получше
    }

    fun stopUpdateThread() {
        threadManager.stopUpdateThread()
    }

    fun grabbed(px: Float, py: Float): Boolean {
        if (grabbedCell == -1) {
            grabbedX = px
            grabbedY = py
            val x = (px / CELL_SIZE).toInt()
            val y = (py / CELL_SIZE).toInt()
            val allCells = mutableListOf<Int>()
            for (i in -1..1) {
                for (j in -1..1) {
                    allCells.addAll(gridManager.getCells(x + i, y + j).toList())
                }
            }
            grabbedCell = allCells.minByOrNull { distanceTo(px, py, it) }
                ?.takeIf { distanceTo(px, py, it) < CELL_RADIUS } ?: return false

            if (grabbedCell != -1 && cellType[grabbedCell] == 16) {
                grabbedCell = -1
                return false
            }
        } else return false
        return true
    }

    private fun distanceTo(px: Float, py: Float, index: Int): Float {
        val dx = px - x[index]
        val dy = py - y[index]
        val sqrt = dx * dx + dy * dy
        if (sqrt <= 0) return 0f
        val result = sqrt(sqrt)
        if (result.isNaN()) throw Exception("TODO потом убрать")
        return result
    }

    fun moveTo(px: Float, py: Float) {
        if (grabbedCell == -1) return
        grabbedX = px
        grabbedY = py
    }

    fun deleteCell(px: Float, py: Float) {

        if (deleteCell == -1) {
            val x = (px / CELL_SIZE).toInt()
            val y = (py / CELL_SIZE).toInt()
            val allCells = mutableListOf<Int>()
            for (i in -1..1) {
                for (j in -1..1) {
                    allCells.addAll(gridManager.getCells(x + i, y + j).toList())
                }
            }
            deleteCell = allCells.minByOrNull { distanceTo(px, py, it) }
                ?.takeIf { distanceTo(px, py, it) < CELL_RADIUS } ?: -1
            if (deleteCell != -1 && cellType[deleteCell] == 16) deleteCell = -1
        }
    }

    fun addCell(px: Float, py: Float, type: Int, isFirst: Boolean = false) {
        cellLastId++
        createCellType(type, cellLastId, true)
        val i = cellLastId
        if (isFirst) {
            id[i] = "0"
        } else {
            id[i] = cellLastId.toString()
        }
        x[i] = px
        y[i] = py
        cellType[i] = type
        isPhantom[i] = false
        gridId[i] = gridManager.addCell(
            (x[cellLastId] / CELL_SIZE).toInt(),
            (y[cellLastId] / CELL_SIZE).toInt(),
            cellLastId
        )
    }

    fun updateBeforeCycle() {
        if (grabbedCell != -1) {
            vx[grabbedCell] += (grabbedX - x[grabbedCell]) * 0.01f
            vy[grabbedCell] += (grabbedY - y[grabbedCell]) * 0.01f
        }
    }

    fun updateAfterCycle() {
        if (cellLastId >= 0 && deleteCell != -1) {
            //TODO тут явно не правильное удаление c потоком threadId, но не страшно, так как это удаление через мышку
            val threadId = if (y[deleteCell] > 480f) 1 else 0
            killCell(deleteCell, threadId)
            deleteCell = -1
        }

//        isAlreadyUpdated.fill(false, 0, cellLastId + 1)
//      TODO так и не смог распаралелить, надо будет к этому вернуться
//        for (i in 0..cellLastId) {
//            if (!isPhantom[i] && cellType[i] != 16) {
//                val oldX = (x[i] / CELL_SIZE).toInt()
//                val oldY = (y[i] / CELL_SIZE).toInt()
//                x[i] += vx[i]
//                y[i] += vy[i]
//                val newX = (x[i] / CELL_SIZE).toInt()
//                val newY = (y[i] / CELL_SIZE).toInt()
//                if (newX != oldX || newY != oldY) {
//                    gridManager.removeCell(oldX, oldY, i)
//                    gridId[i] = gridManager.addCell(newX, newY, i)
//                }
//            }
//        }

        val cellsAmount = cellLastId + 1
        val threads = THREAD_COUNT * 4
        val chunkSize = (cellsAmount + threads - 1) / threads
        for (t in 0 until threads) {
            val start = t * chunkSize
            val end = minOf((t + 1) * chunkSize, cellsAmount + 1)
            threadManager.futures += threadManager.executor.submit {
                for (i in start until end) {
                    if (!isPhantom[i] && cellType[i] != 16) {
                        val oldX = (x[i] / CELL_SIZE).toInt()
                        val oldY = (y[i] / CELL_SIZE).toInt()
                        val rawAx = (vxOld[i] - vx[i]) / 36f
                        val rawAy = (vyOld[i] - vy[i]) / 36f
                        ax[i] = 0.3f * rawAx + 0.7f * ax[i]
                        ay[i] = 0.3f * rawAy + 0.7f * ay[i]
                        vxOld[i] = vx[i]
                        vyOld[i] = vy[i]
                        x[i] += vx[i]
                        y[i] += vy[i]
                        val newX = (x[i] / CELL_SIZE).toInt()
                        val newY = (y[i] / CELL_SIZE).toInt()
                        if (newX == oldX && newY == oldY) continue
                        // Сначала лочим в одном порядке, чтобы избежать дедлоков
                        val first = minOf(oldY * WORLD_CELL_WIDTH + oldX, newY * WORLD_CELL_WIDTH + newX)
                        val second = maxOf(oldY * WORLD_CELL_WIDTH + oldX, newY * WORLD_CELL_WIDTH + newX)
                        gridManager.gridLocks[first].lock()
                        gridManager.gridLocks[second].lock()
                        try {
                            gridManager.removeCellSync(oldX, oldY, i)
                            gridId[i] = gridManager.addCellSync(newX, newY, i)
                        } finally {
                            gridManager.gridLocks[second].unlock()
                            gridManager.gridLocks[first].unlock()
                        }
                    }
                }
            }
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        deletionLinksBuffer.collect(deleteLinkLists, deletedLinkSizes)
        deletionLinksBuffer.flush()

        deletionCellsBuffer.collect(deleteCellLists, deletedCellSizes)
        deletionCellsBuffer.flush()


        subManager.updateCells()

        var isEveryCellIsMutated = true
        var isEveryCellIsDivided = true

        if (cellLastId != -1) {
            for (i in 0..cellLastId) {
                if (cellType[i] != 16) {
                    isEveryCellIsDivided = isDividedInThisStage[i] && isEveryCellIsDivided
                    isEveryCellIsMutated = isMutateInThisStage[i] && isEveryCellIsMutated
                }
            }
        }

        if (isEveryCellIsDivided && isEveryCellIsMutated && genomeStageInstruction.size > genomeStage) {
            if (cellLastId != -1) {
                for (i in 0..cellLastId) {
                    if (cellType[i] != 16) {
                        isDividedInThisStage[i] = false
                        isMutateInThisStage[i] = false
                    }
                }
                genomeStage++
                genomeUpdate()
            }
        }

    }


    companion object {
        const val CELL_RADIUS = 20f
        const val MAX_LINK_AMOUNT = 8
    }
}
