package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.Input.Keys.D
import com.badlogic.gdx.Input.Keys.SPACE
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector3
import kotlin.math.*

class ParticleSimulation : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera
    private val particles = mutableListOf<Particle>()

    private val initialParticleRadius = 5f
    private val maxRepulsionRadius = 50f
    private val maxSpeed = 2f
    private val friction = 0.95f  // Коэффициент трения
    private val attractionForce = 0.002f  // Сила притяжения разделенных частиц
    private val enableAttraction = false  // Включение/отключение притяжения
    private var pauseSimulation = false

    private var dragging = false
    private var lastMouseX = 0f
    private var lastMouseY = 0f

    var pikSounds = emptyList<Sound>()

    override fun create() {
        shapeRenderer = ShapeRenderer()
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)

        pikSounds = listOf<Sound>(
            Gdx.audio.newSound(Gdx.files.internal("pik1.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik2.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik3.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik4.mp3")),
            Gdx.audio.newSound(Gdx.files.internal("pik5.mp3"))
        )

        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (button == Input.Buttons.RIGHT) {
                    dragging = true
                    lastMouseX = screenX.toFloat()
                    lastMouseY = screenY.toFloat()
                    return true
                }
                val worldCoords = camera.unproject(Vector3(screenX.toFloat(), screenY.toFloat(), 0f))
                val y = worldCoords.y
                if (wallFunction(worldCoords.x) > y) {
                    pikSounds.random().play()
                    particles.add(Particle(worldCoords.x, y))
                }
                return true
            }

            override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (button == Input.Buttons.RIGHT) {
                    dragging = false
                    return true
                }
                return false
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                if (dragging) {
                    val deltaX = lastMouseX - screenX
                    val deltaY = screenY - lastMouseY
                    camera.translate(deltaX * camera.zoom, deltaY * camera.zoom)
                    camera.update()
                    lastMouseX = screenX.toFloat()
                    lastMouseY = screenY.toFloat()
                    return true
                }
                val worldCoords = camera.unproject(Vector3(screenX.toFloat(), screenY.toFloat(), 0f))
                val y = worldCoords.y
                if (wallFunction(worldCoords.x) > y) {
                    pikSounds.random().play()
                    particles.add(Particle(worldCoords.x, y))
                }
                return true
            }

            override fun scrolled(amountX: Float, amountY: Float): Boolean {
                val zoomFactor = 1.1f
                val mouseX = Gdx.input.x.toFloat()
                val mouseY = Gdx.graphics.height - Gdx.input.y.toFloat()
                val worldCoords = camera.unproject(Vector3(mouseX, mouseY, 0f))

                if (amountY > 0) {
                    camera.zoom *= zoomFactor
                } else {
                    camera.zoom /= zoomFactor
                }
                camera.zoom = camera.zoom.coerceIn(0.1f, 5f)

                val newWorldCoords = camera.unproject(Vector3(mouseX, mouseY, 0f))
                camera.position.add(worldCoords.x - newWorldCoords.x, worldCoords.y - newWorldCoords.y, 0f)
                camera.update()
                return true
            }

            override fun keyDown(keycode: Int): Boolean {
                return when (keycode) {
                    SPACE -> {
                        pauseSimulation = !pauseSimulation
                        true
                    }
                    D -> {
                        particles.removeAll { it.isMovable }
                        true
                    }
                    else -> false
                }
            }
        }


        addWalls()

    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)

        if (!pauseSimulation) {
            val newParticles = mutableListOf<Particle>()
            val particlesToRemove = mutableListOf<Particle>()

            for (i in particles.indices) {
                val particle = particles[i]

                if (particle.isMovable) {

                    particle.move()
                    if (abs(particle.vx) < 1e-3) particle.vx = 0f
                    if (abs(particle.vy) < 1e-3) particle.vy = 0f

                    if (wallFunction(particle.x) < particle.y) {
                        particlesToRemove.add(particle)
                    }
                }

                for (j in i + 1 until particles.size) {
                    particle.repel(particles[j])
                    if (enableAttraction && particle.isChildOf(particles[j])) {
                        particle.attract(particles[j])
                    }
                }
                if (particle.repulsionRadius >= maxRepulsionRadius && particles.size < 1250) {
                    newParticles.addAll(particle.split())
                    particlesToRemove.add(particle)
                }

            }

            particles.removeAll(particlesToRemove)
            particles.addAll(newParticles)
        }
        shapeRenderer.end()

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = Color.WHITE
        particles.forEach { it.drawShell() }
        shapeRenderer.end()
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = Color.RED
        particles.forEach { it.drawCore() }
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    inner class Particle(var x: Float, var y: Float, val parent: Particle? = null) {
        var vx = 0f
        var vy = 0f
        var radius = initialParticleRadius
        var repulsionRadius = initialParticleRadius * 2
        var isMovable = true

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

        fun drawCore() {
            shapeRenderer.circle(x, y, radius)
        }

        fun repel(other: Particle) {
            val dx = x - other.x
            val dy = y - other.y
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


        fun attract(other: Particle) {
            val dx = other.x - x
            val dy = other.y - y
            val distance = hypot(dx, dy)

            if (distance > repulsionRadius * 2.4) {
                val angle = atan2(dy, dx)
                val force = attractionForce * distance

                vx += cos(angle) * force
                vy += sin(angle) * force

                other.vx -= cos(angle) * force
                other.vy -= sin(angle) * force
            }
        }

        fun split(): List<Particle> {
            val offset = radius / 2
            val childRadius = radius
            val childRepulsionRadius = repulsionRadius / 2

            val particle1 = Particle(x, y - offset, this).apply {
                radius = childRadius
                repulsionRadius = childRepulsionRadius
            }

            val particle2 = Particle(x, y + offset, this).apply {
                radius = childRadius
                repulsionRadius = childRepulsionRadius
            }
            pikSounds.random().play()
            return listOf(particle1, particle2)
        }

        fun isChildOf(other: Particle): Boolean {
            return this.parent == other.parent && this.parent != null
        }

    }

    private fun addWalls() {
        for (x in 0 until Gdx.graphics.width step 3) {
            particles.add(Particle(x.toFloat(), wallFunction(x.toFloat())).apply {
                radius = 0.01f
                repulsionRadius = 5f
                isMovable = false
            })
        }
    }
}

fun wallFunction(x: Float): Float {
    return 800 + 60 * sin(x * 0.05f) // Пример функции стены (синусоида)
}

fun wallFunctionDerivative(x: Float): Float {
    return 0.6f * cos(x * 0.01f) // Производная функции стены
}

