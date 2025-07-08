package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import kotlin.random.Random

class ParameterDialog: ApplicationAdapter() {
    private lateinit var stage: Stage// = Stage(ScreenViewport())

    private lateinit var angleSlider: Slider
    private lateinit var lengthSlider: Slider
    private lateinit var radiusSlider: Slider
    private lateinit var stiffnessSlider: Slider
    private lateinit var redSlider: Slider
    private lateinit var greenSlider: Slider
    private lateinit var blueSlider: Slider

    override fun create() {
//        super.create()
        stage = Stage(ScreenViewport())
        angleSlider = createSlider(0f, 360f, 1f, "Angle")
        lengthSlider = createSlider(10f, 200f, 1f, "Length")
        radiusSlider = createSlider(5f, 100f, 1f, "Radius")
        stiffnessSlider = createSlider(0.1f, 10f, 0.1f, "Stiffness")
        redSlider = createSlider(0f, 1f, 0.01f, "Red")
        greenSlider = createSlider(0f, 1f, 0.01f, "Green")
        blueSlider = createSlider(0f, 1f, 0.01f, "Blue")
        init()
    }

    fun init() {
        Gdx.input.inputProcessor = stage

        val table = Table()
        table.setFillParent(true)

        val defaultColor = Color(Random.nextFloat(), Random.nextFloat(), Random.nextFloat(), 1f)
        redSlider.value = defaultColor.r
        greenSlider.value = defaultColor.g
        blueSlider.value = defaultColor.b

        listOf(angleSlider, lengthSlider, radiusSlider, stiffnessSlider, redSlider, greenSlider, blueSlider).forEach {
            table.add(it).growX().row()
        }
        val skin = Skin(Gdx.files.internal("ui/uiskin.json"))
        val applyButton = TextButton("Apply", skin)
        applyButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
//                onApply(
//                    angleSlider.value,
//                    lengthSlider.value,
//                    radiusSlider.value,
//                    Color(redSlider.value, greenSlider.value, blueSlider.value, 1f),
//                    stiffnessSlider.value
//                )
            }
        })
        table.add(applyButton).colspan(2).padTop(20f)

        stage.addActor(table)
    }

    private fun createSlider(min: Float, max: Float, step: Float, label: String): Slider {
        val skin = Skin(Gdx.files.internal("ui/uiskin.json"))
        val slider = Slider(min, max, step, false, skin)
        slider.value = (min + max) / 2
        val labelWidget = Label("$label: ${slider.value}", skin)

        slider.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
                labelWidget.setText("$label: ${slider.value}")
            }
        })

        val table = Table()
        table.add(labelWidget).padRight(10f)
        table.add(slider).growX()

        return slider
    }

//    override fun show() {}

    override fun render() {
        stage.act(0.1f)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun pause() {}

    override fun resume() {}

//    override fun hide() {}

    override fun dispose() {
        stage.dispose()
    }
}

