package io.github.some_example_name.old.good_one.substances

import io.github.some_example_name.attempts.game.physics.genomeEditorColor

open class Substance {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var radius = 5f
    open var color = genomeEditorColor.random()

    fun move() {
        x += vx
        y += vy
        vx *= 0.93f
        vy *= 0.93f
    }
}
