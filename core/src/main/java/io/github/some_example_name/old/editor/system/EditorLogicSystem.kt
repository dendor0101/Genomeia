package io.github.some_example_name.old.editor.system

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import io.github.some_example_name.old.editor.commands.CommandEditorStackManager
import io.github.some_example_name.old.editor.commands.CtrlY
import io.github.some_example_name.old.editor.commands.CtrlZ
import io.github.some_example_name.old.editor.commands.FlingScreen
import io.github.some_example_name.old.editor.commands.GoToEndOfTimeLine
import io.github.some_example_name.old.editor.commands.NextStageButtonTap
import io.github.some_example_name.old.editor.commands.NextTickButtonClamped
import io.github.some_example_name.old.editor.commands.NextTickButtonTap
import io.github.some_example_name.old.editor.commands.PanScreen
import io.github.some_example_name.old.editor.commands.PrevStageButtonTap
import io.github.some_example_name.old.editor.commands.PrevTickButtonTap
import io.github.some_example_name.old.editor.commands.TapScreen
import io.github.some_example_name.old.editor.commands.TimeSlider
import io.github.some_example_name.old.editor.commands.UiEditorCommands
import io.github.some_example_name.old.editor.commands.UiScreenCommands
import io.github.some_example_name.old.editor.entities.ReplayEntity
import io.github.some_example_name.old.systems.genomics.genome.Action


enum class LastActionType {
    DIVIDE, MUTATE, CHANGE, DELETE
}

class EditorLogicSystem(
    val commandEditorStackManager: CommandEditorStackManager,
    val editorSimulationSystem: EditorSimulationSystem,
    val replayEntity: ReplayEntity
) {
    var currentTick = 0
    var currentStage = 0
    private var grabbedCellIndex = -1
    private var lastGrabbedCellX = 0.0f
    private var lastGrabbedCellY = 0.0f
    private var isDruggingCamera = false
    var uiScreenCommands: UiScreenCommands? = null
    private var defaultActionType: LastActionType? = null
    private var defaultAction: Action? = null

    fun restartSimulation(stage: Int) {
//        grabbedCellIndex = -1
//        editor.restartSimulation(stage)
//        menuUiBuilder.timeSlider.setRange(0f, (replayEntity.size - 1).toFloat())
//        editor.state.currentStage = stage
//        editor.state.currentTick = editor.stages[editor.state.currentStage]
//        setSliderValueProgrammatically(editor.state.currentTick.toFloat())
//        isUpdate = true
//        renderSystem.isUpdateBuffer = true
//        isRestartSimulation = true
        TODO()
    }


    fun putUiCommand(command: UiEditorCommands) {
        when (command) {
            CtrlY -> {
                commandEditorStackManager.undo()?.let { restartSimulation(it) }
            }

            CtrlZ -> {
                commandEditorStackManager.redo()?.let { restartSimulation(it) }
            }

            FlingScreen -> {
//        if (grabbedCellIndex != -1) {
//            val genomeStageInstruction = editor.growthProcessor.currentGenome.genomeStageInstruction
//            timeForProcessingActionStart = System.nanoTime()
//            moveCell(
//                editor,
//                grabbedCellIndex,
//                lastGrabbedCellX,
//                lastGrabbedCellY,
//                commandManager,
//                editor.state.currentStage,
//                autoLinking,
//                genomeStageInstruction
//            )
//        }
//        isDruggingCamera = false
                isDruggingCamera = false
                TODO()
            }
            NextTickButtonTap -> {
//                    if (editor.state.currentTick < editor.replay.size - 1) {
//                        editor.state.currentTick++
//                        editor.state.currentStage = editor.stageByTick.getStage(editor.state.currentTick)
//                        setSliderValueProgrammatically(editor.state.currentTick.toFloat())
//                        editor.stateChanged(false)
//                    }
                TODO()
            }
            is PanScreen -> {
//        if (grabbedCellIndex == -1) {
//            if (!isDruggingCamera) {
//                val (touchedCellX, touchedCellY) = screenToWorld(x, y)
//                val clickedIndex = editor.getClickedCellIndex(touchedCellX, touchedCellY)
//                if (clickedIndex != null && editor.editorCells[clickedIndex].isJustAdded) {
//                    grabbedCellIndex = clickedIndex
//                    lastGrabbedCellX = editor.editorCells[clickedIndex].x
//                    lastGrabbedCellY = editor.editorCells[clickedIndex].y
//                    isDruggingCamera = false
//                } else {
//                    grabbedCellIndex = -1
//                    lastGrabbedCellX = 0.0f
//                    lastGrabbedCellY = 0.0f
//                    isDruggingCamera = true
//                    camera.translate(-deltaX * camera.zoom, deltaY * camera.zoom, 0f)
//                }
//            } else {
//                camera.translate(-deltaX * camera.zoom, deltaY * camera.zoom, 0f)
//            }
//        } else {
//
//            val parentIndex = editor.editorCells[grabbedCellIndex].parentIndex
//            val parentCellX = editor.editorCells[parentIndex].x
//            val parentCellY = editor.editorCells[parentIndex].y
//            val (childCellX, childCellY) = screenToWorld(x, y)
//            val (finalX, finalY) = setMinMaxDistForChildCellToParent(childCellX, childCellY, parentCellX, parentCellY)
//
//            // Сохраняем итоговые координаты
//            // We save the final coordinates
//            editor.editorCells[grabbedCellIndex].x = finalX
//            editor.editorCells[grabbedCellIndex].y = finalY
//            isUpdate = true
//        }
            }
            PrevStageButtonTap -> {
//                if (editor.state.currentStage > 0) {
//                    editor.state.currentStage--
//                    editor.state.currentTick = editor.stages[editor.state.currentStage]
//                    setSliderValueProgrammatically(editor.state.currentTick.toFloat())
//                    editor.stateChanged(false)
//                }
                TODO()
            }
            PrevTickButtonTap -> {
//                if (editor.state.currentTick > 0) {
//                    editor.state.currentTick--
//                    editor.state.currentStage = editor.stageByTick.getStage(editor.state.currentTick)
//                    setSliderValueProgrammatically(editor.state.currentTick.toFloat())
//                    editor.stateChanged(false)
//                }
                TODO()
            }
            NextStageButtonTap -> {
//                if (editor.state.currentStage < editor.stages.size - 1) {
//                    editor.state.currentStage++
//                    editor.state.currentTick = editor.stages[editor.state.currentStage]
//                    setSliderValueProgrammatically(editor.state.currentTick.toFloat())
//                    editor.stateChanged(false)
//                }
                TODO()
            }
            is NextTickButtonClamped -> {
                TODO()
                if (command.isFinish) {
                    // Завершение долгого нажатия
//                    setSliderValueProgrammatically(editor.state.currentTick.toFloat())
//                    editor.stateChanged(false)
                } else {
//                        if (editor.state.currentTick < editor.replay.size - 1) {
//                            editor.state.currentTick++
//                            editor.state.currentStage = editor.stageByTick.getStage(editor.state.currentTick)
//                            setSliderValueProgrammatically(editor.state.currentTick.toFloat())
//                            editor.stateChanged(true)
//                        }
                }
            }
            is TapScreen -> {
//        val clickedIndex = editor.getClickedCellIndex(touchedCellX, touchedCellY) ?: return true
//
//        val clickedCell = editor.editorCells[clickedIndex]
//        grabbedCellIndex = -1
                /*return */if (Gdx.app.type == Application.ApplicationType.Desktop) {
//            when (button) {
//                Input.Buttons.LEFT -> {
//                    leftClick(clickedIndex, clickedCell)
//                    true
//                }
//
//                Input.Buttons.RIGHT -> {
//                    if (!isCtrl) rightClick(clickedIndex, clickedCell)
//                    true
//                }
//
//                else -> {
//                    false
//                }
//            }
                } else {
//            if (!isCtrl) {
//                if (isRightClick) {
//                    rightClick(clickedIndex, clickedCell)
//                } else {
//                    leftClick(clickedIndex, clickedCell)
//                }
//            } else {
//                leftClick(clickedIndex, clickedCell)
//            }
                    true
                }
                TODO()
            }
            GoToEndOfTimeLine -> {
                currentTick = replayEntity.tickStartIndices.size - 1
                currentStage = TODO()//editor.stageByTick.getStage(editor.state.currentTick)
            }

            is TimeSlider -> {
                currentTick = command.value
                if (!command.isDragging) {
//                    editor.stateChanged(false)
                } else {
//                    editor.state.currentTick = timeSlider.value.toInt()
//                    editor.state.currentStage = editor.stageByTick.getStage(editor.state.currentTick)
//                    editor.stateChanged(true)
                }
                TODO()
            }
        }
    }


    /*

    private fun leftClick(clickedIndex: Int, clickedCell: EditorCell) {

        val genomeStageInstruction =
            editor.growthProcessor.currentGenome.genomeStageInstruction
        if (!isCtrl) {
            val newDividedCellPosition = tryToDivideCell(
                clickedCellIndex = clickedIndex,
                gridManager = editor.gridManager,
                editorCells = editor.editorCells
            )

            when {
                clickedCell.isJustAdded && clickedCell.divide != null -> {
                    //Change и remove
                    val dialogMutateOrDivide = ChangeRemoveActionDialog(
                        clickedCell = clickedCell,
                        divide = clickedCell.divide.copy(),
                        game = game,
                        bundle = bundle,
                        onRemove = {
                            tryToRemove(clickedIndex)
                            defaultActionType = LastActionType.DELETE
                            defaultAction = null
                        },
                        onChange = { divide ->
                            tryToChange(clickedIndex, divide.copy())
                            defaultActionType = LastActionType.CHANGE
                            defaultAction = divide.copy(
                                id = -1,
                                angle = null,
                                physicalLink = hashMapOf()
                            )
                        }
                    )
                    dialogMutateOrDivide.show(stage)
                }

                newDividedCellPosition != null && clickedCell.divide == null -> {
                    val dialogMutateOrDivide = MutateOrDivideDialog(
                        clickedCell = clickedCell,
                        onMutate = {
                            showMutateDialog(
                                clickedCell,
                                clickedIndex
                            )
                        },
                        onDivide = {
                            showDivideDialog(
                                clickedCell,
                                clickedIndex,
                                newDividedCellPosition
                            )
                        },
                        game = game,
                        bundle = bundle
                    )
                    dialogMutateOrDivide.show(stage)
                }
                else -> {
                    showMutateDialog(
                        clickedCell,
                        clickedIndex
                    )
                }
            }
        } else {
            if (previousCtrlClicked != -1 && previousCtrlClicked != clickedIndex) {

                val cellFrom = editor.editorCells[previousCtrlClicked].copy()
                val cellTo = editor.editorCells[clickedIndex].copy()
                val linkId = editor.linksPairId.get(cellFrom.id, cellTo.id)
                if (linkId >= 0){
                    val link = editor.editorLinks[linkId]
                    timeForProcessingActionStart = System.nanoTime()
                    commandManager.executeCommand(
                        command = AddNeuralLinkCommand(
                            currentStage = editor.state.currentStage,
                            cellFrom = editor.editorCells[previousCtrlClicked].copy(),
                            cellTo = editor.editorCells[clickedIndex].copy(),
                            genomeStageInstruction = genomeStageInstruction,
                            doesNeedAddNewStage = genomeStageInstruction.size <= editor.state.currentStage,
                            link = link
                        )
                    )
                }
            }
            if (isCtrl) {
                previousCtrlClicked = clickedIndex
            }
        }
    }

    private fun rightClick(clickedIndex: Int, clickedCell: EditorCell) {
        when (defaultActionType) {
            LastActionType.DIVIDE -> {
                if (!clickedCell.isJustAdded && clickedCell.divide == null ) {
                    val newDividedCellPosition = tryToDivideCell(
                        clickedCellIndex = clickedIndex,
                        gridManager = editor.gridManager,
                        editorCells = editor.editorCells
                    )
                    defaultAction?.let {
                        tryToDivide(clickedIndex, newDividedCellPosition, it.copy(
                            id = -1,
                            angle = null,
                            physicalLink = hashMapOf()
                        ))
                    }
                }
            }
            LastActionType.MUTATE -> {
                defaultAction?.let { tryToMutate(clickedIndex, it.copy(
                    id = -1,
                    angle = null,
                    physicalLink = hashMapOf()
                )) }
            }
            LastActionType.CHANGE -> {
                if (clickedCell.isJustAdded && clickedCell.divide != null) {
                    defaultAction?.let {
                        tryToChange(clickedIndex, it.copy(
                            id = -1,
                            angle = null,
                            physicalLink = hashMapOf()
                        ))
                    }
                }
            }
            LastActionType.DELETE -> {
                if (clickedCell.isJustAdded && clickedCell.divide != null) {
                    tryToRemove(clickedIndex)
                }
            }
            null -> {}
        }
    }

    private fun tryToMutate(clickedCellIndex: Int, action: Action) {
        timeForProcessingActionStart = System.nanoTime()
        val genomeStageInstruction = editor.growthProcessor.currentGenome.genomeStageInstruction

        commandManager.executeCommand(
            command = MutateCellCommand(
                stage = editor.state.currentStage,
                action = action,
                clickedCell = editor.editorCells[clickedCellIndex].copy(),
                genomeStageInstruction = genomeStageInstruction,
                doesNeedAddNewStage = genomeStageInstruction.size <= editor.state.currentStage,
            )
        )
    }

    private fun tryToChange(
        clickedIndex: Int,
        divide: Action
    ) {
        timeForProcessingActionStart = System.nanoTime()
        val genomeStageInstruction =
            editor.growthProcessor.currentGenome.genomeStageInstruction
        commandManager.executeCommand(
            command = ChangeDivideCommand(
                stage = editor.state.currentStage,
                clickedCell = editor.editorCells[clickedIndex].copy(),
                divide = divide,
                genomeStageInstruction = genomeStageInstruction
            )
        )
    }

    private fun tryToRemove(clickedCellIndex: Int) {
        timeForProcessingActionStart = System.nanoTime()
        val genomeStageInstruction =
            editor.growthProcessor.currentGenome.genomeStageInstruction
        val clickedCell = editor.editorCells[clickedCellIndex].copy()
        commandManager.executeCommand(
            command = RemoveCellCommand(
                currentStage = editor.state.currentStage,
                clickedCell = editor.editorCells[clickedCellIndex].copy(),
                parentCell = editor.editorCells[clickedCell.parentIndex].copy(),
                genomeStageInstruction = genomeStageInstruction
            )
        )
    }

    private fun tryToDivide(
        clickedCellIndex: Int,
        newDividedCellPosition: Pair<Float, Float>?,
        action: Action
    ) {
        timeForProcessingActionStart = System.nanoTime()
        if (newDividedCellPosition == null) return
//        pikSounds.random().play(GlobalSettings.SOUND_VOLUME / 100f)
        val isLastTick = editor.state.currentTick == editor.replay.size - 1

        val genomeStageInstruction = editor.growthProcessor.currentGenome.genomeStageInstruction

        val neighboursIds = getAllCloseNeighboursEditor(
            newDividedCellPosition.first,
            newDividedCellPosition.second,
            editor.gridManager,
            editor.editorCells,
            null
        )
        val neighboursCells = neighboursIds.map {
            editor.editorCells[it]
        }

        commandManager.executeCommand(
            command = DivideCellCommand(
                clickedCell = editor.editorCells[clickedCellIndex].copy(),
                neighboursCells = neighboursCells,
                divide = action,
                newId = editor.growthProcessor.maxCellId + 1,
                newPoint = newDividedCellPosition,
                doesNeedAddNewStage = genomeStageInstruction.size <= editor.state.currentStage,
                genomeStageInstruction = genomeStageInstruction,
                currentStage = editor.state.currentStage,
                autoLinking = autoLinking
            )
        )

        if (editor.state.currentTick < editor.replay.size - 1 && isLastTick) {
            editor.state.currentTick++
            editor.state.currentStage = editor.stageByTick.getStage(editor.state.currentTick)
            setSliderValueProgrammatically(editor.state.currentTick.toFloat())
            editor.stateChanged(false)
        }
    }
*/


    fun restartSim() {
        grabbedCellIndex = -1
    }
}
