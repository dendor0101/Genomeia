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
import io.github.some_example_name.old.core.DIGameGlobalContainer.bundle
import io.github.some_example_name.old.core.DIGameGlobalContainer.game
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
                menuViewModel.handle(MenuIntent.OpenWorldEditor)
            }) { growX() }
            row()

            visTextButton(bundle.get("button.editor"), onClick = {
                // Решение «список или сразу пустой редактор» принимает ViewModel,
                // экран только показывает диалог, если её попросили.
                showEffect(menuViewModel.handle(MenuIntent.BrowseGenomes))
            }) { growX() }
            row()

            visTextButton(bundle.get("button.options"), onClick = {
                menuViewModel.handle(MenuIntent.OpenSettings)
            }) { growX() }
            row()

            visTextButton(bundle.get("label.support"), onClick = {
                menuViewModel.handle(MenuIntent.OpenSupport)
            }) { growX() }
            row()

            visTextButton(bundle.get("button.exit"), onClick = {
                menuViewModel.handle(MenuIntent.Exit)
            }) { growX() }
            row()

            visLabel(text = "alpha-0.2.4", font = game.mediumFont, textColor = STYLE_BEIGE_BLACK)
            row()
        }
    }

    /** Вёрстка того, что ViewModel попросила показать. */
    private fun showEffect(effect: MenuEffect) {
        when (effect) {
            is MenuEffect.None -> Unit

            is MenuEffect.ShowGenomeList -> GenomeListDialog(
                genomesList = effect.genomes,
                selectedGenomeIndex = null,
                title = bundle.get("button.selectGenome"),
                new = bundle.get("button.new"),
                select = bundle.get("button.select"),
                import = bundle.get("button.import"),
                onNew = { menuViewModel.handle(MenuIntent.OpenGenomeEditor(null)) },
                onNext = { name -> menuViewModel.handle(MenuIntent.OpenGenomeEditor(name)) },
                onRestart = {},
                game = game,
                onResize = { h -> onResize = if (h == {}) null else h },
                isMenu = true
            ).show(stage)
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
