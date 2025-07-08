package io.github.some_example_name.old.good_one.cells

import io.github.some_example_name.attempts.game.physics.redColors
import io.github.some_example_name.old.good_one.Link
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Neural
import io.github.some_example_name.old.logic.CellManager
import io.github.some_example_name.old.logic.CellManager.Companion.MAX_LINK_AMOUNT

class Muscle : Cell(), Neural {
    override var colorCore = redColors.random()
    override var activationFuncType = 0
    override var a = 0f
    override var b = 0f
    override var c = 0f
    override var dTime: Float = -1f
    private var muscleContractionStep = 1f

    override fun specificToThisType() {
        if (energy > 0) {
            physicalLinks.forEach {
                it.degreeOfShortening = 1f
            }

            if (neuronImpulseImport >= 1f) {
                physicalLinks.forEach {
                    it.degreeOfShortening = muscleContractionStep
                    if (muscleContractionStep > 0.5f) {
                        muscleContractionStep -= 0.03f
                    }
                }

                energy -= 0.005f
            } else {
                physicalLinks.forEach {
                    it.degreeOfShortening = muscleContractionStep
                    if (muscleContractionStep < 1f) {
                        muscleContractionStep += 0.03f
                        energy -= 0.005f
                    }
                }
            }
        }
    }

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.energy[id] > 0) {

                if (cm.neuronImpulseImport[id] == 0f) return

                //TODO нужно сделать более адекватную систему, чтобы можно было контролировать степень сжатия
                if (cm.neuronImpulseImport[id] > 0f) {
                    for (i in id * MAX_LINK_AMOUNT..<id * MAX_LINK_AMOUNT + cm.linksAmount[id]) {
                        val linkId = cm.links[i]
                        cm.degreeOfShortening[linkId] = cm.muscleContractionStep[id]
                        if (cm.muscleContractionStep[id] > 0.5f) {
                            cm.muscleContractionStep[id] -= 0.03f
                            cm.energy[id] -= 0.005f
                        }
                    }
                } else {
                    for (i in id * MAX_LINK_AMOUNT..<id * MAX_LINK_AMOUNT + cm.linksAmount[id]) {
                        val linkId = cm.links[i]
                        cm.degreeOfShortening[linkId] = cm.muscleContractionStep[id]
                        if (cm.muscleContractionStep[id] < 1f) {
                            cm.muscleContractionStep[id] += 0.03f
                            cm.energy[id] -= 0.005f
                        }
                    }
                }
            }
        }
    }
}
