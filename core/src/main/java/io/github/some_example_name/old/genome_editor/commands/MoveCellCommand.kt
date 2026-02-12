package io.github.some_example_name.old.genome_editor.commands

import io.github.some_example_name.old.genome.GenomeStage
import io.github.some_example_name.old.genome.LinkData
import io.github.some_example_name.old.genome_editor.Command
import io.github.some_example_name.old.genome_editor.CommandManager
import io.github.some_example_name.old.genome_editor.EditorCell
import io.github.some_example_name.old.genome_editor.GenomeEditorManager
import io.github.some_example_name.old.good_one.utils.distanceTo
import io.github.some_example_name.old.world_logic.GridManager
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import kotlin.math.atan2
import kotlin.math.sqrt

fun getAllCloseNeighboursEditor(
    grabbedX: Float,
    grabbedY: Float,
    gridManager: GridManager,
    editorCells: List<EditorCell>,
    grabbedCellIndex: Int?
): List<Int> {
    val gridGrabbedX = (grabbedX / CELL_SIZE).toInt()
    val gridGrabbedY = (grabbedY / CELL_SIZE).toInt()
    val allCells = mutableListOf<Int>()
    for (i in -1..1) {
        for (j in -1..1) {
            allCells.addAll(gridManager.getCells(gridGrabbedX + i, gridGrabbedY + j).toList())
        }
    }
    val filteredByDistance = allCells.filter {
        distanceTo(
            grabbedX,
            grabbedY,
            editorCells[it].x,
            editorCells[it].y
        ) <= CELL_SIZE
    }

    return if (grabbedCellIndex != null) filteredByDistance.filterNot { it == grabbedCellIndex } else filteredByDistance
}

fun moveCell(
    editor: GenomeEditorManager,
    grabbedCellIndex: Int,
    lastGrabbedCellX: Float,
    lastGrabbedCellY: Float,
    commandManager: CommandManager,
    currentStage: Int,
    autoLinking: Boolean,
    genomeStageInstruction: MutableList<GenomeStage>
) {
    val grabbedEditorCell = editor.editorCells[grabbedCellIndex].copy()
    val newX = editor.editorCells[grabbedCellIndex].x
    val newY = editor.editorCells[grabbedCellIndex].y

    val oldNeighboursIds = getAllCloseNeighboursEditor(
        lastGrabbedCellX,
        lastGrabbedCellY,
        editor.gridManager,
        editor.editorCells,
        null
    )
    val oldNeighboursJustAdded = oldNeighboursIds.map { id ->
        editor.editorCells[id]
    }

    val newNeighboursIds = getAllCloseNeighboursEditor(
        newX,
        newY,
        editor.gridManager,
        editor.editorCells,
        null
    )

    val newNeighbours = newNeighboursIds.map { id ->
        editor.editorCells[id]
    }

    commandManager.executeCommand(MoveCellCommand(
        grabbedEditorCell = grabbedEditorCell,
        parentEditorCell = editor.editorCells[grabbedEditorCell.parentIndex].copy(),
        oldNeighboursJustAdded = oldNeighboursJustAdded,
        newNeighbours = newNeighbours,
        newX = newX,
        newY = newY,
        currentStage = currentStage,
        autoLinking = autoLinking,
        genomeStageInstruction = genomeStageInstruction
    ))
}

class MoveCellCommand(
    val grabbedEditorCell: EditorCell,
    val parentEditorCell: EditorCell,
    val oldNeighboursJustAdded: List<EditorCell>,
    val newNeighbours: List<EditorCell>,
    val newX: Float,
    val newY: Float,
    val currentStage: Int,
    val autoLinking: Boolean,
    val genomeStageInstruction: MutableList<GenomeStage>
): Command  {

    override val stage = currentStage

    private val oldGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    private var newGenomeStageInstruction: List<GenomeStage>? = null

    override fun execute() {
        if (newGenomeStageInstruction != null) {
            genomeStageInstruction.clear()
            genomeStageInstruction.addAll(newGenomeStageInstruction!!)
            return
        }

        move(oldNeighboursJustAdded, newNeighbours, newX, newY)

        newGenomeStageInstruction = genomeStageInstruction.map { it.deepCopy() }
    }

    override fun undo() {
        genomeStageInstruction.clear()
        genomeStageInstruction.addAll(oldGenomeStageInstruction)
    }

    private fun move(
        oldNeighboursJustAdded: List<EditorCell>,
        newNeighbours: List<EditorCell>,
        newX: Float,
        newY: Float
    ) {
        if (autoLinking) {
            //Удаление всех прошлых связок с новыми клетками
            oldNeighboursJustAdded.forEach {
                if (it.isJustAdded) {
                    it.divide?.physicalLink?.remove(grabbedEditorCell.id)
                }
            }

            val physicalLink =
                newNeighbours.filter { it.id != grabbedEditorCell.id }.associate { it ->
                    val deltaX = newX - it.x
                    val deltaY = newY - it.y
                    val length = sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
                    it.id to LinkData(length = length)
                }

            grabbedEditorCell.divide?.also { it ->
                it.physicalLink.clear()
                it.physicalLink.putAll(physicalLink)
            }
        }

        //Добавлением новых связок
        val deltaX = newX - parentEditorCell.x
        val deltaY = newY - parentEditorCell.y

        val angle = atan2(deltaY.toDouble(), deltaX.toDouble()).toFloat() - parentEditorCell.angle

        grabbedEditorCell.divide?.also { it ->
            it.angle = angle
        }
    }
}
