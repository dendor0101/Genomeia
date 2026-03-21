package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.blueColors

class PheromoneSensor : Cell(
    defaultColor = blueColors[2],
    cellTypeId = 22,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) = with(cellEntity) {
        val gridId = getGridId(index)

        //TODO PheromoneManager
        //TODO map color
        val intColor = getColor(index)
        val r = 0
        val g = 0
        val b = 0

        val impulse = 0f
//            r * pheromoneEntity.pheromoneR[gridId] +
//            g * pheromoneEntity.pheromoneG[gridId] +
//            b * pheromoneEntity.pheromoneB[gridId]

        neuronImpulseOutput[index] = activation(index, impulse)

        energy[index] -= 0.0001f
    }

}
