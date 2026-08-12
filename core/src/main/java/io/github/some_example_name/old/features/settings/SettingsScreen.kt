package io.github.some_example_name.old.features.settings

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.ui.VisDslScreen
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visSelectBox
import io.github.some_example_name.old.core.ui.visSlider
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.features.settings.SettingsScreen.Settings.*

class SettingsScreen : VisDslScreen(
    background = Color(0.04f, 0.04f, 0.06f, 1f),
    isScrollable = false
) {

    enum class Settings {
        SOUND, GRAPHICS, LANGUAGE
    }

    // Сохраняем текущий выбранный таб между рекомпозициями
    private var currentSettings = SOUND
    private val viewModel = SettingsViewModel()

    override fun VisTable.compose() {
        visTable(cellInit = {
            growX().fillX()
            width(w * 0.5f)
        }) {
            // === Табы (теперь локализованные) ===
            visTable {
                lateinit var soundButton: VisTextButton
                lateinit var graphicsButton: VisTextButton
                lateinit var languageButton: VisTextButton

                fun switchTo(selected: VisTextButton, settings: Settings) {
                    soundButton.isChecked = selected === soundButton
                    graphicsButton.isChecked = selected === graphicsButton
                    languageButton.isChecked = selected === languageButton

                    if (currentSettings == settings) return
                    currentSettings = settings

                    dynamicContent?.clearChildren()
                    dynamicContent?.composeDynamic(settings)
                    dynamicContent?.invalidateHierarchy()
                }

                soundButton = visToggleButton(
                    text = bundle.get("settings.tab.sound"),
                    checked = currentSettings == SOUND,
                    onCheckedChange = {
                        if (soundButton.isChecked) switchTo(soundButton, SOUND)
                    }
                ) { expandX().fillX() }

                graphicsButton = visToggleButton(
                    text = bundle.get("settings.tab.graphics"),
                    checked = currentSettings == GRAPHICS,
                    onCheckedChange = {
                        if (graphicsButton.isChecked) switchTo(graphicsButton, GRAPHICS)
                    }
                ) { expandX().fillX() }

                languageButton = visToggleButton(
                    text = bundle.get("settings.tab.language"),
                    checked = currentSettings == LANGUAGE,
                    onCheckedChange = {
                        if (languageButton.isChecked) switchTo(languageButton, LANGUAGE)
                    }
                ) { expandX().fillX() }
            }

            row()

            // === Динамический контент ===
            val dynamicContent = visTable (cellInit = {
                growX()
            }) { }
            dynamicContent.composeDynamic(currentSettings)

            // Сохраняем ссылку, чтобы switchTo мог её обновлять
            this@SettingsScreen.dynamicContent = dynamicContent

            row()

            visTextButton(
                text = bundle.get("button.back"),
                onClick = {
                    navigation.performCommand(GoBack)
                    viewModel.saveSettings(GlobalSettings, "./settings/globalSettings.json")

                }
            ) {
                padTop(24f.dp())
            }
        }
    }

    // Нужна ссылка на dynamic-таблицу из switchTo
    private var dynamicContent: VisTable? = null

    fun VisTable.sound() {
        val musicLabel = visLabel(
            text = "${bundle.get("label.music_volume")}: ${GlobalSettings.MUSIC_VOLUME}"
        ) { left().fillX().growX() }
        row()

        visSlider(
            min = 0f, max = 100f, step = 1f,
            value = GlobalSettings.MUSIC_VOLUME.toFloat(),
            onValueChange = { value ->
                GlobalSettings.MUSIC_VOLUME = value.toInt()
                game.currentMusic.volume = value / 100f
                musicLabel.setText("${bundle.get("label.music_volume")}: ${GlobalSettings.MUSIC_VOLUME}")
            }
        ) { fillX() }
        row()

        val soundLabel = visLabel(
            text = "${bundle.get("label.sound_volume")}: ${GlobalSettings.SOUND_VOLUME}"
        ) { left().fillX() }
        row()

        visSlider(
            min = 0f, max = 100f, step = 1f,
            value = GlobalSettings.SOUND_VOLUME.toFloat(),
            onValueChange = { value ->
                GlobalSettings.SOUND_VOLUME = value.toInt()
                soundLabel.setText("${bundle.get("label.sound_volume")}: ${GlobalSettings.SOUND_VOLUME}")
            }
        ) { fillX() }
        row()
    }

    fun VisTable.graphics() {
        visLabel(bundle.get("settings.graphics.todo")) {
            pad(16f)
        }
    }

    fun VisTable.language() {
        val languages = viewModel.getAvailableLanguages()

        val displayNames = languages.map { locale ->
            locale.getDisplayName(locale).replaceFirstChar { it.uppercase() }
        }.toTypedArray()

        val currentIndex = languages.indexOfFirst {
            it.language == DIGameGlobalContainer.currentLocale.language
        }.coerceAtLeast(0)

        // SelectBox — оставляем nested, если планируешь туда ещё что-то
        visTable(cellInit = { expandX().fillX() }) {
            visSelectBox(
                items = displayNames,
                selectedIndex = currentIndex,
                onChange = { _, index ->
                    val newLocale = languages[index]
                    GlobalSettings.currentLanguageTag = newLocale.toLanguageTag()
                    DIGameGlobalContainer.setLanguage(newLocale)
                    recompose()
                }
            ) {
                expandX().fillX().padTop(16.dp())
            }
        }
        row()

        // Hint — напрямую в таблицу language(), тогда он гарантированно на всю ширину
        visLabel(
            text = bundle.get("settings.language.hint"),
            align = Align.center
        ) {
            padTop(8f)
            expandX().fillX()
        }.apply {
            setWrap(true)
        }
        row()

        visTable(cellInit = { expandX().fillX() }) {
            visTextButton("Go to Github", onClick = {
                Gdx.net.openURI("https://github.com/dendor0101/Genomeia")
            }) {
                // если кнопку тоже хочешь на всю ширину — добавь expandX().fillX()
            }
        }
    }

    fun VisTable.composeDynamic(settings: Settings) {
        when (settings) {
            SOUND -> sound()
            GRAPHICS -> graphics()
            LANGUAGE -> language()
        }
    }
}
