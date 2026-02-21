package io.github.some_example_name.old.screens

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisCheckBox.VisCheckBoxStyle
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSlider
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.I18NBundle
import io.github.some_example_name.old.platform_flag.FileProvider
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import io.github.some_example_name.old.world_logic.WorldSize

class SettingsScreen(
    val game: MyGame,
    val multiPlatformFileProvider: FileProvider,
    val bundle: I18NBundle
) : Screen {

    private lateinit var stage: Stage

    override fun show() {
        stage = Stage(ScreenViewport())
        stage.root.setOrigin(stage.width / 2f, stage.height / 2f)
        Gdx.input.inputProcessor = stage

        val table = VisTable()
        table.setFillParent(true)
        table.defaults().pad(10f)
        stage.addActor(table)

        val density = Gdx.graphics.density  // Получаем density один раз

        // Локальный стиль для чекбоксов (копия дефолтного)
        val checkBoxStyle = VisCheckBoxStyle(VisUI.getSkin().get("default", VisCheckBoxStyle::class.java))
        val checkBoxSize = if (Gdx.app.type == Application.ApplicationType.Android) 10f else 15f
        checkBoxStyle.checkBackground.minWidth = checkBoxSize * density  // Размер квадрата в on/off
        checkBoxStyle.checkBackground.minHeight = checkBoxSize * density
        checkBoxStyle.checkBackgroundOver?.minWidth = checkBoxSize * density
        checkBoxStyle.checkBackgroundOver?.minHeight = checkBoxSize * density
        checkBoxStyle.checkBackgroundDown?.minWidth = checkBoxSize * density
        checkBoxStyle.checkBackgroundDown?.minHeight = checkBoxSize * density
        checkBoxStyle.tick.minWidth = checkBoxSize * density
        checkBoxStyle.tick.minHeight = checkBoxSize * density
        checkBoxStyle.tickDisabled?.minWidth = checkBoxSize * density
        checkBoxStyle.tickDisabled?.minHeight = checkBoxSize * density
        checkBoxStyle.font = if (Gdx.app.type == Application.ApplicationType.Android) game.mediumFont else game.largeFont

        // === MSAA слайдер ===
        val msaaLabel = VisLabel("${bundle.get("label.msaa")}: ${GlobalSettings.MSAA}")
        game.applyCustomFontMedium(msaaLabel)
        val msaaSlider = VisSlider(1f, 8f, 1f, false/*, sliderStyle*/).apply {
            value = GlobalSettings.MSAA.toFloat()
            addListener { e ->
                if (valueChanged(e)) {
                    GlobalSettings.MSAA = value.toInt()
                    msaaLabel.setText("${bundle.get("label.msaa")}: ${GlobalSettings.MSAA}")
                }
                false
            }
            invalidateHierarchy()  // Обновляем layout после изменений
        }
        table.add(msaaLabel).left()
        table.row()
        table.add(msaaSlider).fillX()
        table.row()

        val drawLinks = VisCheckBox(bundle.get("checkbox.draw_links"), checkBoxStyle).apply {
            isChecked = GlobalSettings.DRAW_LINK_SHADER
            addListener { e ->
                if (changed(e)) GlobalSettings.DRAW_LINK_SHADER = isChecked
                false
            }
        }
        table.add(drawLinks).left()
        table.row()

        val saveDivision = VisCheckBox(bundle.get("checkbox.safe_division_mode"), checkBoxStyle).apply {
            game.applyCustomFont(this)
            isChecked = GlobalSettings.SAFE_DIVISION_MODE
            addListener { e ->
                if (changed(e)) GlobalSettings.SAFE_DIVISION_MODE = isChecked
                false
            }
        }
        table.add(saveDivision).left()
        table.row()

        val hydroDragBox = VisCheckBox(bundle.get("checkbox.hydroDrag"), checkBoxStyle).apply {
            game.applyCustomFont(this)
            isChecked = GlobalSettings.HYDRODYNAMIC_DRAG
            addListener { e ->
                if (changed(e)) GlobalSettings.HYDRODYNAMIC_DRAG = isChecked
                false
            }
        }
        table.add(hydroDragBox).left()
        table.row()


        // === Громкость музыки ===
        val musicLabel = VisLabel("${bundle.get("label.music_volume")}: ${GlobalSettings.MUSIC_VOLUME}")
        game.applyCustomFontMedium(musicLabel)
        val musicSlider = VisSlider(0f, 100f, 1f, false/*, sliderStyle*/).apply {
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
        game.applyCustomFontMedium(soundLabel)
        val soundSlider = VisSlider(0f, 100f, 1f, false/*, sliderStyle*/).apply {
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

        // === Кнопка назад ===
        val backButton = VisTextButton(bundle.get("button.back")).apply {
            game.applyCustomFont(this)
            addListener { e ->
                if (clicked(e)) {
                    game.screen = MenuScreen(game, multiPlatformFileProvider)
                }
                false
            }
        }
        table.add(backButton).colspan(2).center().padTop(30f)
            .width(180f * density)   // ширина
            .height(40f * density)   // высота
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
    }

    // Утилиты для читаемости
    private fun clicked(e: Event) = e is ChangeListener.ChangeEvent
    private fun changed(e: Event) = e is ChangeListener.ChangeEvent
    private fun valueChanged(e: Event) = e is ChangeListener.ChangeEvent
}

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

    var WORLD_SIZE_TYPE = WorldSize.XL
    var WORLD_CELL_WIDTH = WORLD_SIZE_TYPE.size
    var WORLD_CELL_HEIGHT = WORLD_SIZE_TYPE.size
    var GRID_SIZE = WORLD_CELL_WIDTH * WORLD_CELL_HEIGHT
    var WORLD_WIDTH = WORLD_CELL_WIDTH * CELL_SIZE
    var WORLD_HEIGHT = WORLD_CELL_HEIGHT * CELL_SIZE
    var MAX_ZOOM = WORLD_SIZE_TYPE.maxZoom

    var UI_SCALE = 1f
//
//    fun applyUiScale(actors: Array<Actor>) {
//        for (a in actors) {
//            when (a) {
//                is VisTextButton -> {
//                    a.label.setFontScale(UI_SCALE)
//                    a.invalidateHierarchy()
//                }
//                is VisLabel -> {
//                    a.setFontScale(UI_SCALE)
//                    a.invalidateHierarchy()
//                }
//                is VisSlider -> {
//                    a.setSize(a.width, 40f * UI_SCALE)
//                    a.invalidateHierarchy()
//                }
//            }
//            if (a is Group) {
//                applyUiScale(a.children)
//            }
//        }
//    }

    fun setWorldSize(ws: WorldSize) {
        WORLD_SIZE_TYPE = ws
        WORLD_CELL_WIDTH = ws.size
        WORLD_CELL_HEIGHT = ws.size
        GRID_SIZE = WORLD_CELL_WIDTH * WORLD_CELL_HEIGHT
        WORLD_WIDTH = WORLD_CELL_WIDTH * CELL_SIZE
        WORLD_HEIGHT = WORLD_CELL_HEIGHT * CELL_SIZE
        MAX_ZOOM = ws.maxZoom
    }
}
