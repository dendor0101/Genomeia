package io.github.some_example_name.old.editor.system.logic

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.SessionJournal
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.editor.system.command.CommandEditorStackManager
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.grabbedCellIndex
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.isDruggingCamera
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.isNeuralLinking
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.isRightClick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastGrabbedCellX
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastGrabbedCellY
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.linkColor
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.nextStageTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.previousCtrlClicked
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.showPhysicalLink
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.uiScreenCommands
import io.github.some_example_name.old.editor.entities.CellReplay
import io.github.some_example_name.old.editor.system.CellSearchManager
import io.github.some_example_name.old.editor.system.control.LeftRightClickManager
import io.github.some_example_name.old.editor.system.control.TryActionManager
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.editor.tutorial.EditorTutorial
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.genome.saveGenome
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

    /**
     * Обучение, если редактор открыт в нём (GoTutorial), иначе null. Его выставляет и
     * снимает экран; задания обучения смотрят на каждую команду отсюда.
     */
    var tutorial: EditorTutorial? = null
        set(value) {
            field = value
            commandEditorStackManager.historyListener = value
        }

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

    /**
     * Единственный вход для действий игрока в редакторе генома: экран только собирает
     * команду из жеста или кнопки, а состояние редактора меняется здесь.
     */
    fun putUiCommand(command: UiEditorCommands) =
        SessionJournal.execute(CommandSource.EDITOR, command) {
            tutorial?.beforeCommand(it)
            applyCommand(it)
            dropStaleNeuralLinkStart()
            tutorial?.afterCommand(it)
        }

    private fun applyCommand(command: UiEditorCommands) {
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

                    // Левый ли это клик, решено ещё при создании команды (см. TapScreen).
                    when {
                        command.isLeft -> leftRightClickManager.leftClick(clickedIndex, clickedCell, command.isCtrl)
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
                newDividedCellPosition = command.newDividedCellPosition,
                places = command.places
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
            is TryToCancelMutation -> tryActionManager.tryToCancelMutation(clickedCellIndex = command.clickedCellIndex)

            is SetNeuralLinking -> {
                isNeuralLinking = command.enabled
                if (!command.enabled) previousCtrlClicked = -1
            }

            is SetRepeatLastAction -> isRightClick = command.enabled

            is SetNeuralLinkColor -> linkColor = Color(command.rgba8888)

            is SetSymmetry -> DIGenomeEditorContainer.symmetryManager.symmetryMode = command.mode

            is SetShowPhysicalLinks -> showPhysicalLink = command.visible

            is SetRenderMode -> DIGenomeEditorContainer.editorRenderSystem.mode = command.mode

            is SaveGenome -> saveCurrentGenome(command.genomeName)

            // Окно «поделиться» при повторе бага ни к чему: файл и так в песочнице.
            is ExportGenome -> if (!BugReplay.isActive) {
                DIGameGlobalContainer.game.multiPlatformFileProvider.exportGenome("genomes/${command.genomeName}.genome")
            }

            TutorialNextStep -> tutorial?.nextStep()

            is ZoomCamera -> {
                camera.zoom = command.zoom
                camera.position.x = command.x
                camera.position.y = command.y
                camera.update()
            }
        }
    }

    /**
     * Начатая нейросвязь тянется от клетки, которой на текущей стадии больше нет (сдвинули
     * время, отменили деление) — забываем её.
     *
     * Раньше это делал рендер, прямо при рисовании линии к курсору. Тогда состояние
     * редактора менялось не командой, а кадром, и повтор по журналу без окна расходился
     * бы с тем, что видел игрок. Здесь — после каждой команды: только они и меняют
     * стадию и геном, так что результат тот же.
     */
    private fun dropStaleNeuralLinkStart() {
        if (previousCtrlClicked != -1 && cellReplay.getCellIndex(nextStageTick, previousCtrlClicked) == null) {
            previousCtrlClicked = -1
        }
    }

    /**
     * Запись генома в файл. Тело в этот момент уже выращено в сущностях редактора,
     * поэтому раскладка считается прямо по ним (GenomeBaker.bakeGrown).
     */
    private fun saveCurrentGenome(genomeName: String) {
        val genome = DIGenomeEditorContainer.genomeBaker.bakeGrown(genomeName)

        // Выгрузка топологии для стенда физики. Здесь же, потому что момент тот
        // самый: тело выращено целиком и отработало последний тик, значит координаты
        // это реальная поза покоя. Путь к файлу уходит в лог.
        DIGenomeEditorContainer.bodyExport.exportToFile()

        saveGenome(genome, genomeName)
    }
}
