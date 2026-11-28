package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.CellSettings
import io.github.some_example_name.old.core.utils.pinkColors

class Neuron(cellTypeId: Int) : Cell(
    key = "neuron",
    defaultColor = pinkColors.first(),
    cellTypeId = cellTypeId,
    isNeural = true
) {

    override fun onStart(cellIndex: Int, threadId: Int, genomeIndex: Int) {
        //TODO понять до конца, почему neuronImpulseOutput может оказаться Nan при мутации из обычной Leaf в нейрон
        cellEntity.neuronImpulseOutput[cellIndex] = 0f
    }

    override fun doOnTick(cellIndex: Int, threadId: Int) {
        cellEntity.energy[cellIndex] -= cellEntity.substrateSettings
            .cellsSettings[cellEntity.cellType[cellIndex].toInt()]
            .energyActionCost
    }

}

