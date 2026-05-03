package io.github.some_example_name.old.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.kotcrab.vis.ui.util.TableUtils
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.widget.VisLabel
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.systems.genomics.genome.GenomeJsonReader
import io.github.some_example_name.old.ui.dialogs.GenomeListDialog
import io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.editor.ui.GenomeEditorScreen

class MenuScreen(
    private val game: MyGame,
    val multiPlatformFileProvider: FileProvider
) : Screen {

    private val stage = Stage(ScreenViewport())

    val genomeJsonReader: GenomeJsonReader = GenomeJsonReader()
    var onResize: (() -> Unit)? = null

    init {
        val density = Gdx.graphics.density
        val screenWidth = Gdx.graphics.width
        val screenHeight = Gdx.graphics.height
        
        // Адаптивные размеры в зависимости от экрана
        val isSmallScreen = screenWidth < 600 || screenHeight < 400
        val buttonWidth = if (isSmallScreen) 280f * density else 350f * density
        val buttonHeight = if (isSmallScreen) 45f * density else 55f * density
        val titleSize = if (isSmallScreen) 36f * density else 48f * density
        
        val table = VisTable()
        TableUtils.setSpacingDefaults(table)
        table.columnDefaults(0).pad(15f * density)
        table.setFillParent(true)
        table.background = game.skin.getDrawable("bg-background")

        // Заголовок с градиентным эффектом
        val genomeia = VisLabel(bundle.get("title.genomeia"), "title")
        genomeia.setAlignment(Align.center)
        genomeia.setFontScale(titleSize / genomeia.prefHeight)
        table.add(genomeia).fillX().padBottom(20f * density).row()

        // Пустой спейсер для визуального разделения
        table.add().height(15f * density).row()

        // Кнопка "Пустой мир"
        val emptyButton = VisTextButton(bundle.get("button.empty"), "default")
        game.applyCustomFont(emptyButton)
        table.add(emptyButton).width(buttonWidth).height(buttonHeight).row()
        emptyButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                val oldScreen = game.screen
                game.screen =
                    SimulationScreen(multiPlatformFileProvider, game, null, bundle, null)
                oldScreen.dispose()
            }
        })

        table.add().height(10f * density).row()

        // Кнопка "Редактор генома"
        val genomeEditorButton = VisTextButton(bundle.get("button.editor"), "default")
        game.applyCustomFont(genomeEditorButton)
        table.add(genomeEditorButton).width(buttonWidth).height(buttonHeight).row()
        genomeEditorButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                val genomes = genomeJsonReader.getGenomeFileNamesFromFolder("user_genomes")

                when (genomes.size) {
                    0 -> {}
                    else -> {
                        GenomeListDialog(
                            genomesList = genomes,
                            selectedGenomeIndex = null,
                            title = bundle.get("button.selectGenome"),
                            new = bundle.get("button.new"),
                            select = bundle.get("button.select"),
                            import = bundle.get("button.import"),
                            onNew = {
                                game.screen = GenomeEditorScreen(
                                    game = game,
                                    genomeName = null
                                )
                            },
                            onNext = { genomeName ->
                                println("kek ${genomeName}")
                                game.screen = GenomeEditorScreen(
                                    game = game,
                                    genomeName = genomeName
                                )
                            },
                            onRestart = {},
                            game = game,
                            onResize = { handler ->
                                onResize = if (handler == {}) null else handler
                            },
                             isMenu = true
                        ).show(stage)
                    }
                }
            }
        })

        table.add().height(10f * density).row()

        // Кнопка "Настройки"
        val optionsButton = VisTextButton(bundle.get("button.options"), "default")
        game.applyCustomFont(optionsButton)
        table.add(optionsButton).width(buttonWidth).height(buttonHeight).row()
        optionsButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                game.screen = SettingsScreen(game, multiPlatformFileProvider, bundle = bundle)
            }
        })

        table.add().height(10f * density).row()

        // Кнопка "Настройки субстрата"
        val substrateSettingsButton = VisTextButton(bundle.get("button.substrateSettings"), "default")
        game.applyCustomFont(substrateSettingsButton)
        table.add(substrateSettingsButton).width(buttonWidth).height(buttonHeight).row()
        substrateSettingsButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                game.screen = JsonEditorScreen(game, multiPlatformFileProvider, bundle = bundle)
            }
        })

        table.add().height(20f * density).row()

        // Кнопка "Выход"
        val exitButton = VisTextButton(bundle.get("button.exit"), "accent")
        game.applyCustomFont(exitButton)
        table.add(exitButton).width(buttonWidth).height(buttonHeight).row()
        exitButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                Gdx.app.exit()
            }
        })

        stage.addActor(table)
        Gdx.input.inputProcessor = stage

        // Отладка: проверьте размер шрифта в логах
        Gdx.app.log("FontDebug", "Menu font cap height: ${game.mediumFont.capHeight}")
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(0.12f, 0.12f, 0.18f, 1f)  // Используем цвет фона из скина
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        if (width == 0 || height == 0) return  // Avoid divide-by-zero on minimize
        stage.viewport.update(width, height, true)
        applyUIScale()
        onResize?.invoke()
    }
    
    fun applyUIScale() {
        val scale = GlobalSettings.UI_SCALE
        stage.root.scaleX = scale
        stage.root.scaleY = scale
    }

    override fun pause() {}
    override fun resume() {}
    override fun show() {}
    override fun hide() {}
    override fun dispose() {
        stage.dispose()
    }
}
