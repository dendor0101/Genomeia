package io.github.some_example_name.attempts.game.main

import io.github.some_example_name.attempts.game.main.Genomeia.Companion.CELL_SIZE
import io.github.some_example_name.attempts.game.main.Genomeia.Companion.GRID_SIZE
import io.github.some_example_name.attempts.game.physics.Particle



class WorldGridManager {

    var grid: Array<Array<MutableList<Int>>> =
        Array(GRID_SIZE) { Array(GRID_SIZE) { mutableListOf() } } //TODO UintArray возможно улучшит

    fun initGrid(particles: MutableList<Particle>) {
        particles.forEachIndexed { index, particle ->
            val cellPositions = grid[(particle.x / CELL_SIZE).toInt()][(particle.y / CELL_SIZE).toInt()]
            cellPositions.add(index)
        }
    }
}
