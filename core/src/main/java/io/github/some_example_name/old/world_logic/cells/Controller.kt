package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.activation
import io.github.some_example_name.old.world_logic.CellManager
import java.util.TreeMap

// Глобальный массив из 9 элементов // Global array of 9 elements
val keyStates = BooleanArray(9)
// Массив для хранения предыдущего состояния клавиш // Array for storing the previous state of the keys
val previousKeyStates = BooleanArray(9)

val controllerIndexesLol = TreeMap<Int, Boolean>()

class Controller: Cell() {

    companion object {
        fun specificToThisType(cm: CellManager, id: Int) {
            if (cm.tickRestriction[id] == 2) {
                cm.tickRestriction[id] = 0

                val keyIndex = controllerIndexesLol[cm.id[id]] ?: return
                if (keyIndex) {
                    cm.neuronImpulseOutput[id] = activation(cm, id, 1f)
                } else {
                    cm.neuronImpulseOutput[id] = 0f
                }

            } else {
                cm.tickRestriction[id] += 1
            }
            cm.energy[id] -= cm.cellsSettings[cm.cellType[id] + 1].energyActionCost
        }
    }
}
