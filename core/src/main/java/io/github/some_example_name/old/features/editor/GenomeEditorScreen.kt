package io.github.some_example_name.old.features.editor

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ScreenViewport
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer
import io.github.some_example_name.old.editor.system.logic.CtrlY
import io.github.some_example_name.old.editor.system.logic.CtrlZ
import io.github.some_example_name.old.editor.system.logic.FlingScreen
import io.github.some_example_name.old.editor.system.logic.GoToEndOfTimeLine
import io.github.some_example_name.old.editor.system.logic.PanScreen
import io.github.some_example_name.old.editor.system.logic.TapScreen
import io.github.some_example_name.old.editor.system.logic.TouchDown
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.currentTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.lastTick
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.previousCtrlClicked
import io.github.some_example_name.old.editor.di.DIGenomeEditorContainer.uiScreenCommands
import io.github.some_example_name.old.editor.system.logic.ShowChangeRemoveDialog
import io.github.some_example_name.old.editor.system.logic.ShowDivideDialog
import io.github.some_example_name.old.editor.system.logic.ShowMutateDialog
import io.github.some_example_name.old.editor.system.logic.ShowMutateOrDivideDialog
import io.github.some_example_name.old.editor.system.logic.UiScreenCommands
import io.github.some_example_name.old.core.ui.CameraControl

data class GenomeEditorData(
    var currentTick: Int,
    var lastTick: Int
)

class GenomeEditorScreen(
    val genomeName: String?
) : Screen {

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
            editorLogicSystem.putUiCommand(TapScreen(x, y, isLeft, composeGenomeEditor.isCtrl))
        },
        onFling = {
            editorLogicSystem.putUiCommand(FlingScreen)
        },
        onPan = { x, y, dx, dy ->
            editorLogicSystem.putUiCommand(PanScreen(x, y, dx, dy))
        }
    )

    override fun show() {
        editorSimulationSystem.reinitGenome(genomeName)
        currentTick = 0
        editorLogicSystem.restartSimulation()

        Gdx.input.inputProcessor = cameraControl.getInputMultiplexer(stage)
        renderSystem.create(shape, camera)
        editorLogicSystem.bindToScreen(camera)

        composeGenomeEditor.composeGenomeEditor(
            stage,
            editorSimulationSystem,
            renderSystem,
            editorLogicSystem
        )
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

        if (Gdx.input.isKeyJustPressed(Input.Keys.Z) && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) {
            editorLogicSystem.putUiCommand(CtrlZ)
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.Y) && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) {
            editorLogicSystem.putUiCommand(CtrlY)
        }

        if (Gdx.app.type == Application.ApplicationType.Desktop || Gdx.app.type == Application.ApplicationType.WebGL) {
            composeGenomeEditor.isCtrl = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
            if (!composeGenomeEditor.isCtrl) previousCtrlClicked = -1
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            editorLogicSystem.putUiCommand(GoToEndOfTimeLine)
        }

        actionDialog(uiScreenCommands)
        uiScreenCommands = null

        stage.act(delta)
        stage.draw()
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
                onMutate = { editorLogicSystem.putUiCommand(it) }
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
        stage.dispose()
        shape.dispose()
    }
}
