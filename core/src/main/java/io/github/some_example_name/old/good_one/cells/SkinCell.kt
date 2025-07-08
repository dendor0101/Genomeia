package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.leafColors
import io.github.some_example_name.old.good_one.Link
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.logic.CellManager

class SkinCell: Cell(), Neural {
    override var colorCore = leafColors[3]
    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f

    override fun specificToThisType() {

        val friction = if (neuronImpulseImport > 1) 1f else  if (neuronImpulseImport < 0) 0f else neuronImpulseImport

        frictionLevel = friction * 0.93f
    }

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            val friction = if (cm.neuronImpulseImport[id] > 1) 1f else  if (cm.neuronImpulseImport[id] < 0) 0f else cm.neuronImpulseImport[id]

            cm.frictionLevel[id] = friction * 0.93f
        }
    }
}
