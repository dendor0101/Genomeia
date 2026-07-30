package io.github.some_example_name.old.systems.genomics

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity

class NeuralLinkManager(
    val cellEntity: CellEntity,
    val worldCommandsManager: WorldCommandsManager,
    val neuralLinkEntity: NeuralLinkEntity
) {

    fun iterate() {
        with(neuralLinkEntity) {
            aliveList.forEach { linkIndex ->
                transportNeuralSignal(
                    linkIndex
                )
            }
        }
    }

    fun transportNeuralSignal(
        linkIndex: Int,
        threadId: Int = 0
    ) = with(cellEntity) {
        val linkCellA = neuralLinkEntity.links1[linkIndex]
        val linkCellB = neuralLinkEntity.links2[linkIndex]
        val isLink1NeuralDirected = neuralLinkEntity.isLink1NeuralDirected[linkIndex]
        val signalToCellIndex = if (isLink1NeuralDirected) linkCellA else linkCellB
        val signalFromCellIndex = if (isLink1NeuralDirected) linkCellB else linkCellA

        val neuronImpulseOutput = neuronImpulseOutput[signalFromCellIndex]

        if (isNeural[signalToCellIndex]) {
            if (getIsSum(signalToCellIndex)) {
                neuronImpulseInput[signalToCellIndex] += neuronImpulseOutput
            } else {
                neuronImpulseInput[signalToCellIndex] *= neuronImpulseOutput
            }
        } else {
            neuronImpulseInput[signalToCellIndex] += neuronImpulseOutput
        }

        //Добавление нейро-линков для мутировших в клетку нуждающующихся в нейролинках
        if (command[linkCellA] == 0.toByte()) {
            worldCommandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.ADD_NEURAL_CONNECTION,
                ints = intArrayOf(linkCellA, linkIndex)
            )
        }
        if (command[linkCellB] == 0.toByte()) {
            worldCommandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.ADD_NEURAL_CONNECTION,
                ints = intArrayOf(linkCellB, linkIndex)
            )
        }
    }
}
