package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.whiteColors
import io.github.some_example_name.old.good_one.cells.base.Cell

class Bone: Cell() {
    override val maxEnergy = 2f
    override var colorCore = whiteColors.random()
    override val linkStrength = 0.4f
    override val cellStrength = 4f
}
