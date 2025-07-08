package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.purpleColors
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.good_one.substances
import io.github.some_example_name.old.logic.CellManager

class Sensor: Cell(), Neural {
    override var colorCore = purpleColors.random()
    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f

//    var neuronImpulseExport = 0f

    override fun specificToThisType() {
        var senseValue = 0f
        substances.forEach {
            val dx = it.x - x
            val dy = it.y - y
            senseValue += 625 / (dx * dx + dy * dy)
        }

        if (senseValue.isNaN()) throw Exception("TODO потом убрать")
        if (senseValue > 1f) senseValue = 1f

        //func activation

//        senseValue = 3f / sqrt(senseValue)

        energy -= 0.001f
        neuronImpulseImport = senseValue
//        println(senseValue)

//        println(neuronImpulseImport)
    }

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            cm.energy[id] -= 0.001f
            if (cm.tickRestriction[id] == 48) {
                cm.tickRestriction[id] = 0
                var senseValue = cm.subManager.sensor(cm.x[id], cm.y[id])
                if (senseValue.isNaN()) throw Exception("TODO потом убрать")
                if (senseValue > 1f) senseValue = 1f
                cm.neuronImpulseImport[id] = senseValue
            } else {
                cm.tickRestriction[id] += 1
            }
        }
    }
}
