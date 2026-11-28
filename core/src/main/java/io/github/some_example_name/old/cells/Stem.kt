package io.github.some_example_name.old.cells

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DIGameGlobalContainer.morphogenesis
import io.github.some_example_name.old.core.utils.collectParticles
import io.github.some_example_name.old.core.utils.pinkColors
import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS

class Stem(cellTypeId: Int): Cell(
    key = "stem",
    defaultColor = pinkColors[0],
    cellTypeId = cellTypeId,
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
//        if (energy[cellIndex] < substrateSettings.cellsSettings[cellType[cellIndex].toInt()].maxEnergy) {
//            energy[cellIndex] += substrateSettings.data.amountOfSolarEnergy
//        } else if (simulationData.tickCounter % 8 == cellIndex % 8) {
//
//            var impulse = 0f
//            val posX = cellEntity.getX(cellIndex)
//            val posY = cellEntity.getY(cellIndex)
//            var xPheromone = -1f
//            var yPheromone = -1f
//            var isPain = false
//
//            pheromonesManager.findAllPheromonesInPoint(posX, posY, 18) { pheromoneIndex ->
//                val dx = posX - pheromoneEntity.x[pheromoneIndex]
//                val dy = posY - pheromoneEntity.y[pheromoneIndex]
//                val distSq = dx * dx + dy * dy
//
//                val radiusSquared = pheromoneEntity.radiusSquared[pheromoneIndex]
//
//                if (distSq <= radiusSquared) {
////                    setColor(cellIndex, Color.WHITE.toIntBits())
////                    cellType[cellIndex] = 2
////                    cellEntity.setCellStiffness(cellIndex, cellsSettings[2].cellStiffness)
//                    degreeOfShortening[cellIndex] -= 0.01f
//                    degreeOfShortening[cellIndex] = degreeOfShortening[cellIndex].coerceIn(0.7f, 1.0f)
////                    return@with
//                    isPain = true
//                }
//            }
//
//            if (!isPain) {
//                pheromonesManager.findAllPheromonesInPoint(posX, posY, 0) { pheromoneIndex ->
//                    val dx = posX - pheromoneEntity.x[pheromoneIndex]
//                    val dy = posY - pheromoneEntity.y[pheromoneIndex]
//                    val distSq = dx * dx + dy * dy
//
//                    val radiusSquared = pheromoneEntity.radiusSquared[pheromoneIndex]
//
//                    if (distSq <= radiusSquared) {
//                        degreeOfShortening[cellIndex] += 0.01f
//                        degreeOfShortening[cellIndex] =
//                            degreeOfShortening[cellIndex].coerceIn(0.6f, 1.5f)
//                    }
//                }
//            }
//
//            if (degreeOfShortening[cellIndex] < 1.0f) {
//                degreeOfShortening[cellIndex] += 0.005f
//            } else {
//                degreeOfShortening[cellIndex] -= 0.005f
//            }
//
//
//            degreeOfShortening[cellIndex] = degreeOfShortening[cellIndex].coerceIn(0.6f, 1.5f)
//
//            if (!isOnEdge[cellIndex]) return@with
//
////            pheromonesManager.findAllPheromonesInPoint(posX, posY, 18) { pheromoneIndex ->
////                val dx = posX - pheromoneEntity.x[pheromoneIndex]
////                val dy = posY - pheromoneEntity.y[pheromoneIndex]
////                val distSq = dx * dx + dy * dy
////
////                val radiusSquared = pheromoneEntity.radiusSquared[pheromoneIndex]
////
////                if (distSq <= radiusSquared) {
////                    degreeOfShortening[cellIndex] -= 0.025f
////                    degreeOfShortening[cellIndex] = degreeOfShortening[cellIndex].coerceIn(0.6f, 1.5f)
////                }
////            }
//
//
//            //TODO думаю это можно как-то оптимизировать через среднее арифметическое для каждой ячекйи 32*32
//            pheromonesManager.findAllPheromonesInPoint(posX, posY, 11) { pheromoneIndex ->
//                val dx = posX - pheromoneEntity.x[pheromoneIndex]
//                val dy = posY - pheromoneEntity.y[pheromoneIndex]
//                val distSq = dx * dx + dy * dy
//
//                val a = pheromoneEntity.time[pheromoneIndex]
//                val radiusSquared = pheromoneEntity.radiusSquared[pheromoneIndex]
//
//                if (distSq <= radiusSquared) {
//                    val result = pheromonesManager.f(distSq, a)
//                    impulse += result
//
//                    xPheromone = pheromoneEntity.x[pheromoneIndex]
//                    yPheromone = pheromoneEntity.y[pheromoneIndex]
//                }
//
//                if (impulse >= 1.0f) {
//                    //Отсекаем все что больше 1.0 это позволит не считать кучу источников феромонов
//                    impulse = 1f
//                    return@findAllPheromonesInPoint
//                }
//            }
//
//            if (impulse <= 0.0f) return@with
//
//            val closestCells = gridManager.collectParticles(
//                gridX = cellEntity.getX(cellIndex).toInt(),
//                gridY = cellEntity.getY(cellIndex).toInt(),
//                radius = 2
//            )
//
//            val organCellsIndexes = closestCells
//                .filter { particleEntity.isCell[it] }
//                .map { particleEntity.holderEntityIndex[it] }
//                .filter { cellIndex != it}
//
//            val xNeigh = FloatArray(organCellsIndexes.size)
//            val yNeigh = FloatArray(organCellsIndexes.size)
//            val rNeigh = FloatArray(organCellsIndexes.size)
//
//            organCellsIndexes.forEachIndexed { orderIndex, cellIndex ->
//                xNeigh[orderIndex] = cellEntity.getX(cellIndex)
//                yNeigh[orderIndex] = cellEntity.getY(cellIndex)
//                rNeigh[orderIndex] = cellEntity.getRadius(cellIndex)
//            }
//
//            val newCellPosition = morphogenesis.placeNewCell(
//                xPheromone = xPheromone,
//                yPheromone = yPheromone,
//                xCellParent = cellEntity.getX(cellIndex),
//                yCellParent = cellEntity.getY(cellIndex),
//                rParent = cellEntity.getRadius(cellIndex),
//                rNew = 0.5f,
//                xNeigh = xNeigh,
//                yNeigh = yNeigh,
//                rNeigh = rNeigh
//            )
//
//            if (newCellPosition == null) {
//                isOnEdge[cellIndex] = false
//                setColor(cellIndex, defaultColor.toIntBits())
//                return@with
//            }
//
//            val color: Int = defaultColor.toIntBits()
//            val radius: Float = PARTICLE_MAX_RADIUS
//            val cellType: Int = cellTypeId
//            val organIndex = organIndex[cellIndex]
//            val parentIndex: Int = cellIndex
//            val angleDiffCosDefault: Float = 1f
//            val angleDiffSinDefault: Float = 0f
//            val colorDifferentiation: Int = 7
//            val visibilityRange: Float = 4.25f
//            val a: Float = 1f
//            val b: Float = 0f
//            val c: Float = 0f
//            val isSum: Boolean = true
//            val activationFuncType: Int = 0
//            val finalCos = angleCos[cellIndex]
//            val finalSin = angleSin[cellIndex]
//
//            var x = newCellPosition.x
//            var y = newCellPosition.y
//
//            if (x < 0) {
//                x = 0.1f
//            }
//            if (x > gridManager.gridWidth) {
//                x = gridManager.gridWidth - 0.1f
//            }
//            if (y < 0) {
//                y = 0.1f
//            }
//            if (y > gridManager.gridHeight) {
//                y = gridManager.gridHeight - 0.1f
//            }
//
//            val isMorphogenesis = true
//            val pheromoneType = 1
//
//            worldCommandsManager.worldCommandBuffer[threadId].push(
//                type = WorldCommandType.ADD_CELL,
//                booleans = booleanArrayOf(isSum, isMorphogenesis),
//                floats = floatArrayOf(
//                    x,
//                    y,
//                    radius,
//                    finalCos,
//                    finalSin,
//                    angleDiffCosDefault,
//                    angleDiffSinDefault,
//                    visibilityRange,
//                    a,
//                    b,
//                    c
//                ),
//                ints = intArrayOf(
//                    color,
//                    0,          //cellGenomeId
//                    cellType,
//                    organIndex,
//                    parentIndex,
//                    colorDifferentiation,
//                    activationFuncType,
//                    pheromoneType,
//                    -1, //mod data
//                    // Поколение родителя на момент постановки команды: до применения он
//                    // может умереть, а его индекс — достаться другой клетке.
//                    getGeneration(parentIndex)
//                )
//            )
//
//            energy[cellIndex] = 0.01f
//        }
    }
}
