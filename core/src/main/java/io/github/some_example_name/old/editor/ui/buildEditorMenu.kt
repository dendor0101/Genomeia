package io.github.some_example_name.old.editor.ui

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Timer
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSlider
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.editor.commands.CtrlY
import io.github.some_example_name.old.editor.commands.CtrlZ
import io.github.some_example_name.old.editor.commands.NextStageButtonTap
import io.github.some_example_name.old.editor.commands.NextTickButtonClamped
import io.github.some_example_name.old.editor.commands.NextTickButtonTap
import io.github.some_example_name.old.editor.commands.PrevStageButtonTap
import io.github.some_example_name.old.editor.commands.PrevTickButtonTap
import io.github.some_example_name.old.editor.commands.TimeSlider
import io.github.some_example_name.old.editor.entities.ReplayEntity
import io.github.some_example_name.old.editor.system.EditorLogicSystem
import io.github.some_example_name.old.editor.system.EditorRenderSystem
import io.github.some_example_name.old.editor.system.EditorSimulationSystem
import io.github.some_example_name.old.genome_editor_deprecated.SaveGenomeDialog
import io.github.some_example_name.old.systems.genomics.genome.GenomeJsonReader
import io.github.some_example_name.old.systems.genomics.genome.domainToJson
import io.github.some_example_name.old.ui.screens.MenuScreen
import io.github.some_example_name.old.ui.screens.MyGame
import io.github.some_example_name.old.ui.screens.SimulationScreen
import io.github.some_example_name.old.ui.screens.applyCustomFont
import io.github.some_example_name.old.ui.screens.applyCustomFontMedium


class MenuUiBuilder(
    val game: MyGame,
    val stage: Stage,
    val editorLogicSystem: EditorLogicSystem,
    val genomeJsonReader: GenomeJsonReader,
    val replayEntity: ReplayEntity,
    val renderSystem: EditorRenderSystem,
    val fileProvider: FileProvider,
    val editorSimulationSystem: EditorSimulationSystem
) {

    lateinit var timeSlider: VisSlider
    lateinit var stageText: VisLabel
    lateinit var tickText: VisLabel
    var isCtrl = false
    var previousCtrlClicked = -1
    private var isRightClick = false

    var isProgrammaticChange = false

    fun setSliderValueProgrammatically(value: Float) {
        isProgrammaticChange = true
        timeSlider.value = value
        isProgrammaticChange = false
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

        timeSlider = VisSlider(0f, (replayEntity.size - 1).toFloat(), 1f, false)
        timeSlider.value = 0f

        timeSlider.addListener { event ->
            if (!isProgrammaticChange && event is ChangeListener.ChangeEvent) {
                editorLogicSystem.putUiCommand(
                    TimeSlider(
                        value = timeSlider.value.toInt(),
                        isDragging = timeSlider.isDragging
                    )
                )
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
        showPhysicalLinkButton.isChecked = renderSystem.showPhysicalLink

        // Toggle кнопка
        showPhysicalLinkButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                renderSystem.showPhysicalLink = showPhysicalLinkButton.isChecked
            }
        })

        val ctrlZ = VisTextButton("Ctrl+z")
        ctrlZ.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                editorLogicSystem.putUiCommand(CtrlZ)
            }
        })

        val ctrlY = VisTextButton("Ctrl+y")
        ctrlY.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                editorLogicSystem.putUiCommand(CtrlY)
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
                editorLogicSystem.putUiCommand(PrevStageButtonTap)
            }
        })

        nextStageButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                editorLogicSystem.putUiCommand(NextStageButtonTap)
            }
        })

        prevTickButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                editorLogicSystem.putUiCommand(PrevTickButtonTap)
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
                        editorLogicSystem.putUiCommand(NextTickButtonClamped(false))
                    }
                }, 0.5f, 0.0083333f) // Старт через 0.5 сек, повтор каждые 50 мс

                return true
            }

            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                longPressTask?.cancel()
                longPressTask = null

                val pressDuration = System.currentTimeMillis() - pressStartTime
                if (!isLongPressing && pressDuration < longPressThreshold) {
                    editorLogicSystem.putUiCommand(NextTickButtonTap)
                } else if (isLongPressing) {
                    editorLogicSystem.putUiCommand(NextTickButtonClamped(true))
                }

                super.touchUp(event, x, y, pointer, button)
            }
        })
    }

    fun saveDialog(isGoToMenu: Boolean) {
        val genome = editorSimulationSystem.genome.domainToJson()

        SaveGenomeDialog(
            genomeJsonReader = genomeJsonReader,
            genome = genome,
            onSaveAndTest = { genomeNameForTest ->
                game.screen.dispose()
                game.screen = SimulationScreen(
                    multiPlatformFileProvider = fileProvider,
                    game = game,
                    bundle = bundle,
                    map = null,
                    genomeName = genomeNameForTest
                )
            },
            onGoMenu = {
                game.screen.dispose()
                game.screen = MenuScreen(
                    multiPlatformFileProvider = fileProvider,
                    game = game,
                )
            },
            game = game,
            bundle = bundle,
            isGoToMenu = isGoToMenu
        ).show(stage)
    }

}

