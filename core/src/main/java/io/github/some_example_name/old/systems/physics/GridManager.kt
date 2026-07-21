package io.github.some_example_name.old.systems.physics
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.WorldResizable
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList

class GridManager (
    var gridWidth: Int,
    var gridHeight: Int,
    val diContext: DIContext,
    val maxAmountOfParticles: Int
): WorldResizable {
    var gridSize = gridWidth * gridHeight
    var grid = IntArray(gridSize * maxAmountOfParticles) { -1 }
    var particleCounts = IntArray(gridSize)
    var mapMoreThenMax = Array(diContext.totalChunks * 2) { Int2ObjectOpenHashMap<IntArrayList>() }

    private var halfChunkSize = diContext.chunkSize / 2
    private fun getHalfChunkId(gridIndex: Int) = gridIndex / halfChunkSize

    fun addParticle(x: Int, y: Int, value: Int): Int {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight) {
            throw Exception("Out of grid bounds")
        }
        val cellIndex = y * gridWidth + x
        val threadId = getHalfChunkId(cellIndex)
        synchronized(mapMoreThenMax[threadId]) {
            if (particleCounts[cellIndex] >= maxAmountOfParticles) {
                var list = mapMoreThenMax[threadId].get(cellIndex)
                if (list == null) {
                    list = IntArrayList()
                    mapMoreThenMax[threadId].put(cellIndex, list)
                }
                list.add(value)
            } else {
                val gridIndex = cellIndex * maxAmountOfParticles + particleCounts[cellIndex]
                grid[gridIndex] = value
            }
            particleCounts[cellIndex]++
            return cellIndex
        }
    }

    fun addCell(cellIndex: Int, value: Int): Int {
        val threadId = getHalfChunkId(cellIndex)
        synchronized(mapMoreThenMax[threadId]) {
            if (particleCounts[cellIndex] >= maxAmountOfParticles) {
                var list = mapMoreThenMax[threadId].get(cellIndex)
                if (list == null) {
                    list = IntArrayList()
                    mapMoreThenMax[threadId].put(cellIndex, list)
                }
                list.add(value)
            } else {
                val gridIndex = cellIndex * maxAmountOfParticles + particleCounts[cellIndex]
                grid[gridIndex] = value
            }
            particleCounts[cellIndex]++
            return cellIndex
        }
    }

    fun removeParticle(cellIndex: Int, value: Int): Boolean {
        val threadId = getHalfChunkId(cellIndex)
        synchronized(mapMoreThenMax[threadId]) {
            val start = cellIndex * maxAmountOfParticles
            if (particleCounts[cellIndex] <= maxAmountOfParticles) {
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
                val end = start + maxAmountOfParticles - 1
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
                        mapMoreThenMax[threadId].remove(cellIndex)
                    }
                    return true
                } else throw Exception("Couldn't delete list but particleCounts > MAX_AMOUNT_OF_PARTICLES")
            }
            return false
        }
    }
    //TODO local every thread IntArray for return to avoid allocation
    fun getParticlesIndex(cellIndex: Int): IntArray {
        val threadId = getHalfChunkId(cellIndex)
        synchronized(mapMoreThenMax[threadId]) {
            val start = cellIndex * maxAmountOfParticles
            return if (particleCounts[cellIndex] <= maxAmountOfParticles) {
                grid.copyOfRange(start, start + particleCounts[cellIndex])
            } else {
                val extraList = mapMoreThenMax[threadId].get(cellIndex)
                    ?: throw Exception("List is null or empty but particleCounts > MAX_AMOUNT_OF_PARTICLES")
                val extraSize = extraList.size
                IntArray(particleCounts[cellIndex]).apply {
                    if (extraSize > 0) System.arraycopy(extraList.elements(), 0, this, 0, extraSize)
                    System.arraycopy(grid, start, this, extraSize, maxAmountOfParticles)
                }
            }
        }
    }

    fun getParticles(x: Int, y: Int): IntArray {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight) {
            return IntArray(0)
        }
        val cellIndex = y * gridWidth + x
        return getParticlesIndex(cellIndex)
    }

    fun clearAll() {
        particleCounts.fill(0)
        mapMoreThenMax.forEach { it.clear() }
    }

    override fun resize() {
        gridWidth = diContext.gridWidth
        gridHeight = diContext.gridHeight
        gridSize = gridWidth * gridHeight
        grid = IntArray(gridSize * maxAmountOfParticles) { -1 }
        particleCounts = IntArray(gridSize)
        halfChunkSize = diContext.chunkSize / 2
        mapMoreThenMax = Array(diContext.totalChunks * 2) { Int2ObjectOpenHashMap<IntArrayList>() }
    }

}
