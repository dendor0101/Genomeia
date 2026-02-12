package io.github.some_example_name.old.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.viewport.FitViewport
import com.kotcrab.vis.ui.util.TableUtils
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.widget.VisLabel
import io.github.some_example_name.old.genome.json.GenomeJsonReader
import io.github.some_example_name.old.genome_editor.GenomeEditorScreen
import io.github.some_example_name.old.good_one.CellSimulation
import io.github.some_example_name.old.good_one.GenomeListDialog
import io.github.some_example_name.old.platform_flag.FileProvider
import io.github.some_example_name.old.world_logic.cells.currentGenomeIndex
import java.util.Locale

class MenuScreen(
    private val game: MyGame,
    val multiPlatformFileProvider: FileProvider
) : Screen {

    private val stage = Stage(ScreenViewport())
    private val bundle: I18NBundle = I18NBundle.createBundle(Gdx.files.internal("ui/i18n/MyBundle"), Locale.getDefault())

    val genomeJsonReader: GenomeJsonReader = GenomeJsonReader()
    var onResize: (() -> Unit)? = null

    init {
        val density = Gdx.graphics.density
        val table = VisTable()
        TableUtils.setSpacingDefaults(table)
//        table.defaults().minWidth(400f)  // Увеличьте для места под большой текст
        table.columnDefaults(0).pad(10f * density)  // Больше отступов
        table.setFillParent(true)

        val genomeia = VisLabel(bundle.get("title.genomeia"))
        game.applyCustomFont(genomeia)
        genomeia.setAlignment(Align.center)
        table.add(genomeia).fillX().padBottom(10f).row()

        val emptyButton = VisTextButton(bundle.get("button.empty"))
        emptyButton.pad(4f)
        game.applyCustomFont(emptyButton)
        table.add(emptyButton).fillX().height(30f * density).row()
        emptyButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                game.screen = WorldEditorScreen(
                    multiPlatformFileProvider = multiPlatformFileProvider,
                    game = game,
                    bundle = bundle
                )
            }
        })

        currentGenomeIndex = 0
        val genomeEditorButton = VisTextButton(bundle.get("button.editor"))
        genomeEditorButton.pad(4f)
        game.applyCustomFont(genomeEditorButton)
        table.add(genomeEditorButton).fillX().height(30f * density).row()
        genomeEditorButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                val genomes = genomeJsonReader.getGenomeFileNamesFromFolder("user_genomes")

                when (genomes.size) {
                    0 -> game.screen = GenomeEditorScreen(
                        multiPlatformFileProvider = multiPlatformFileProvider,
                        game = game,
                        genomeName = null,
                        bundle = bundle
                    )
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
                                    multiPlatformFileProvider = multiPlatformFileProvider,
                                    game = game,
                                    genomeName = null,
                                    bundle = bundle
                                )
                            },
                            onNext = { genomeName ->
                                game.screen = GenomeEditorScreen(
                                    multiPlatformFileProvider = multiPlatformFileProvider,
                                    game = game,
                                    genomeName = "$genomeName.json",
                                    bundle = bundle
                                )
                            },
                            onRestart = {

                            },
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

        val optionsButton = VisTextButton(bundle.get("button.options"))
        optionsButton.pad(4f)
        game.applyCustomFont(optionsButton)
        table.add(optionsButton).fillX().height(30f * density).row()
        optionsButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                game.screen = SettingsScreen(game, multiPlatformFileProvider, bundle = bundle)
            }
        })

        val substrateSettingsButton = VisTextButton(bundle.get("button.substrateSettings"))
        substrateSettingsButton.pad(4f)
        game.applyCustomFont(substrateSettingsButton)
        table.add(substrateSettingsButton).fillX().height(30f * density).row()
        substrateSettingsButton.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent, x: Float, y: Float) {
                game.screen = JsonEditorScreen(game, multiPlatformFileProvider, bundle = bundle)
            }
        })

        val exitButton = VisTextButton(bundle.get("button.exit"))
        emptyButton.pad(4f)
        game.applyCustomFont(exitButton)
        table.add(exitButton).fillX().height(30f * density).row()
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
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        if (width == 0 || height == 0) return  // Avoid divide-by-zero on minimize
        stage.viewport.update(width, height, true)
        onResize?.invoke()
    }

    override fun pause() {}
    override fun resume() {}
    override fun show() {}
    override fun hide() {}
    override fun dispose() {
        stage.dispose()
    }
}
