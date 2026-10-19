package io.github.some_example_name.old.features.editor

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ScreenViewport
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.bug.ReplayTarget
import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.editor.system.logic.UiEditorCommands
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.editor.system.logic.CtrlY
import io.github.some_example_name.old.editor.system.logic.CtrlZ
import io.github.some_example_name.old.editor.system.logic.FlingScreen
import io.github.some_example_name.old.editor.system.logic.GoToEndOfTimeLine
import io.github.some_example_name.old.editor.system.logic.PanScreen
import io.github.some_example_name.old.editor.system.logic.TapScreen
import io.github.some_example_name.old.editor.system.logic.TouchDown
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.isNeuralLinking
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.isRightClick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.previousCtrlClicked
import io.github.some_example_name.old.editor.system.logic.SetNeuralLinking
import io.github.some_example_name.old.editor.system.logic.ZoomCamera
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.uiScreenCommands
import io.github.some_example_name.old.editor.system.logic.ShowChangeRemoveDialog
import io.github.some_example_name.old.editor.system.logic.ShowDivideDialog
import io.github.some_example_name.old.editor.system.logic.ShowMutateDialog
import io.github.some_example_name.old.editor.system.logic.ShowMutateOrDivideDialog
import io.github.some_example_name.old.editor.system.logic.UiScreenCommands
import io.github.some_example_name.old.core.ui.CameraControl
import io.github.some_example_name.old.core.ui.KeyboardFocus
import io.github.some_example_name.old.core.ui.keepWindowsOnScreen
import io.github.some_example_name.old.commands.GoMenu
import io.github.some_example_name.old.commands.GoReplace
import io.github.some_example_name.old.core.DIGameGlobalContainer.navigationCommandsManager
import io.github.some_example_name.old.editor.system.logic.TutorialNextStep
import io.github.some_example_name.old.editor.tutorial.EditorTutorial
import io.github.some_example_name.old.editor.tutorial.OrganismSnapshot
import io.github.some_example_name.old.editor.tutorial.TutorialSteps

data class GenomeEditorData(
    var currentTick: Int,
    var lastTick: Int
)

/**
 * Редактор генома. [isTutorial] — он же в обучении (GoTutorial): новый геном, поверх —
 * панель шагов, а задания засчитывает EditorTutorial по командам редактора.
 */
class GenomeEditorScreen(
    val genomeName: String?,
    val isTutorial: Boolean = false
) : Screen, ReplayTarget {

    private val renderSystem = DIGenomeEditorContainer.editorRenderSystem
    private val editorLogicSystem = DIGenomeEditorContainer.editorLogicSystem
    private val commandEditorStackManager = DIGenomeEditorContainer.commandEditorStackManager

    private val camera = OrthographicCamera().apply {
        setToOrtho(false, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
    }
    private val stage = Stage(ScreenViewport())
    private val shape = ShapeRenderer()
    private val composeGenomeEditor = ComposeGenomeEditor()

    private var state = GenomeEditorData(
        currentTick = 0,
        lastTick = 0
    )

    private var tutorial: EditorTutorial? = null

    /** Проход ввода к панели обучения поверх окон (TutorialPanel.inputBridge). */
    private var tutorialInputBridge: Actor? = null

    /** Панель обучения справа: при пересборке вёрстки переезжает в новую, а не создаётся заново. */
    private var tutorialPanel: TutorialPanel? = null

    var isRestartSimulation = false
    val editorSimulationSystem = DIGenomeEditorContainer.editorSimulationSystem

    private var currentScreenWidth = Gdx.graphics.width
    private var currentScreenHeight = Gdx.graphics.height

    private val cameraControl = CameraControl(
        camera = camera,
        onTouchDown = { x, y, _ ->
            editorLogicSystem.putUiCommand(TouchDown(x, y))
        },
        onTap = { x, y, isLeft ->
            // На телефоне кнопки мыши нет: «правым кликом» там считается нажатие в режиме
            // «повторить последнее действие», а в режиме нейросвязей клик всегда левый.
            // Решается здесь, а не в обработчике: команда не должна зависеть от платформы.
            val isDesktop = Gdx.app.type == Application.ApplicationType.Desktop
            val isLeftClick = if (isDesktop) isLeft else isNeuralLinking || !isRightClick
            editorLogicSystem.putUiCommand(TapScreen(x, y, isLeftClick, isNeuralLinking))
        },
        onFling = {
            editorLogicSystem.putUiCommand(FlingScreen)
        },
        onPan = { x, y, dx, dy ->
            editorLogicSystem.putUiCommand(PanScreen(x, y, dx, dy))
        },
        onZoom = { zoom, x, y ->
            editorLogicSystem.putUiCommand(ZoomCamera(zoom, x, y))
        }
    )

    override fun show() {
        editorSimulationSystem.reinitGenome(genomeName)
        currentTick = 0
        // Редактор открывается с чистым режимом нейросвязей: состояние живёт в контейнере
        // и иначе пережило бы прошлый заход.
        isNeuralLinking = false
        previousCtrlClicked = -1
        editorLogicSystem.restartSimulation()

        Gdx.input.inputProcessor = cameraControl.getInputMultiplexer(stage)
        renderSystem.create(shape, camera)
        editorLogicSystem.bindToScreen(camera)

        tutorialPanel = startTutorial()
        compose()
    }

    private fun compose() {
        composeGenomeEditor.composeGenomeEditor(
            stage,
            editorSimulationSystem,
            editorLogicSystem,
            renderSystem,
            sidePanel = tutorialPanel?.root
        )
        // Свежая вёрстка показывает тик 0: пусть render заново выставит тик и шкалу.
        state.currentTick = -1
        state.lastTick = -1
    }

    /**
     * Обучение — только в редакторе, открытом командой GoTutorial; обычный редактор
     * снимает прошлое обучение: контейнер редактора общий для всех заходов.
     */
    private fun startTutorial(): TutorialPanel? {
        if (!isTutorial) {
            editorLogicSystem.tutorial = null
            return null
        }
        // История прошлого генома к учебному не относится, а задания считают её правки.
        commandEditorStackManager.clear()
        val tutorial = EditorTutorial(TutorialSteps.all) {
            OrganismSnapshot.of(DIGenomeEditorContainer.cellEntity, DIGenomeEditorContainer.neuralLinkEntity)
        }
        this.tutorial = tutorial
        editorLogicSystem.tutorial = tutorial

        val panel = TutorialPanel(
            tutorial = tutorial,
            onNext = { editorLogicSystem.putUiCommand(TutorialNextStep) },
            onFinish = { navigationCommandsManager.performCommand(GoReplace(GoMenu)) }
        )
        tutorial.onChanged = panel::refresh
        tutorialInputBridge = panel.inputBridge.also(stage::addActor)
        return panel
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.10f, 0.12f, 0.14f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        if (state.currentTick != currentTick) {
            state.currentTick = currentTick

            composeGenomeEditor.tickLabel.setText("${bundle.get("button.tick")}$currentTick")
            composeGenomeEditor.isProgrammaticChange = true
            composeGenomeEditor.timeSlider.value = currentTick.toFloat()
            composeGenomeEditor.isProgrammaticChange = false
            renderSystem.isUpdateBuffer = true
        }

        if (state.lastTick != lastTick) {
            composeGenomeEditor.isProgrammaticChange = true
            composeGenomeEditor.timeSlider.setRange(0f, lastTick.toFloat())
            composeGenomeEditor.isProgrammaticChange = false
            state.lastTick = lastTick
        }

        val disabledUndo = commandEditorStackManager.undoStack.isEmpty()
        if (composeGenomeEditor.undoButton.isDisabled != disabledUndo) {
            composeGenomeEditor.undoButton.isDisabled = disabledUndo
        }

        val disabledRedo = commandEditorStackManager.redoStack.isEmpty()
        if (composeGenomeEditor.redoButton.isDisabled != disabledRedo) {
            composeGenomeEditor.redoButton.isDisabled = disabledRedo
        }

        camera.update()

        val (touchedCellX, touchedCellY) = cameraControl.getInput()
        renderSystem.render(touchedCellX, touchedCellY)

        isRestartSimulation = false

        // Пока игрок печатает (имя генома, описание бага), клавиши — тексту, а не редактору:
        // иначе пробел в имени генома перематывал бы таймлайн, а Ctrl+Z отменял правку генома.
        val typing = KeyboardFocus.isTyping(stage)

        if (!typing && Gdx.input.isKeyJustPressed(Input.Keys.Z) && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) {
            editorLogicSystem.putUiCommand(CtrlZ)
        }
        if (!typing && Gdx.input.isKeyJustPressed(Input.Keys.Y) && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) {
            editorLogicSystem.putUiCommand(CtrlY)
        }

        // На десктопе режим нейросвязей — пока зажат Ctrl. Команда только на смену
        // состояния: опрос каждый кадр, а журналу нужно нажатие, а не кадр.
        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            val ctrl = !typing && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
            if (ctrl != isNeuralLinking) editorLogicSystem.putUiCommand(SetNeuralLinking(ctrl))
        }

        if (!typing && Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            editorLogicSystem.putUiCommand(GoToEndOfTimeLine)
        }

        // При повторе бага окна действий не открываются: что в них выбрали, придёт
        // следующей командой из записи, а закрыть их было бы некому.
        if (!BugReplay.isActive) actionDialog(uiScreenCommands)
        uiScreenCommands = null

        // Окна добавляются на сцену позже моста — он поднимается над ними. toFront у того,
        // кто уже последний, ничего не делает.
        tutorialInputBridge?.toFront()

        stage.act(delta)
        stage.draw()
    }

    override fun replay(command: GameCommand): Boolean {
        if (command !is UiEditorCommands) return false
        editorLogicSystem.putUiCommand(command)
        return true
    }

    private fun actionDialog(uiScreenCommands: UiScreenCommands?) {
        when (uiScreenCommands) {
            is ShowChangeRemoveDialog -> stage.changeRemoveActionDialog(
                command = uiScreenCommands,
                onRemove = { editorLogicSystem.putUiCommand(it) },
                onChange = { editorLogicSystem.putUiCommand(it) }
            )
            is ShowDivideDialog -> stage.divideActionDialog(
                command = uiScreenCommands,
                onDivide = { editorLogicSystem.putUiCommand(it) }
            )
            is ShowMutateDialog -> stage.mutateActionDialog(
                command = uiScreenCommands,
                onMutate = { editorLogicSystem.putUiCommand(it) },
                onCancelMutation = { editorLogicSystem.putUiCommand(it) }
            )
            is ShowMutateOrDivideDialog -> stage.mutateOrDivideDialog(
                command = uiScreenCommands,
                onDivide = { editorLogicSystem.putUiCommand(it)},
                onMutate = { editorLogicSystem.putUiCommand(it)},
            )
            null -> {}
        }
    }

    override fun resize(width: Int, height: Int) {
        if (width == currentScreenWidth && height == currentScreenHeight) return
        stage.viewport.update(width, height, true)
        compose()
        stage.keepWindowsOnScreen()
        renderSystem.isUpdateBuffer = true
        renderSystem.resize(width, height)

        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
        currentScreenWidth = width
        currentScreenHeight = height
    }

    override fun pause() { }

    override fun resume() { }

    override fun hide() { }

    override fun dispose() {
        if (tutorial != null && editorLogicSystem.tutorial === tutorial) editorLogicSystem.tutorial = null
        stage.dispose()
        shape.dispose()
    }
}
