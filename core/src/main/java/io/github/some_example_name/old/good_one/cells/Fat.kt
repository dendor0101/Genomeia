package io.github.some_example_name.old.good_one.cells


import io.github.some_example_name.attempts.game.physics.yellowColors
import io.github.some_example_name.old.good_one.cells.base.Cell

class Fat: Cell() {
    override var colorCore = yellowColors.random()
    override val maxEnergy = 10f
    override val cellStrength = 0.5f
    override val linkStrength = 0.05f
    override val elasticity = 5.5f
    override val isLooseEnergy = false
}
