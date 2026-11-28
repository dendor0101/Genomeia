package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.blueColors

class Compass(cellTypeId: Int) : Cell(
    key = "compass",
    defaultColor = blueColors[6],
    cellTypeId = cellTypeId,
    isDirected = true,
    isNeural = true,
    isNeuronTransportable = false
) {

    /**
     * Сырой сигнал — синус угла стрелки в мире: 1 — стрелка смотрит вверх, −1 — вниз,
     * 0 — вбок; дальше он идёт через формулу клетки.
     *
     * До 27.11 цена действия списывалась здесь дважды (две одинаковые строки, `energy` и
     * `cellEntity.energy` внутри `with(cellEntity)` — один и тот же массив): компас стоил вдвое
     * больше, чем указано в настройках клеток. Вернули его игрокам — оставили одно списание.
     */
    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        neuronImpulseOutput[cellIndex] = activation(cellIndex, angleSin[cellIndex])
        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }

}
