package io.github.some_example_name.old.editor.system.control

import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.system.CellSearchManager
import io.github.some_example_name.old.editor.system.command.CommandEditorStackManager
import io.github.some_example_name.old.editor.system.logic.ToEditorDataMapper
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.editor.undo_redo_commands.CancelMutationCommand
import io.github.some_example_name.old.editor.undo_redo_commands.ChangeDivideCommand
import io.github.some_example_name.old.editor.undo_redo_commands.DivideCellCommand
import io.github.some_example_name.old.editor.undo_redo_commands.MutateCellCommand
import io.github.some_example_name.old.editor.undo_redo_commands.RemoveCellCommand
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.systems.genomics.genome.Action
import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS

class TryActionManager(
    val commandEditorStackManager: CommandEditorStackManager,
    val editorSimulationSystem: EditorSimulationSystem,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val cellSearchManager: CellSearchManager,
    val toEditorDataMapper: ToEditorDataMapper
) {
    var defaultActionType: LastActionType? = null
    var defaultAction: Action? = null

    fun tryToMutate(clickedCellIndex: Int, action: Action) {
        val genomeStageInstruction = editorSimulationSystem.genomeStageInstruction

        commandEditorStackManager.executeCommand(
            command = MutateCellCommand(
                currentTick = currentTick,
                action = action,
                clickedCell = toEditorDataMapper.mapToEditorData(clickedCellIndex),
                stageInstruction = genomeStageInstruction
            )
        )

        defaultActionType = LastActionType.MUTATE
        defaultAction = action.copy(
            id = -1,
            angle = null,
            physicalLink = hashMapOf()
        )
    }

    fun tryToCancelMutation(clickedCellIndex: Int) {
        commandEditorStackManager.executeCommand(
            command = CancelMutationCommand(
                currentTick = currentTick,
                clickedCell = toEditorDataMapper.mapToEditorData(clickedCellIndex),
                stageInstruction = editorSimulationSystem.genomeStageInstruction
            )
        )
    }

    fun tryToChange(
        clickedIndex: Int,
        divide: Action
    ) {
        val genomeStageInstruction = editorSimulationSystem.genomeStageInstruction
        commandEditorStackManager.executeCommand(
            command = ChangeDivideCommand(
                currentTick = currentTick,
                clickedCell = toEditorDataMapper.mapToEditorData(clickedIndex),
                divide = divide,
                stageInstruction = genomeStageInstruction
            )
        )
        defaultActionType = LastActionType.CHANGE
        defaultAction = divide.copy(
            id = -1,
            angle = null,
            physicalLink = hashMapOf()
        )
    }

    fun tryToRemove(clickedCellIndex: Int) {
        val clickedCell = toEditorDataMapper.mapToEditorData(clickedCellIndex)
        commandEditorStackManager.executeCommand(
            command = RemoveCellCommand(
                currentTick = currentTick,
                clickedCell = clickedCell,
                parentCell = toEditorDataMapper.mapToEditorData(clickedCell.parentIndex),
                stageInstruction = editorSimulationSystem.genomeStageInstruction
            )
        )
        defaultActionType = LastActionType.DELETE
        defaultAction = null
    }

    fun tryToDivide(
        clickedCellIndex: Int,
        newDividedCellPosition: Pair<Float, Float>?,
        action: Action
    ) {
        if (newDividedCellPosition == null) return

        val genomeStageInstruction = editorSimulationSystem.genomeStageInstruction

        // Связи — со всеми клетками, которых коснётся дочь своим настоящим радиусом.
        val radius = action.radius ?: PARTICLE_MAX_RADIUS

        val neighboursIds = cellSearchManager.getAllCloseNeighboursEditor(
            grabbedX = newDividedCellPosition.first,
            grabbedY = newDividedCellPosition.second,
            grabbedRadius = radius
        )
        val neighboursCells = neighboursIds.map {
            toEditorDataMapper.mapToEditorData(it)
        }

        commandEditorStackManager.executeCommand(
            command = DivideCellCommand(
                clickedCell = toEditorDataMapper.mapToEditorData(clickedCellIndex),
                neighboursCells = neighboursCells,
                divide = action,
                newId = editorSimulationSystem.maxCellId + 1,
                newPoint = newDividedCellPosition,
                stageInstruction = genomeStageInstruction,
                currentTick = currentTick,
            )
        )

        defaultActionType = LastActionType.DIVIDE
        defaultAction = action.copy(
            id = -1,
            angle = null,
            physicalLink = hashMapOf()
        )
    }
}
