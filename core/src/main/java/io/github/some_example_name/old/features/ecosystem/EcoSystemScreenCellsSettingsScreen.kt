package io.github.some_example_name.old.features.ecosystem

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonWriter
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.Timer
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.util.TableUtils
import com.kotcrab.vis.ui.widget.*
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.DIGameGlobalContainer.cellTypes
import io.github.some_example_name.old.core.DIGameGlobalContainer.roundStyle
import io.github.some_example_name.old.core.DIGameGlobalContainer.substrateSettings
import io.github.some_example_name.old.core.GlobalSimulationSettings
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.game.applyCustomFont
import kotlin.collections.iterator
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible


class EcoSystemScreenCellsSettingsScreen: Screen {

    private lateinit var stage: Stage
    val gridTable = VisTable()
    private lateinit var errorLabel: VisLabel
    private lateinit var fileHandle: FileHandle
    private val json = Json()
    private var validationTask: Timer.Task? = null
    private val values = mutableListOf<Pair<String, VisTextField>>()
    private val panelSize = 280f
    private val panelsList = mutableListOf<VisTable>()
    private var settings: GlobalSimulationSettings = GlobalSimulationSettings()
    private val navigationCommandsManager = DIGameGlobalContainer.navigationCommandsManager

    override fun show() {
        json.setOutputType(JsonWriter.OutputType.json)
        json.setUsePrototypes(false)

        val substrateSettings = SubstrateSettings()

        fileHandle = substrateSettings.getFileHandle()

        stage = Stage(ScreenViewport())
        Gdx.input.inputProcessor = stage

        val table = VisTable()
        table.setFillParent(true)
        TableUtils.setSpacingDefaults(table)
        stage.addActor(table)

        errorLabel = VisLabel("")
//        errorLabel.color = Color.RED
////        game.applyCustomFontMedium(errorLabel)
//        table.add(errorLabel).height(16f * Gdx.graphics.density).pad(10f)
//        table.row()

        settings = loadJson()

        gridTable.defaults().pad(10f * Gdx.graphics.density)

        val actualPanelWidth = (panelSize * 1.35f) * Gdx.graphics.density
        val panelHeight = panelSize * Gdx.graphics.density

        val panelSpacing = 20f * Gdx.graphics.density

        val countRows = (Gdx.graphics.width / (actualPanelWidth + panelSpacing)).toInt().coerceAtLeast(1)


        var i = 0
        val whatSkip = settings.cellsSettings.count() / countRows

        println(whatSkip)
        for (cell in settings.cellsSettings) {
            val panel = VisTable()
            panel.background = VisUI.getSkin().getDrawable("window")
            panel.pad(20f)

            val iconName = "ui/cell-icons/" + cellTypes[i].textureName

            val texture = Texture(Gdx.files.internal(iconName))
            val cellImage = VisImage(texture)
            cellImage.setScaling(Scaling.fit)

            panel.add(cellImage).center().pad(10f)

            panel.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    CellSettings(cell, iconName, settings).show(stage)
                }
            })

            val cellName = VisLabel(cell.key)
            game.applyCustomFont(cellName)
            panel.add(cellName).center()

            gridTable.add(panel).uniformX().uniformY().fill().width(actualPanelWidth).height(panelHeight)
            i++
            if (i % countRows == 0) {
                gridTable.row()
            }

            panelsList.add(panel)
        }


//            val parametr = VisLabel(element.key.name)
//            game.applyCustomFont(parametr)
//            parametr.setAlignment(Align.left)
//
//            val splitter = VisLabel(":")
//            game.applyCustomFont(splitter)
//
//            val input = VisTextField(element.value.toString())
//            table.add(parametr).left()
//            table.add(splitter)
//            table.add(input).padLeft(25f).row()
//
//            values.addLast(element.key.name to input)

        val buttonsTable = VisTable()
        buttonsTable.defaults().pad(10f)

        val roundStyle = roundStyle

        val saveButton = VisTextButton(bundle.get("button.save"), roundStyle)
        game.applyCustomFont(saveButton)
        saveButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor?) {
                saveJson()
            }
        })
        buttonsTable.add(saveButton).height(60f * Gdx.graphics.density)

        val resetButton = VisTextButton(bundle.get("button.reset"), roundStyle)
        game.applyCustomFont(resetButton)
        resetButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor?) {
                resetToDefault()
            }
        })
        buttonsTable.add(resetButton).height(60f * Gdx.graphics.density)

        val menuButton = VisTextButton(bundle.get("button.back"), roundStyle)
        game.applyCustomFont(menuButton)
        menuButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor?) {
                navigationCommandsManager.performCommand(GoBack)
            }
        })
        buttonsTable.add(menuButton).height(60f * Gdx.graphics.density)

        val scrollTable = VisScrollPane(gridTable)
        scrollTable.setFadeScrollBars(false);
        scrollTable.setScrollingDisabled(true, false);

        table.add(scrollTable).expand().center().row() // Сетка займет всё свободное место
        table.add(buttonsTable).padBottom(20f).center()

        validateJson()

        table.children.forEach { actor ->
            println("Имя элемента: ${actor.name}")
        }
    }

    private fun addSpaseForKeyboard() = "\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n"

    private fun loadJson(): GlobalSimulationSettings {
        if (!fileHandle.exists()) {
            val defaultSettings = GlobalSimulationSettings()
            return defaultSettings
        }

        val jsonString = fileHandle.readString()
        try {
            val settings = json.fromJson(GlobalSimulationSettings::class.java, jsonString)
            return settings
        } catch (e: Exception) {
            errorLabel.setText("Error: Invalid JSON - ${e.message}")
            println(e.message)
            return GlobalSimulationSettings()
        }
    }

    private fun resetToDefault() {
        settings = GlobalSimulationSettings()
        saveJson()
    }

    private fun debounceValidate() {
        validationTask?.cancel()
        validationTask = Timer.schedule(object : Timer.Task() {
            override fun run() {
                validateJson()
            }
        }, 0.4f)
    }

    private fun validateJson() {
        try {
            errorLabel.setText("")
        } catch (e: Exception) {
            errorLabel.setText("Error: Invalid JSON - ${e.message}")
        }
    }

    fun setProperty(obj: Any, propName: String, value: String) {
        val prop = obj::class.memberProperties.find { it.name == propName }
        println("Found property: ${prop?.name}, isMutable: ${prop is KMutableProperty<*>}")
        prop?.let {
            it.isAccessible = true
            val convertedValue = when (it.returnType.classifier) {
                String::class -> value
                Int::class -> value.toInt()
                Boolean::class -> value.toBoolean()
                Float::class -> value.toFloat()
                Double::class -> value.toDouble()
                Long::class -> value.toLong()
                else -> throw IllegalArgumentException("Unsupported type: ${it.returnType}")
            }

            if (it is KMutableProperty<*>) {
                it.setter.call(obj, convertedValue)
            }
        }
    }

    private fun saveJson() {
        try {
            val jsonSettings = json.toJson(settings)
            val prettyJson = json.prettyPrint(jsonSettings)
            fileHandle.writeString(prettyJson, false)

            errorLabel.setText("")

            substrateSettings.update()
        } catch (e: Exception) {
            errorLabel.setText("Error: Invalid JSON - ${e.message}")
        }
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        gridTable.clearChildren()

        // 2. Делаем расчеты на основе НОВОЙ ширины (width), пришедшей в метод
        val actualPanelWidth = (panelSize * 1.35f) * Gdx.graphics.density
        val panelHeight = panelSize * Gdx.graphics.density
        val cellSpacing = 20f * Gdx.graphics.density

        // Используем width из параметров метода resize
        val countColumns = (width / (actualPanelWidth + cellSpacing)).toInt().coerceAtLeast(1)

        // 3. Заново строим сетку
        var i = 0
        for (panel in panelsList) {
            gridTable.add(panel)
                .uniformX()
                .uniformY()
                .fill()
                .width(actualPanelWidth)
                .height(panelHeight)

            i++
            if (i % countColumns == 0) {
                gridTable.row()
            }
        }

        stage.viewport.update(width, height, true)
    }

    override fun pause() {}
    override fun resume() {}
    override fun hide() {}
    override fun dispose() {
        stage.dispose()
        validationTask?.cancel()
    }
}
