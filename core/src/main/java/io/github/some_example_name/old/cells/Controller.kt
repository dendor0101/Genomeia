package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.skyBlueColors

class Controller(cellTypeId: Int): Cell(
    defaultColor = skyBlueColors.last(),
    cellTypeId = cellTypeId,
    isNeural = true,
    isNeuronTransportable = false,
    specialData = ControllerData::class
) {
    //TODO PC: WASD ↑←↓→ 1234567890 (SPACE)
    //TODO smartphone: ↑←↓→ XYAB

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        //TODO сделать назначение клавиш на пк и добавление кнопок на телефонах
        val keyIndex = simulationData.controllerIndexesLol[cellGenomeId[cellIndex]] ?: return
        if (keyIndex) {
            neuronImpulseOutput[cellIndex] = activation(cellIndex, 1f)
        } else {
            neuronImpulseOutput[cellIndex] = 0f
        }
        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }
}

@JvmInline
value class ControllerData(
    val attachedKey: Char
): SpecialModData
