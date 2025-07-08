package io.github.some_example_name.old
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import kotlin.math.*

class TryQuadtree2 : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera
    private val particles = mutableListOf<Particle>()
    private lateinit var quadtree: Quadtree

    private val initialParticleRadius = 1f
    private val maxRepulsionRadius = 5f
    private val friction = 0.95f  // Коэффициент трения
    private var pauseSimulation = false

    override fun create() {
        shapeRenderer = ShapeRenderer()
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)

        quadtree = Quadtree(Rectangle(0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat()))

        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                val y = Gdx.graphics.height - screenY
                repeat(10) {
                    particles.add(Particle(screenX.toFloat() + it % 3 - 1, y.toFloat() + it / 3 - 1))
                }
                return true
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                val y = Gdx.graphics.height - screenY
                repeat(10) {
                    particles.add(Particle(screenX.toFloat() + it % 3 - 1, y.toFloat() + it / 3 - 1))
                }
                return true
            }
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)

        if (!pauseSimulation) {
            quadtree = Quadtree(Rectangle(0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat()))

            for (particle in particles) {
                quadtree.insert(particle)
                particle.move()
            }

            for (particle in particles) {
                val range = Rectangle(
                    particle.x - maxRepulsionRadius,
                    particle.y - maxRepulsionRadius,
                    maxRepulsionRadius * 2,
                    maxRepulsionRadius * 2
                )
                val nearby = quadtree.query(range, mutableListOf())
                for (other in nearby) {
                    if (particle != other) {
                        particle.repel(other)
                    }
                }
            }
        }

        shapeRenderer.end()

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = Color.WHITE
        particles.forEach { it.drawShell() }
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    inner class Particle(var x: Float, var y: Float, val parent: Particle? = null) {
        private var vx = 0f
        private var vy = 0f
        private var repulsionRadius = initialParticleRadius * 2

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
                val force = (repulsionRadius + other.repulsionRadius - distance) * 0.05f

                vx += cos(angle) * force
                vy += sin(angle) * force

                other.vx -= cos(angle) * force
                other.vy -= sin(angle) * force
            }
        }
    }
}

class Quadtree(val boundary: Rectangle, val capacity: Int = 4) {
    private val particles = mutableListOf<TryQuadtree2.Particle>()
    private var divided = false
    private var northeast: Quadtree? = null
    private var northwest: Quadtree? = null
    private var southeast: Quadtree? = null
    private var southwest: Quadtree? = null

    fun insert(particle: TryQuadtree2.Particle): Boolean {
        if (!boundary.contains(particle.x, particle.y)) {
            return false
        }

        if (particles.size < capacity) {
            particles.add(particle)
            return true
        }

        if (!divided) {
            subdivide()
        }

        return northeast?.insert(particle) == true ||
            northwest?.insert(particle) == true ||
            southeast?.insert(particle) == true ||
            southwest?.insert(particle) == true
    }

    private fun subdivide() {
        val x = boundary.x
        val y = boundary.y
        val w = boundary.width / 2
        val h = boundary.height / 2

        northeast = Quadtree(Rectangle(x + w, y + h, w, h), capacity)
        northwest = Quadtree(Rectangle(x, y + h, w, h), capacity)
        southeast = Quadtree(Rectangle(x + w, y, w, h), capacity)
        southwest = Quadtree(Rectangle(x, y, w, h), capacity)

        divided = true
    }

    fun query(range: Rectangle, found: MutableList<TryQuadtree2.Particle>): MutableList<TryQuadtree2.Particle> {
        if (!boundary.overlaps(range)) {
            return found
        }

        for (p in particles) {
            if (range.contains(p.x, p.y)) {
                found.add(p)
            }
        }

        if (divided) {
            northeast?.query(range, found)
            northwest?.query(range, found)
            southeast?.query(range, found)
            southwest?.query(range, found)
        }

        return found
    }
}
