package io.github.some_example_name.old.genome_editor.dialog.color

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.ButtonBar
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisWindow
import com.kotcrab.vis.ui.widget.color.ColorPickerListener
import com.kotcrab.vis.ui.widget.color.ColorPickerStyle
import io.github.some_example_name.old.screens.MyGame
import io.github.some_example_name.old.screens.applyCustomFont
import io.github.some_example_name.old.screens.setupTitleSize


//TODO сделать dispose всех ColorPicker
class ColorPicker(
    styleName: String = "default",
    title: String = "Color Picker",
    listener: ColorPickerListener? = null,
    val game: MyGame,
    val colorInit: Color
) : VisWindow(title, VisUI.getSkin().get(styleName, ColorPickerStyle::class.java)), Disposable {

    private val picker: BasicColorPicker

    var listener: ColorPickerListener? = listener
        private set

//    private lateinit var restoreButton: VisTextButton
//    private lateinit var cancelButton: VisTextButton
    private lateinit var okButton: VisTextButton

    var closeAfterPickingFinished: Boolean = true

    private var fadeOutDueToCanceled: Boolean = false

    init {
        setupTitleSize(game)

        val style = style as ColorPickerStyle
        picker = BasicColorPicker(game, style.pickerStyle, listener)
        picker.setColor(colorInit)

        isModal = true
        isMovable = true

//        addCloseButton()
        closeOnEscape()

//        picker.setAllowAlphaEdit(false)
        add(picker)
        row()
        val density = Gdx.graphics.density
        add(createButtons()).align(Align.center).pad(8f * density)

        pack()
        centerWindow()

        createListeners()
    }

    private fun createButtons(): VisTable {
        val density = Gdx.graphics.density
        val buttonBar = ButtonBar()
        buttonBar.isIgnoreSpacing = true
//        buttonBar.setButton(ButtonType.LEFT, VisTextButton(RESTORE.get()).also {
//            game.applyCustomFont(it)
//            it.labelCell.pad(5f * density).padLeft(25f * density).padRight(25f * density)
//            restoreButton = it
//        })
//        buttonBar.setButton(ButtonType.OK, VisTextButton("Ok").also {
//            game.applyCustomFont(it)
//            it.labelCell.pad(10f * density).padLeft(35f * density).padRight(35f * density)
//            okButton = it
//            it.align(Align.center)
//        })
//        buttonBar.setButton(ButtonType.CANCEL, VisTextButton(CANCEL.get()).also {
//            game.applyCustomFont(it)
//            it.labelCell.pad(5f * density).padLeft(25f * density).padRight(25f * density)
//            cancelButton = it
//        })

        val table = VisTable()
        VisTextButton("Ok").also {
            game.applyCustomFont(it)
            it.labelCell.pad(10f * density).padLeft(35f * density).padRight(35f * density)
            okButton = it
            table.add(it).align(Align.center)
            it.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    listener?.finished(Color(picker.color))
                    setColor(picker.color)
                    if (closeAfterPickingFinished) fadeOut()
                }
            })
        }
        return table
    }

    private fun createListeners() {
//        restoreButton.addListener(object : ChangeListener() {
//            override fun changed(event: ChangeEvent, actor: Actor) {
//                picker.restoreLastColor()
//            }
//        })
//
//        okButton.addListener(object : ChangeListener() {
//            override fun changed(event: ChangeEvent, actor: Actor) {
//                listener?.finished(Color(picker.color))
//                setColor(picker.color)
//                if (closeAfterPickingFinished) fadeOut()
//            }
//        })

//        cancelButton.addListener(object : ChangeListener() {
//            override fun changed(event: ChangeEvent, actor: Actor) {
//                fadeOutDueToCanceled = true
//                close()
//            }
//        })
    }

    override fun setStage(stage: Stage?) {
        super.setStage(stage)
        if (stage == null && fadeOutDueToCanceled) {
            fadeOutDueToCanceled = false
            setColor(picker.oldColor)
        }
    }

    override fun close() {
        listener?.canceled(picker.oldColor)
        super.close()
    }

    override fun dispose() {
        picker.dispose()
    }

//    var showHexFields: Boolean
//        get() = picker.isShowHexFields()
//        set(value) = picker.setShowHexFields(value)
//
//    val disposed: Boolean
//        get() = picker.isDisposed()
//
//    var allowAlphaEdit: Boolean
//        get() = picker.isAllowAlphaEdit()
//        set(value) = picker.setAllowAlphaEdit(value)
//
//    fun restoreLastColor() {
//        picker.restoreLastColor()
//    }

    override fun setColor(newColor: Color) {
        picker.setColor(newColor)
    }
}
