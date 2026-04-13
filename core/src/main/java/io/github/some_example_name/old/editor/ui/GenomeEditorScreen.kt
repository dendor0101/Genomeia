package io.github.some_example_name.old.editor.ui

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ScreenViewport
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DIGenomeEditorContainer
import io.github.some_example_name.old.editor.commands.CtrlY
import io.github.some_example_name.old.editor.commands.CtrlZ
import io.github.some_example_name.old.editor.commands.FlingScreen
import io.github.some_example_name.old.editor.commands.PanScreen
import io.github.some_example_name.old.editor.commands.ShowDivideDialog
import io.github.some_example_name.old.editor.commands.ShowMutateDialog
import io.github.some_example_name.old.editor.commands.TapScreen
import io.github.some_example_name.old.systems.genomics.genome.Action
import io.github.some_example_name.old.systems.genomics.genome.GenomeJsonReader
import io.github.some_example_name.old.ui.screens.MyGame
import kotlin.Float
import kotlin.system.measureNanoTime


data class GenomeEditorData(
    var currentTick: Int,
    var currentStage: Int
)

class GenomeEditorScreen(
    val game: MyGame
) : Screen, GestureDetector.GestureListener {

    val replayEntity = DIGenomeEditorContainer.replayEntity
    val renderSystem = DIGenomeEditorContainer.editorRenderSystem
    val editorLogicSystem = DIGenomeEditorContainer.editorLogicSystem
    val fileProvider = DIGameGlobalContainer.fileProvider

    val genomeJsonReader: GenomeJsonReader = GenomeJsonReader()
    private lateinit var camera: OrthographicCamera
    private var virtualWidth = 0f
    private var virtualHeight = 0f
    private val stage = Stage(ScreenViewport())
    lateinit var shape: ShapeRenderer

    private var state = GenomeEditorData(
        currentTick = 0,
        currentStage = 0
    )
    private var initialZoom = 0f
    private var currentPinchCenter: Vector2? = null

    var isRestartSimulation = false
    val editorSimulationSystem = DIGenomeEditorContainer.editorSimulationSystem

    private var currentScreenWidth = Gdx.graphics.width
    private var currentScreenHeight = Gdx.graphics.height

    lateinit var menuUiBuilder: MenuUiBuilder

    override fun show() {
        camera = OrthographicCamera().apply {
            setToOrtho(false, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        }
        shape = ShapeRenderer()


        editorSimulationSystem.simulate()

        virtualWidth = Gdx.graphics.width.toFloat()
        virtualHeight = Gdx.graphics.height.toFloat()
        val multiplexer = InputMultiplexer()
        multiplexer.addProcessor(stage)
        multiplexer.addProcessor(GestureDetector(this))
        multiplexer.addProcessor(object : InputAdapter() {
            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                val mouseX = Gdx.input.x.toFloat()
                val mouseY = Gdx.input.y.toFloat()
                val screenPos = Vector3(mouseX, mouseY, 0f)
                val worldBefore = camera.unproject(screenPos.cpy())

                val zoomFactor = if (amountY > 0) 1.05f else 0.95f
                camera.zoom = MathUtils.clamp(camera.zoom * zoomFactor, 0.001f, 1000f)

                camera.update()
                val worldAfter = camera.unproject(screenPos.cpy())
                camera.position.add(worldBefore.x - worldAfter.x, worldBefore.y - worldAfter.y, 0f)
                return true
            }
        })
        Gdx.input.inputProcessor = multiplexer

        renderSystem.create(shape, camera)

        menuUiBuilder = MenuUiBuilder(
            game = game,
            stage = stage,
            editorLogicSystem = editorLogicSystem,
            genomeJsonReader = genomeJsonReader,
            replayEntity = replayEntity,
            renderSystem = renderSystem,
            fileProvider = fileProvider,
            editorSimulationSystem = editorSimulationSystem
        )

        menuUiBuilder.buildEditorMenu()
        camera.position.set(64f, 64f, 0f)
        camera.zoom = 0.01f
        camera.update()
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.50f, 0.62f, 0.64f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        if (state.currentTick != editorLogicSystem.currentTick || state.currentStage != editorLogicSystem.currentStage) {
            state.currentTick = editorLogicSystem.currentTick
            state.currentStage = editorLogicSystem.currentStage

            menuUiBuilder.stageText.setText(editorLogicSystem.currentStage.toString())
            menuUiBuilder.tickText.setText(editorLogicSystem.currentTick.toString())

            menuUiBuilder.setSliderValueProgrammatically(editorLogicSystem.currentTick.toFloat())
            renderSystem.isUpdateBuffer = true
        }

        camera.update()
        renderSystem.render()

        isRestartSimulation = false

        if (Gdx.input.isKeyJustPressed(Input.Keys.Z) && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) {
            editorLogicSystem.putUiCommand(CtrlZ)
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.Y) && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) {
            editorLogicSystem.putUiCommand(CtrlY)
        }

        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            menuUiBuilder.isCtrl = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
            if (!menuUiBuilder.isCtrl) menuUiBuilder.previousCtrlClicked = -1
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            editorLogicSystem.putUiCommand(CtrlY)
        }

        stage.act(delta)
        stage.draw()

        if (editorLogicSystem.uiScreenCommands != null) {
            when (editorLogicSystem.uiScreenCommands) {
                is ShowDivideDialog -> {
                    TODO()
                }
                is ShowMutateDialog -> {
                    TODO()
                }
                null -> {
                    TODO()
                }
            }
            editorLogicSystem.uiScreenCommands = null
        }
    }

    override fun resize(width: Int, height: Int) {
        if (width == currentScreenWidth && height == currentScreenHeight) return
        stage.viewport.update(width, height, true)
        menuUiBuilder.buildEditorMenu()

        menuUiBuilder.setSliderValueProgrammatically(state.currentTick.toFloat())
        menuUiBuilder.stageText.setText(state.currentStage.toString())
        menuUiBuilder.tickText.setText(state.currentTick.toString())
        renderSystem.isUpdateBuffer = true

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
//        shaderManager.dispose()
        shape.dispose()
//        editor.growthProcessor.clearAll()
//        editor.dispose()
    }

    override fun touchDown(
        x: Float,
        y: Float,
        pointer: Int,
        button: Int
    ): Boolean {
        return false
    }

    override fun tap(
        x: Float,
        y: Float,
        count: Int,
        button: Int
    ): Boolean {
        val (touchedCellX, touchedCellY) = screenToWorld(x, y)
        editorLogicSystem.putUiCommand(
            TapScreen(
                x = touchedCellX,
                y = touchedCellY,
                isLeft = button == Input.Buttons.LEFT
            )
        )
        return true
    }

    override fun longPress(x: Float, y: Float) = false

    private fun screenToWorld(screenX: Float, screenY: Float): Pair<Float, Float> {
        val screenPos = Vector3(screenX, screenY, 0f)
        val worldPos = camera.unproject(screenPos)
        return Pair(worldPos.x, worldPos.y)
    }

    override fun fling(
        velocityX: Float,
        velocityY: Float,
        button: Int
    ): Boolean {
        editorLogicSystem.putUiCommand(FlingScreen)
        return false
    }

    override fun pan(
        x: Float,
        y: Float,
        deltaX: Float,
        deltaY: Float
    ): Boolean {
        val (touchedCellX, touchedCellY) = screenToWorld(x, y)
        editorLogicSystem.putUiCommand(
            PanScreen(
                x = touchedCellX,
                y = touchedCellY,
                deltaX = -deltaX * camera.zoom,
                deltaY = deltaY * camera.zoom
            )
        )
        return true
    }

    override fun panStop(x: Float, y: Float, pointer: Int, button: Int) = false

    override fun zoom(initialDistance: Float, distance: Float): Boolean {
        if (currentPinchCenter == null) return false
        val centerX = currentPinchCenter!!.x
        val centerY = currentPinchCenter!!.y
        val screenPos = Vector3(centerX, centerY, 0f)
        val worldBefore = camera.unproject(screenPos.cpy())
        val ratio = initialDistance / distance
        camera.zoom = initialZoom * ratio
        camera.zoom = MathUtils.clamp(camera.zoom, 0.1f, 10f)
        camera.update()
        val worldAfter = camera.unproject(screenPos.cpy())
        camera.position.add(worldBefore.x - worldAfter.x, worldBefore.y - worldAfter.y, 0f)
        return true
    }

    override fun pinch(
        initialPointer1: Vector2?,
        initialPointer2: Vector2?,
        pointer1: Vector2?,
        pointer2: Vector2?
    ): Boolean {
        if (initialPointer1 != null && initialPointer2 != null && currentPinchCenter == null) {
            initialZoom = camera.zoom
        }
        if (pointer1 == null || pointer2 == null) {
            currentPinchCenter = null
            return false
        }
        currentPinchCenter = pointer1.cpy().add(pointer2).scl(0.5f)
        return false
    }

    override fun pinchStop() {
        currentPinchCenter = null
        initialZoom = 0f
    }
}
