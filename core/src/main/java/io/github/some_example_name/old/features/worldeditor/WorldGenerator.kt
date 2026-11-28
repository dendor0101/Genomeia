package io.github.some_example_name.old.features.worldeditor

import kotlin.random.Random

var randomWallSeed = Random(12)

class WorldGenerator {
    companion object {
        /** Параметры по умолчанию: ими начинается редактор мира и карта нового уровня. */
        const val DEFAULT_DAY_NIGHT = 15
        const val DEFAULT_INTERPOLATE = 12

        var GENERATOR_DAY_NIGHT = DEFAULT_DAY_NIGHT
        var GENERATOR_INTERPOLATE = DEFAULT_INTERPOLATE
    }

    fun generateWorld(
        width: Int,
        height: Int,
        seed: Long = 12L
    ): Array<BooleanArray> {
        val map = Array(height) { BooleanArray(width) }
        val random = Random(seed)
        generateMap(map, random, width, height)
        return map
    }

    private fun generateMap(
        map: Array<BooleanArray>,
        random: Random,
        width: Int,
        height: Int
    ) {
        randomFillMap(map, random, width, height)
        repeat(GENERATOR_DAY_NIGHT) { simulateDayNightCycle(map, width, height) }
        repeat(GENERATOR_INTERPOLATE) { interpolateMap(map, width, height) }
    }

    private fun randomFillMap(
        map: Array<BooleanArray>,
        random: Random,
        width: Int,
        height: Int
    ) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                map[y][x] = random.nextBoolean()
            }
        }
    }

    private fun simulateDayNightCycle(
        map: Array<BooleanArray>,
        width: Int,
        height: Int
    ) {
        val tempMap = Array(height) { BooleanArray(width) }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val aliveNeighbors = countAliveNeighbors(x, y, map, width, height)
                tempMap[y][x] = if (map[y][x]) aliveNeighbors >= 4 else aliveNeighbors >= 5
            }
        }
        copyTempToMap(tempMap, map, width, height)
    }

    private fun interpolateMap(
        map: Array<BooleanArray>,
        width: Int,
        height: Int
    ) {
        val tempMap = Array(height) { BooleanArray(width) }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val aliveNeighbors = countAliveNeighbors(x, y, map, width, height)
                tempMap[y][x] = aliveNeighbors > 4
            }
        }
        copyTempToMap(tempMap, map, width, height)
    }

    private fun copyTempToMap(
        tempMap: Array<BooleanArray>,
        map: Array<BooleanArray>,
        width: Int,
        height: Int
    ) {
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                map[y][x] = tempMap[y][x]
            }
        }
    }

    private fun countAliveNeighbors(
        x: Int,
        y: Int,
        map: Array<BooleanArray>,
        width: Int,
        height: Int
    ): Int {
        var count = 0
        for (j in -1..1) {
            for (i in -1..1) {
                if (i == 0 && j == 0) continue
                val ny = y + j
                val nx = x + i
                if (ny in 0 until height && nx in 0 until width && map[ny][nx]) {
                    count++
                }
            }
        }
        return count
    }
}
