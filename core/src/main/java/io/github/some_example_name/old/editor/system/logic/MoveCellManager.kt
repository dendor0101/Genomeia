package io.github.some_example_name.old.editor.system.logic

import io.github.some_example_name.old.core.utils.setMinMaxDistForChildCellToParent
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.grabbedCellIndex
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastGrabbedCellX
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastGrabbedCellY
import io.github.some_example_name.old.editor.system.CellSearchManager
import io.github.some_example_name.old.editor.system.SymmetryManager
import io.github.some_example_name.old.editor.system.command.CommandEditorStackManager
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.editor.undo_redo_commands.MoveCellCommand
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.ParticleEntity

class MoveCellManager(
    val commandEditorStackManager: CommandEditorStackManager,
    val editorSimulationSystem: EditorSimulationSystem,
    val cellEntity: CellEntity,
    val particleEntity: ParticleEntity,
    val symmetryManager: SymmetryManager,
    val cellSearchManager: CellSearchManager,
    val toEditorDataMapper: ToEditorDataMapper,
) {

    fun movingCell(command: PanScreen) {
        val parentIndex = cellEntity.parentIndex[grabbedCellIndex]
        val parentCellX = particleEntity.x[parentIndex]
        val parentCellY = particleEntity.y[parentIndex]

        val (finalX, finalY) = setMinMaxDistForChildCellToParent(
            childCellX = command.x,
            childCellY = command.y,
            childRadius = particleEntity.radius[grabbedCellIndex],
            parentCellX = parentCellX,
            parentCellY = parentCellY,
            parentRadius = particleEntity.radius[parentIndex]
        )

        particleEntity.x[grabbedCellIndex] = finalX
        particleEntity.y[grabbedCellIndex] = finalY
    }

    fun cellMoved() {
        val grabbedEditorCell = toEditorDataMapper.mapToEditorData(grabbedCellIndex)

        val (x, y) = symmetryManager.snapPosition(
            particleEntity.x[grabbedCellIndex],
            particleEntity.y[grabbedCellIndex],
            cellIndex = grabbedCellIndex
        )

        particleEntity.x[grabbedCellIndex] = x
        particleEntity.y[grabbedCellIndex] = y

        val newX = particleEntity.x[grabbedCellIndex]
        val newY = particleEntity.y[grabbedCellIndex]
        val parentIndex = cellEntity.parentIndex[grabbedCellIndex]

        val oldNeighboursIds = cellSearchManager.getAllCloseNeighboursEditor(
            lastGrabbedCellX,
            lastGrabbedCellY,
            grabbedRadius = particleEntity.radius[grabbedCellIndex],
            grabbedCellIndex,
        )
        val oldNeighboursJustAdded = oldNeighboursIds.map { id ->
            toEditorDataMapper.mapToEditorData(id)
        }

        val newNeighboursIds = cellSearchManager.getAllCloseNeighboursEditor(
            newX,
            newY,
            grabbedRadius = particleEntity.radius[grabbedCellIndex],
            grabbedCellIndex
        )

        val newNeighbours = newNeighboursIds.map { id ->
            toEditorDataMapper.mapToEditorData(id)
        }

        commandEditorStackManager.executeCommand(
            MoveCellCommand(
                grabbedEditorCell = grabbedEditorCell,
                parentEditorCell = toEditorDataMapper.mapToEditorData(parentIndex),
                oldNeighboursJustAdded = oldNeighboursJustAdded,
                newNeighbours = newNeighbours,
                newX = newX,
                newY = newY,
                currentTick = currentTick,
                stageInstruction = editorSimulationSystem.genomeStageInstruction
            )
        )
    }
}
