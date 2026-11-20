package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.blueColors

class SpeedSensor(cellTypeId: Int) : Cell(
    key = "speed_sensor",
    defaultColor = blueColors[6],
    cellTypeId = cellTypeId,
    isDirected = true,
    isNeural = true,
    isNeuronTransportable = false
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        val vx = getVx(cellIndex)
        val vy = getVy(cellIndex)
        val cos = angleCos[cellIndex]
        val sin = angleSin[cellIndex]

        // Проекция скорости на направление клетки (скалярное произведение)
        // > 0 — движется по направлению вектора
        // < 0 — движется против направления вектора
        //
        // До 20.11 здесь стояло vx * cos + vy * sin * 100: на 100 умножалась только
        // вертикальная часть, и показания зависели от того, куда смотрит клетка. Множитель
        // 100 к тому же насыщал датчик уже на ~4.5 клетки в секунду (скорость игры —
        // смещение за тик / 7.5, PhysicsUnits): рыба и медуза одинаково давали 1.
        // SCALE подобран так, чтобы быстрые пловцы (Fish Snake, ~25 клеток/с) давали около 1,
        // медуза ~0.3, шагающий паук ~0.4 — показания различают скорость, а не только знак.
        var projectedSpeed = ((vx * cos + vy * sin) * SCALE).coerceIn(-1f, 1f)

        if (projectedSpeed < 0.0001f && projectedSpeed > -0.0001f) projectedSpeed = 0f

        neuronImpulseOutput[cellIndex] = activation(cellIndex, projectedSpeed)

        energy[cellIndex] -= substrateSettings.cellsSettings[cellType[cellIndex].toInt()].energyActionCost
    }

    private companion object {
        const val SCALE = 15f
    }
}
