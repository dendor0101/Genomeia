package io.github.some_example_name.old.systems.physics
import io.github.some_example_name.old.core.DISimulationContainer.chunkSize
import io.github.some_example_name.old.core.DISimulationContainer.totalChunks
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList

class GridManager (
    val gridWidth: Int,
    val gridHeight: Int
) {
    var gridSize = gridWidth * gridHeight
    val grid = IntArray(gridSize * MAX_AMOUNT_OF_PARTICLES) { -1 }
    val particleCounts = IntArray(gridSize)
    val mapMoreThenMax = Array(totalChunks * 2) { Int2ObjectOpenHashMap<IntArrayList>() }

    private val halfChunkSize = chunkSize / 2
    private fun getHalfChunkId(gridIndex: Int) = gridIndex / halfChunkSize

    fun addParticle(x: Int, y: Int, value: Int): Int {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight) {
            //TODO Запретить спавн клетки за границей сетки
            throw Exception("Out of grid bounds")
        }
        val cellIndex = y * gridWidth + x
        if (particleCounts[cellIndex] >= MAX_AMOUNT_OF_PARTICLES) {
            val threadId = getHalfChunkId(cellIndex)
            var list = mapMoreThenMax[threadId].get(cellIndex)
            if (list == null) {
                list = IntArrayList()
                mapMoreThenMax[threadId].put(cellIndex, list)
            }
            list.add(value)
        } else {
            val gridIndex = cellIndex * MAX_AMOUNT_OF_PARTICLES + particleCounts[cellIndex]
            grid[gridIndex] = value
        }

        particleCounts[cellIndex]++
        return cellIndex
    }

    fun addCell(cellIndex: Int, value: Int): Int {
        if (particleCounts[cellIndex] >= MAX_AMOUNT_OF_PARTICLES) {
            val threadId = getHalfChunkId(cellIndex)
            var list = mapMoreThenMax[threadId].get(cellIndex)
            if (list == null) {
                list = IntArrayList()
                mapMoreThenMax[threadId].put(cellIndex, list)
            }
            list.add(value)
        } else {
            val gridIndex = cellIndex * MAX_AMOUNT_OF_PARTICLES + particleCounts[cellIndex]
            grid[gridIndex] = value
        }

        particleCounts[cellIndex]++
        return cellIndex
    }

    fun removeParticle(cellIndex: Int, value: Int): Boolean {
//        if (x < 0 || x >= gridCellWidthSize || y < 0 || y >= gridCellHeightSize) {
//            throw Exception("Out of grid bounds")
//        }
//        val cellIndex = y * gridWidth + x
        val start = cellIndex * MAX_AMOUNT_OF_PARTICLES
        if (particleCounts[cellIndex] <= MAX_AMOUNT_OF_PARTICLES) {
            val end = start + particleCounts[cellIndex] - 1
            for (i in start..end) {
                if (grid[i] == value) {
                    grid[i] = grid[end]
                    grid[end] = -1
                    particleCounts[cellIndex]--
                    return true
                }
            }
        } else {
            val end = start + MAX_AMOUNT_OF_PARTICLES - 1
            val threadId = getHalfChunkId(cellIndex)
            val list = mapMoreThenMax[threadId].get(cellIndex)
            for (i in start..end) {
                if (grid[i] == value) {
                    grid[i] = list?.removeInt(list.size - 1) ?: throw Exception("List is null or empty but particleCounts > MAX_AMOUNT_OF_PARTICLES")
                    if (list.isEmpty()) {
                        mapMoreThenMax[threadId].remove(cellIndex)
                    }
                    particleCounts[cellIndex]--
                    return true
                }
            }
            if (list?.rem(value) ?: false) {
                particleCounts[cellIndex]--
                if (list.isEmpty()) {
                    mapMoreThenMax[threadId].remove(cellIndex)//TODO swap remove without copy array
                }
            } else throw Exception("Couldn't delete list but particleCounts > MAX_AMOUNT_OF_PARTICLES")
            return true
        }

        return false
    }

    fun getParticles(x: Int, y: Int): IntArray {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight) {
            return IntArray(0)
        }
        val cellIndex = y * gridWidth + x
        return getParticlesIndex(cellIndex)
    }

    //TODO local every thread IntArray for return to avoid allocation
    fun getParticlesIndex(cellIndex: Int): IntArray {
        val start = cellIndex * MAX_AMOUNT_OF_PARTICLES
        return if (particleCounts[cellIndex] <= MAX_AMOUNT_OF_PARTICLES) {
            grid.copyOfRange(start, start + particleCounts[cellIndex])
        } else {
            val threadId = getHalfChunkId(cellIndex)
            val extraList = mapMoreThenMax[threadId].get(cellIndex) ?: throw Exception("List is null or empty but particleCounts > MAX_AMOUNT_OF_PARTICLES")
            val extraSize = extraList.size
            IntArray(particleCounts[cellIndex]).apply {
                if (extraSize > 0) System.arraycopy(extraList.elements(), 0, this, 0, extraSize)
                System.arraycopy(grid, start, this, extraSize, MAX_AMOUNT_OF_PARTICLES)
            }
        }
    }

    fun clearAll() {
        particleCounts.fill(0)
        mapMoreThenMax.forEach { it.clear() }
    }

    companion object {
        const val MAX_AMOUNT_OF_PARTICLES = 4
    }
}
