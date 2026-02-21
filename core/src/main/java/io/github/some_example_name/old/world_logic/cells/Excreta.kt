package io.github.some_example_name.old.world_logic.cells

import io.github.some_example_name.old.substances.SubstanceAdd
import io.github.some_example_name.old.world_logic.cells.base.Cell
import io.github.some_example_name.old.world_logic.cells.base.Directed
import io.github.some_example_name.old.world_logic.CellManager
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class Excreta: Cell(), Directed {

    companion object {
        //TODO гадить под себя // shit under oneself
        fun specificToThisType(cm: CellManager, id: Int, threadId: Int) {
            if(cm.energy[id] < cm.globalSettings.amountOfFoodEnergy) return
            val angleRad = cm.angle[id]
            val cosA = cos(angleRad)
            val sinA = sin(angleRad)

            // Рассчитываем направление движения // We calculate the direction of movement
            val directionX = cosA * 3f
            val directionY = sinA * 3f
            if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать  then remove")


            cm.addSubstances[threadId].add(
                SubstanceAdd(
                    cm.x[id],
                    cm.y[id],
                    directionX * 9 - (Random.nextFloat() - 0.5f) * 3f,
                    directionY * 9 - (Random.nextFloat() - 0.5f) * 3f
                )
            )

            cm.energy[id] -= cm.globalSettings.amountOfFoodEnergy
        }
    }
}
