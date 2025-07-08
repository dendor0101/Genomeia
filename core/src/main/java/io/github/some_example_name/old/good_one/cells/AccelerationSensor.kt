package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.skyBlueColors
import io.github.some_example_name.old.good_one.Link
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.good_one.cells.base.activation
import io.github.some_example_name.old.logic.CellManager
import kotlin.math.sqrt

class AccelerationSensor: Cell(), Neural {
    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f

    override var colorCore = skyBlueColors.random()

    override fun specificToThisType() {
        val acceleration = sqrt(ax * ax + ay * ay)
        neuronImpulseImport = activation(acceleration)
        energy -= 0.001f
    }

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            val ax = cm.ax[id]
            val ay = cm.ay[id]
            val acceleration = sqrt(ax * ax + ay * ay)
            cm.neuronImpulseImport[id] = activation(cm, id, acceleration)
            cm.energy[id] -= 0.001f
        }
    }
}
