package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import kotlin.random.Random

class ZoomGame : ApplicationAdapter() {
    private lateinit var camera: OrthographicCamera
    private lateinit var shapeRenderer: ShapeRenderer
    private val circles = mutableListOf<CircleData>()

    private var zoomLevel = 1f
    private val minZoom = 0.33f
    private val maxZoom = 2f

    private val screenWidth = 960f
    private val screenHeight = 960f

    private var lastTouch = Vector2()
    private var dragging = false

    override fun create() {
        camera = OrthographicCamera(screenWidth, screenHeight)
        camera.setToOrtho(false, screenWidth, screenHeight)

        shapeRenderer = ShapeRenderer()

        repeat(50) {
            circles.add(
                CircleData(
                    x = Random.nextFloat() * screenWidth * 2,
                    y = Random.nextFloat() * screenHeight * 2,
                    radius = 20f
                )
            )
        }

        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                if (amountY != 0f) {
                    // Получаем координаты мыши в мировых координатах
                    val mouseX = Gdx.input.x.toFloat()
                    val mouseY = Gdx.graphics.height - Gdx.input.y.toFloat()
                    applyZoom(if (amountY > 0) 0.9f else 1.1f, mouseX, mouseY)
                }
                return true
            }

            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (button == Input.Buttons.LEFT) {
                    lastTouch.set(screenX.toFloat(), screenY.toFloat())
                    dragging = true
                    return true
                }
                return false
            }

            override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (button == Input.Buttons.LEFT) {
                    dragging = false
                    return true
                }
                return false
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                if (dragging) {
                    val deltaX = (lastTouch.x - screenX) * camera.zoom
                    val deltaY = (screenY - lastTouch.y) * camera.zoom

                    camera.translate(deltaX, deltaY)
                    camera.update()

                    lastTouch.set(screenX.toFloat(), screenY.toFloat())
                    return true
                }
                return false
            }
        }
    }

    private fun applyZoom(zoomFactor: Float, mouseScreenX: Float, mouseScreenY: Float) {
        // 1. Запоминаем мировые координаты курсора до зума
        val mouseWorldBefore = Vector3(mouseScreenX, mouseScreenY, 0f)
        camera.unproject(mouseWorldBefore)

        // 2. Изменяем зум
        val newZoom = MathUtils.clamp(zoomLevel * zoomFactor, minZoom, maxZoom)
        if (newZoom == zoomLevel) return

        // 3. Применяем новый зум
        camera.zoom = newZoom

        // 4. Вычисляем мировые координаты курсора после зума
        val mouseWorldAfter = Vector3(mouseScreenX, mouseScreenY, 0f)
        camera.unproject(mouseWorldAfter)

        // 5. Корректируем позицию камеры
        camera.position.add(
            mouseWorldBefore.x - mouseWorldAfter.x,
            mouseWorldBefore.y - mouseWorldAfter.y,
            0f
        )

        zoomLevel = newZoom
        camera.update()
    }

    override fun render() {
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        circles.forEach { circle ->
            shapeRenderer.circle(circle.x, circle.y, circle.radius)
        }
        shapeRenderer.end()
    }

    override fun resize(width: Int, height: Int) {
        camera.setToOrtho(false, screenWidth, screenHeight)
        camera.update()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }
}

data class CircleData(
    val x: Float,
    val y: Float,
    val radius: Float
)
