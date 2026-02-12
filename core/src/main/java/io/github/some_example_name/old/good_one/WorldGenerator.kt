package io.github.some_example_name.old.good_one


import io.github.some_example_name.old.world_logic.GridManager.Companion.WORLD_SIZE_TYPE
import kotlin.random.Random

var randomWallSeed = Random(12)

class WorldGenerator {
    companion object {
        var GENERATOR_WORLD_SIZE = WORLD_SIZE_TYPE.generateWorldSize
        var GENERATOR_DAY_NIGHT = 15
        var GENERATOR_INTERPOLATE = 12
    }


    private var map: Array<BooleanArray>? = null // Храним карту

    fun generateWorld(seed: Long = 12): Array<BooleanArray> {
        map = Array(GENERATOR_WORLD_SIZE) { BooleanArray(GENERATOR_WORLD_SIZE) }
        val random = Random(seed)
        generateMap(map!!, random)
        return map!!
    }

    private fun generateMap(map: Array<BooleanArray>, random: Random) {
        randomFillMap(map, random)
        repeat(GENERATOR_DAY_NIGHT) { simulateDayNightCycle(map) }
        repeat(GENERATOR_INTERPOLATE) { interpolateMap(map) }
    }

    private fun randomFillMap(map: Array<BooleanArray>, random: Random) {
        for (y in 0 until GENERATOR_WORLD_SIZE) {
            for (x in 0 until GENERATOR_WORLD_SIZE) {
                map[y][x] = random.nextBoolean()
            }
        }
    }

    private fun simulateDayNightCycle(map: Array<BooleanArray>) {
        val tempMap = Array(GENERATOR_WORLD_SIZE) { BooleanArray(GENERATOR_WORLD_SIZE) }
        for (y in 1 until GENERATOR_WORLD_SIZE - 1) {
            for (x in 1 until GENERATOR_WORLD_SIZE - 1) {
                val aliveNeighbors = countAliveNeighbors(x, y, map)
                tempMap[y][x] = if (map[y][x]) aliveNeighbors >= 4 else aliveNeighbors >= 5
            }
        }
        for (y in 1 until GENERATOR_WORLD_SIZE - 1) {
            for (x in 1 until GENERATOR_WORLD_SIZE - 1) {
                map[y][x] = tempMap[y][x]
            }
        }
    }

    private fun interpolateMap(map: Array<BooleanArray>) {
        val tempMap = Array(GENERATOR_WORLD_SIZE) { BooleanArray(GENERATOR_WORLD_SIZE) }
        for (y in 1 until GENERATOR_WORLD_SIZE - 1) {
            for (x in 1 until GENERATOR_WORLD_SIZE - 1) {
                val aliveNeighbors = countAliveNeighbors(x, y, map)
                tempMap[y][x] = aliveNeighbors > 4
            }
        }
        for (y in 1 until GENERATOR_WORLD_SIZE - 1) {
            for (x in 1 until GENERATOR_WORLD_SIZE - 1) {
                map[y][x] = tempMap[y][x]
            }
        }
    }

    private fun countAliveNeighbors(x: Int, y: Int, map: Array<BooleanArray>): Int {
        var count = 0
        for (j in -1..1) {
            for (i in -1..1) {
                if (i == 0 && j == 0) continue
                if (map[y + j][x + i]) count++
            }
        }
        return count
    }
}


