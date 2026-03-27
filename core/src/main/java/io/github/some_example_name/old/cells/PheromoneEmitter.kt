package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.blueColors

class PheromoneEmitter : Cell(
    defaultColor = blueColors[3],
    cellTypeId = 21,
    isNeural = true
) {

    override fun doOnTick(index: Int, threadId: Int) = with(cellEntity) {
        val impulse = neuronImpulseOutput[index]
        val gridId = getGridId(index)

        //TODO do checks
//        if (gridId < 0 || gridId >= cm.gridManager.GRID_SIZE) return
        //TODO PheromoneManager
        //TODO map color
        val intColor = getColor(index)
        val r = 0
        val g = 0
        val b = 0

//        pheromoneEntity.pheromoneR[gridId] += r * impulse
//        pheromoneEntity.pheromoneG[gridId] += g * impulse
//        pheromoneEntity.pheromoneB[gridId] += b * impulse

        energy[index] -= impulse * 0.01f
    }
}
