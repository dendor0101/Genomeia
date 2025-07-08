package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

class ParticleSimulationSimplified2 : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera
    private val particles = mutableListOf<Particle>()

    private val threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    private var running = true

    private val initialParticleRadius = 5f
    private val maxRepulsionRadius = 50f
    private val friction = 0.95f
    private val fixedTimeStep = 1f / 60f // Фиксированное обновление 60 FPS

    // Границы симуляции
    private lateinit var bounds: Rectangle

    // Quadtree для оптимизации поиска соседей
    private lateinit var quadtree: Quadtree

    override fun create() {
        shapeRenderer = ShapeRenderer()
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)
        bounds = Rectangle(0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        quadtree = Quadtree(bounds, 4) // Инициализация quadtree

        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                addParticles(screenX, screenY)
                return true
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                addParticles(screenX, screenY)
                return true
            }
        }

        // Запуск потока обновления
        threadPool.execute { updateLoop() }
    }

    private fun addParticles(screenX: Int, screenY: Int) {
        synchronized(particles) {
            val y = Gdx.graphics.height - screenY
            repeat(10) { i ->
                val offsetX = (i % 3 - 1) * 10f // Случайное смещение
                val offsetY = (i / 3 - 1) * 10f
                particles.add(Particle(screenX.toFloat() + offsetX, y.toFloat() + offsetY))
            }
        }
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
                snapshot = ArrayList(particles) // Создаем копию списка частиц
            }

            // Обновляем quadtree
            quadtree.clear()
            snapshot.forEach { quadtree.insert(it) }

            // Разделяем работу между потоками
            val batchSize = max(1, snapshot.size / Runtime.getRuntime().availableProcessors())
            val futures = mutableListOf<Runnable>()

            // Параллельное обновление движения
            for (i in snapshot.indices step batchSize) {
                val end = min(i + batchSize, snapshot.size)
                futures.add(Runnable {
                    for (j in i until end) {
                        snapshot[j].move(deltaTime)
                        keepWithinBounds(snapshot[j]) // Удерживаем частицы в пределах экрана
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
                        val neighbors = quadtree.queryRange(snapshot[j].getBounds())
                        for (neighbor in neighbors) {
                            if (neighbor != snapshot[j]) {
                                snapshot[j].repel(neighbor)
                            }
                        }
                    }
                })
            }

            futures.map { threadPool.submit(it) }.forEach { it.get() } // Ждем выполнения
        }
    }

    private fun keepWithinBounds(particle: Particle) {
        if (particle.x < bounds.x) {
            particle.x = bounds.x
            particle.vx = abs(particle.vx) // Отскок от левой границы
        } else if (particle.x > bounds.width) {
            particle.x = bounds.width
            particle.vx = -abs(particle.vx) // Отскок от правой границы
        }

        if (particle.y < bounds.y) {
            particle.y = bounds.y
            particle.vy = abs(particle.vy) // Отскок от нижней границы
        } else if (particle.y > bounds.height) {
            particle.y = bounds.height
            particle.vy = -abs(particle.vy) // Отскок от верхней границы
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)

        synchronized(particles) {
            particles.forEach { it.draw(shapeRenderer) }
        }

        shapeRenderer.end()
    }

    override fun dispose() {
        running = false
        threadPool.shutdown()
        threadPool.awaitTermination(1, TimeUnit.SECONDS) // Ожидаем завершения задач
        shapeRenderer.dispose()
    }

    override fun resize(width: Int, height: Int) {
        camera.setToOrtho(false, width.toFloat(), height.toFloat())
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
    }

    inner class Particle(var x: Float, var y: Float) {
        var vx = 0f
        var vy = 0f
        private var repulsionRadius = initialParticleRadius * 2

        fun move(deltaTime: Float) {
            x += vx * deltaTime
            y += vy * deltaTime
            vx *= friction
            vy *= friction
            if (maxRepulsionRadius * 1.1 > repulsionRadius) repulsionRadius += 0.03f
        }

        fun draw(shapeRenderer: ShapeRenderer) {
            // Цвет зависит от скорости частицы
            val speed = sqrt(vx * vx + vy * vy)
            val color = Color(1f, 1f - speed / 10f, 1f - speed / 10f, 1f)
            shapeRenderer.color = color
            shapeRenderer.circle(x, y, initialParticleRadius)
        }

        fun repel(other: Particle) {
            val dx = x - other.x
            val dy = y - other.y
            val distanceSq = dx * dx + dy * dy

            if (distanceSq < (repulsionRadius + other.repulsionRadius).pow(2) && distanceSq > 0) {
                val distance = sqrt(distanceSq)
                val angle = atan2(dy, dx)
                val force = (repulsionRadius + other.repulsionRadius - distance) * 0.05f

                vx += cos(angle) * force
                vy += sin(angle) * force

                other.vx -= cos(angle) * force
                other.vy -= sin(angle) * force
            }
        }

        fun getBounds(): Rectangle {
            return Rectangle(x - repulsionRadius, y - repulsionRadius, repulsionRadius * 2, repulsionRadius * 2)
        }
    }

    // Простая реализация Quadtree
    class Quadtree(bounds: Rectangle, private val capacity: Int) {
        private val particles = mutableListOf<Particle>()
        private var divided = false
        private var northeast: Quadtree? = null
        private var northwest: Quadtree? = null
        private var southeast: Quadtree? = null
        private var southwest: Quadtree? = null
        private val bounds: Rectangle

        init {
            this.bounds = Rectangle(bounds)
        }

        fun insert(particle: Particle): Boolean {
            if (!bounds.contains(particle.getBounds())) {
                return false
            }

            if (particles.size < capacity) {
                particles.add(particle)
                return true
            }

            if (!divided) {
                subdivide()
            }

            return northeast!!.insert(particle) ||
                northwest!!.insert(particle) ||
                southeast!!.insert(particle) ||
                southwest!!.insert(particle)
        }

        fun queryRange(range: Rectangle): List<Particle> {
            val found = mutableListOf<Particle>()

            if (!bounds.overlaps(range)) {
                return found
            }

            for (particle in particles) {
                if (range.contains(particle.getBounds())) {
                    found.add(particle)
                }
            }

            if (divided) {
                found.addAll(northeast!!.queryRange(range))
                found.addAll(northwest!!.queryRange(range))
                found.addAll(southeast!!.queryRange(range))
                found.addAll(southwest!!.queryRange(range))
            }

            return found
        }

        fun clear() {
            particles.clear()
            if (divided) {
                northeast!!.clear()
                northwest!!.clear()
                southeast!!.clear()
                southwest!!.clear()
            }
            divided = false
        }

        private fun subdivide() {
            val x = bounds.x
            val y = bounds.y
            val w = bounds.width / 2
            val h = bounds.height / 2

            northeast = Quadtree(Rectangle(x + w, y + h, w, h), capacity)
            northwest = Quadtree(Rectangle(x, y + h, w, h), capacity)
            southeast = Quadtree(Rectangle(x + w, y, w, h), capacity)
            southwest = Quadtree(Rectangle(x, y, w, h), capacity)

            divided = true
        }
    }
}
