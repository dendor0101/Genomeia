/*
package io.github.some_example_name.old.game_entity

class CellManager(val physicsCircleCellController: PhysicsCircleCellController) {

    private val cells: MutableList<Cell> = mutableListOf()
    private var cellCounter = 0
    var isSomethingChanged = false

    fun tick(deltaTime: Float) {
        val updateCells = getCells()
        val removed = mutableListOf<Cell>()
        val added = mutableListOf<Cell>()
        updateCells.forEachIndexed { index, cell ->
            cell.worldTick(
                deltaTime = deltaTime,
                divide = {
                    println("divide ${cell.id}")
//                    cells.removeAt(index)
                    removed.add(cell)
//                    cells.addAll(splitCell(cell))
                    added.addAll(splitCell(cell))
                    isSomethingChanged = true
                },
                died = {
                    println("died")
//                    cells.removeAt(index)
                    removed.add(cell)
                    isSomethingChanged = true
                }
            )
        }

        cells.removeAll(removed)
        cells.addAll(added)
    }

    private fun splitCell(cell: Cell): List<Cell> {
        val angle = cell.genome.angleCellDivision
        val offset = cell.mass / 2 // Смещение от центра

        val cellBall = physicsCircleCellController.getBalls().firstOrNull{ it.cellId == cell.id } ?: return emptyList()
        val position = cellBall.body.position
        // Вычисляем координаты для новых клеток
        val (x1, y1) = calculateNewCellPosition(position, angle, offset)
        val (x2, y2) = calculateNewCellPosition(position, angle + 180, offset)

        // Вычисляем импульсы для новых клеток
        val impulse1 = calculateImpulse(angle, MOMENTUM_FORCE_DURING_SEPARATION)
        val impulse2 = calculateImpulse(angle + 180, MOMENTUM_FORCE_DURING_SEPARATION)

        // Создаем новые клетки
        val newCell1 = createNewCell(cell, x1, y1, impulse1, cellCounter++)
        val newCell2 = createNewCell(cell, x2, y2, impulse2, cellCounter++)

        return listOf(newCell1, newCell2)
    }

    fun putCell(x: Float, y: Float) {
        cells.add(Cell().apply {
            id = cellCounter++
            this.x = x
            this.y = y
        })
        isSomethingChanged = true
    }

    fun getCells() = cells.toList()

    fun clearAll() {
        cells.clear()
        isSomethingChanged = true
    }

    companion object {
        private const val MOMENTUM_FORCE_DURING_SEPARATION = 1.5f
    }
}
*/
