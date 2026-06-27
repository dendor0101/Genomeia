package io.github.some_example_name.old.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.badlogic.gdx.video.VideoPlayer
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.FileProvider
import io.github.some_example_name.old.editor.ui.GenomeEditorScreen
import io.github.some_example_name.old.systems.genomics.genome.GenomeJsonReader
import io.github.some_example_name.old.systems.render.CoreDesktopShaders
import io.github.some_example_name.old.ui.dialogs.GenomeListDialog

class MenuScreen(
    private val game: MyGame,
    val multiPlatformFileProvider: FileProvider
) : Screen {

    private val stage = CoreDesktopShaders.newStage(ScreenViewport())
    private val batch = CoreDesktopShaders.newBatch()
    private val shape = CoreDesktopShaders.newShapeRenderer()
    private val cam   = OrthographicCamera()

    private val video: VideoPlayer? = runCatching {
        val home = System.getProperty("user.home")
        val os   = System.getProperty("os.name").lowercase()
        val cacheDir = when {
            os.contains("win") -> (System.getenv("APPDATA") ?: "$home/AppData/Roaming") + "/Genomeia"
            os.contains("mac") -> "$home/Library/Application Support/Genomeia"
            else               -> "$home/.local/share/genomeia"
        }
        val cacheFile = Gdx.files.absolute("$cacheDir/bg.webm")
        if (!cacheFile.exists()) {
            val src = Gdx.files.internal("ui/bg.webm")
            if (!src.exists()) return@runCatching null
            cacheFile.parent().mkdirs()
            src.copyTo(cacheFile)
        }
        game.videoFactory?.invoke()?.also { p ->
            p.play(cacheFile)
            p.setOnCompletionListener { p.play(cacheFile) }
        }
    }.getOrNull()

    private val extraTextures = mutableListOf<Texture>()

    val genomeJsonReader: GenomeJsonReader = GenomeJsonReader()
    var onResize: (() -> Unit)? = null

    init {
        cam.setToOrtho(false, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())

        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()
        val density = Gdx.graphics.density

        val table = VisTable()
        table.setFillParent(true)
        table.left()
        table.pad(0f, (w * 0.04f).coerceAtLeast(24f), 0f, 0f)

        val titleLabel = VisLabel("GENOMEIA")
        titleLabel.style = Label.LabelStyle(game.titleFont, Color(STYLE_BEIGE))
        titleLabel.setAlignment(Align.left)
        table.add(titleLabel).left().padBottom(h * 0.018f).row()

        val btnW   = (w * 0.26f).coerceIn(180f * density, 520f * density)
        val btnGap = (h * 0.012f).coerceAtLeast(6f * density)

        data class Btn(val label: String, val action: () -> Unit)

        val btns = listOf(
            Btn(bundle.get("button.empty")) {
                val old = game.screen
                game.screen = SimulationScreen(multiPlatformFileProvider, game, null, bundle, null)
                old.dispose()
            },
            Btn(bundle.get("button.editor")) {
                val genomes = genomeJsonReader.getGenomeFileNamesFromFolder("user_genomes")
                if (genomes.isEmpty()) {
                    game.screen = GenomeEditorScreen(game = game, genomeName = null)
                } else {
                    GenomeListDialog(
                        genomesList = genomes, selectedGenomeIndex = null,
                        title  = bundle.get("button.selectGenome"),
                        new    = bundle.get("button.new"),
                        select = bundle.get("button.select"),
                        import = bundle.get("button.import"),
                        onNew  = { game.screen = GenomeEditorScreen(game = game, genomeName = null) },
                        onNext = { n -> game.screen = GenomeEditorScreen(game = game, genomeName = n) },
                        onRestart = {},
                        game = game,
                        onResize = { h -> onResize = if (h == {}) null else h },
                        isMenu = true
                    ).show(stage)
                }
            },
            Btn(bundle.get("button.options")) {
                game.screen = SettingsScreen(game, multiPlatformFileProvider, bundle = bundle)
            },
            Btn(bundle.get("button.substrateSettings")) {
                game.screen = EcoSystemScreen(game, multiPlatformFileProvider, bundle = bundle)
//                game.screen = JsonEditorScreen(game, multiPlatformFileProvider, bundle = bundle)
            },
            Btn(bundle.get("button.exit")) { Gdx.app.exit() }
        )

        val glyphLayout = GlyphLayout()
        for (btn in btns) {
            val b = makeStyledButton(btn.label, game, extraTextures)
            // Scale label to fill ~85% of button width
            glyphLayout.setText(game.buttonFont, btn.label)
            if (glyphLayout.width > 0f) {
                // Scale is almost always < 1 (shrinking from 48dp down), so no blur
                val scale = (btnW * 0.85f / glyphLayout.width).coerceIn(0.3f, 1.0f)
                b.label.setFontScale(scale)
            }
            val action = btn.action
            b.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) = action()
            })
            table.add(b).left().width(btnW).padBottom(btnGap).row()
        }

        stage.addActor(table)
        Gdx.input.inputProcessor = stage
    }

    private fun drawCover(tex: Texture, sw: Float, sh: Float) {
        val scale = maxOf(sw / tex.width, sh / tex.height)
        batch.draw(tex, (sw - tex.width * scale) / 2f, (sh - tex.height * scale) / 2f,
            tex.width * scale, tex.height * scale)
    }

    override fun render(delta: Float) {
        video?.update()

        Gdx.gl.glClearColor(0.04f, 0.04f, 0.06f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()

        batch.projectionMatrix = cam.combined
        batch.begin()
        video?.texture?.let { drawCover(it, w, h) }
        batch.end()

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shape.projectionMatrix = cam.combined
        shape.begin(ShapeRenderer.ShapeType.Filled)
        shape.rect(0f, 0f, w * 0.50f, h,
            Color(0.04f, 0.04f, 0.06f, 0.90f), Color(0.04f, 0.04f, 0.06f, 0f),
            Color(0.04f, 0.04f, 0.06f, 0f),    Color(0.04f, 0.04f, 0.06f, 0.90f))
        shape.end()
        Gdx.gl.glDisable(GL20.GL_BLEND)

        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        if (width == 0 || height == 0) return
        cam.setToOrtho(false, width.toFloat(), height.toFloat())
        stage.viewport.update(width, height, true)
        onResize?.invoke()
    }

    override fun pause()  {}
    override fun resume() {}
    override fun show()   {}
    override fun hide()   { video?.pause() }

    override fun dispose() {
        video?.dispose()
        stage.dispose()
        batch.dispose()
        shape.dispose()
        extraTextures.forEach { it.dispose() }
    }
}
