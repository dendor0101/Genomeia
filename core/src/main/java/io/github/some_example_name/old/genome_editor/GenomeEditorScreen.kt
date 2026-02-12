package io.github.some_example_name.old.genome_editor

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.input.GestureDetector
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.Timer
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSlider
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.genome.json.GenomeJsonReader
import io.github.some_example_name.old.genome.json.write.domainToJson
import io.github.some_example_name.old.genome_editor.GenomeEditorGrowthProcessor.Companion.START_EDITOR_CELL_X
import io.github.some_example_name.old.genome_editor.GenomeEditorGrowthProcessor.Companion.START_EDITOR_CELL_Y
import io.github.some_example_name.old.genome_editor.commands.AddNeuralLinkCommand
import io.github.some_example_name.old.genome_editor.commands.ChangeDivideCommand
import io.github.some_example_name.old.genome_editor.commands.DivideCellCommand
import io.github.some_example_name.old.genome_editor.commands.MutateCellCommand
import io.github.some_example_name.old.genome_editor.commands.RemoveCellCommand
import io.github.some_example_name.old.genome_editor.commands.getAllCloseNeighboursEditor
import io.github.some_example_name.old.genome_editor.commands.moveCell
import io.github.some_example_name.old.genome_editor.commands.tryToDivideCell
import io.github.some_example_name.old.genome_editor.dialog.ChangeRemoveActionDialog
import io.github.some_example_name.old.genome_editor.dialog.DivideActionDialog
import io.github.some_example_name.old.genome_editor.dialog.MutateActionDialog
import io.github.some_example_name.old.genome_editor.dialog.MutateOrDivideDialog
import io.github.some_example_name.old.good_one.CellSimulation
import io.github.some_example_name.old.good_one.pikSounds
import io.github.some_example_name.old.good_one.utils.drawArrowWithRotationAngle
import io.github.some_example_name.old.good_one.utils.drawTriangleMiddle
import io.github.some_example_name.old.platform_flag.FileProvider
import io.github.some_example_name.old.screens.GlobalSettings
import io.github.some_example_name.old.screens.MenuScreen
import io.github.some_example_name.old.screens.MyGame
import io.github.some_example_name.old.screens.applyCustomFont
import io.github.some_example_name.old.screens.applyCustomFontMedium
import io.github.some_example_name.old.shader_instancing.InstancingTextureShaderManager
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import kotlin.Float

data class GenomeEditorData(
    var currentTick: Int,
    var currentStage: Int
)

interface RestartSimulationCallback {
    fun restartSimulation(stage: Int)
}

enum class LastActionType {
    DIVIDE, MUTATE, CHANGE, DELETE
}

var timeForProcessingActionStart = System.nanoTime()
var timeForProcessingActionResult = 0f

class GenomeEditorScreen(
    val multiPlatformFileProvider: FileProvider,
    val game: MyGame,
    val genomeName: String? = null,
    val bundle: I18NBundle
) : Screen, GestureDetector.GestureListener, RestartSimulationCallback {
    private var defaultActionType: LastActionType? = null
    private var defaultAction: Action? = null
    val editor = GenomeEditorManager(genomeName)
    val commandManager = CommandManager(this)
    val genomeJsonReader: GenomeJsonReader = GenomeJsonReader()
    private lateinit var camera: OrthographicCamera
    private var virtualWidth = 0f
    private var virtualHeight = 0f
    private val stage = Stage(ScreenViewport())
    private lateinit var shaderManager: InstancingTextureShaderManager
    lateinit var shape: ShapeRenderer
    lateinit var timeSlider: VisSlider
    lateinit var stageText: VisLabel
    lateinit var tickText: VisLabel

//    lateinit var fpsText: VisLabel
    private var state = GenomeEditorData(
        currentTick = 0,
        currentStage = 0
    )
    private var initialZoom = 0f
    private var currentPinchCenter: Vector2? = null
    private var isDruggingCamera = false
    private var grabbedCellIndex = -1
    private var lastGrabbedCellX = 0.0f
    private var lastGrabbedCellY = 0.0f
    // Флаг, чтобы отслеживать программное изменение
    private var isProgrammaticChange = false
    private var autoLinking = true
    private var showPhysicalLink = true
    private var isCtrl = false
    private var isRightClick = false
    private var previousCtrlClicked = -1
    var isUpdate = true
    var isRestartSimulation = false

    private var currentScreenWidth = Gdx.graphics.width
    private var currentScreenHeight = Gdx.graphics.height

    // Установка значения слайдера программно (например, из кода)
    // Setting the slider value programmatically (for example, from code)
    private fun setSliderValueProgrammatically(value: Float) {
        isProgrammaticChange = true
        timeSlider.value = value
        isProgrammaticChange = false
    }

    override fun show() {
        camera = OrthographicCamera().apply {
            setToOrtho(false, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        }
        shape = ShapeRenderer()
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
                camera.zoom *= if (amountY > 0) 1.05f else 0.95f
                camera.zoom = MathUtils.clamp(camera.zoom, 0.1f, 10f)
                camera.update()
                val worldAfter = camera.unproject(screenPos.cpy())
                camera.position.add(worldBefore.x - worldAfter.x, worldBefore.y - worldAfter.y, 0f)
                return true
            }
        })
        Gdx.input.inputProcessor = multiplexer


        shaderManager = InstancingTextureShaderManager(camera, this)

        buildEditorMenu()
        camera.position.set(START_EDITOR_CELL_X, START_EDITOR_CELL_Y, 0f)
        camera.update()
    }

    fun saveDialog(isGoToMenu: Boolean) {
        val genome = editor.growthProcessor.currentGenome.domainToJson()

        SaveGenomeDialog(
            genomeJsonReader = genomeJsonReader,
            genome = genome,
            onSaveAndTest = { genomeNameForTest ->
                game.screen.dispose()
                game.screen = CellSimulation(
                    multiPlatformFileProvider = multiPlatformFileProvider,
                    game = game,
                    bundle = bundle,
                    map = null,
                    genomeName = genomeNameForTest
                )
            },
            onGoMenu = {
                game.screen.dispose()
                game.screen = MenuScreen(
                    multiPlatformFileProvider = multiPlatformFileProvider,
                    game = game,
                )
            },
            game = game,
            bundle = bundle,
            isGoToMenu = isGoToMenu
        ).show(stage)
    }

    fun buildEditorMenu() {
        val density = Gdx.graphics.density
        stage.clear()
        val root = Table()
        root.setFillParent(true)
        stage.addActor(root)

        val prevStageButton = VisTextButton(" << ")
        game.applyCustomFont(prevStageButton)
        val prevTickButton = VisTextButton(" < ")
        game.applyCustomFont(prevTickButton)
        val nextTickButton = VisTextButton(" > ")
        game.applyCustomFont(nextTickButton)
        val nextStageButton = VisTextButton(" >> ")
        game.applyCustomFont(nextStageButton)
        stageText = VisLabel("0")
        game.applyCustomFontMedium(stageText)
        stageText.setAlignment(Align.left) // Center-align text
        tickText = VisLabel("0")
        game.applyCustomFontMedium(tickText)
//        fpsText = VisLabel("0 FPS")
//        game.applyCustomFontMedium(fpsText)

        timeSlider = VisSlider(0f, (editor.replay.size - 1).toFloat(), 1f, false)
        timeSlider.value = 0f

        timeSlider.addListener { event ->
            if (!isProgrammaticChange && event is ChangeListener.ChangeEvent) {
                if (!timeSlider.isDragging) {
                    editor.stateChanged(false)
                } else {
                    editor.state.currentTick = timeSlider.value.toInt()
                    editor.state.currentStage = editor.stageByTick.getStage(editor.state.currentTick)
                    editor.stateChanged(true)
                }
            }
            false
        }

        val goToMenuButton = VisTextButton(bundle.get("button.menu"))
        goToMenuButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                saveDialog(true)
            }
        })

        val chooseColorButton = VisTextButton(bundle.get("button.saveGenome"))
        chooseColorButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                saveDialog(false)
            }
        })

        val showPhysicalLinkButton = VisTextButton(bundle.get("button.showPhysicalLink"), "toggle")
        showPhysicalLinkButton.isChecked = showPhysicalLink

        // Toggle кнопка
        showPhysicalLinkButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                showPhysicalLink = showPhysicalLinkButton.isChecked
            }
        })


        val ctrlZ = VisTextButton("Ctrl+z")
        ctrlZ.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                commandManager.undo()
            }
        })

        val ctrlY = VisTextButton("Ctrl+y")
        ctrlY.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                commandManager.redo()
            }
        })

        val ctrl = VisTextButton(bundle.get("button.neural-linking"), "toggle")
        ctrl.isChecked = isCtrl
        ctrl.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                isCtrl = ctrl.isChecked
                if (!isCtrl) previousCtrlClicked = -1
            }
        })

        val rightClick = VisTextButton(bundle.get("button.performLastAction"), "toggle")
        rightClick.isChecked = isRightClick
        rightClick.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                isRightClick = rightClick.isChecked
            }
        })

        val buttons = mutableListOf(
            goToMenuButton, chooseColorButton, showPhysicalLinkButton
        )
        if (Gdx.app.type == Application.ApplicationType.Android) {
            buttons.add(ctrlZ)
            buttons.add(ctrlY)
            buttons.add(ctrl)
            buttons.add(rightClick)
        }

        val controls = Table()
        controls.defaults().pad(0f).space(0f) // Remove default padding/spacing

        // Nested table for the top row with wrapping
        val topControls = Table()
        topControls.defaults().pad(8f * density).center() // Pad 8f around each cell, align center

        var currentWidth = 0f
        var rowTable = Table()
        rowTable.defaults().pad(8f * density).center()

        for (button in buttons) {
            game.applyCustomFont(button) // Uncomment if you have this function
            val prefWidth = button.prefWidth + 16f * density // Approximate with padding
            if (currentWidth + prefWidth > Gdx.graphics.width && currentWidth > 0f) {
                topControls.add(rowTable).growX().center().row()
                rowTable = Table()
                rowTable.defaults().padLeft(8f * density).padRight(8f * density).center()
                currentWidth = 0f
            }
            rowTable.add(button).height(25f * density)
            currentWidth += prefWidth
        }
        if (rowTable.hasChildren()) {
            topControls.add(rowTable).growX().center()
        }

        // Add topControls to controls, left-aligned
        controls.add(topControls).center().pad(16f * density).row()

        // Spacer
        controls.add().growY().row()

        // Nested table for the labels row
        val labelsRow = Table()

        val labelsRow1 = Table()
        val labelsRow2 = Table()


//        labelsRow1.defaults().pad(0f).space(0f)
        val tick = VisLabel(bundle.get("button.tick"))
        game.applyCustomFontMedium(tick)
        labelsRow1.add(tick).padRight(4f * density).padLeft(40f * density)
        labelsRow1.add(tickText).size(40f * density, 30f * density).padRight(16f * density)
        val stage = VisLabel(bundle.get("button.stage"))
        game.applyCustomFontMedium(stage)
        labelsRow2.add(stage).padRight(4f * density).padLeft(0f)
        labelsRow2.add(stageText).size(40f * density, 30f * density)

        labelsRow.add(labelsRow1)//.row()
        labelsRow.add(labelsRow2)
        // Add labelsRow to controls, left-aligned
        controls.add(labelsRow).center().pad(8f * density).row()

        // Nested table for the slider row (original buttonRow)
        val sliderRow = Table()
        sliderRow.defaults().pad(0f).space(0f) // Ensure no extra padding/spacing
        sliderRow.add(prevStageButton).size(30f * density, 30f * density).padRight(8f * density)
        sliderRow.add(prevTickButton).size(30f * density, 30f * density).padRight(8f * density)
        sliderRow.add(timeSlider).growX() // Slider takes remaining width
        sliderRow.add(nextTickButton).size(30f * density, 30f * density).padLeft(8f * density)
        sliderRow.add(nextStageButton).size(30f * density, 30f * density).padLeft(8f * density)

        // Add sliderRow to controls, stretching to full width
        controls.add(sliderRow).growX().pad(32f * density).row()

        // Add controls to root
        root.add(controls).expand().fill()

        prevStageButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                if (editor.state.currentStage > 0) {
                    editor.state.currentStage--
                    editor.state.currentTick = editor.stages[editor.state.currentStage]
                    setSliderValueProgrammatically(editor.state.currentTick.toFloat())
                    editor.stateChanged(false)
                }
            }
        })

        nextStageButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                if (editor.state.currentStage < editor.stages.size - 1) {
                    editor.state.currentStage++
                    editor.state.currentTick = editor.stages[editor.state.currentStage]
                    setSliderValueProgrammatically(editor.state.currentTick.toFloat())
                    editor.stateChanged(false)
                }
            }
        })

        prevTickButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                if (editor.state.currentTick > 0) {
                    editor.state.currentTick--
                    editor.state.currentStage = editor.stageByTick.getStage(editor.state.currentTick)
                    setSliderValueProgrammatically(editor.state.currentTick.toFloat())
                    editor.stateChanged(false)
                }
            }
        })

        nextTickButton.addListener(object : ClickListener() {
            private var pressStartTime: Long = 0
            private val longPressThreshold = 500L // 0.5 секунды
            private var isLongPressing = false
            private var longPressTask: Timer.Task? = null

            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                pressStartTime = System.currentTimeMillis()
                isLongPressing = false

                // Запускаем задачу для долгого нажатия
                longPressTask = Timer.schedule(object : Timer.Task() {
                    override fun run() {
                        isLongPressing = true
                        if (editor.state.currentTick < editor.replay.size - 1) {
                            editor.state.currentTick++
                            editor.state.currentStage = editor.stageByTick.getStage(editor.state.currentTick)
                            setSliderValueProgrammatically(editor.state.currentTick.toFloat())
                            editor.stateChanged(true)
                        }
                    }
                }, 0.5f, 0.0083333f) // Старт через 0.5 сек, повтор каждые 50 мс

                return true
            }

            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                longPressTask?.cancel()
                longPressTask = null

                val pressDuration = System.currentTimeMillis() - pressStartTime
                if (!isLongPressing && pressDuration < longPressThreshold) {
                    // Обычный клик (исходная логика)
                    if (editor.state.currentTick < editor.replay.size - 1) {
                        editor.state.currentTick++
                        editor.state.currentStage = editor.stageByTick.getStage(editor.state.currentTick)
                        setSliderValueProgrammatically(editor.state.currentTick.toFloat())
                        editor.stateChanged(false)
                    }
                } else if (isLongPressing) {
                    // Завершение долгого нажатия
                    setSliderValueProgrammatically(editor.state.currentTick.toFloat())
                    editor.stateChanged(false)
                }

                super.touchUp(event, x, y, pointer, button)
            }
        })
    }

    override fun restartSimulation(stage: Int) {
        grabbedCellIndex = -1
        editor.restartSimulation(stage)
        timeSlider.setRange(0f, (editor.replay.size - 1).toFloat())
//        editor.state.currentStage = stage
//        editor.state.currentTick = editor.stages[editor.state.currentStage]
//        setSliderValueProgrammatically(editor.state.currentTick.toFloat())
        isUpdate = true
        isRestartSimulation = true
    }

    override fun render(delta: Float) {
        if (state.hashCode() != editor.state.hashCode() && state != editor.state) {
            //Как бы подписка на изменение ui
            // Like a subscription to UI changes? This translation seems off...
            state = editor.state.copy()
            stageText.setText(editor.state.currentStage.toString())
            tickText.setText(editor.state.currentTick.toString())
            isUpdate = true
        }

//        fpsText.setText(Gdx.graphics.framesPerSecond.toString() + " FPS\n${timeForProcessingActionResult} ms")

        camera.update()
        shaderManager.render(editor.editorCells, true, grabbedCellIndex, isRestartSimulation)
        isUpdate = false
        isRestartSimulation = false

        shape.color = Color.WHITE
        shape.projectionMatrix = camera.combined
        shape.begin(ShapeRenderer.ShapeType.Line)
        shape.rect(
            0f,
            0f,
            editor.gridManager.gridCellWidthSize * CELL_SIZE,
            editor.gridManager.gridCellHeightSize * CELL_SIZE
        )

        Gdx.gl.glLineWidth(2f)

        editor.editorLinks.forEach {
            when (it.isNeuralTo2) {
                true -> {
                    shape.color = Color.CYAN
                    shape.drawTriangleMiddle(
                        it.x1,
                        it.y1,
                        it.x2,
                        it.y2
                    )
                }
                false -> {
                    shape.color = Color.CYAN
                    shape.drawTriangleMiddle(
                        it.x2,
                        it.y2,
                        it.x1,
                        it.y1
                    )
                }
                null -> {
                    shape.color = Color.RED
                }
            }
            if (showPhysicalLink || it.isNeuralTo2 != null) {
                shape.line(
                    it.x1,
                    it.y1,
                    it.x2,
                    it.y2
                )
            }
        }

        editor.specialCells.forEach {
            when (it.cellType) {
                6 -> {
                    shape.color = Color.CYAN
                    shape.circle(
                        it.x,
                        it.y,
                        150f
                    )
                }

                14 -> {
                    shape.color = Color.CYAN
                    shape.drawArrowWithRotationAngle(
                        startX = it.x,
                        startY = it.y,
                        baseAngle = it.angle,
                        length = it.length,
                        isDrawWithoutTriangle = true,
                    )
                }

                3, 9, 15, 19, 21 -> {
                    shape.color = Color.CYAN
                    shape.drawArrowWithRotationAngle(
                        startX = it.x,
                        startY = it.y,
                        baseAngle = it.angle,
                        length = 15f
                    )
                }
            }
        }

        if (previousCtrlClicked != -1 && previousCtrlClicked < editor.editorCells.size) {
            val cell = editor.editorCells[previousCtrlClicked]
            shape.color = Color.CYAN
            shape.circle(cell.x, cell.y, 5f)
        } else {
            previousCtrlClicked = -1
        }

        shape.end()

        if (Gdx.input.isKeyJustPressed(Input.Keys.Z) && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) {
            commandManager.undo()
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.Y) && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) {
            commandManager.redo()
        }

        if (Gdx.app.type == Application.ApplicationType.Desktop) {
            isCtrl = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
            if (!isCtrl) previousCtrlClicked = -1
        }

        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            editor.state.currentTick = editor.replay.size - 1
            editor.state.currentStage = editor.stageByTick.getStage(editor.state.currentTick)
            setSliderValueProgrammatically(editor.state.currentTick.toFloat())
            editor.stateChanged(false)
        }

        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        if (width == currentScreenWidth && height == currentScreenHeight) return
        stage.viewport.update(width, height, true) // Update stage viewport if needed
        buildEditorMenu() // Rebuild menu to handle wrapping on resize

        //Update text and slider
        setSliderValueProgrammatically(editor.state.currentTick.toFloat())
        state = editor.state.copy()
        stageText.setText(editor.state.currentStage.toString())
        tickText.setText(editor.state.currentTick.toString())
        isUpdate = true

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
        shaderManager.dispose()
        shape.dispose()
        editor.growthProcessor.clearAll()
        editor.dispose()
    }

    override fun touchDown(
        x: Float,
        y: Float,
        pointer: Int,
        button: Int
    ): Boolean {
        return false
    }

    fun showDivideDialog(
        clickedCell: EditorCell,
        clickedIndex: Int,
        newDividedCellPosition: Pair<Float, Float>
    ) {
        val dialogDivide = DivideActionDialog(
            clickedCell = clickedCell,
            newDividedCellPosition = newDividedCellPosition,
            game = game,
            bundle = bundle,
            onDivide = { action ->
                defaultActionType = LastActionType.DIVIDE
                defaultAction = action.copy(
                    id = -1,
                    angle = null,
                    physicalLink = hashMapOf()
                )
                println(action.toString())
                tryToDivide(clickedIndex, newDividedCellPosition, action.copy())
            }
        )
        dialogDivide.show(stage)
    }

    fun showMutateDialog(
        clickedCell: EditorCell,
        clickedIndex: Int
    ) {
        val dialogMutate = MutateActionDialog(
            clickedCell = clickedCell,
            cellFullReplay = editor.growthProcessor.simulationFullReplay[state.currentStage],
            clickedIndex = clickedIndex,
            game = game,
            bundle = bundle,
            onMutate = { action ->
                defaultActionType = LastActionType.MUTATE
                defaultAction = action.copy(
                    id = -1,
                    angle = null,
                    physicalLink = hashMapOf()
                )
                println(action.toString())
                tryToMutate(clickedIndex, action.copy())
            }
        )
        dialogMutate.show(stage)
    }

    override fun tap(
        x: Float,
        y: Float,
        count: Int,
        button: Int
    ): Boolean {
        val (touchedCellX, touchedCellY) = screenToWorld(x, y)
        val clickedIndex = editor.getClickedCellIndex(touchedCellX, touchedCellY) ?: return true

        val clickedCell = editor.editorCells[clickedIndex]
        grabbedCellIndex = -1
        return if (Gdx.app.type == Application.ApplicationType.Desktop) {
            when (button) {
                Input.Buttons.LEFT -> {
                    leftClick(clickedIndex, clickedCell)
                    true
                }

                Input.Buttons.RIGHT -> {
                    if (!isCtrl) rightClick(clickedIndex, clickedCell)
                    true
                }

                else -> {
                    false
                }
            }
        } else {
            if (!isCtrl) {
                if (isRightClick) {
                    rightClick(clickedIndex, clickedCell)
                } else {
                    leftClick(clickedIndex, clickedCell)
                }
            } else {
                leftClick(clickedIndex, clickedCell)
            }
            true
        }
    }

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
        pikSounds.random().play(GlobalSettings.SOUND_VOLUME / 100f)
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

    override fun longPress(x: Float, y: Float): Boolean {
        return false
    }

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
        if (grabbedCellIndex != -1) {
            val genomeStageInstruction = editor.growthProcessor.currentGenome.genomeStageInstruction
            timeForProcessingActionStart = System.nanoTime()
            moveCell(
                editor,
                grabbedCellIndex,
                lastGrabbedCellX,
                lastGrabbedCellY,
                commandManager,
                editor.state.currentStage,
                autoLinking,
                genomeStageInstruction
            )
        }
        isDruggingCamera = false
        return false
    }

    override fun pan(
        x: Float,
        y: Float,
        deltaX: Float,
        deltaY: Float
    ): Boolean {
        if (grabbedCellIndex == -1) {
            if (!isDruggingCamera) {
                val (touchedCellX, touchedCellY) = screenToWorld(x, y)
                val clickedIndex = editor.getClickedCellIndex(touchedCellX, touchedCellY)
                if (clickedIndex != null && editor.editorCells[clickedIndex].isJustAdded) {
                    grabbedCellIndex = clickedIndex
                    lastGrabbedCellX = editor.editorCells[clickedIndex].x
                    lastGrabbedCellY = editor.editorCells[clickedIndex].y
                    isDruggingCamera = false
                } else {
                    grabbedCellIndex = -1
                    lastGrabbedCellX = 0.0f
                    lastGrabbedCellY = 0.0f
                    isDruggingCamera = true
                    camera.translate(-deltaX * camera.zoom, deltaY * camera.zoom, 0f)
                }
            } else {
                camera.translate(-deltaX * camera.zoom, deltaY * camera.zoom, 0f)
            }
        } else {

            val parentIndex = editor.editorCells[grabbedCellIndex].parentIndex
            val parentCellX = editor.editorCells[parentIndex].x
            val parentCellY = editor.editorCells[parentIndex].y
            val (childCellX, childCellY) = screenToWorld(x, y)
            val (finalX, finalY) = setMinMaxDistForChildCellToParent(childCellX, childCellY, parentCellX, parentCellY)

            // Сохраняем итоговые координаты
            // We save the final coordinates
            editor.editorCells[grabbedCellIndex].x = finalX
            editor.editorCells[grabbedCellIndex].y = finalY
            isUpdate = true
        }
        return true
    }

    override fun panStop(
        x: Float,
        y: Float,
        pointer: Int,
        button: Int
    ): Boolean {
        return false
    }

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
