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
import kotlin.math.*
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RecursiveAction
import java.util.concurrent.locks.ReentrantLock
import kotlin.system.measureTimeMillis

class GridBasedOptimized : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera
    private val particles = mutableListOf<Particle>()
    private val grid = mutableMapOf<Pair<Int, Int>, MutableList<Particle>>()

    private val cellSize = 10
    private val initialParticleRadius = 1f
    private val maxRepulsionRadius = 5f
    private val friction = 0.99f
    private var pauseSimulation = false

    private val parallelThreshold = 5_000
    private val forkJoinPool = ForkJoinPool((Runtime.getRuntime().availableProcessors()))
    private val physicsLock = ReentrantLock()
    private var lastPhysicsTime = System.nanoTime()
    private var physicsThread: Thread? = null

    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var fpsStringBuilder: StringBuilder


    @Volatile
    var phisicsTimeTic: Long = 0
    @Volatile
    var phisicsTimeTic1: Long = 0
    @Volatile
    var phisicsTimeTic2: Long = 0
    @Volatile
    var phisicsTimeTic3: Long = 0

    @Volatile
    var checkNeighborsTic1: Long = 0
    @Volatile
    var checkNeighborsTic2: Long = 0
    @Volatile
    var checkNeighborsTic3: Long = 0
    @Volatile
    var updateGrid: Long = 0

    var drawTime: Long = 0
    var particlesAmount: Long = 0


    var cameraWidth = Pair(100, 200)
    var cameraHeight = Pair(100, 200)

    override fun create() {
        shapeRenderer = ShapeRenderer()
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)

        batch = SpriteBatch()
        font = BitmapFont()
        fpsStringBuilder = java.lang.StringBuilder()
        cameraWidth = Pair(Gdx.graphics.width / 3, Gdx.graphics.width * 2 / 3)
        cameraHeight = Pair(Gdx.graphics.height / 3, Gdx.graphics.height * 2 / 3)
        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (Gdx.graphics.framesPerSecond > 0) {
                    addParticlesAt(screenX, Gdx.graphics.height - screenY)
                }
                return true
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                if (Gdx.graphics.framesPerSecond > 0) {
                    addParticlesAt(screenX, Gdx.graphics.height - screenY)
                }
                return true
            }
        }

        startPhysicsThread()
    }

    private fun startPhysicsThread() {
        physicsThread = Thread {
            while (true) {
                val currentTime = System.nanoTime()
                val deltaTime = (currentTime - lastPhysicsTime) / 1e9f
                lastPhysicsTime = currentTime
                if (!pauseSimulation) {
                    phisicsTimeTic = measureTimeMillis {
                        updatePhysics(deltaTime)
                    }
                }
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun addParticlesAt(x: Int, y: Int) {
        val positions = listOf(
            0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1,
            1 to 1, -1 to -1, 1 to -1, -1 to 1,

            2 to 0, -2 to 0, 0 to 2, 0 to -2,
            2 to 2, -2 to -2, 2 to -2, -1 to 2,

            3 to 0, -3 to 0, 0 to 3, 0 to -3,
            3 to 3, -3 to -3, 3 to -3, -3 to 3,

            4 to 0, -4 to 0, 0 to 4, 0 to -4,
            4 to 4, -4 to -4, 4 to -4, -4 to 4,

            5 to 0, -5 to 0, 0 to 5, 0 to -5,
            5 to 5, -5 to -5, 5 to -5, -5 to 5
        )
        physicsLock.lock()
        positions.forEach { (dx, dy) ->
            val particle = Particle(x + dx.toFloat(), y + dy.toFloat())
            particles.add(particle)
            addToGrid(particle)
        }
        physicsLock.unlock()
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

    private fun updatePhysics(deltaTime: Float) {
        physicsLock.lock()
        phisicsTimeTic1 = measureTimeMillis {
            updateGrid()
        }

        phisicsTimeTic2 = measureTimeMillis {
            if (particles.size > parallelThreshold) {
                forkJoinPool.invoke(ParallelCheckNeighbors(particles, 0, particles.size))
            } else {
                particles.forEach { checkNeighbors(it) }
            }
        }

        phisicsTimeTic3 = measureTimeMillis {
            particles.forEach { it.move(deltaTime) }
        }
        physicsLock.unlock()
    }

    var counterAvarage = 0
    var phisicsTimeTicAvarage = 0f
    var phisicsTimeTicAvarage1 = 0f
    var phisicsTimeTicAvarage2 = 0f
    var phisicsTimeTicAvarage3 = 0f
    var drawTimeAvarage = 0f
    var fpsAvarage = 0f
    val avarage = 10


    override fun render() {
        drawTime = measureTimeMillis {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

            val fps = Gdx.graphics.framesPerSecond
            counterAvarage++
            phisicsTimeTicAvarage += phisicsTimeTic
            phisicsTimeTicAvarage1 += phisicsTimeTic1
            phisicsTimeTicAvarage2 += phisicsTimeTic2
            phisicsTimeTicAvarage3 += phisicsTimeTic3
            drawTimeAvarage += drawTime
            fpsAvarage += fps

            if (counterAvarage >= avarage) {
                fpsStringBuilder.setLength(0) // Очищаем StringBuilder
                fpsStringBuilder.append("FPS: ").append(fpsAvarage / avarage)
                    .append("\nDraw time:").append(drawTimeAvarage / avarage)
                    .append("\nMath time:").append(phisicsTimeTicAvarage / avarage)
                    .append("\n     updateGrid:").append(phisicsTimeTicAvarage1 / avarage)
                    .append("\n     checkNeighbors:").append(phisicsTimeTicAvarage2 / avarage)
                    .append("\n     move:").append(phisicsTimeTicAvarage3 / avarage)
                    .append("\nParticles amount:").append(particlesAmount)
                counterAvarage = 0
                phisicsTimeTicAvarage = 0f
                phisicsTimeTicAvarage1 = 0f
                phisicsTimeTicAvarage2 = 0f
                phisicsTimeTicAvarage3 = 0f
                drawTimeAvarage = 0f
                fpsAvarage = 0f
            }

            // Получаем текущий FPS


            batch.begin()
            font.draw(
                batch,
                fpsStringBuilder.toString(),
                20f,
                Gdx.graphics.getHeight() - 20f
            ) // Выводим FPS в верхний левый угол
            batch.end()

            shapeRenderer.projectionMatrix = camera.combined
            shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
//            physicsLock.lock()
            particlesAmount = particles.size.toLong()
            particles.forEach {
                if (it.x > cameraWidth.first && it.x < cameraWidth.second && it.y > cameraHeight.first && it.y < cameraHeight.second) {
                    it.drawShell()
                }
            }
//            physicsLock.unlock()


            shapeRenderer.end()
        }
    }

    private fun checkNeighbors(particle: Particle) {
        val cell = getCell(particle.x, particle.y)//

        val neighbors = getNeighborCells(cell)//
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

        fun move(deltaTime: Float) {
            val fps = Gdx.graphics.framesPerSecond
            x += vx * deltaTime * 60//* fps
            y += vy * deltaTime * 60//* fps
            vx *= friction
            vy *= friction
            if (maxRepulsionRadius * 1.1 > repulsionRadius) repulsionRadius += 0.003f
        }

        fun drawShell() {
            shapeRenderer.color = Color.CORAL
            shapeRenderer.circle(x, y, repulsionRadius + 1f)
            val speed = sqrt(vx * vx + vy * vy)
            val color = Color(1f, 1f - speed / 2f, 1f - speed / 2f, 1f)
            shapeRenderer.color = color
            shapeRenderer.circle(x, y, repulsionRadius)
        }

        fun repel(other: Particle) {
            val dx = x - other.x
            val dy = y - other.y
            if (dx > repulsionRadius + other.repulsionRadius) return
            if (dy > repulsionRadius + other.repulsionRadius) return
            val distance = hypot(dx, dy)

            if (distance < repulsionRadius + other.repulsionRadius && distance > 0) {
                val angle = atan2(dy, dx)
                val force = (repulsionRadius + other.repulsionRadius - distance) * 0.025f

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
            println(Runtime.getRuntime().availableProcessors())
            if (end - start <= parallelThreshold / (Runtime.getRuntime().availableProcessors())) {
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
