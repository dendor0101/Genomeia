package io.github.some_example_name.attempts.game.physics


import io.github.some_example_name.attempts.game.gameabstraction.entity.ParticleType
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_SIZE_TYPE
import kotlin.random.Random

class WorldGenerator {

    companion object {
        val GENERATOR_WORLD_SIZE = WORLD_SIZE_TYPE.generateWorldSize
    }

    fun generateWorld(seed: Long = 7): MutableList<Particle> {
        val particles = mutableListOf<Particle>()
        val map = Array(GENERATOR_WORLD_SIZE) { BooleanArray(GENERATOR_WORLD_SIZE) }
        val random = Random(seed)
        generateMap(map, random)
        map.forEachIndexed { indexX, booleans ->
            if (indexX != 0 && indexX != map.size - 1)
                booleans.forEachIndexed { indexY, b ->
                    if (indexY != 0 && indexY != booleans.size - 1)
                        if (b) {
                            particles.add(
                                Particle(
                                    indexX.toFloat() * 6.3f + 250,
                                    indexY.toFloat() * 6.3f + 250,
                                    type = ParticleType.WALL
                                )
                            )
                        } else {
                            if (Random.nextInt(60) == 1)
                                particles.add(
                                    Particle(
                                        indexX.toFloat() * 6.3f + 250 + Random.nextDouble(0.0, 5.0).toFloat(),
                                        indexY.toFloat() * 6.3f + 250 + Random.nextDouble(0.0, 5.0).toFloat(),
                                        type = ParticleType.LEAF
                                    )
                                )
                        }
                }
        }

        return particles
    }

    private fun generateMap(map: Array<BooleanArray>, random: Random) {
        randomFillMap(map, random)
        repeat(15) { simulateDayNightCycle(map) }
        repeat(8) { interpolateMap(map) }
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
                tempMap[y][x] = if (map[y][x]) {
                    aliveNeighbors >= 4 // Выживание клетки
                } else {
                    aliveNeighbors >= 5 // Рождение клетки
                }
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


