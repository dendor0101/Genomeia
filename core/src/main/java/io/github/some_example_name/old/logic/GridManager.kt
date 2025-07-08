package io.github.some_example_name.old.logic

import io.github.some_example_name.old.logic.CellManager.Companion.CELL_RADIUS
import java.util.concurrent.locks.ReentrantLock

class GridManager {

    //TODO возможно будет лучше: Off-heap хранение (ByteBuffer) Для очень больших сеток (если важно избегать GC)
    val grid = IntArray(WORLD_CELL_WIDTH * WORLD_CELL_HEIGHT * MAX_AMOUNT_OF_CELLS) { -1 }
    val cellCounts = IntArray(WORLD_CELL_WIDTH * WORLD_CELL_HEIGHT) { 0 } // Счетчик элементов в каждой ячейке
//    val strokeCellCounts = IntArray(WORLD_CELL_HEIGHT) { 0 }
    //TODO лучше переделать на AtomicIntegerArray, должно быть побыстрее
    val gridLocks = Array(WORLD_CELL_WIDTH * WORLD_CELL_HEIGHT) { ReentrantLock() }


    fun addCellSync(x: Int, y: Int, value: Int): Int {
        if (x < 0 || x >= WORLD_CELL_WIDTH || y < 0 || y >= WORLD_CELL_HEIGHT) return -1
        val cellIndex = y * WORLD_CELL_WIDTH + x
        val lock = gridLocks[cellIndex]
        lock.lock()
        try {
            val currentCount = cellCounts[cellIndex]
            if (currentCount >= MAX_AMOUNT_OF_CELLS) {
                println("MAX_AMOUNT_OF_CELLS")
                return -1
            }
            val gridIndex = cellIndex * MAX_AMOUNT_OF_CELLS + currentCount
            grid[gridIndex] = value
            cellCounts[cellIndex]++
            return cellIndex
        } finally {
            lock.unlock()
        }
    }

    fun removeCellSync(x: Int, y: Int, value: Int): Boolean {
        if (x < 0 || x >= WORLD_CELL_WIDTH || y < 0 || y >= WORLD_CELL_HEIGHT) return false
        val cellIndex = y * WORLD_CELL_WIDTH + x
        val lock = gridLocks[cellIndex]
        lock.lock()
        try {
            val start = cellIndex * MAX_AMOUNT_OF_CELLS
            val end = start + cellCounts[cellIndex] - 1
            for (i in start..end) {
                if (grid[i] == value) {
                    grid[i] = grid[end]
                    grid[end] = -1
                    cellCounts[cellIndex]--
                    return true
                }
            }
            return false
        } finally {
            lock.unlock()
        }
    }


    // Добавить элемент в ячейку (x, y)
    fun addCell(x: Int, y: Int, value: Int): Int {
        if (x < 0 || x >= WORLD_CELL_WIDTH || y < 0 || y >= WORLD_CELL_HEIGHT) return -1
        val cellIndex = y * WORLD_CELL_WIDTH + x
        val currentCount = cellCounts[cellIndex]

        if (currentCount >= MAX_AMOUNT_OF_CELLS) {
            println("MAX_AMOUNT_OF_CELLS")
            return -1 // Ячейка заполнена
        }

        val gridIndex = cellIndex * MAX_AMOUNT_OF_CELLS + currentCount
        grid[gridIndex] = value
        cellCounts[cellIndex]++
//        strokeCellCounts[y]++
        return cellIndex
    }

    // Удалить элемент из ячейки (x, y) по значению (если порядок не важен)
    fun removeCell(x: Int, y: Int, value: Int): Boolean {
        if (x < 0 || x >= WORLD_CELL_WIDTH || y < 0 || y >= WORLD_CELL_HEIGHT) return false
        val cellIndex = y * WORLD_CELL_WIDTH + x
        val start = cellIndex * MAX_AMOUNT_OF_CELLS
        val end = start + cellCounts[cellIndex] - 1

        for (i in start..end) {
            if (grid[i] == value) {
                // Заменяем удаляемый элемент последним в ячейке
                grid[i] = grid[end]
                grid[end] = -1
                cellCounts[cellIndex]--
//                strokeCellCounts[y]--
                return true
            }
        }
        return false // Элемент не найден
    }

    fun getCellsCount(x: Int, y: Int): Int {
        if (x < 0 || x >= WORLD_CELL_WIDTH || y < 0 || y >= WORLD_CELL_HEIGHT) {
            return 0
        }
        val cellIndex = y * WORLD_CELL_WIDTH + x
        return cellCounts[cellIndex]
    }

    // Получить все элементы ячейки (x, y)
    fun getCells(x: Int, y: Int): IntArray {
        if (x < 0 || x >= WORLD_CELL_WIDTH || y < 0 || y >= WORLD_CELL_HEIGHT) {
            return IntArray(0) // Возвращаем пустой массив вместо списка
        }

        val cellIndex = y * WORLD_CELL_WIDTH + x
        val start = cellIndex * MAX_AMOUNT_OF_CELLS
        val count = cellCounts[cellIndex]

        // Создаем массив нужного размера и копируем данные
        return grid.copyOfRange(start, start + count)
    }

    fun getCell(x: Int, y: Int, index: Int): Int {
        return grid[(y * WORLD_CELL_WIDTH + x) * MAX_AMOUNT_OF_CELLS + index]
    }

    fun setCell(x: Int, y: Int, index: Int, value: Int) {
        grid[(y * WORLD_CELL_WIDTH + x) * MAX_AMOUNT_OF_CELLS + index] = value
    }

    companion object {
        val WORLD_SIZE_TYPE = WorldSize.XL
        val WORLD_CELL_WIDTH = WORLD_SIZE_TYPE.size
        val WORLD_CELL_HEIGHT = WORLD_SIZE_TYPE.size
        val GRID_SIZE = WORLD_CELL_WIDTH * WORLD_CELL_HEIGHT
        val CELL_SIZE = CELL_RADIUS * 2
        val MAX_AMOUNT_OF_CELLS = 12
        val WORLD_WIDTH = WORLD_CELL_WIDTH * CELL_SIZE
        val WORLD_HEIGHT = WORLD_CELL_HEIGHT * CELL_SIZE
    }
}

enum class WorldSize(val size: Int, val threadCount: Int, val generateWorldSize: Int) {
    XS(12, 1, 18), //Extra Small (очень маленький)

    S(24, 2, 37), //Small (маленький)

    M(48, 4, 75), //Medium (средний)

    L(96, 8, 153), //Large (большой)

    XL(192, 12, 305), //Extra Large (очень большой)

    XXL(384, 13, 611), //Extra Extra Large (2XL, очень-очень большой)

    XXXL(768, 14, 611), //Extra Extra Extra Large (3XL)

    XXXXL(1536, 14, 611), //(4XL) и т. д.
}
