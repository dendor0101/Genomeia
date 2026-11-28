package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.SpecialModData
import io.github.some_example_name.old.core.utils.blueColors
import io.github.some_example_name.old.systems.genomics.genome.Action
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

class Tail(cellTypeId: Int): Cell(
    key = "tail",
    defaultColor = blueColors.first(),
    cellTypeId = cellTypeId,
    isNeural = true,
    isDirected = true
) {

    /** Своё состояние на клетку: скорость разгоняется к импульсу и меняется каждый тик. */
    override fun createSpecialData(action: Action): SpecialModData = TailState()

    override fun doOnTick(cellIndex: Int, threadId: Int): Unit = with(cellEntity) {
        if (energy[cellIndex] > 0) {
            var impulse = neuronImpulseOutput[cellIndex]
            if (impulse < 0f) impulse = 0f
            if (impulse > 1f) impulse = 1f

            val tail = specialEntity.getSpecialData(cellIndex) as? TailState ?: return
            val speed = run {
                val speed = tail.speed
                if (tail.speed < impulse) {
                    tail.speed = speed + 0.012f
                } else if (tail.speed > impulse) {
                    tail.speed = speed - 0.012f
                }
                if (tail.speed <= 0.013f) return
                tail.speed
            }

            val tailMaxSpeedCoefficient = substrateSettings.data.tailMaxSpeedCoefficient
            val thrust = 0.5f * speed * tailMaxSpeedCoefficient
            // Галочка отладки «без хвостов»: виден собственный дрейф тела.
            if (!simulationData.debugNoTailThrust) addVelocity(cellIndex, -angleCos[cellIndex] * thrust, -angleSin[cellIndex] * thrust)
            energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
        }
    }
}

/**
 * Состояние хвоста: текущая скорость. Изменяемое — поэтому объект свой у каждой клетки
 * (Tail.createSpecialData) и пишет в него только воркер этой клетки.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("tail")
data class TailState(
    @ProtoNumber(1) var speed: Float = 0f
) : SpecialModData
