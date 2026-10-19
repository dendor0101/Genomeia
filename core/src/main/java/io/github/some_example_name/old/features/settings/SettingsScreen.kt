package io.github.some_example_name.old.features.settings

import io.github.some_example_name.old.commands.GameCommand
import io.github.some_example_name.old.bug.ReplayTarget
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.utils.Align
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.some_example_name.old.commands.GoBack
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.core.PlatformWindow
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DISimulationContainer.renderSystem
import io.github.some_example_name.old.core.ui.StyledSelectBox
import io.github.some_example_name.old.core.ui.VisDslScreen
import io.github.some_example_name.old.core.ui.dp
import io.github.some_example_name.old.core.ui.renderModeSelectBox
import io.github.some_example_name.old.core.ui.styledSelectBox
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visSelectBox
import io.github.some_example_name.old.core.ui.visSlider
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.visToggleButton
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.features.simulation.SimulationIntent
import io.github.some_example_name.render.RenderMode

class SettingsScreen : VisDslScreen(
    background = Color(0.04f, 0.04f, 0.06f, 1f),
    isScrollable = false
), ReplayTarget {

    private val viewModel = SettingsViewModel()

    /** Повтор бага: команда настроек, и вёрстка заново — переключатели должны её показать. */
    override fun replay(command: GameCommand): Boolean {
        if (command !is SettingsIntent) return false
        viewModel.handle(command)
        recompose()
        return true
    }

    // Нужна ссылка на dynamic-таблицу из switchTo
    private var dynamicContent: VisTable? = null

    init {
        viewModel.onLanguageChanged = { recompose() }
    }

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

                fun switchTo(selected: VisTextButton, tab: SettingsTab) {
                    soundButton.isChecked = selected === soundButton
                    graphicsButton.isChecked = selected === graphicsButton
                    languageButton.isChecked = selected === languageButton

                    if (viewModel.currentTab == tab) return
                    viewModel.handle(SettingsIntent.SelectTab(tab))

                    dynamicContent?.clearChildren()
                    dynamicContent?.composeDynamic(tab)
                    dynamicContent?.invalidateHierarchy()
                }

                soundButton = visToggleButton(
                    text = bundle.get("settings.tab.sound"),
                    checked = viewModel.currentTab == SettingsTab.SOUND,
                    onCheckedChange = {
                        if (soundButton.isChecked) switchTo(soundButton, SettingsTab.SOUND)
                    }
                ) { expandX().fillX() }

                graphicsButton = visToggleButton(
                    text = bundle.get("settings.tab.graphics"),
                    checked = viewModel.currentTab == SettingsTab.GRAPHICS,
                    onCheckedChange = {
                        if (graphicsButton.isChecked) switchTo(graphicsButton, SettingsTab.GRAPHICS)
                    }
                ) { expandX().fillX() }

                languageButton = visToggleButton(
                    text = bundle.get("settings.tab.language"),
                    checked = viewModel.currentTab == SettingsTab.LANGUAGE,
                    onCheckedChange = {
                        if (languageButton.isChecked) switchTo(languageButton, SettingsTab.LANGUAGE)
                    }
                ) { expandX().fillX() }
            }

            row()

            // === Динамический контент ===
            val dynamicContent = visTable (cellInit = {
                growX()
            }) { }
            dynamicContent.composeDynamic(viewModel.currentTab)

            // Сохраняем ссылку, чтобы switchTo мог её обновлять
            this@SettingsScreen.dynamicContent = dynamicContent

            row()

            visTextButton(
                text = bundle.get("button.back"),
                onClick = { navigation.performCommand(GoBack) }
            ) {
                padTop(24f.dp())
            }
        }
    }

    fun VisTable.sound() {
        val musicLabel = visLabel(
            text = "${bundle.get("label.music_volume")}: ${viewModel.musicVolume}"
        ) { left().fillX().growX() }
        row()

        visSlider(
            min = 0f, max = 100f, step = 1f,
            value = viewModel.musicVolume.toFloat(),
            onValueChange = { value ->
                viewModel.handle(SettingsIntent.SetMusicVolume(value.toInt()))
                musicLabel.setText("${bundle.get("label.music_volume")}: ${viewModel.musicVolume}")
            }
        ) { fillX() }
        row()

        val soundLabel = visLabel(
            text = "${bundle.get("label.sound_volume")}: ${viewModel.soundVolume}"
        ) { left().fillX() }
        row()

        visSlider(
            min = 0f, max = 100f, step = 1f,
            value = viewModel.soundVolume.toFloat(),
            onValueChange = { value ->
                viewModel.handle(SettingsIntent.SetSoundVolume(value.toInt()))
                soundLabel.setText("${bundle.get("label.sound_volume")}: ${viewModel.soundVolume}")
            }
        ) { fillX() }
        row()
    }

    fun VisTable.graphics() {


        visTable(cellInit = { expandX().fillX() }) {
            visLabel("Default Render Mode: ", align = Align.left) {}
            val selectBox = renderModeSelectBox(
                selected = RenderMode.entries[GlobalSettings.RENDER_MODE],
                onSelect = { viewModel.handle(SettingsIntent.SetRenderMode(RenderMode.entries.indexOf(it))) }
            )
            this.add(selectBox) as Cell<StyledSelectBox<String>>
        }
        row()

        visToggleButton(
            text = bundle.get("settings.showHints"),
            checked = GlobalSettings.SHOW_HINTS,
            onCheckedChange = { viewModel.handle(SettingsIntent.SetShowHints(it)) }
        ) { left().padTop(16.dp()) }
        row()

        // Полный экран есть только на ПК; состояние берём у окна, а не храним: из него можно выйти и мимо кнопки.
        if (PlatformWindow.isSupported) {
            visToggleButton(
                text = bundle.get("settings.fullscreen"),
                checked = PlatformWindow.isFullscreen,
                onCheckedChange = { viewModel.handle(SettingsIntent.SetFullscreen(it)) }
            ) { left().padTop(16.dp()) }
            row()
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
                    // recompose() дёрнет сама ViewModel через onLanguageChanged.
                    viewModel.handle(SettingsIntent.SetLanguage(languages[index].toLanguageTag()))
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
                viewModel.handle(SettingsIntent.OpenGithub)
            })
        }
    }

    fun VisTable.composeDynamic(tab: SettingsTab) {
        when (tab) {
            SettingsTab.SOUND -> sound()
            SettingsTab.GRAPHICS -> graphics()
            SettingsTab.LANGUAGE -> language()
        }
    }
}
