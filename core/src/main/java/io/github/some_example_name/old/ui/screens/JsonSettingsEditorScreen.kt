package io.github.some_example_name.old.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonWriter
import com.badlogic.gdx.utils.Timer
import com.badlogic.gdx.utils.viewport.ScreenViewport
import io.github.some_example_name.old.systems.render.CoreDesktopShaders
import com.kotcrab.vis.ui.util.TableUtils
import com.kotcrab.vis.ui.widget.ScrollableTextArea
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DIGameGlobalContainer.substrateSettings
import io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.core.GlobalSimulationSettings
import io.github.some_example_name.old.core.SubstrateSettings

class JsonEditorScreen(
    val game: MyGame,
    val multiPlatformFileProvider: FileProvider,
    val bundle: I18NBundle
) : Screen {

    private lateinit var stage: Stage
    private lateinit var textArea: ScrollableTextArea
    private lateinit var errorLabel: VisLabel
    private lateinit var fileHandle: FileHandle
    private val json = Json()
    private var validationTask: Timer.Task? = null
    private val extraTextures = mutableListOf<Texture>()

    override fun show() {
        json.setOutputType(JsonWriter.OutputType.json)
        json.setUsePrototypes(false)

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

        textArea = ScrollableTextArea(loadJson())
        val scrollPane = textArea.createCompatibleScrollPane()

        scrollPane.style.vScrollKnob.minWidth = 32f * Gdx.graphics.density
        scrollPane.style.vScroll.minWidth = 32f * Gdx.graphics.density
        table.add(scrollPane).grow().pad(10f).row()
        game.applyCustomFont(textArea)

        textArea.setTextFieldListener(object : VisTextField.TextFieldListener {
            override fun keyTyped(textField: VisTextField, c: Char) {
                debounceValidate()
            }
        })

        val btnH = Gdx.graphics.height * 0.065f
        val buttonsTable = VisTable()
        buttonsTable.defaults().pad(8f * Gdx.graphics.density)

        val saveButton = makeStyledButton(bundle.get("button.save"), game, extraTextures)
        saveButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor?) { saveJson() }
        })
        buttonsTable.add(saveButton).height(btnH)

        val resetButton = makeStyledButton(bundle.get("button.reset"), game, extraTextures)
        resetButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor?) { resetToDefault() }
        })
        buttonsTable.add(resetButton).height(btnH)

        val menuButton = makeStyledButton(bundle.get("button.menu"), game, extraTextures)
        menuButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor?) {
                game.screen = MenuScreen(game, multiPlatformFileProvider)
            }
        })
        buttonsTable.add(menuButton).height(btnH)

        val copyToClipboardButton = makeStyledButton(bundle.get("button.copyToClipboard"), game, extraTextures)
        copyToClipboardButton.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor?) {
                Gdx.app.clipboard.contents = textArea.text
            }
        })
        buttonsTable.add(copyToClipboardButton).height(btnH)

        loadJson()
        table.add(buttonsTable).center()
        table.row()
        validateJson()

        validationTask?.cancel()
        validationTask = Timer.schedule(object : Timer.Task() {
            override fun run() {
                textArea.text = textArea.text + " "
            }
        }, 0.4f)
    }

    private fun addSpaseForKeyboard() = "\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n"

    private fun loadJson(): String {
        if (!fileHandle.exists()) {
            val defaultSettings = GlobalSimulationSettings()
            val prettyJson = json.prettyPrint(defaultSettings)
            fileHandle.writeString(prettyJson, false)
            return prettyJson.replace("\t", "    ") + addSpaseForKeyboard()
        }

        val jsonString = fileHandle.readString()
        try {
            val settings = json.fromJson(GlobalSimulationSettings::class.java, jsonString)
            val prettyJson = json.prettyPrint(settings)
            return prettyJson.replace("\t", "    ") + addSpaseForKeyboard()
        } catch (e: Exception) {
            errorLabel.setText("Error: Invalid JSON - ${e.message}")
            println(e.message)
            return jsonString.replace("\t", "    ") + addSpaseForKeyboard()
        }
    }

    private fun resetToDefault() {
        val defaultSettings = GlobalSimulationSettings()
        textArea.text = json.prettyPrint(defaultSettings).replace("\t", "    ") + addSpaseForKeyboard()
        validateJson()
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
        val editedText = textArea.text
        try {
            json.fromJson(GlobalSimulationSettings::class.java, editedText)
            errorLabel.setText("")
        } catch (e: Exception) {
            errorLabel.setText("Error: Invalid JSON - ${e.message}")
        }
    }

    private fun saveJson() {
        val editedText = textArea.text
        try {
            val settings = json.fromJson(GlobalSimulationSettings::class.java, editedText)
            val prettyJson = json.prettyPrint(settings)
            fileHandle.writeString(prettyJson, false)
            textArea.text = prettyJson.replace("\t", "    ") + addSpaseForKeyboard()
            errorLabel.setText("")
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
        extraTextures.forEach { it.dispose() }
    }
}
