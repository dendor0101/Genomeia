package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import java.util.concurrent.Executors
import kotlin.math.*

class ParticleSimulationSimplified : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera
    private val particles = mutableListOf<Particle>()

    private val threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    private var running = true

    private val initialParticleRadius = 1f
    private val maxRepulsionRadius = 5f
    private val friction = 0.95f
    private val fixedTimeStep = 1f / 60f // Фиксированное обновление 60 FPS

    override fun create() {
        shapeRenderer = ShapeRenderer()
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)

        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                synchronized(particles) {
                    val y = Gdx.graphics.height - screenY
                    particles.add(Particle(screenX.toFloat(), y.toFloat()))
                    particles.add(Particle(screenX.toFloat() + 1, y.toFloat()))
                    particles.add(Particle(screenX.toFloat() + 0, y.toFloat()))
                    particles.add(Particle(screenX.toFloat() - 1, y.toFloat()))
                    particles.add(Particle(screenX.toFloat() + 1, y.toFloat() - 1))
                    particles.add(Particle(screenX.toFloat() + 0, y.toFloat() - 1))
                    particles.add(Particle(screenX.toFloat() - 1, y.toFloat() - 1))
                    particles.add(Particle(screenX.toFloat() + 1, y.toFloat()+ 1))
                    particles.add(Particle(screenX.toFloat() + 0, y.toFloat()+ 1))
                    particles.add(Particle(screenX.toFloat() - 1, y.toFloat()+ 1))
                }
                return true
            }
            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                synchronized(particles) {
                    val y = Gdx.graphics.height - screenY
                    particles.add(Particle(screenX.toFloat(), y.toFloat()))
                    particles.add(Particle(screenX.toFloat() + 1, y.toFloat()))
                    particles.add(Particle(screenX.toFloat() + 0, y.toFloat()))
                    particles.add(Particle(screenX.toFloat() - 1, y.toFloat()))
                    particles.add(Particle(screenX.toFloat() + 1, y.toFloat() - 1))
                    particles.add(Particle(screenX.toFloat() + 0, y.toFloat() - 1))
                    particles.add(Particle(screenX.toFloat() - 1, y.toFloat() - 1))
                    particles.add(Particle(screenX.toFloat() + 1, y.toFloat()+ 1))
                    particles.add(Particle(screenX.toFloat() + 0, y.toFloat()+ 1))
                    particles.add(Particle(screenX.toFloat() - 1, y.toFloat()+ 1))
                    println(particles.size)
                }
                return true
            }
        }

        // Запуск потока обновления
        threadPool.execute { updateLoop() }
    }

    private fun updateLoop() {
        var lastTime = System.nanoTime()

        while (running) {
            val now = System.nanoTime()
            val deltaTime = (now - lastTime) / 1_000_000_000f
            if (deltaTime < fixedTimeStep) {
                Thread.sleep(1)
                continue
            }
            lastTime = now

            val snapshot: List<Particle>
            synchronized(particles) {
                snapshot = particles.toList() // Создаем копию списка частиц
            }

            // Разделяем работу между потоками
            val batchSize = max(1, snapshot.size / Runtime.getRuntime().availableProcessors())
            val futures = mutableListOf<Runnable>()

            // Параллельное обновление движения
            for (i in snapshot.indices step batchSize) {
                val end = min(i + batchSize, snapshot.size)
                futures.add(Runnable {
                    for (j in i until end) {
                        snapshot[j].move(deltaTime)
                    }
                })
            }

            futures.map { threadPool.submit(it) }.forEach { it.get() } // Ждем выполнения

            // Параллельное обновление взаимодействия
            futures.clear()
            for (i in snapshot.indices step batchSize) {
                val end = min(i + batchSize, snapshot.size)
                futures.add(Runnable {
                    for (j in i until end) {
                        for (k in j + 1 until snapshot.size) {
                            snapshot[j].repel(snapshot[k])
                        }
                    }
                })
            }

            futures.map { threadPool.submit(it) }.forEach { it.get() } // Ждем выполнения
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = Color.WHITE

        synchronized(particles) {
            particles.forEach { it.drawShell() }
        }

        shapeRenderer.end()
    }

    override fun dispose() {
        running = false
        threadPool.shutdown()
        shapeRenderer.dispose()
    }

    inner class Particle(var x: Float, var y: Float) {
        private var vx = 0f
        private var vy = 0f
        private var repulsionRadius = initialParticleRadius * 2

        fun move(deltaTime: Float) {
            x += vx /** deltaTime * 60*/ // Нормализация на 60 FPS
            y += vy /** deltaTime * 60*/
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
            if (dx > repulsionRadius + other.repulsionRadius) return
            if (dy > repulsionRadius + other.repulsionRadius) return
            val distance = hypot(dx, dy)

            if (distance < repulsionRadius + other.repulsionRadius && distance > 0) {
                val angle = atan2(dy, dx)
                val force = (repulsionRadius + other.repulsionRadius - distance) * 0.05f

                vx += cos(angle) * force
                vy += sin(angle) * force

                other.vx -= cos(angle) * force
                other.vy -= sin(angle) * force
            }
        }

        fun fastDistance(dx: Float, dy: Float): Float {
            val absDx = abs(dx)
            val absDy = abs(dy)
            return 0.9604f * max(absDx, absDy) + 0.3984f * min(absDx, absDy)
        }
    }
}
