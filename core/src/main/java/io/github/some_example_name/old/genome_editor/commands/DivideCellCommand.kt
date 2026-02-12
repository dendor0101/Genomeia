package io.github.some_example_name.old.genome_editor.commands

import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.genome.CellAction
import io.github.some_example_name.old.genome.GenomeStage
import io.github.some_example_name.old.genome.LinkData
import io.github.some_example_name.old.genome_editor.Command
import io.github.some_example_name.old.genome_editor.EditorCell
import io.github.some_example_name.old.genome_editor.findNewOptimalCellPosition
import io.github.some_example_name.old.world_logic.GridManager
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import kotlin.math.atan2
import kotlin.math.sqrt

fun getAll2LayersNeighboursEditor(
    clickedX: Float,
    clickedY: Float,
    gridManager: GridManager,
    clickedCellIndex: Int
): List<Int> {
    val gridGrabbedX = (clickedX / CELL_SIZE).toInt()
    val gridGrabbedY = (clickedY / CELL_SIZE).toInt()
    val allCells = mutableListOf<Int>()
    for (i in -2..2) {
        for (j in -2..2) {
            if (i == 2 && j == 2) continue
            if (i == -2 && j == 2) continue
            if (i == 2 && j == -2) continue
            if (i == -2 && j == -2) continue
            allCells.addAll(gridManager.getCells(gridGrabbedX + i, gridGrabbedY + j).toList())
        }
    }
    return allCells.filter { it != clickedCellIndex }
}

fun tryToDivideCell(
    clickedCellIndex: Int,
    gridManager: GridManager,
    editorCells: List<EditorCell>,
): Pair<Float, Float>? {
    val clickedCell = editorCells[clickedCellIndex]
    val xs = mutableListOf<Float>()
    val ys = mutableListOf<Float>()

    val neighboursAllowedForConnectionIds = getAll2LayersNeighboursEditor(
        clickedCell.x,
        clickedCell.y,
        gridManager,
        clickedCellIndex
    )

    neighboursAllowedForConnectionIds.forEach { it
        xs.add(editorCells[it].x)
        ys.add(editorCells[it].y)
    }

    val newPoint = findNewOptimalCellPosition(clickedCell.x, clickedCell.y, xs, ys)
    return newPoint
}

class DivideCellCommand(
    val clickedCell: EditorCell,
    val neighboursCells: List<EditorCell>,
    val divide: Action,
    val newId: Int,
    val newPoint: Pair<Float, Float>,
    val doesNeedAddNewStage: Boolean,
    val genomeStageInstruction: MutableList<GenomeStage>,
    val currentStage: Int,
    val autoLinking: Boolean
) : Command {

    override var stage = currentStage

    private val oldGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    private var newGenomeStageInstruction: List<GenomeStage>? = null

    override fun execute() {

        if (newGenomeStageInstruction != null) {
            genomeStageInstruction.clear()
            genomeStageInstruction.addAll(newGenomeStageInstruction!!)
            return
        }

        val justAddedCellX = newPoint.first
        val justAddedCellY = newPoint.second

        val deltaXAngle = justAddedCellX - clickedCell.x
        val deltaYAngle = justAddedCellY - clickedCell.y

        val angle = atan2(deltaYAngle.toDouble(), deltaXAngle.toDouble()).toFloat() - clickedCell.angle

        val physicalLink = if (autoLinking) HashMap(neighboursCells.associate {
            val deltaX = justAddedCellX - it.x
            val deltaY = justAddedCellY - it.y
            val length = sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
            it.id to LinkData(length = length)
        }) else HashMap()

        if (doesNeedAddNewStage) {
            genomeStageInstruction.add(GenomeStage())
        }
        val divideAction = divide.copy(
            id = newId,
            angle = angle,
            physicalLink = physicalLink
        )

        genomeStageInstruction[currentStage].cellActions.compute(clickedCell.id) { _, oldValue ->
            oldValue?.copy(divide = divideAction)
                ?: CellAction(
                    divide = divideAction
                )
        }
        newGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    }

    override fun undo() {
        genomeStageInstruction.clear()
        genomeStageInstruction.addAll(oldGenomeStageInstruction)
    }
}
