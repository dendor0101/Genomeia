package io.github.some_example_name.old.cells

import io.github.some_example_name.old.cells.base.activation
import io.github.some_example_name.old.core.utils.pinkColors
import kotlin.math.sqrt

class Magnet(cellTypeId: Int) : Cell(
    defaultColor = pinkColors[4],
    cellTypeId = cellTypeId,
    isNeural = true,
    isCollidable = true
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        // Проверяем активацию нейросигналом
        if (activation(cellIndex, neuronImpulseInput[cellIndex]) < 0.5f) return@with

        val myX = particleEntity.x[cellIndex]
        val myY = particleEntity.y[cellIndex]
        
        // Ищем другие клетки Magnet в пределах радиуса действия
        val searchRadius = 50f
        val searchRadius2 = searchRadius * searchRadius
        
        var totalForceX = 0f
        var totalForceY = 0f
        var magnetCount = 0

        // Проходим по всем частицам и ищем другие магниты
        for (otherIdx in 0 until particleEntity.amount) {
            if (!particleEntity.isCell[otherIdx]) continue
            
            val otherCellIndex = particleEntity.holderEntityIndex[otherIdx]
            if (otherCellIndex == cellIndex) continue
            
            // Проверяем, является ли другая клетка магнитом
            if (cellEntity.cellType[otherCellIndex].toInt() != cellTypeId) continue
            
            val otherX = particleEntity.x[otherIdx]
            val otherY = particleEntity.y[otherIdx]
            
            val dx = otherX - myX
            val dy = otherY - myY
            val dist2 = dx * dx + dy * dy
            
            if (dist2 > searchRadius2 || dist2 < 1f) continue
            
            val dist = sqrt(dist2)
            
            // Сила притяжения увеличивается с расстоянием (как настоящий магнит)
            // Но ограничиваем максимальную силу
            val force = (dist / searchRadius) * 0.5f
            
            // Направляем силу к другому магниту
            totalForceX += (dx / dist) * force
            totalForceY += (dy / dist) * force
            magnetCount++
        }

        // Применяем суммарную силу к частице
        if (magnetCount > 0) {
            val particleIdx = cellEntity.particleIdx[cellIndex]
            particleEntity.vx[particleIdx] += totalForceX
            particleEntity.vy[particleIdx] += totalForceY
        }
    }
}
