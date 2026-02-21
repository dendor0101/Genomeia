package io.github.some_example_name.old.genome_editor.dialog

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ActorGestureListener
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Array
import com.badlogic.gdx.utils.I18NBundle
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.util.FloatDigitsOnlyFilter
import com.kotcrab.vis.ui.util.IntDigitsOnlyFilter
import com.kotcrab.vis.ui.widget.VisCheckBox
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisSelectBox
import com.kotcrab.vis.ui.widget.VisSlider
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.genome.Action
import io.github.some_example_name.old.good_one.encodeColorToBits
import io.github.some_example_name.old.good_one.getColorFromBits
import io.github.some_example_name.old.world_logic.cells.base.cellsType
import kotlin.math.PI
import kotlin.math.roundToInt
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.genome_editor.dialog.color.ColorPicker
import io.github.some_example_name.old.screens.MyGame
import io.github.some_example_name.old.screens.applyCustomFont
import io.github.some_example_name.old.screens.applyCustomFontMedium
import io.github.some_example_name.old.world_logic.cells.base.formulaType

fun actionButton(
    text: String,
    game: MyGame,
    onAction: () -> Unit
): VisTextButton {
    val actionButton = VisTextButton(text).apply {
        addListener(
            object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    onAction.invoke()
                }
            }
        )
    }
    game.applyCustomFont(actionButton)

    return actionButton
}

fun colorPicker(
    colorPicker: ColorPicker,
    game: MyGame,
    bundle: I18NBundle
): VisTextButton {
    val colorButton = VisTextButton(bundle.get("button.chooseColorDialog")).apply {
        addListener(
            object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    stage?.addActor(colorPicker)
                    colorPicker.fadeIn()
                }
            }
        )
    }
    game.applyCustomFont(colorButton)
    return colorButton
}

fun cellTypePicker(
    cellTypeFrom: Int,
    game: MyGame,
    onAction: (Int) -> Unit
): VisSelectBox<String> {
    val celTypePicker = VisSelectBox<String>().apply {
        items = Array(cellsType)
        selectedIndex = cellTypeFrom
        setSize(100f, 40f)
        addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                onAction.invoke(selectedIndex)
            }
        })
    }
    game.applyCustomFont(celTypePicker)
    return celTypePicker
}

fun angleDirected(
    action: Action,
    scrollPane: ScrollPane,
    game: MyGame,
    bundle: I18NBundle,
    onInitAction: (angle: Float) -> Unit
): Table {
    val density = Gdx.graphics.density
    val angleAction = ((action.angleDirected ?: 0.0f) * (180 / PI.toFloat()) * 10).roundToInt() / 10.0f
    val volumeLabel = VisLabel("${bundle.get("button.angle")} $angleAction°")
    game.applyCustomFontMedium(volumeLabel)
    volumeLabel.setAlignment(Align.center)

    val volumeSlider = VisSlider(-180f, 180f, 0.1f, false).apply {
        disableScrollWhileDragging(scrollPane)
    }
    volumeSlider.value = angleAction

    val minus01Angle = VisTextButton("<").apply {
        addListener(
            object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (volumeSlider.value >= -180.1f)
                        volumeSlider.value -= 0.1f
                }
            }
        )
    }
    game.applyCustomFont(minus01Angle)

    val plus01Angle = VisTextButton(">").apply {
        addListener(
            object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (volumeSlider.value <= 180.1f)
                        volumeSlider.value += 0.1f
                }
            }
        )
    }
    game.applyCustomFont(plus01Angle)

    val minus1Angle = VisTextButton("<<").apply {
        addListener(
            object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (volumeSlider.value >= -179.0f)
                        volumeSlider.value -= 1f
                }
            }
        )
    }
    game.applyCustomFont(minus1Angle)

    val plus1Angle = VisTextButton(">>").apply {
        addListener(
            object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (volumeSlider.value <= 179.0f)
                        volumeSlider.value += 1f
                }
            }
        )
    }
    game.applyCustomFont(plus1Angle)

    volumeSlider.addListener { event ->
        if (event is ChangeEvent) {
            val angle = (volumeSlider.value * 10).roundToInt() / 10.0f
            volumeLabel.setText("${bundle.get("button.angle")}: $angle°")
            onInitAction.invoke(angle * (Math.PI.toFloat() / 180.0f))
        }
        false
    }

    val angleTable = Table().apply {
        val angleTextTable = Table().apply {
            add(minus1Angle).width(20f * density).padRight(5f * density)
            add(minus01Angle).width(20f * density).padRight(5f * density)
            add(volumeLabel).width(100f * density).align(Align.center)
            add(plus01Angle).width(20f * density).padLeft(5f * density)
            add(plus1Angle).width(20f * density).padLeft(5f * density)
        }
        add(angleTextTable).width(200f * density).row()
        add(volumeSlider).width(200f * density)
    }

    return angleTable
}

fun neuron(
    action: Action,
    game: MyGame,
    bundle: I18NBundle,
    onFuncChange: (func: Int) -> Unit,
    onAChange: (a: Float) -> Unit,
    onBChange: (b: Float) -> Unit,
    onCChange: (c: Float) -> Unit,
    onIsSumChange: (isSum: Boolean) -> Unit
): VisTable {
    val density = Gdx.graphics.density
    val table = VisTable()

    val inputSignals = VisLabel(bundle.get("button.inputSignals"))
    game.applyCustomFontMedium(inputSignals)

    val additionCheckBox = VisCheckBox(bundle.get("button.addition"))
    game.applyCustomFont(additionCheckBox)
    val multiplicationCheckBox = VisCheckBox(bundle.get("button.multiplication"))
    game.applyCustomFont(multiplicationCheckBox)

    val buttonGroup = ButtonGroup<VisCheckBox>()
    buttonGroup.add(additionCheckBox)
    buttonGroup.add(multiplicationCheckBox)

    additionCheckBox.isChecked = action.isSum ?: true
    multiplicationCheckBox.isChecked = action.isSum != true

    additionCheckBox.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            if (additionCheckBox.isChecked) {
                onIsSumChange(true)
            }
        }
    })

    multiplicationCheckBox.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            if (multiplicationCheckBox.isChecked) {
                onIsSumChange(false)
            }
        }
    })


    val formulaPicker = VisSelectBox<String>().apply {
        items = Array(formulaType)
        selectedIndex = action.funActivation ?: 0
        addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                onFuncChange(selectedIndex)
            }
        })
    }
    game.applyCustomFont(formulaPicker)

    val formulaAbcTable = Table()

    val aButtonTable = Table().apply {
        val aLabel = VisLabel("a")
        game.applyCustomFontMedium(aLabel)
        val aTextField = VisTextField((action.a ?: 1f).toString())
        game.applyCustomFont(aTextField)
        aTextField.textFieldFilter = FloatDigitsOnlyFilter(true)
        aTextField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                val value = aTextField.text.toFloatOrNull() ?: 1.0f
                onAChange(value)
            }
        })
        add(aLabel).padRight(10f * density)
        add(aTextField)
    }
    formulaAbcTable.add(aButtonTable).padRight(10f * density)

    val bButtonTable = Table().apply {
        val bLabel = VisLabel("b")
        game.applyCustomFontMedium(bLabel)
        val bTextField = VisTextField((action.b ?: 0f).toString())
        game.applyCustomFont(bTextField)
        bTextField.textFieldFilter = FloatDigitsOnlyFilter(true)
        bTextField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                val value = bTextField.text.toFloatOrNull() ?: 0.0f
                onBChange(value)
            }
        })
        add(bLabel).padRight(10f * density)
        add(bTextField)
    }
    formulaAbcTable.add(bButtonTable).padRight(10f * density)

    val cButtonTable = Table().apply {
        val cLabel = VisLabel("c")
        game.applyCustomFontMedium(cLabel)
        val cTextField = VisTextField((action.c ?: 0f).toString())
        game.applyCustomFont(cTextField)
        cTextField.textFieldFilter = FloatDigitsOnlyFilter(true)
        cTextField.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) {
                val value = cTextField.text.toFloatOrNull() ?: 0.0f
                onCChange(value)
            }
        })
        add(cLabel).padRight(10f * density)
        add(cTextField)
    }
    formulaAbcTable.add(cButtonTable)

    table.add(formulaAbcTable).align(Align.center).fillX().padBottom(10f * density).row()
    table.add(formulaPicker).align(Align.left).size(200f * density, 30f * density).padBottom(10f * density).row()
    table.add(inputSignals).align(Align.left).padBottom(5f * density).row()
    table.add(additionCheckBox).align(Align.left).padBottom(5f * density).row()
    table.add(multiplicationCheckBox).align(Align.left).padBottom(10f * density).row()

    return table
}

fun eye(
    action: Action,
    scrollPane: ScrollPane,
    game: MyGame,
    bundle: I18NBundle,
    onDistanceChange: (distance: Float) -> Unit,
    onColorChange: (bits: Int) -> Unit
): VisTable {
    val density = Gdx.graphics.density
    val table = VisTable()

    val distanceAction = action.lengthDirected ?: 0.0f
    val volumeLabel = VisLabel("${bundle.get("button.distance")} $distanceAction")
    game.applyCustomFontMedium(volumeLabel)
    volumeLabel.setAlignment(Align.center)

    val volumeSlider = VisSlider(25f, 1400f, 1f, false).apply {
        disableScrollWhileDragging(scrollPane)
    }
    volumeSlider.value = distanceAction

    val minusDistance = VisTextButton("<").apply {
        addListener(
            object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (volumeSlider.value >= 26.0f)
                        volumeSlider.value -= 1f
                }
            }
        )
    }
    game.applyCustomFont(minusDistance)

    val plusDistance = VisTextButton(">").apply {
        addListener(
            object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (volumeSlider.value <= 1399.0f)
                        volumeSlider.value += 1f
                }
            }
        )
    }
    game.applyCustomFont(plusDistance)

    volumeSlider.addListener { event ->
        if (event is ChangeEvent) {
            val distance = volumeSlider.value
            volumeLabel.setText("${bundle.get("button.distance")}: ${distance.toInt()}")
            onDistanceChange(distance)
        }
        false
    }

    val distanceTable = Table().apply {
        val angleTextTable = Table().apply {
            add(minusDistance).width(20f * density).padRight(5f * density)
            add(volumeLabel).width(150f * density).align(Align.center)
            add(plusDistance).width(20f * density).padLeft(5f * density)
        }

        add(angleTextTable).width(200f * density).row()
        add(volumeSlider).width(200f * density)
    }
    table.add(distanceTable).padBottom(10f * density).row()

    val colorRecognition = VisLabel(bundle.get("button.colorRecognition"))
    game.applyCustomFontMedium(colorRecognition)
    table.add(colorRecognition).align(Align.left).padBottom(10f * density).row()

    val color = getColorFromBits(action.colorRecognition ?: 7)

    val rCheckBox = VisCheckBox("R")
    game.applyCustomFont(rCheckBox)
    rCheckBox.isChecked = color.r > 0f
    val gCheckBox = VisCheckBox("G")
    game.applyCustomFont(gCheckBox)
    gCheckBox.isChecked = color.g > 0f
    val bCheckBox = VisCheckBox("B")
    game.applyCustomFont(bCheckBox)
    bCheckBox.isChecked = color.b > 0f

    fun updateColor() {
        val r = if (rCheckBox.isChecked) 1f else 0f
        val g = if (gCheckBox.isChecked) 1f else 0f
        val b = if (bCheckBox.isChecked) 1f else 0f
        val bits = encodeColorToBits(r, g, b)
        println(bits)
        onColorChange(bits)
    }

    rCheckBox.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            updateColor()
        }
    })
    table.add(rCheckBox).align(Align.left).padBottom(10f * density).row()

    gCheckBox.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            updateColor()
        }
    })
    table.add(gCheckBox).align(Align.left).padBottom(10f * density).row()

    bCheckBox.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent, actor: Actor) {
            updateColor()
        }
    })
    table.add(bCheckBox).align(Align.left).padBottom(10f * density).row()

    return table
}

fun controller(): VisTextField {
    val controllerKey = VisTextField("1")
    controllerKey.textFieldFilter = IntDigitsOnlyFilter(false)
    controllerKey.maxLength = 1

    return controllerKey
}


fun Slider.disableScrollWhileDragging(scrollPane: ScrollPane) {
    this.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent?, actor: Actor?) {
        }
    })

    this.addListener(object : InputListener() {
        override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
            scrollPane.setFlickScroll(false)
            return true
        }

        override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
            scrollPane.setFlickScroll(true)
        }

        override fun touchDragged(event: InputEvent?, x: Float, y: Float, pointer: Int) {
        }
    })
}
