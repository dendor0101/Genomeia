package io.github.some_example_name.old.core.ui

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Window
import com.badlogic.gdx.utils.Scaling
import com.kotcrab.vis.ui.widget.VisImageButton
import com.kotcrab.vis.ui.widget.VisWindow
import io.github.some_example_name.old.game.MyGame
import io.github.some_example_name.old.game.applyCustomFontLarge
import kotlin.math.roundToInt

fun VisWindow.setupTitleSize(game: MyGame) {
    roundCorners()
    addCloseButton()
    transparentCloseButton()

    val d = Gdx.graphics.density
    val titleLabel = getTitleLabel()
    game.applyCustomFontLarge(titleLabel)

    val titleTable = getTitleTable()
    titleTable.pad(5f * d)
    titleTable.padTop(5f * d).padBottom(5f * d)

    val closeButton = titleTable.children.last() as? VisImageButton ?: return
    val closeBtnSize = if (Gdx.app.type == Application.ApplicationType.Android) 20f else 32f
    closeButton.image.setScaling(Scaling.fit)
    closeButton.imageCell.size(closeBtnSize * d * 0.75f)
    titleTable.getCell(closeButton).size(closeBtnSize * d, closeBtnSize * d)

    titleTable.invalidateHierarchy()
    padTop(titleTable.getPrefHeight())
}

// Remove the square button background from the close (×) button; keep only the icon
private fun VisWindow.transparentCloseButton() {
    for (cell in getTitleTable().cells) {
        val btn = cell.actor
        if (btn is ImageButton) {
            val orig = btn.style
            val ns = ImageButton.ImageButtonStyle()
            ns.imageUp   = orig.imageUp
            ns.imageDown = orig.imageDown
            ns.imageOver = orig.imageOver
            btn.style = ns
        }
    }
}

/**
 * Окно игры поменяло размер — вернуть открытые окна (диалоги) на экран.
 *
 * Вёрстку экрана пересобирает сам экран, а окна лежат на сцене отдельно и остаются там,
 * где стояли: после сужения окна игры диалог уезжал за край, и его кнопки было не нажать.
 * Окно, которое больше экрана, ужимается до экрана (содержимое у таких — в прокрутке),
 * остальные встают по центру: двигать их по старым координатам бессмысленно.
 */
fun Stage.keepWindowsOnScreen() {
    for (actor in root.children) {
        val window = actor as? Window ?: continue
        window.pack()
        if (window.width > width) window.width = width
        if (window.height > height) window.height = height
        window.setPosition(((width - window.width) / 2f).roundToInt().toFloat(), ((height - window.height) / 2f).roundToInt().toFloat())
    }
}

/**
 * Касание мимо окна закрывает его — для всплывающих окон-списков (инструмент мира).
 * Модальное окно ловит касания по всему экрану (Window.hit), поэтому промах приходит сюда
 * же, в координатах окна за его пределами, и до мира под окном не доходит.
 */
fun VisWindow.closeOnClickOutside() {
    addListener(object : InputListener() {
        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
            if (x >= 0f && y >= 0f && x <= width && y <= height) return false
            fadeOut()
            return true
        }
    })
}
