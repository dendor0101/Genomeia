package io.github.some_example_name.old.good_one.shader

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.BufferUtils
import io.github.some_example_name.old.good_one.SHADER_TEXTURE_SIZE
import io.github.some_example_name.old.good_one.SHADER_TEXTURE_SIZE_POW_2
import io.github.some_example_name.old.good_one.shader.ShaderManagerSampler2D.Companion.CELLS_FLOAT_COUNT
import io.github.some_example_name.old.good_one.shader.ShaderManagerSampler2D.Companion.LINKS_FLOAT_COUNT
import io.github.some_example_name.old.screens.GlobalSettings
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import io.github.some_example_name.old.world_logic.GridManager.Companion.WORLD_CELL_HEIGHT
import io.github.some_example_name.old.world_logic.GridManager.Companion.WORLD_CELL_WIDTH
import io.github.some_example_name.old.world_logic.ThreadManager.Companion.THREAD_COUNT
import io.github.some_example_name.old.world_logic.cells.base.isDirected
import java.nio.Buffer
import java.util.concurrent.Executors
import java.util.concurrent.Future

open class ShaderManager {


    /*
    * ShaderSystem
    * */

    //Cell
    val floatArraySize = SHADER_TEXTURE_SIZE * SHADER_TEXTURE_SIZE * 4
    val intArraySize = SHADER_TEXTURE_SIZE * SHADER_TEXTURE_SIZE * 2
    val cellsMaxAmount = floatArraySize - floatArraySize % CELLS_FLOAT_COUNT - CELLS_FLOAT_COUNT
    val drawStrokeCellCounts = IntArray(SHADER_TEXTURE_SIZE) { 0 }
    val drawIntGrid = IntArray(SHADER_TEXTURE_SIZE * SHADER_TEXTURE_SIZE * 2) { 0 }
    val bufferFloat = BufferUtils.newFloatBuffer(floatArraySize)
    val bufferPheromoneFloat = BufferUtils.newFloatBuffer(floatArraySize)
    val bufferInt = BufferUtils.newIntBuffer(intArraySize) // 2 int на пиксель
    val bufferFloatEmpty = BufferUtils.newFloatBuffer(floatArraySize)
    val bufferIntEmpty = BufferUtils.newIntBuffer(intArraySize) // 2 int на пиксель

    val zoomManager = ZoomManager()
    val linksGrid = LinksDrawGridStructure()

    val executor = Executors.newFixedThreadPool(THREAD_COUNT)
    val futures = mutableListOf<Future<*>>()
    var backgroundColor = Color(0.064f, 0.070f, 0.098f, 1f)
    var drawRays = false

    fun setGridValue(base: Int, total: Int, x: Int, y: Int) {
        val index = ((y shl SHADER_TEXTURE_SIZE_POW_2) + x) shl 1 // (y * 256 + x) * 2
        if (index < 0 || index + 1 >= intArraySize) return
        drawIntGrid[index] = base
        drawIntGrid[index + 1] = total
    }



    val specialCellsMaxSize = 20_000
    @Volatile
    var specialCellsId = 0
    var drawSpecialCells = IntArray(specialCellsMaxSize) { -1 }

}



private fun CellManager.updateDrawChunk(
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
            var linkCountZero = true
            val batchSize = if (GlobalSettings.DRAW_LINK_SHADER) {
                val linksCount = linksGrid.getLinksCount(cx, cy)
                linkCountZero = linksCount == 0
                cellsCount * CELLS_FLOAT_COUNT + linksCount * LINKS_FLOAT_COUNT
            } else {
                linkCountZero = true
                cellsCount * CELLS_FLOAT_COUNT
            }

            val gridCellIndex = cy * WORLD_CELL_WIDTH + cx
            val textureCoordIndex = (cy - startY) * WORLD_CELL_WIDTH + cx - startX
            if (gridCellIndex < gridManager.GRID_SIZE) {
                bufferPheromoneFloat.put(textureCoordIndex*4, pheromoneR[gridCellIndex])
                bufferPheromoneFloat.put(textureCoordIndex*4 + 1, pheromoneG[gridCellIndex])
                bufferPheromoneFloat.put(textureCoordIndex*4 + 2, pheromoneB[gridCellIndex])
            }

            val base = if (cellsCount == 0 && linkCountZero) {
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
                if (drawRays) {
                    if (cellType[id] == 6 || cellType[id].isDirected() || cellType[id] == 0) {
                        if (specialCellsId < specialCellsMaxSize - 1) {
                            drawSpecialCells[specialCellsId++] = id
                        }
                    }
                }
                if (counterThread < cellsMaxAmount) {
                    bufferFloat.put(counterThread++, (1f))
                    bufferFloat.put(
                        counterThread++,
                        ((x[id] - zoomManager.screenOffsetX) * zoom) / Gdx.graphics.width
                    )
                    bufferFloat.put(
                        counterThread++,
                        (((y[id] - zoomManager.screenOffsetY) * zoom) / Gdx.graphics.height)/* * aspectRatio*/
                    )
                    bufferFloat.put(counterThread++, (ax[id]))
                    bufferFloat.put(counterThread++, (ay[id]))
                    bufferFloat.put(
                        counterThread++, /*energy[id]*/
                        (if (cellType[id] != -1) energy[id] else 0f)
                    )
                    bufferFloat.put(counterThread++, (colorR[id]))
                    bufferFloat.put(counterThread++, (colorG[id]))
                    bufferFloat.put(counterThread++, (colorB[id]))
                    bufferFloat.put(counterThread++, (0f))
                }
            }

            if (GlobalSettings.DRAW_LINK_SHADER) {
                val links = linksGrid.getLinks(cx, cy)
                for (id in links) {
                    if (counterThread < cellsMaxAmount) {
                        val c1 = links1[id]
                        val c2 = links2[id]
                        bufferFloat.put(counterThread++, (2f))
                        bufferFloat.put(
                            counterThread++,
                            ((x[c1] - zoomManager.screenOffsetX) * zoom) / Gdx.graphics.width
                        )
                        bufferFloat.put(
                            counterThread++,
                            (((y[c1] - zoomManager.screenOffsetY) * zoom) / Gdx.graphics.height)/* * aspectRatio*/
                        )
                        bufferFloat.put(
                            counterThread++,
                            ((x[c2] - zoomManager.screenOffsetX) * zoom) / Gdx.graphics.width
                        )
                        bufferFloat.put(
                            counterThread++,
                            (((y[c2] - zoomManager.screenOffsetY) * zoom) / Gdx.graphics.height)/* * aspectRatio*/
                        )
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
}

private fun CellManager.putLinksToGrid(endCameraX: Float, endCameraY: Float) {
    val linksAmount = linksLastId + 1
    val threadAmount = THREAD_COUNT * 4
    val batchSize = (linksAmount + threadAmount - 1) / threadAmount

    for (i in 0 until threadAmount) {
        val start = i * batchSize
        val end = minOf(start + batchSize, linksAmount)

        if (start >= end) break

        futures.add(executor.submit {
            for (linkId in start until end) {
                if (!isAliveLink[linkId]) continue
                val c1 = links1[linkId]
                val c2 = links2[linkId]
                if (y[c1] > endCameraY) continue
                if (x[c1] > endCameraX) continue
                if (x[c1] < zoomManager.screenOffsetX) continue
                if (y[c1] < zoomManager.screenOffsetY) continue

                val dx = x[c1] - x[c2]
                val dy = y[c1] - y[c2]
                val dx2 = dx * dx
                val dy2 = dy * dy
                if (dx2 + dy2 < 1225) continue

                val cx = (((x[c1] + x[c2]) / 2f) / CELL_SIZE).toInt()
                val cy = (((y[c1] + y[c2]) / 2f) / CELL_SIZE).toInt()
                linksGrid.addLink(cx, cy, linkId)
            }
        })
    }

    futures.forEach { it.get() }
    futures.clear()
}

fun CellManager.updateDraw() {
    specialCellsId = 0
    val aspectRatio = Gdx.graphics.height.toFloat() / Gdx.graphics.width.toFloat()
    val zoom = zoomManager.zoomScale * zoomManager.shaderCellSize

    val endCameraX = zoomManager.screenOffsetX + Gdx.graphics.width / zoom
    val endCameraY = zoomManager.screenOffsetY + Gdx.graphics.width / zoom
    val startX = ((zoomManager.screenOffsetX) / CELL_SIZE).toInt().coerceIn(0, WORLD_CELL_WIDTH)
    val startY =
        ((zoomManager.screenOffsetY) / CELL_SIZE).toInt().coerceIn(0, WORLD_CELL_HEIGHT)
    val endX = ((zoomManager.screenOffsetX + Gdx.graphics.width / zoom) / CELL_SIZE).toInt()
        .coerceIn(0, WORLD_CELL_WIDTH - 1)
    val endY = ((zoomManager.screenOffsetY + Gdx.graphics.height / zoom) / CELL_SIZE).toInt()
        .coerceIn(0, WORLD_CELL_HEIGHT - 1)
    val cameraX = ((zoomManager.screenOffsetX) / CELL_SIZE).toInt()
    val cameraY = ((zoomManager.screenOffsetY) / CELL_SIZE).toInt()

    if (GlobalSettings.DRAW_LINK_SHADER)
        putLinksToGrid(endCameraX, endCameraY)

    val strokeCountsSize = endY - startY
    if (strokeCountsSize >= SHADER_TEXTURE_SIZE) return
    for (cy in startY..endY) {
        for (cx in startX..endX) {
            val cellIndex = cy * WORLD_CELL_WIDTH + cx
            val linkIndex = cy * LinksDrawGridStructure.Companion.SCREEN_CELL_WIDTH_LINK + cx
            if (GlobalSettings.DRAW_LINK_SHADER) {
                drawStrokeCellCounts[cy - startY] += gridManager.cellCounts[cellIndex] * CELLS_FLOAT_COUNT + linksGrid.linkCounts[linkIndex] * LINKS_FLOAT_COUNT
            } else {
                drawStrokeCellCounts[cy - startY] += gridManager.cellCounts[cellIndex] * CELLS_FLOAT_COUNT
            }
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
        futures.add(executor.submit {
            updateDrawChunk(
                start,
                end,
                startX,
                endX,
                startY,
                cameraX,
                cameraY,
                zoom,
                aspectRatio
            )
        })
    }

    futures.forEach { it.get() }
    futures.clear()

    (bufferFloat as Buffer).position(0)

    (bufferInt as Buffer).clear()
    bufferInt.put(drawIntGrid)
    (bufferInt as Buffer).position(0)

    futures.add(executor.submit {
        drawIntGrid.fill(2)
        drawStrokeCellCounts.fill(0)
        if (GlobalSettings.DRAW_LINK_SHADER) {
            linksGrid.clear()
        }
    })

    futures.forEach { it.get() }
    futures.clear()
}
