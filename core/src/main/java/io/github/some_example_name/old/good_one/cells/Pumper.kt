package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.redColors
import io.github.some_example_name.old.good_one.Link
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Neural

class Pumper: Cell(), Neural {
    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f

    override var colorCore = redColors[2]

}
