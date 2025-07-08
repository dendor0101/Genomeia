package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RecursiveAction
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.system.measureTimeMillis


class GridBased : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera
    private val particles = mutableListOf<Particle>()
    private val grid = mutableMapOf<Pair<Int, Int>, MutableList<Particle>>()

    private val cellSize = 6
    private val initialParticleRadius = 1f
    private val maxRepulsionRadius = 3f
    private val friction = 1f
    private var pauseSimulation = false

    private val parallelThreshold = 5000
    private val forkJoinPool = ForkJoinPool(Runtime.getRuntime().availableProcessors())

    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var fpsStringBuilder: StringBuilder

    override fun create() {
        shapeRenderer = ShapeRenderer()
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)

        batch = SpriteBatch()
        font = BitmapFont()
        fpsStringBuilder = java.lang.StringBuilder()

        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                addParticlesAt(screenX, Gdx.graphics.height - screenY)
                return true
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                addParticlesAt(screenX, Gdx.graphics.height - screenY)
                println(particles.size)
                return true
            }
        }
    }

    private fun addParticlesAt(x: Int, y: Int) {
        val positions = listOf(
            0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1,
            1 to 1, -1 to -1, 1 to -1, -1 to 1,

            0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1,
            1 to 1, -1 to -1, 1 to -1, -1 to 1,
        )
        positions.forEach { (dx, dy) ->
            val particle = Particle(x + dx.toFloat(), y + dy.toFloat())
            particles.add(particle)
            addToGrid(particle)
        }
    }

    private fun addToGrid(particle: Particle) {
        val cell = getCell(particle.x, particle.y)
        grid.computeIfAbsent(cell) { mutableListOf() }.add(particle)
        particle.currentCell = cell
    }

    private fun updateGrid() {
        particles.forEach { particle ->
            val newCell = getCell(particle.x, particle.y)
            if (particle.currentCell != newCell) {
                grid[particle.currentCell]?.remove(particle)
                grid.computeIfAbsent(newCell) { mutableListOf() }.add(particle)
                particle.currentCell = newCell
            }
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)

        // Получаем текущий FPS
        val fps = Gdx.graphics.framesPerSecond
        fpsStringBuilder.setLength(0) // Очищаем StringBuilder
        fpsStringBuilder.append("FPS: ").append(fps)

        batch.begin()
        font.draw(batch, fpsStringBuilder.toString(), 20f, Gdx.graphics.getHeight() - 20f) // Выводим FPS в верхний левый угол
        batch.end()

        if (!pauseSimulation) {
            val parallelTime = measureTimeMillis {
                updateGrid()
            }
            println("updateGrid: $parallelTime")

            val parallelTime1 = measureTimeMillis {
                particles.forEach { it.move() }
            }
            println("move: $parallelTime1")

            val parallelTime2 = measureTimeMillis {
                if (particles.size > parallelThreshold) {
                    forkJoinPool.invoke(ParallelCheckNeighbors(particles, 0, particles.size))
                } else {
                    particles.forEach { checkNeighbors(it) }
                }
            }
            println("checkNeighbors: $parallelTime2")
        }
        shapeRenderer.end()

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = Color.WHITE
        particles.forEach { it.drawShell() }
        shapeRenderer.end()
    }

    private fun checkNeighbors(particle: Particle) {
        val cell = getCell(particle.x, particle.y)
        val neighbors = getNeighborCells(cell)
        for (neighborCell in neighbors) {
            grid[neighborCell]?.forEach { other ->
                if (other !== particle) {
                    particle.repel(other)
                }
            }
        }
    }

    private fun getCell(x: Float, y: Float) = (x / cellSize).toInt() to (y / cellSize).toInt()

    private fun getNeighborCells(cell: Pair<Int, Int>): List<Pair<Int, Int>> {
        val (cx, cy) = cell
        return listOf(
            cx to cy, cx + 1 to cy, cx - 1 to cy,
            cx to cy + 1, cx to cy - 1,
            cx + 1 to cy + 1, cx - 1 to cy - 1,
            cx + 1 to cy - 1, cx - 1 to cy + 1
        )
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    inner class Particle(var x: Float, var y: Float) {
        private var vx = 0f
        private var vy = 0f
        private var repulsionRadius = initialParticleRadius * 2
        var currentCell: Pair<Int, Int>? = null

        fun move() {
            x += vx
            y += vy
            vx *= friction
            vy *= friction
            if (maxRepulsionRadius * 1.1 > repulsionRadius) repulsionRadius += 0.03f
        }

        fun drawShell() {
            shapeRenderer.circle(x, y, repulsionRadius)
        }

        fun repel(other: Particle) {
            val dx = x - other.x
            val dy = y - other.y
            val distance = hypot(dx, dy)

            if (distance < repulsionRadius + other.repulsionRadius && distance > 0) {
                val angle = atan2(dy, dx)
                val force = (repulsionRadius + other.repulsionRadius - distance) * 0.1f

                vx += cos(angle) * force
                vy += sin(angle) * force

//                other.vx -= cos(angle) * force
//                other.vy -= sin(angle) * force
            }
        }
    }

    inner class ParallelCheckNeighbors(
        private val particles: List<Particle>,
        private val start: Int, private val end: Int
    ) : RecursiveAction() {
        override fun compute() {
            if (end - start <= parallelThreshold / Runtime.getRuntime().availableProcessors()) {
                for (i in start until end) {
                    checkNeighbors(particles[i])
                }
            } else {
                val mid = (start + end) / 2
                invokeAll(
                    ParallelCheckNeighbors(particles, start, mid),
                    ParallelCheckNeighbors(particles, mid, end)
                )
            }
        }
    }
}
