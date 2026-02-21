package io.github.some_example_name.old.world_logic

import com.badlogic.gdx.math.MathUtils.clamp
import io.github.some_example_name.old.world_logic.CellManager.Companion.CELL_RADIUS


//TODO оценить масштаб проблемы grid based оптимизации
// TODO: Assess the scale of the grid-based optimization problem
class GridManager (var gridCellWidthSize: Int = WORLD_CELL_WIDTH, var gridCellHeightSize: Int = WORLD_CELL_HEIGHT) {

    var GRID_SIZE = gridCellWidthSize * gridCellHeightSize

    var WORLD_WIDTH = gridCellWidthSize * CELL_SIZE
    var WORLD_HEIGHT = gridCellHeightSize * CELL_SIZE
    var WORLD_WIDTH_MINUS_CELL_RADIUS = WORLD_WIDTH - CELL_RADIUS
    var WORLD_HEIGHT_MINUS_CELL_RADIUS = WORLD_HEIGHT - CELL_RADIUS

    //TODO возможно будет лучше: Off-heap хранение (ByteBuffer) Для очень больших сеток (если важно избегать GC)
    // TODO might be better: Off-heap storage (ByteBuffer) For very large grids (if avoiding GC is important)
    val grid = IntArray(GRID_SIZE * MAX_AMOUNT_OF_CELLS) { -1 }
    val cellCounts = IntArray(GRID_SIZE) { 0 } // Счетчик элементов в каждой ячейке // Count of elements in each cell
    val globalSettings = readSettings()

    // Добавить элемент в ячейку (x, y) // Add an element to cell (x, y)
    inline fun addCell(x: Int, y: Int, value: Int, killCell: () -> Unit): Int {
        if (x < 0 || x >= gridCellWidthSize || y < 0 || y >= gridCellHeightSize) {
            killCell.invoke()
            return -1
        }
        val cellIndex = y * gridCellWidthSize + x
        val currentCount = cellCounts[cellIndex]

        if (currentCount >= MAX_AMOUNT_OF_CELLS) {
            println("MAX_AMOUNT_OF_CELLS")
            killCell.invoke()
            return -1 // Ячейка заполнена
        }

        val gridIndex = cellIndex * MAX_AMOUNT_OF_CELLS + currentCount
        grid[gridIndex] = value
        cellCounts[cellIndex]++
        return cellIndex
    }

    fun addCell(cellIndex: Int, value: Int): Int {
        val currentCount = cellCounts[cellIndex]

        if (currentCount >= MAX_AMOUNT_OF_CELLS) {
            println("MAX_AMOUNT_OF_CELLS")
            return -1 // Ячейка заполнена // The cell is filled
        }

        val gridIndex = cellIndex * MAX_AMOUNT_OF_CELLS + currentCount
        grid[gridIndex] = value
        cellCounts[cellIndex]++
        return cellIndex
    }

    // Удалить элемент из ячейки (x, y) по значению (если порядок не важен)
    // Remove element from cell (x, y) by value (if order doesn't matter)
    fun removeCell(x: Int, y: Int, value: Int): Boolean {
        if (x < 0 || x >= gridCellWidthSize || y < 0 || y >= gridCellHeightSize) return false
        val cellIndex = y * gridCellWidthSize + x
        val start = cellIndex * MAX_AMOUNT_OF_CELLS
        val end = start + cellCounts[cellIndex] - 1

        for (i in start..end) {
            if (grid[i] == value) {
                // Заменяем удаляемый элемент последним в ячейке
                // Replace the deleted element with the last one in the cell
                grid[i] = grid[end]
                grid[end] = -1
                cellCounts[cellIndex]--
                return true
            }
        }
        return false // Элемент не найден // Element not found
    }

    fun getCellsCount(x: Int, y: Int): Int {
        if (x < 0 || x >= gridCellWidthSize || y < 0 || y >= gridCellHeightSize) {
            return 0
        }
        val cellIndex = y * gridCellWidthSize + x
        return cellCounts[cellIndex]
    }

    // Получить все элементы ячейки (x, y) // Get all cell elements (x, y)
    fun getCells(x: Int, y: Int): IntArray {
        if (x < 0 || x >= gridCellWidthSize || y < 0 || y >= gridCellHeightSize) {
            return IntArray(0) // Возвращаем пустой массив вместо списка // Return an empty array instead of a list
        }

        val cellIndex = y * gridCellWidthSize + x
        val start = cellIndex * MAX_AMOUNT_OF_CELLS
        val count = cellCounts[cellIndex]

        // Создаем массив нужного размера и копируем данные // Create an array of the required size and copy the data
        return grid.copyOfRange(start, start + count)
    }

    fun getCells(cellIndex: Int): IntArray {
        val start = cellIndex * MAX_AMOUNT_OF_CELLS
        val count = cellCounts[cellIndex]

        // Создаем массив нужного размера и копируем данные // Create an array of the required size and copy the data
        return grid.copyOfRange(start, start + count)
    }

    fun clearAll() {
//        grid.fill(-1)
        cellCounts.fill(0)
    }

    private fun diffusePheromones(cm: CellManager, x1: Int, y1: Int, x2: Int, y2: Int) {
        val cellIndex1 = y1 * gridCellWidthSize + x1
        val cellIndex2 = y2 * gridCellWidthSize + x2
        if (cellIndex1 == cellIndex2) return
        if (cellIndex1 < 0 || cellIndex1 >= GRID_SIZE || cellIndex2 < 0 || cellIndex2 >= GRID_SIZE) return
        val dR = cm.pheromoneR[cellIndex2] - cm.pheromoneR[cellIndex1]
        val dG = cm.pheromoneG[cellIndex2] - cm.pheromoneG[cellIndex1]
        val dB = cm.pheromoneB[cellIndex2] - cm.pheromoneB[cellIndex1]
        val diffusionRate = globalSettings.rateOfPheromoneDiffusion
        cm.pheromoneR[cellIndex1] += dR * diffusionRate
        cm.pheromoneR[cellIndex2] -= dR * diffusionRate
        cm.pheromoneG[cellIndex1] += dG * diffusionRate
        cm.pheromoneG[cellIndex2] -= dG * diffusionRate
        cm.pheromoneB[cellIndex1] += dB * diffusionRate
        cm.pheromoneB[cellIndex2] -= dB * diffusionRate
    }

    fun pheromoneUpdate(cm: CellManager, x: Int, y: Int) {
        // Diffuse pheromones between neighboring chunks and slightly reduce their concentration
        val cellIndex = y * gridCellWidthSize + x
        val degradationRate = globalSettings.rateOfPheromoneDegradation
        cm.pheromoneR[cellIndex] -= degradationRate
        cm.pheromoneG[cellIndex] -= degradationRate
        cm.pheromoneB[cellIndex] -= degradationRate
        // TODO: This operation is just a simple box blur, I'm like 99% sure there is some simple optimisation for this
        for (i in -1..1) {
            if (x + i !in 0..<gridCellWidthSize) continue
            for (j in -1..1) {
                if (y + j !in 0..<gridCellHeightSize) continue
                diffusePheromones(cm, x, y, x + i, y + j)
            }
        }
        // Clamp between 0 and 1
        cm.pheromoneR[cellIndex] = clamp(cm.pheromoneR[cellIndex], 0f, 1f)
        cm.pheromoneG[cellIndex] = clamp(cm.pheromoneG[cellIndex], 0f, 1f)
        cm.pheromoneB[cellIndex] = clamp(cm.pheromoneB[cellIndex], 0f, 1f)
    }

    companion object {
        var WORLD_SIZE_TYPE = WorldSize.XL
        val WORLD_CELL_WIDTH = WORLD_SIZE_TYPE.size
        val WORLD_CELL_HEIGHT = WORLD_SIZE_TYPE.size
        val GRID_SIZE = WORLD_CELL_WIDTH * WORLD_CELL_HEIGHT
        const val CELL_SIZE = CELL_RADIUS * 2
        const val MAX_AMOUNT_OF_CELLS = 12
        val MAX_ZOOM = WORLD_SIZE_TYPE.maxZoom
    }
}

enum class WorldSize(val size: Int, val threadCount: Int, val generateWorldSize: Int, val maxZoom: Float = 0.2f ) {
    S(24, 2, 37, 1f), //Small (маленький)

    M(48, 4, 75, 0.5f), //Medium (средний)

    L(96, 6, 153, 0.25f), //Large (большой)

    XL(192, 6, 305, 0.125f), //Extra Large (очень большой)
}
//
//    XXL(384, 13, 611), //Extra Extra Large (2XL, очень-очень большой) // TODO чинить
//
//    XXXL(768, 14, 611), //Extra Extra Extra Large (3XL)// TODO чинить
//
//    XXXXL(1536, 14, 611), //(4XL) и т. д.// TODO чинить
