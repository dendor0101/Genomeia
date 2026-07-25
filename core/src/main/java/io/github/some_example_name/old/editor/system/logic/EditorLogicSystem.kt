package io.github.some_example_name.old.editor.system.logic

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import io.github.some_example_name.old.editor.system.command.CommandEditorStackManager
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.grabbedCellIndex
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.isDruggingCamera
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.isRightClick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastGrabbedCellX
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastGrabbedCellY
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.uiScreenCommands
import io.github.some_example_name.old.editor.entities.CellReplay
import io.github.some_example_name.old.editor.system.CellSearchManager
import io.github.some_example_name.old.editor.system.control.LeftRightClickManager
import io.github.some_example_name.old.editor.system.control.TryActionManager
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.physics.GridManager
import kotlin.system.measureNanoTime

class EditorLogicSystem(
    val commandEditorStackManager: CommandEditorStackManager,
    val editorSimulationSystem: EditorSimulationSystem,
    val cellReplay: CellReplay,
    val cellEntity: CellEntity,
    val particleEntity: ParticleEntity,
    val linkEntity: LinkEntity,
    val gridManager: GridManager,
    val cellSearchManager: CellSearchManager,
    val toEditorDataMapper: ToEditorDataMapper,
    val leftRightClickManager: LeftRightClickManager,
    val moveCellManager: MoveCellManager,
    val tryActionManager: TryActionManager
): RestartSimulationCallBack {

    private lateinit var camera: OrthographicCamera

    init {
        commandEditorStackManager.bind(this)
    }

    fun bindToScreen(
        camera: OrthographicCamera
    ) {
        this.camera = camera
    }

    override fun restartSimulation() {
        val nanoTime = measureNanoTime {
            editorSimulationSystem.simulate()
        }
        println("simulate: ${nanoTime / 1_000_000.0} ms")
        lastTick = cellReplay.getTickCount() - 1
        if (lastTick < currentTick) currentTick = lastTick
    }

    fun putUiCommand(command: UiEditorCommands) {
        when (command) {
            CtrlY -> commandEditorStackManager.redo()

            CtrlZ -> commandEditorStackManager.undo()

            is TouchDown -> {
                val clickedCell = cellSearchManager.getClickedCellIndex(
                    clickX = command.x,
                    clickY = command.y
                )

                if (clickedCell != null && clickedCell.second) {
                    grabbedCellIndex = clickedCell.first
                    lastGrabbedCellX = particleEntity.x[grabbedCellIndex]
                    lastGrabbedCellY = particleEntity.y[grabbedCellIndex]
                    isDruggingCamera = false
                } else {
                    grabbedCellIndex = -1
                    lastGrabbedCellX = -1.0f
                    lastGrabbedCellY = -1.0f
                    isDruggingCamera = true
                }
            }

            is PanScreen -> {
                if (grabbedCellIndex == -1) {
                    if (isDruggingCamera) {
                        camera.translate(command.deltaX, command.deltaY, 0f)
                    }
                } else {
                    moveCellManager.movingCell(command)
                }
            }

            FlingScreen -> {
                if (grabbedCellIndex != -1) {
                    moveCellManager.cellMoved()
                }

                grabbedCellIndex = -1
                lastGrabbedCellX = -1.0f
                lastGrabbedCellY = -1.0f
                isDruggingCamera = false
            }

            NextTickButtonTap -> if (currentTick < lastTick) currentTick++

            PrevTickButtonTap -> if (currentTick > 0) currentTick--

            is TapScreen -> {
                cellSearchManager.getClickedCellIndex(
                    clickX = command.x,
                    clickY = command.y
                )?.let {
                    val clickedIndex = it.first
                    val clickedCell = toEditorDataMapper.mapToEditorData(clickedIndex)
                    grabbedCellIndex = -1
                    val isDesktop = Gdx.app.type == Application.ApplicationType.Desktop || Gdx.app.type == Application.ApplicationType.WebGL

                    val isLeftClick = if (isDesktop) command.isLeft else command.isCtrl || !isRightClick

                    when {
                        isLeftClick -> leftRightClickManager.leftClick(clickedIndex, clickedCell, command.isCtrl)
                        !command.isCtrl -> leftRightClickManager.rightClick(clickedIndex, clickedCell)
                    }
                }

                grabbedCellIndex = -1
                lastGrabbedCellX = -1.0f
                lastGrabbedCellY = -1.0f
                isDruggingCamera = false
            }

            GoToEndOfTimeLine -> currentTick = lastTick

            GoToStartOfTimeLine -> currentTick = 0

            is TimeSlider -> currentTick = command.value

            is DivideDialog -> uiScreenCommands = ShowDivideDialog(
                clickedCell = command.clickedCell,
                newDividedCellPosition = command.newDividedCellPosition
            )
            is MutateDialog -> uiScreenCommands = ShowMutateDialog(
                clickedCell = command.clickedCell,
                parentCell = command.parentCell,
                currentTick = command.currentTick,
            )

            is TryToChange -> tryActionManager.tryToChange(
                clickedIndex = command.clickedCellIndex,
                divide = command.divide
            )
            is TryToDivide -> tryActionManager.tryToDivide(
                clickedCellIndex = command.clickedCellIndex,
                newDividedCellPosition = command.newDividedCellPosition,
                action = command.divide
            )
            is TryToMutate -> tryActionManager.tryToMutate(
                clickedCellIndex = command.clickedCellIndex,
                action = command.mutate
            )
            is TryToRemove -> tryActionManager.tryToRemove(clickedCellIndex = command.clickedCellIndex)
        }
    }
}
