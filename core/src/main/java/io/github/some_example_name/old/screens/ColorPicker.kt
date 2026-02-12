/*
 * Copyright 2014-2017 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *//*


package com.kotcrab.vis.ui.widget.color

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Disposable
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.ButtonBar
import com.kotcrab.vis.ui.widget.ButtonBar.ButtonType
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisWindow
import com.kotcrab.vis.ui.widget.color.internal.ColorPickerText.CANCEL
import com.kotcrab.vis.ui.widget.color.internal.ColorPickerText.OK
import com.kotcrab.vis.ui.widget.color.internal.ColorPickerText.RESTORE
import com.kotcrab.vis.ui.widget.color.internal.ColorPickerText.TITLE

*/
/**
 * Color Picker dialog, allows user to select color. ColorPicker is relatively heavy dialog and should be reused whenever possible.
 * This dialog must be disposed when no longer needed! ColorPicker will be centered on screen after adding to Stage
 * use [setCenterOnAdd] to change this.
 * @author Kotcrab
 * @see ColorPicker
 * @see BasicColorPicker
 * @see ExtendedColorPicker
 * @since 0.6.0
 *//*

class ColorPicker : VisWindow, Disposable {
    private var picker: BasicColorPicker

    var listener: ColorPickerListener? = null

    private lateinit var restoreButton: VisTextButton
    private lateinit var cancelButton: VisTextButton
    private lateinit var okButton: VisTextButton

    private var closeAfterPickingFinished = true

    private var fadeOutDueToCanceled = false

    constructor() : this(null as String?)

    constructor(title: String?) : this("default", title, null)

    constructor(title: String?, listener: ColorPickerListener?) : this("default", title, listener)

    constructor(listener: ColorPickerListener?) : this("default", null, listener)

    constructor(styleName: String, title: String?, listener: ColorPickerListener?) : super(title ?: "", VisUI.getSkin().get(styleName, ColorPickerStyle::class.java)) {
        this.listener = listener

        val style = getStyle() as ColorPickerStyle

//        if (title == null) titleLabel.text = TITLE.get()

        isModal = true
        isMovable = true

        addCloseButton()
        closeOnEscape()

        picker = BasicColorPicker(style.pickerStyle, listener)
        picker.setAllowAlphaEdit(false) // Убираем возможность редактировать альфа-канал

        add(picker)
        row()
        add(createButtons()).pad(3f).right().expandX().colspan(3)

        pack()

        val density = Gdx.graphics.density
        setSize(width * density, height * density) // Масштабируем размеры в зависимости от density экрана

        centerWindow()

        createListeners()
    }

    private fun createButtons(): VisTable {
        val buttonBar = ButtonBar()
        buttonBar.isIgnoreSpacing = true
        buttonBar.setButton(ButtonType.LEFT, VisTextButton(RESTORE.get()).also { restoreButton = it })
        buttonBar.setButton(ButtonType.OK, VisTextButton(OK.get()).also { okButton = it })
        buttonBar.setButton(ButtonType.CANCEL, VisTextButton(CANCEL.get()).also { cancelButton = it })
        return buttonBar.createTable()
    }

    private fun createListeners() {
        restoreButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                picker.restoreLastColor()
            }
        })

        okButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                listener?.finished(Color(picker.color))
                color = picker.color
                if (closeAfterPickingFinished) fadeOut()
            }
        })

        cancelButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                fadeOutDueToCanceled = true
                close()
            }
        })
    }

    override fun setStage(stage: Stage?) {
        super.setStage(stage)
        if (stage == null && fadeOutDueToCanceled) {
            fadeOutDueToCanceled = false
            color = picker.oldColor
        }
    }

    */
/**
     * Controls whether to fade out color picker after users finished color picking and has pressed OK button. If
     * this is set to false picker won't close after pressing OK button. Default is true.
     * Note that by default picker is a modal window so might also want to call `colorPicker.setModal(false)` to
     * disable it.
     *//*

    fun setCloseAfterPickingFinished(closeAfterPickingFinished: Boolean) {
        this.closeAfterPickingFinished = closeAfterPickingFinished
    }

    override fun close() {
        listener?.canceled(picker.oldColor)
        super.close()
    }

    override fun dispose() {
        picker.dispose()
    }

    */
/** @return internal dialog color picker *//*

    fun getPicker(): BasicColorPicker {
        return picker
    }

    // ColorPicker delegates

    fun isShowHexFields(): Boolean {
        return picker.isShowHexFields
    }

    fun setShowHexFields(showHexFields: Boolean) {
        picker.isShowHexFields = showHexFields
    }

    fun isDisposed(): Boolean {
        return picker.isDisposed
    }

    fun restoreLastColor() {
        picker.restoreLastColor()
    }

    override fun setColor(newColor: Color) {
        picker.color = newColor
    }

    fun setListener(listener: ColorPickerListener?) {
        this.listener = listener
        picker.listener = listener
    }

    fun getListener(): ColorPickerListener? {
        return picker.listener
    }
}
*/
