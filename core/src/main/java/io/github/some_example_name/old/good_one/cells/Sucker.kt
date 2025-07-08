package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.orangeColors
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.substances.Substance
import io.github.some_example_name.old.good_one.substances
import io.github.some_example_name.old.logic.CellManager

class Sucker : Cell() {
    override var colorCore = orangeColors.random()
    override val maxEnergy = 8f

    override fun specificToThisType() {
        var removeSubstance: Substance? = null
        for (substance in substances) {
            if (distanceTo(substance.x, substance.y) < 20f) {
                removeSubstance = substance
                energy += 4f
                if (maxEnergy < energy) {
                    energy = maxEnergy
                }
                break
            }
        }

        removeSubstance?.let { substances.remove(it) }
    }

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.tickRestriction[id] == 14) {
                cm.tickRestriction[id] = 0

//                val startTime = System.nanoTime()
                val isEaten = cm.subManager.suckIt(cm.x[id], cm.y[id])

//                val elapsedTime = (System.nanoTime() - startTime) / 1_000_000.0 // в миллисекундах
//                println("suckIt: $elapsedTime")
                if (isEaten)
                    cm.energy[id] += 4f
                if (cm.energy[id] > cm.maxEnergy[id]) cm.energy[id] = cm.maxEnergy[id]
            } else {
                cm.tickRestriction[id] += 1
            }
        }
    }
}
