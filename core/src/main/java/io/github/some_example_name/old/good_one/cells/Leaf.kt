package io.github.some_example_name.old.good_one.cells


import io.github.some_example_name.attempts.game.physics.genomeEditorColor
import io.github.some_example_name.attempts.game.physics.leafColors
import io.github.some_example_name.attempts.game.physics.pinkColors
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.logic.CellManager

class Leaf: Cell() {
    override var colorCore = leafColors.random()
    override val maxEnergy = 5f

    override fun specificToThisType() {

        if (energy < maxEnergy) {
            energy += 0.01f
        }
    }

    companion object {
        const val maxEnergy = 5f
        fun getColor() = leafColors.random()
        fun specificToThisType(cellManager: CellManager, id: Int) {
            if (cellManager.energy[id] < cellManager.maxEnergy[id]) {
                cellManager.energy[id] += 0.01f
            }
        }
    }
}
