package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.brownColors
import kotlin.math.cos
import kotlin.math.sin

class Excreta: Cell(
    defaultColor = brownColors.first(),
    cellTypeId = 8,
    isDirected = true,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) = with(cellEntity) {
        if(energy[index] < substrateSettings.data.amountOfFoodEnergy) return
        val angleRad = angle[index]
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)

        // Рассчитываем направление движения // We calculate the direction of movement
        val directionX = cosA * 3f
        val directionY = sinA * 3f
        if (directionX.isNaN() || directionY.isNaN()) throw Exception("TODO потом убрать  then remove")

//          TODO сделать добавление sub particle
//        SubstanceAdd(
//            cm.x[index],
//            cm.y[index],
//            directionX * 9 - (Random.nextFloat() - 0.5f) * 3f,
//            directionY * 9 - (Random.nextFloat() - 0.5f) * 3f
//        )

        energy[index] -= substrateSettings.data.amountOfFoodEnergy
    }

}
