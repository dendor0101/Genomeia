package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.ScreenUtils
import kotlin.collections.ArrayDeque

class GridGame : ApplicationAdapter() {
    private val gridSize = 32
    private val cellSize = 20f
    private val cursorSize = cellSize * 0.8f
    private lateinit var grid: Array<BooleanArray>
    private lateinit var cursor: Cursor
    private lateinit var shapeRenderer: ShapeRenderer

    override fun create() {
        // Инициализация сетки
        grid = Array(gridSize) { BooleanArray(gridSize) }
        cursor = Cursor(gridSize / 2, gridSize / 2) // Каретка в центре

        // Инициализация ShapeRenderer
        shapeRenderer = ShapeRenderer()

        // Обновляем сетку при старте
        updateGrid()
    }

    override fun render() {
        // Очистка экрана
        ScreenUtils.clear(0.4f, 0.4f, 0.4f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // Обработка ввода
        handleInput()

        // Отрисовка сетки и каретки
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)

        // Отрисовка сетки
        for (x in 0 until gridSize) {
            for (y in 0 until gridSize) {
                val color = if (grid[x][y]) Color.BLACK else Color.WHITE
                shapeRenderer.color = color
                shapeRenderer.rect(x * cellSize, y * cellSize, cellSize, cellSize)
            }
        }

        // Отрисовка каретки
        shapeRenderer.color = Color.RED
        shapeRenderer.rect(
            cursor.x * cellSize - (cursorSize - cellSize) / 2,
            cursor.y * cellSize - (cursorSize - cellSize) / 2,
            cursorSize,
            cursorSize
        )

        shapeRenderer.end()
    }

    private fun handleInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.W)) {
            cursor.y = (cursor.y + 1) % gridSize
            updateGrid()
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.S)) {
            cursor.y = (cursor.y - 1 + gridSize) % gridSize
            updateGrid()
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.A)) {
            cursor.x = (cursor.x - 1 + gridSize) % gridSize
            updateGrid()
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.D)) {
            cursor.x = (cursor.x + 1) % gridSize
            updateGrid()
        }
    }

    private fun updateGrid() {
        val visited = Array(gridSize) { BooleanArray(gridSize) }
        val queue = ArrayDeque<Pair<Int, Int>>()

        // Начинаем с клетки, на которой стоит каретка
        queue.add(Pair(cursor.x, cursor.y))
        visited[cursor.x][cursor.y] = true
        grid[cursor.x][cursor.y] = true // Черная клетка

        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()

            // Перебираем соседние клетки
            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue // Пропускаем текущую клетку

                    val nx = (x + dx + gridSize) % gridSize // Учитываем замкнутость сетки
                    val ny = (y + dy + gridSize) % gridSize

                    if (!visited[nx][ny]) {
                        visited[nx][ny] = true
                        grid[nx][ny] = !grid[x][y] // Инвертируем цвет
                        queue.add(Pair(nx, ny))
                    }
                }
            }
        }
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }
}

data class Cursor(var x: Int, var y: Int)

