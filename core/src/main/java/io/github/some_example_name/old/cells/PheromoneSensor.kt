package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.blueColors

class PheromoneSensor(cellTypeId: Int) : Cell(
    defaultColor = blueColors[2],
    cellTypeId = cellTypeId,
    isNeural = true,
    isNeuronTransportable = false
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val gridId = getGridId(cellIndex)

        //TODO PheromoneManager
        //TODO map color
        val intColor = getColor(cellIndex)
        val r = 0
        val g = 0
        val b = 0

        val impulse = 0f
//            r * pheromoneEntity.pheromoneR[gridId] +
//            g * pheromoneEntity.pheromoneG[gridId] +
//            b * pheromoneEntity.pheromoneB[gridId]

        neuronImpulseOutput[cellIndex] = activation(cellIndex, impulse)

        energy[cellIndex] -= 0.0001f
    }

}
