package io.github.some_example_name.old.cells

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.pinkColors
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem.Companion.MAX_LINK_AMOUNT

class Magnet(cellTypeId: Int) : Cell(
    defaultColor = pinkColors[0],
    cellTypeId = cellTypeId,
    isNeural = true,
    effectOnContact = false
) {

    override fun doOnTick(cellIndex: Int, threadId: Int) = with(cellEntity) {
        // При получении нейросигнала (impulse >= 1) создаём связи-линки с другими магнитами поблизости
        if (neuronImpulseOutput[cellIndex] >= 1f) {
            val currentCellIndex = cellIndex
            val currentX = getX(currentCellIndex)
            val currentY = getY(currentCellIndex)
            
            // Ищем другие клетки-магниты в радиусе действия
            val searchRadius = 5f // Радиус поиска других магнитов
            
            // Вычисляем диапазон ячеек сетки для поиска
            val gridCellSize = 1f // Размер ячейки сетки (предполагаем, что равен 1)
            val radiusInCells = (searchRadius / gridCellSize).toInt() + 1
            
            val gridX = (currentX / gridCellSize).toInt()
            val gridY = (currentY / gridCellSize).toInt()
            
            val gridWidth = gridManager.gridWidth
            val gridHeight = gridManager.gridHeight
            
            // Проверяем соседние ячейки сетки в радиусе
            for (dy in -radiusInCells..radiusInCells) {
                for (dx in -radiusInCells..radiusInCells) {
                    val nx = gridX + dx
                    val ny = gridY + dy
                    
                    if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue
                    
                    val items = gridManager.getParticles(nx, ny)
                    if (items.isNotEmpty()) {
                        for (particleIndex in items) {
                            if (!particleEntity.isCell[particleIndex]) continue
                            
                            val otherCellIndex = particleEntity.holderEntityIndex[particleIndex]
                            
                            // Проверяем, что это тоже магнит и не та же самая клетка
                            if (otherCellIndex != currentCellIndex && 
                                cellEntity.cellType[otherCellIndex].toInt() == cellTypeId) {
                                
                                // Проверяем расстояние до кандидата
                                val otherX = getX(otherCellIndex)
                                val otherY = getY(otherCellIndex)
                                val distSq = (currentX - otherX) * (currentX - otherX) + (currentY - otherY) * (currentY - otherY)
                                
                                if (distSq <= searchRadius * searchRadius) {
                                    // Проверяем, есть ли уже линк между этими клетками
                                    val existingLink = linkEntity.linkIndexMap.get(currentCellIndex, otherCellIndex)
                                    if (existingLink == -1) {
                                        // Создаём новую связь только если у обеих клеток есть свободные слоты для линков
                                        if (cellEntity.linksAmount[currentCellIndex] < MAX_LINK_AMOUNT &&
                                            cellEntity.linksAmount[otherCellIndex] < MAX_LINK_AMOUNT) {
                                            
                                            val distance = kotlin.math.sqrt(distSq)
                                            
                                            worldCommandsManager.worldCommandBuffer[threadId].push(
                                                type = WorldCommandType.ADD_LINK,
                                                booleans = booleanArrayOf(
                                                    isStickyLink = false,      // Это не sticky линк, а магнитный
                                                    isNeuronLink = false,      // Не нейронная связь
                                                    isLink1NeuralDirected = false
                                                ),
                                                floats = floatArrayOf(distance, 0.5f), // degreeOfShortening = 0.5 для притяжения
                                                ints = intArrayOf(currentCellIndex, otherCellIndex)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}
