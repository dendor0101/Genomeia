package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.redColors
import kotlin.math.min

class Pumper(cellTypeId: Int): Cell(
    key = "pumper",
    defaultColor = redColors[2],
    cellTypeId = cellTypeId,
    effectOnContact = true
) {

    override fun onContact(cellIndex: Int, particleIndexCollided: Int, distance: Float, threadId: Int) = with(cellEntity) {
        if (particleEntity.isCell[particleIndexCollided]) {
            val collidedCellIndex = particleEntity.holderEntityIndex[particleIndexCollided]
            // Отрицательный остаток уже ждёт обычной проверки смерти в CellSystem.
            // Повторные контакты не должны продолжать брать энергию из этого долга.
            if (energy[collidedCellIndex] < 0f) return@with
            val rateOfEnergy = substrateSettings.data.rateOfEnergyTransferForPumper
            val capacity = substrateSettings.cellsSettings[cellType[cellIndex].toInt()].maxEnergy - energy[cellIndex]
            val amount = min(rateOfEnergy, capacity)
            if (amount > 0f) {
                // Последняя порция может вывести донора в минус; обеим клеткам
                // меняем запас на одну величину, не переполняя получателя.
                energy[cellIndex] += amount
                energy[collidedCellIndex] -= amount
            }
        }
    }
}
