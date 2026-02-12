package io.github.some_example_name.old.good_one.shader

import io.github.some_example_name.old.world_logic.GridManager.Companion.WORLD_SIZE_TYPE

import java.util.concurrent.atomic.AtomicIntegerArray

class LinksDrawGridStructure {

    val grid = IntArray(SCREEN_CELL_WIDTH_LINK * SCREEN_CELL_HEIGHT_LINK * MAX_AMOUNT_OF_LINKS) { -1 }
    val linkCounts = AtomicIntegerArray(SCREEN_CELL_WIDTH_LINK * SCREEN_CELL_HEIGHT_LINK)

    fun clear() {
        grid.fill(-1)
        for (i in 0 until linkCounts.length()) {
            linkCounts.set(i, 0)
        }
    }

    // Добавить элемент в ячейку (x, y)
    fun addLink(x: Int, y: Int, value: Int): Int {
        if (x < 0 || x >= SCREEN_CELL_WIDTH_LINK || y < 0 || y >= SCREEN_CELL_HEIGHT_LINK) return -1
        val cellIndex = y * SCREEN_CELL_WIDTH_LINK + x

        val currentCount = linkCounts.getAndIncrement(cellIndex)
        if (currentCount >= MAX_AMOUNT_OF_LINKS) {
            // Чтобы не портить сетку, откатить счётчик назад
            linkCounts.decrementAndGet(cellIndex)
//            println("MAX_AMOUNT_OF_LINKS_IN_DRAW")
            return -1
        }

        val gridIndex = cellIndex * MAX_AMOUNT_OF_LINKS + currentCount
        grid[gridIndex] = value
        return cellIndex
    }

    fun getLinksCount(x: Int, y: Int): Int {
        if (x !in 0 until SCREEN_CELL_WIDTH_LINK || y !in 0 until SCREEN_CELL_HEIGHT_LINK) {
            return 0
        }
        val cellIndex = y * SCREEN_CELL_WIDTH_LINK + x
        return linkCounts.get(cellIndex)
    }

    fun getLinks(x: Int, y: Int): IntArray {
        if (x !in 0 until SCREEN_CELL_WIDTH_LINK || y !in 0 until SCREEN_CELL_HEIGHT_LINK) {
            return IntArray(0)
        }
        val cellIndex = y * SCREEN_CELL_WIDTH_LINK + x
        val start = cellIndex * MAX_AMOUNT_OF_LINKS
        val count = linkCounts.get(cellIndex)
        return grid.copyOfRange(start, start + count)
    }

    companion object {
        val SCREEN_CELL_WIDTH_LINK = WORLD_SIZE_TYPE.size.coerceIn(24, 192)
        val SCREEN_CELL_HEIGHT_LINK = WORLD_SIZE_TYPE.size.coerceIn(24, 192)
        val GRID_SIZE = SCREEN_CELL_WIDTH_LINK * SCREEN_CELL_HEIGHT_LINK
        const val MAX_AMOUNT_OF_LINKS = 20
    }
}
