package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.Array

class VoronoiNoise : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private val points = Array<Vector2>()
    private val numPoints = 20 // Количество точек
    private val pointRadius = 5f
    private var selectedPoint: Vector2? = null

    override fun create() {
        shapeRenderer = ShapeRenderer()
        generateRandomPoints()
    }

    private fun generateRandomPoints() {
        points.clear()
        for (i in 0 until numPoints) {
            val x = (0 until Gdx.graphics.width).random().toFloat()
            val y = (0 until Gdx.graphics.height).random().toFloat()
            points.add(Vector2(x, y))
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(1f, 1f, 1f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        handleInput()

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        drawPoints()
        shapeRenderer.end()

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        drawVoronoiEdges()
        shapeRenderer.end()
    }

    private fun handleInput() {
        if (Gdx.input.isTouched) {
            val mouseX = Gdx.input.x.toFloat()
            val mouseY = Gdx.graphics.height - Gdx.input.y.toFloat()

            if (selectedPoint == null) {
                for (point in points) {
                    if (point.dst(mouseX, mouseY) < pointRadius) {
                        selectedPoint = point
                        break
                    }
                }
            } else {
                selectedPoint?.set(mouseX, mouseY)
            }
        } else {
            selectedPoint = null
        }
    }

    private fun drawPoints() {
        shapeRenderer.color = Color.RED
        for (point in points) {
            shapeRenderer.circle(point.x, point.y, pointRadius)
        }
    }

    private fun drawVoronoiEdges() {
        shapeRenderer.color = Color.BLACK

        // Размер экрана
        val width = Gdx.graphics.width
        val height = Gdx.graphics.height

        // Для каждого пикселя проверяем, есть ли граница с соседними пикселями
        for (x in 0 until width step 2) { // Шаг 2 для оптимизации
            for (y in 0 until height step 2) {
                val closest = findClosestPoint(x.toFloat(), y.toFloat())

                // Проверяем соседние пиксели
                if (x > 0) {
                    val leftClosest = findClosestPoint((x - 1).toFloat(), y.toFloat())
                    if (closest != leftClosest) {
                        // Рисуем толстую линию (3 пикселя)
                        for (i in -1..1) {
                            shapeRenderer.line(x.toFloat(), y.toFloat() + i, x.toFloat(), (y + 1).toFloat() + i)
                        }
                    }
                }
                if (y > 0) {
                    val topClosest = findClosestPoint(x.toFloat(), (y - 1).toFloat())
                    if (closest != topClosest) {
                        // Рисуем толстую линию (3 пикселя)
                        for (i in -1..1) {
                            shapeRenderer.line(x.toFloat() + i, y.toFloat(), (x + 1).toFloat() + i, y.toFloat())
                        }
                    }
                }
            }
        }
    }

    private fun findClosestPoint(x: Float, y: Float): Vector2 {
        var closest = points.first()
        var minDistance = Float.MAX_VALUE

        for (point in points) {
            val distance = point.dst2(x, y)
            if (distance < minDistance) {
                minDistance = distance
                closest = point
            }
        }

        return closest
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }
}
