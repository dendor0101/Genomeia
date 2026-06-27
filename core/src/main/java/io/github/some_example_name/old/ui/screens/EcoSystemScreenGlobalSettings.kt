package io.github.some_example_name.old.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.*
import com.badlogic.gdx.utils.viewport.ScreenViewport
import io.github.some_example_name.old.systems.render.CoreDesktopShaders
import com.kotcrab.vis.ui.util.TableUtils
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.core.DIGameGlobalContainer.substrateSettings
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.core.GlobalSimulationSettings
import io.github.some_example_name.old.core.SubstrateSettings
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible


class EcoSystemScreenGlobalSettings(
    val game: MyGame,
    val multiPlatformFileProvider: FileProvider,
    val bundle: I18NBundle
) : Screen {

    private lateinit var stage: Stage
    //private lateinit var textArea: ScrollableTextArea
    private lateinit var errorLabel: VisLabel
    private lateinit var fileHandle: FileHandle
    private val json = Json()
    private var validationTask: Timer.Task? = null
    private val values = mutableListOf<Pair<String, VisTextField>>()

    override fun show() {
        json.setOutputType(JsonWriter.OutputType.json)
        json.setUsePrototypes(false)

        val substrateSettings = SubstrateSettings()

        fileHandle = substrateSettings.getFileHandle()

        stage = CoreDesktopShaders.newStage(ScreenViewport())
        Gdx.input.inputProcessor = stage

        val table = VisTable()
        table.setFillParent(true)
        TableUtils.setSpacingDefaults(table)
        stage.addActor(table)

        errorLabel = VisLabel("")
        errorLabel.color = Color.RED
//        game.applyCustomFontMedium(errorLabel)
        table.add(errorLabel).height(16f * Gdx.graphics.density).pad(10f)
        table.row()

        val settings = loadJson()
        val allFields = GlobalSimulationSettings::class.memberProperties.associateWith { it.get(settings) }
        val primitiveFields = allFields.filter { it.value !is List<*> }
        for (element in primitiveFields) {
            if (element.key.name == "cellsSettings") {
                continue
            }
            val parametr = VisLabel(element.key.name)
            game.applyCustomFont(parametr)
            parametr.setAlignment(Align.left)

            val splitter = VisLabel(":")
            game.applyCustomFont(splitter)

            val input = VisTextField(element.value.toString())
            table.add(parametr).left()
            table.add(splitter)
            table.add(input).padLeft(25f).row()

            values.add(values.size, element.key.name to input)
        }

        val buttonsTable = VisTable()
        buttonsTable.defaults().pad(10f)

        val roundStyle = DISimulationContainer.roundStyle

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
                game.screen = EcoSystemScreen(game, multiPlatformFileProvider, bundle = bundle)
            }
        })
        buttonsTable.add(menuButton).height(60f * Gdx.graphics.density)

        table.add(buttonsTable).center()
        table.row()
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
        val defaultSettings = GlobalSimulationSettings()

        for ((fieldName, textField) in values) {
            val prop = defaultSettings::class.memberProperties.find { it.name == fieldName }
            prop?.let {
                it.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val value = (it as KProperty1<GlobalSimulationSettings, *>).get(defaultSettings)
                textField.text = value.toString()
            }
        }

        val prettyJson = json.prettyPrint(defaultSettings)
        fileHandle.writeString(prettyJson, false)

        substrateSettings.update()

        errorLabel.setText("")
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
        //val editedText = textArea.text
        try {
            //json.fromJson(GlobalSimulationSettings::class.java, editedText)

            errorLabel.setText("")
        } catch (e: Exception) {
            errorLabel.setText("Error: Invalid JSON - ${e.message}")
        }
    }

    fun setProperty(obj: Any, propName: String, value: String) {
        val prop = obj::class.memberProperties.find { it.name == propName }
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
        //val editedText = textArea.text
        try {
            //val settings = json.fromJson(GlobalSimulationSettings::class.java, editedText)
           // val prettyJson = json.prettyPrint(settings)
            //fileHandle.writeString(prettyJson, false)
            //textArea.text = prettyJson.replace("\t", "    ") + addSpaseForKeyboard()
            val settings = loadJson()

            for (element in values) {
                setProperty(settings, element.component1(), element.component2().toString())
            }
            println(settings.amountOfSolarEnergy)

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
