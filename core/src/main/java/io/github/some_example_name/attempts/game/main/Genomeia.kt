package io.github.some_example_name.attempts.game.main

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector2
import io.github.some_example_name.attempts.game.logutils.fpsStringBuilder
import io.github.some_example_name.attempts.game.main.Genomeia.Companion.INIT_FRICTION
import io.github.some_example_name.attempts.game.physics.PhysicsManager
import io.github.some_example_name.attempts.game.physics.WorldGenerator
import io.github.some_example_name.attempts.game.render.RenderManager
import io.github.some_example_name.attempts.game.sound.SoundManager
import kotlin.math.*

//DenDor studio  Game name: "Genomeia"

var lastX = 0f
var lastY = 0f
val zoomSpeed
    get() = zoomScale * zoomScale / 15
val minZoom = 1f
val maxZoom = 15f

var zoomScale = 1f
var cameraOffset = Vector2(0f, 0f)
var friction = INIT_FRICTION  // Коэффициент трения
var isWallMovable = true

var generationCounter: Int? = 30 //TODO костыль
@Volatile var isPaused = false

class Genomeia : ApplicationAdapter() {

    companion object {
        const val MAX_REPULSION_RADIUS = 5f
        const val INIT_PARTICLE_RADIUS = 1f
        const val INIT_FRICTION = 0.0005f
        const val FRICTION = 0.83f
        const val GRID_SIZE = 512
        const val CELL_SIZE = MAX_REPULSION_RADIUS * 2
        const val WORLD_SIZE = GRID_SIZE * CELL_SIZE
        const val REST_LENGTH = 10f
        const val STIFFNESS = 0.08f
    }

    //LIBGDX структуры
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera
    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont

    lateinit var gameController: GameController

    override fun create() {
        shapeRenderer = ShapeRenderer()
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)

        batch = SpriteBatch()
        font = BitmapFont()
        fpsStringBuilder = java.lang.StringBuilder()

        //Sorta DI
        val worldGridManager = WorldGridManager()
        val soundManager = SoundManager()
        gameController = GameController(
            renderManager = RenderManager(
                shapeRenderer = shapeRenderer,
                batch = batch,
                font = font,
            ),
            worldGenerator = WorldGenerator(),
            worldGridManager = worldGridManager,
            physicsManager = PhysicsManager(worldGridManager, soundManager)
        )

        lastX = Gdx.graphics.width / 2f
        lastY = Gdx.graphics.height / 2f
        Gdx.input.isCursorCatched = true // Привязать курсор к окну
        Gdx.input.setCursorPosition(lastX.roundToInt(), lastY.roundToInt())

        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (keycode == Input.Keys.ESCAPE) {
                    Gdx.app.exit() // Закрыть приложение
                }
                if (keycode == 62) {
                    isPaused = !isPaused
                    return true
                }
                return true
            }

            override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
                val deltaX = (lastX - screenX) / zoomScale
                val deltaY = (screenY - lastY) / zoomScale
                lastX = screenX.toFloat()
                lastY = screenY.toFloat()
                cameraOffset.add(deltaX, deltaY)
                gameController.moveCamera(cameraOffset)
                return true
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                val deltaX = (lastX - screenX) / zoomScale
                val deltaY = (screenY - lastY) / zoomScale
                lastX = screenX.toFloat()
                lastY = screenY.toFloat()
                cameraOffset.add(deltaX, deltaY)
                gameController.moveCamera(cameraOffset)
                return true
            }

            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (button == Input.Buttons.LEFT) {
                    val x = -1 * cameraOffset.x + Gdx.graphics.width / 2f
                    val y = -1 * cameraOffset.y + Gdx.graphics.height / 2f
                    gameController.click(x, y)
                }
                return true
            }

            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                val zoomDirection = if (amountY > 0) -1f else 1f
                val newZoomScale = (zoomScale + zoomDirection * zoomSpeed).coerceIn(minZoom, maxZoom)
                // Обновляем масштаб
                zoomScale = newZoomScale
                gameController.zoom(zoomScale)
                return true
            }
        }

        gameController.init()
    }

    override fun render() {
        shapeRenderer.projectionMatrix = camera.combined
        gameController.renderTick()
    }

    override fun dispose() {
        shapeRenderer.dispose()
        batch.dispose()
        font.dispose()
    }
}
