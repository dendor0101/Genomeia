package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.purpleColors

class FoodSensor(cellTypeId: Int): Cell(
    key = "food_sensor",
    defaultColor = purpleColors.first(),
    cellTypeId = cellTypeId,
    isNeuronTransportable = false,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {

        if (simulationData.tickCounter % 4 == cellIndex % 4) {
            var impulse = 0f

            val posX = cellEntity.getX(cellIndex)
            val posY = cellEntity.getY(cellIndex)

            pheromonesManager.findAllPheromonesInPoint(posX, posY, 0) { pheromoneIndex ->
                val dx = posX - pheromoneEntity.x[pheromoneIndex]
                val dy = posY - pheromoneEntity.y[pheromoneIndex]
                val distSq = dx * dx + dy * dy

                val a = pheromoneEntity.time[pheromoneIndex]
                val radiusSquared = pheromoneEntity.radiusSquared[pheromoneIndex]

                if (distSq <= radiusSquared) {
                    val result = pheromonesManager.f(distSq, a)
                    impulse += result
                }

                if (impulse >= 1f) {
                    //Отсекаем все что больше 1.0 это позволит не считать кучу источников феромонов
                    impulse = 1f
                    neuronImpulseOutput[cellIndex] = impulse + neuronImpulseInput[cellIndex]
                    return@with
                }
            }

            neuronImpulseOutput[cellIndex] = impulse + neuronImpulseInput[cellIndex]
        }
    }

}
