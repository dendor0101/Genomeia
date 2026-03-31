package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.blueColors

class PheromoneEmitter(cellTypeId: Int) : Cell(
    defaultColor = blueColors[3],
    cellTypeId = cellTypeId,
    isNeural = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val impulse = neuronImpulseOutput[cellIndex]
        val gridId = getGridId(cellIndex)

        //TODO do checks
//        if (gridId < 0 || gridId >= cm.gridManager.GRID_SIZE) return
        //TODO PheromoneManager
        //TODO map color
        val intColor = getColor(cellIndex)
        val r = 0
        val g = 0
        val b = 0

//        pheromoneEntity.pheromoneR[gridId] += r * impulse
//        pheromoneEntity.pheromoneG[gridId] += g * impulse
//        pheromoneEntity.pheromoneB[gridId] += b * impulse

        energy[cellIndex] -= impulse * 0.01f
    }
}
