package io.github.some_example_name.old.ui.screens

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisCheckBox.VisCheckBoxStyle
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSlider
import io.github.some_example_name.old.ui.screens.makeStyledSlider
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.I18NBundle
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.gridHeight
import io.github.some_example_name.old.core.DISimulationContainer.gridWidth
import io.github.some_example_name.old.core.DISimulationContainer.heightMultiplier
import io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.systems.render.usePostProcess
import io.github.some_example_name.old.ui.screens.GlobalSettings.GRAVITATION
import io.github.some_example_name.old.ui.screens.GlobalSettings.GRID_HEIGHT
import io.github.some_example_name.old.ui.screens.GlobalSettings.GRID_WIDTH
import kotlin.math.round

class SettingsScreen(
    val game: MyGame,
    val multiPlatformFileProvider: FileProvider,
    val bundle: I18NBundle
) : Screen {

    private lateinit var stage: Stage
    private val extraTextures = mutableListOf<Texture>()

    override fun show() {
        stage = Stage(ScreenViewport())
        stage.root.setOrigin(stage.width / 2f, stage.height / 2f)
        Gdx.input.inputProcessor = stage

        val table = VisTable()
        table.setFillParent(true)
        table.defaults().pad(10f)
        stage.addActor(table)

        val density = Gdx.graphics.density

        val checkBoxStyle = VisCheckBoxStyle(VisUI.getSkin().get("default", VisCheckBoxStyle::class.java))
        val checkBoxSize = if (Gdx.app.type == Application.ApplicationType.Android) 10f else 15f
        checkBoxStyle.checkBackground.minWidth  = checkBoxSize * density
        checkBoxStyle.checkBackground.minHeight = checkBoxSize * density
        checkBoxStyle.checkBackgroundOver?.minWidth = checkBoxSize * density
        checkBoxStyle.checkBackgroundOver?.minHeight = checkBoxSize * density
        checkBoxStyle.checkBackgroundDown?.minWidth = checkBoxSize * density
        checkBoxStyle.checkBackgroundDown?.minHeight = checkBoxSize * density
        checkBoxStyle.tick.minWidth = checkBoxSize * density
        checkBoxStyle.tick.minHeight = checkBoxSize * density
        checkBoxStyle.tickDisabled?.minWidth = checkBoxSize * density
        checkBoxStyle.tickDisabled?.minHeight = checkBoxSize * density
        checkBoxStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) game.largeFont else game.extraLargeFont


        // === Громкость музыки ===
        val musicLabel = VisLabel("${bundle.get("label.music_volume")}: ${GlobalSettings.MUSIC_VOLUME}")
        game.applyCustomFont(musicLabel)
        val musicSlider = makeStyledSlider(0f, 100f, 1f, false, extraTextures).apply {
            value = GlobalSettings.MUSIC_VOLUME.toFloat()
            addListener { e ->
                if (valueChanged(e)) {
                    GlobalSettings.MUSIC_VOLUME = value.toInt()
                    game.currentMusic.volume = value / 100
                    musicLabel.setText("${bundle.get("label.music_volume")}: ${GlobalSettings.MUSIC_VOLUME}")
                }
                false
            }
            invalidateHierarchy()
        }
        table.add(musicLabel).left()
        table.row()
        table.add(musicSlider).fillX()
        table.row()

        // === Громкость звуков ===
        val soundLabel = VisLabel("${bundle.get("label.sound_volume")}: ${GlobalSettings.SOUND_VOLUME}")
        game.applyCustomFont(soundLabel)
        val soundSlider = makeStyledSlider(0f, 100f, 1f, false, extraTextures).apply {
            value = GlobalSettings.SOUND_VOLUME.toFloat()
            addListener { e ->
                if (valueChanged(e)) {
                    GlobalSettings.SOUND_VOLUME = value.toInt()
                    soundLabel.setText("${bundle.get("label.sound_volume")}: ${GlobalSettings.SOUND_VOLUME}")
                }
                false
            }
            invalidateHierarchy()
        }
        table.add(soundLabel).left()
        table.row()
        table.add(soundSlider).fillX()
        table.row()

        val gridWidthLabel = VisLabel("World width: $GRID_WIDTH")
        game.applyCustomFont(gridWidthLabel)
        val gridWidthSlider = makeStyledSlider(16f, 3440f, heightMultiplier.toFloat(), false, extraTextures).apply {
            value = GRID_WIDTH.toFloat()
            addListener { e ->
                if (valueChanged(e)) {
                    GRID_WIDTH = value.toInt()
                    gridWidthLabel.setText("World width: $GRID_WIDTH")
                }
                false
            }
            invalidateHierarchy()
        }
        table.add(gridWidthLabel).left()
        table.row()
        table.add(gridWidthSlider).fillX()
        table.row()

        val gridHeightLabel = VisLabel("World height: $GRID_HEIGHT")
        game.applyCustomFont(gridHeightLabel)
        val gridHeightSlider = makeStyledSlider(16f, 3440f, heightMultiplier.toFloat(), false, extraTextures).apply {
            value = GRID_HEIGHT.toFloat()
            addListener { e ->
                if (valueChanged(e)) {
                    GRID_HEIGHT = value.toInt()
                    gridHeightLabel.setText("World height: $GRID_HEIGHT")
                }
                false
            }
            invalidateHierarchy()
        }
        table.add(gridHeightLabel).left()
        table.row()
        table.add(gridHeightSlider).fillX()
        table.row()


        val gravitationLabel = VisLabel("Gravitation: ${GRAVITATION  * 100}")
        game.applyCustomFont(gravitationLabel)
        val gravitationSlider = makeStyledSlider(-0.1f, 0.1f, 0.01f, false, extraTextures).apply {
            value = GRAVITATION  * 100
            addListener { e ->
                if (valueChanged(e)) {
                    GRAVITATION = round((value / 100f) * 10000f) / 10000f
                    gravitationLabel.setText("Gravitation: ${round(value * 10000f) / 10000f}")
                }
                false
            }
            invalidateHierarchy()
        }
        table.add(gravitationLabel).left()
        table.row()
        table.add(gravitationSlider).fillX()
        table.row()
        // TODO: Change from constant name to bundle, а зачем я пишу на английском? Нужно поменять на bundle.get
        val debugMode = makeStyledButton("Debug Mode", game, extraTextures, toggle = true)
        game.applyCustomFont(debugMode)

        debugMode.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                GlobalSettings.DEBUG_MODE = debugMode.isChecked
            }
        })

        table.add(debugMode)
        table.row()

        val backButton = makeStyledButton(bundle.get("button.back"), game, extraTextures).apply {
            addListener { e ->
                if (clicked(e)) {
                    game.screen = MenuScreen(game, multiPlatformFileProvider)
                }
                false
            }
        }
        table.add(backButton).colspan(2).center().padTop(30f)
            .width(Gdx.graphics.width * 0.20f)
            .height(Gdx.graphics.height * 0.065f)
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
        stage.root.setOrigin(stage.width / 2f, stage.height / 2f)
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}
    override fun dispose() {
        stage.dispose()
        extraTextures.forEach { it.dispose() }
    }

    // Утилиты для читаемости
    private fun clicked(e: Event) = e is ChangeListener.ChangeEvent
    private fun changed(e: Event) = e is ChangeListener.ChangeEvent
}

fun valueChanged(e: Event) = e is ChangeListener.ChangeEvent

// === Глобальные настройки ===
object GlobalSettings {
    var MSAA = 1
    var SAFE_DIVISION_MODE = true
    var HYDRODYNAMIC_DRAG = false
    var DRAW_LINK_SHADER = true
    var HYDRO_ENABLED = false
    var HYDRO_VISUALIZATION = false
    var MUSIC_VOLUME = 0
    var SOUND_VOLUME = 50
    var GRID_WIDTH = gridWidth
    var GRID_HEIGHT = gridHeight
    var GRAVITATION = 0f
    var DEBUG_MODE = false

//    var WORLD_SIZE_TYPE = WorldSize.XL
//    var WORLD_CELL_WIDTH = WORLD_SIZE_TYPE.size
//    var WORLD_CELL_HEIGHT = WORLD_SIZE_TYPE.size
//    var GRID_SIZE = WORLD_CELL_WIDTH * WORLD_CELL_HEIGHT
//    var WORLD_WIDTH = WORLD_CELL_WIDTH * CELL_SIZE
//    var WORLD_HEIGHT = WORLD_CELL_HEIGHT * CELL_SIZE
//    var MAX_ZOOM = WORLD_SIZE_TYPE.maxZoom

    var UI_SCALE = 1f
}
