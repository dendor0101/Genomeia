package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.math.Vector3


class CaveGenerator : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera

    private val map = Array(350) { BooleanArray(350) }

    override fun create() {
        shapeRenderer = ShapeRenderer()
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)
//        generateMap(map)

        Gdx.input.inputProcessor = object : InputAdapter() {
            private var lastX = 0f
            private var lastY = 0f
            private var dragging = false

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                if (!dragging) {
                    lastX = screenX.toFloat()
                    lastY = screenY.toFloat()
                    dragging = true
                } else {
                    val deltaX = (lastX - screenX) * camera.zoom
                    val deltaY = (screenY - lastY) * camera.zoom
                    camera.translate(deltaX, deltaY)
                    lastX = screenX.toFloat()
                    lastY = screenY.toFloat()
                }
                return true
            }

            override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                dragging = false
                return true
            }

            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                val zoomFactor = 1f + amountY * 0.1f
                val newZoom = (camera.zoom * zoomFactor).coerceIn(0.005f, 10f)

                val mouseX = Gdx.input.x.toFloat()
                val mouseY = Gdx.input.y.toFloat()
                val worldCoordinatesBeforeZoom = camera.unproject(Vector3(mouseX, mouseY, 0f))

                camera.zoom = newZoom
                camera.update()

                val worldCoordinatesAfterZoom = camera.unproject(Vector3(mouseX, mouseY, 0f))
                val zoomAdjustment = worldCoordinatesBeforeZoom.sub(worldCoordinatesAfterZoom)
                camera.translate(zoomAdjustment.x, zoomAdjustment.y)

                return true
            }
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        camera.update()
        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
//        for (y in 0 until heightCaveGenerator) {
//            for (x in 0 until widthCaveGenerator) {
//                if (map[y][x]) {
//                    shapeRenderer.color = Color.WHITE
//                    shapeRenderer.rect(x * cellSize, y * cellSize, cellSize, cellSize)
//                }
//            }
//        }
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }
}




