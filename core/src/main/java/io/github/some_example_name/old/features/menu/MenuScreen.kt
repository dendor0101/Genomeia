package io.github.some_example_name.old.features.menu

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Matrix4
import com.kotcrab.vis.ui.widget.VisTable
import io.github.some_example_name.old.commands.GoExit
import io.github.some_example_name.old.commands.GoGenomeEditor
import io.github.some_example_name.old.commands.GoSettings
import io.github.some_example_name.old.commands.GoSupport
import io.github.some_example_name.old.commands.GoWorldEditor
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
import io.github.some_example_name.old.core.DIGameGlobalContainer.genomeJsonReader
import io.github.some_example_name.old.core.ui.STYLE_BEIGE_BLACK
import io.github.some_example_name.old.core.ui.VisDslScreen
import io.github.some_example_name.old.core.ui.h
import io.github.some_example_name.old.core.ui.visLabel
import io.github.some_example_name.old.core.ui.visTable
import io.github.some_example_name.old.core.ui.visTextButton
import io.github.some_example_name.old.core.ui.w
import io.github.some_example_name.old.features.simulation.GenomeListDialog

class MenuScreen : VisDslScreen(
    background = Color(0f, 0f, 0f, 1f),
    isScrollable = false
) {

    private val batch = SpriteBatch()
    private val camera = OrthographicCamera()
    var onResize: (() -> Unit)? = null
    private val spriteBatch = SpriteBatch()
    private val font = BitmapFont()
    private val fontMatrix = Matrix4()
    private val shapeRenderer = ShapeRenderer()

    private val menuViewModel = MenuViewModel()

    init {
        println("MenuScreen init")
        menuViewModel.startMenuSimulation()
        menuViewModel.renderSystem.isRenderUi = false

        menuViewModel.renderSystem.create(
            fontMatrix = fontMatrix,
            spriteBatch = spriteBatch,
            font = font,
            shapeRenderer = shapeRenderer,
            camera = camera
        )
    }

    override fun dslShow() {
        camera.setToOrtho(false, w, h)
        camera.position.set(24f, 24f, 0f)
        camera.zoom = 0.02f
        camera.update()
    }

    override fun VisTable.compose() {
        visTable {
            visLabel(text = "GENOMEIA", font = game.buttonFont, textColor = STYLE_BEIGE_BLACK)
            row()

            visTextButton(bundle.get("button.empty"), onClick = {
                navigation.performCommand(GoWorldEditor)
            }) { growX() }
            row()

            visTextButton(bundle.get("button.editor"), onClick = {
                val genomes = genomeJsonReader.getGenomeFileNamesFromFolder()
                if (genomes.isEmpty()) {
                    navigation.performCommand(GoGenomeEditor(null))
                } else {
                    GenomeListDialog(
                        genomesList = genomes,
                        selectedGenomeIndex = null,
                        title = bundle.get("button.selectGenome"),
                        new = bundle.get("button.new"),
                        select = bundle.get("button.select"),
                        import = bundle.get("button.import"),
                        onNew = {
                            navigation.performCommand(GoGenomeEditor(null))
                        },
                        onNext = { n ->
                            navigation.performCommand(GoGenomeEditor(n))
                        },
                        onRestart = {},
                        game = game,
                        onResize = { h -> onResize = if (h == {}) null else h },
                        isMenu = true
                    ).show(stage)
                }
            }) { growX() }
            row()

            visTextButton(bundle.get("button.options"), onClick = {
                navigation.performCommand(GoSettings)
            }) { growX() }
            row()

            visTextButton(bundle.get("label.support"), onClick = {
                navigation.performCommand(GoSupport)
            }) { growX() }
            row()

            visTextButton(bundle.get("button.exit"), onClick = {
                navigation.performCommand(GoExit)
            }) { growX() }
            row()

            visLabel(text = "alpha-0.2.4", font = game.mediumFont, textColor = STYLE_BEIGE_BLACK)
            row()
        }
    }

    override fun dslRender(delta: Float) {
        menuViewModel.updateFrame()
        menuViewModel.moveCamera(camera, delta)

        batch.projectionMatrix = camera.combined
        batch.begin()
        batch.end()

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    override fun dslResize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()

        font.data.setScale(Gdx.graphics.density)

        menuViewModel.renderSystem.resize(width, height)
        spriteBatch.projectionMatrix = fontMatrix.setToOrtho2D(0f, 0f, w, h)

        onResize?.invoke()
    }

    override fun dslDispose() {
        batch.dispose()
        spriteBatch.dispose()
        font.dispose()
        shapeRenderer.dispose()
    }
}
