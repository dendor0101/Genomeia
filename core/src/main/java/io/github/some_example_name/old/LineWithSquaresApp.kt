package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import io.github.some_example_name.attempts.game.physics.invSqrt
import io.github.some_example_name.old.good_one.cells.visitedGrid
import kotlin.math.*

class LineWithSquaresApp : ApplicationAdapter(), InputProcessor {
    private lateinit var shapeRenderer: ShapeRenderer
    private var startX = 100f
    private var startY = 100f
    private var endX = 300f
    private var endY = 300f
    private var pixelSize = 40f
    private var draggingStart = false
    private var draggingEnd = false
    private val visitedCells = mutableListOf<Pair<Int, Int>>()

    override fun create() {
        shapeRenderer = ShapeRenderer()
        Gdx.input.inputProcessor = this
        updateVisitedCells()
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)


        // Draw visited cells (squares around the line)
        shapeRenderer.begin(ShapeType.Filled)
        shapeRenderer.color = Color(0.5f, 0.5f, 1f, 0.5f) // Semi-transparent blue
        for ((gx, gy) in visitedCells) {
            val x = gx * pixelSize
            val y = gy * pixelSize
            shapeRenderer.rect(x, y, pixelSize, pixelSize)
        }
        shapeRenderer.end()

        // Draw grid
        shapeRenderer.begin(ShapeType.Line)
        shapeRenderer.color = Color.DARK_GRAY
        val gridWidth = Gdx.graphics.width
        val gridHeight = Gdx.graphics.height
        for (x in 0 until gridWidth step pixelSize.toInt()) {
            shapeRenderer.line(x.toFloat(), 0f, x.toFloat(), gridHeight.toFloat())
        }
        for (y in 0 until gridHeight step pixelSize.toInt()) {
            shapeRenderer.line(0f, y.toFloat(), gridWidth.toFloat(), y.toFloat())
        }
        shapeRenderer.end()
        // Draw the line
        shapeRenderer.begin(ShapeType.Line)
        shapeRenderer.color = Color.RED
        shapeRenderer.line(startX, startY, endX, endY)
        shapeRenderer.end()

        // Draw start and end points
        shapeRenderer.begin(ShapeType.Filled)
        shapeRenderer.color = Color.GREEN
        shapeRenderer.circle(startX, startY, 10f)
        shapeRenderer.color = Color.YELLOW
        shapeRenderer.circle(endX, endY, 10f)
        shapeRenderer.end()
    }

    private fun updateVisitedCells() {
        visitedCells.clear()
        drawThickLineGridTraversal(startX, startY, endX, endY, pixelSize)
    }

    private fun drawThickLineGridTraversal(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        pixelSize: Float = 40f
    ) {
        visitedGrid.clear()
        // Ваш алгоритм здесь
        val dx = x2 - x1
        val dy = y2 - y1

        var gx = floor(x1 / pixelSize).toInt()
        var gy = floor(y1 / pixelSize).toInt()
        val gxEnd = floor(x2 / pixelSize).toInt()
        val gyEnd = floor(y2 / pixelSize).toInt()
        visitedGrid.offsetX = min(gx, gxEnd) - 1
        visitedGrid.offsetY = min(gy, gyEnd) - 1

        val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0

        val tDeltaX = if (dx == 0f) Float.POSITIVE_INFINITY else abs(pixelSize / dx)
        val tDeltaY = if (dy == 0f) Float.POSITIVE_INFINITY else abs(pixelSize / dy)

        val xBound = (gx + if (stepX > 0) 1 else 0) * pixelSize
        val yBound = (gy + if (stepY > 0) 1 else 0) * pixelSize

        var tMaxX = if (dx == 0f) Float.POSITIVE_INFINITY else abs((xBound - x1) / dx)
        var tMaxY = if (dy == 0f) Float.POSITIVE_INFINITY else abs((yBound - y1) / dy)

        val dirLength = 1.0f / invSqrt(dx * dx + dy * dy)
        val normX = if (dirLength > 0) -dy / dirLength else 0f
        val normY = if (dirLength > 0) dx / dirLength else 0f

        while (true) {
            // Центр и 2 соседние ячейки по нормали для толщины = 3
            for (i in -1..1) {
                val nx = gx + round(normX * i).toInt()
                val ny = gy + round(normY * i).toInt()
                if (!visitedGrid.isVisited(nx, ny)) {
                    visitedGrid.markVisited(nx, ny)
                    visitedCells.add(nx to ny)
                } else {
                    println("the same")
                }
            }

            if (gx == gxEnd && gy == gyEnd) break

            if (tMaxX < tMaxY) {
                tMaxX += tDeltaX
                gx += stepX
            } else {
                tMaxY += tDeltaY
                gy += stepY
            }
        }
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    // InputProcessor methods
    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        val y = Gdx.graphics.height - screenY // Convert to libGDX coordinate system

        // Check if clicked on start or end point
        if (sqrt((screenX - startX).pow(2) + (y - startY).pow(2)) < 20) {
            draggingStart = true
            return true
        }
        if (sqrt((screenX - endX).pow(2) + (y - endY).pow(2)) < 20) {
            draggingEnd = true
            return true
        }
        return false
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        val y = Gdx.graphics.height - screenY
        if (draggingStart) {
            startX = screenX.toFloat()
            startY = y.toFloat()
            updateVisitedCells()
            return true
        }
        if (draggingEnd) {
            endX = screenX.toFloat()
            endY = y.toFloat()
            updateVisitedCells()
            return true
        }
        return false
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        draggingStart = false
        draggingEnd = false
        return true
    }

    override fun touchCancelled(p0: Int, p1: Int, p2: Int, p3: Int): Boolean {
        return true
    }

    override fun mouseMoved(screenX: Int, screenY: Int): Boolean = false
    override fun scrolled(amountX: Float, amountY: Float): Boolean = false
    override fun keyDown(keycode: Int): Boolean = false
    override fun keyUp(keycode: Int): Boolean = false
    override fun keyTyped(character: Char): Boolean = false
}
