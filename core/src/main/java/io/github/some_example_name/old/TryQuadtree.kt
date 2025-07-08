package io.github.some_example_name.old

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import kotlin.math.*

class TryQuadtree : ApplicationAdapter() {
    private lateinit var shapeRenderer: ShapeRenderer
    private lateinit var camera: OrthographicCamera
//    private val particles = mutableListOf<Particle>()
    private lateinit var quadtreeBoundary: Rectangle
    private val maxRepulsionRadius = 5f
    private val friction = 0.9f
    private var pauseSimulation = false
    private lateinit var quadtree: Quadtree

    private val boundariesRects = mutableListOf<Rectangle>()

    override fun create() {
        shapeRenderer = ShapeRenderer()
        camera = OrthographicCamera(Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        camera.setToOrtho(false)
        quadtreeBoundary = Rectangle(0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        quadtree = Quadtree(quadtreeBoundary, 2)
        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                val y = Gdx.graphics.height - screenY
                quadtree.insert(listOf(Particle(screenX.toFloat(), y.toFloat())))

                println(quadtree.toString())
                return true
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                val y = Gdx.graphics.height - screenY
                quadtree.insert(listOf(Particle(screenX.toFloat(), y.toFloat())))

                println(quadtree.toString())
                return true
            }

//            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
//                val y = Gdx.graphics.height - screenY
//                particles.add(Particle(screenX.toFloat(), y.toFloat()))
//                particles.add(Particle(screenX.toFloat() + 1, y.toFloat()))
//                particles.add(Particle(screenX.toFloat() + 0, y.toFloat()))
//                particles.add(Particle(screenX.toFloat() - 1, y.toFloat()))
//                particles.add(Particle(screenX.toFloat() + 1, y.toFloat() - 1))
//                particles.add(Particle(screenX.toFloat() + 0, y.toFloat() - 1))
//                particles.add(Particle(screenX.toFloat() - 1, y.toFloat() - 1))
//                particles.add(Particle(screenX.toFloat() + 1, y.toFloat()+ 1))
//                particles.add(Particle(screenX.toFloat() + 0, y.toFloat()+ 1))
//                particles.add(Particle(screenX.toFloat() - 1, y.toFloat()+ 1))
//                particles.add(Particle(screenX.toFloat(), y.toFloat()))
//                particles.add(Particle(screenX.toFloat() + 1, y.toFloat()))
//                particles.add(Particle(screenX.toFloat() + 0, y.toFloat()))
//                particles.add(Particle(screenX.toFloat() - 1, y.toFloat()))
//                particles.add(Particle(screenX.toFloat() + 1, y.toFloat() - 1))
//                particles.add(Particle(screenX.toFloat() + 0, y.toFloat() - 1))
//                particles.add(Particle(screenX.toFloat() - 1, y.toFloat() - 1))
//                particles.add(Particle(screenX.toFloat() + 1, y.toFloat()+ 1))
//                particles.add(Particle(screenX.toFloat() + 0, y.toFloat()+ 1))
//                particles.add(Particle(screenX.toFloat() - 1, y.toFloat()+ 1))
//                println(particles.size)
//                return true
//            }
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)


//        val quadtree = Quadtree(quadtreeBoundary, 16)
//        for (particle in particles) {
//            quadtree.insert(particle)
//        }


//        if (!pauseSimulation) {
//            for (particle in particles) {
//                val nearbyParticles = mutableListOf<Particle>()
//                quadtree.query(Rectangle(particle.x - maxRepulsionRadius, particle.y - maxRepulsionRadius, maxRepulsionRadius * 2, maxRepulsionRadius * 2), nearbyParticles)
//                particle.move()
//                for (other in nearbyParticles) {
//                    if (particle != other) {
//                        particle.repel(other)
//                    }
//                }
//            }
//        }

//        quadtree.query()

        val particles = mutableListOf<Particle>()
        quadtree.goTrough(particles)


        shapeRenderer.projectionMatrix = camera.combined
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled)
        shapeRenderer.color = Color.WHITE
        particles.forEach { it.drawShell() }
        shapeRenderer.end()
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line)
        shapeRenderer.color = Color.RED
        boundariesRects.forEach { shapeRenderer.rect(it.x, it.y, it.width, it.height) }
        shapeRenderer.end()
    }

    override fun dispose() {
        shapeRenderer.dispose()
    }

    inner class Particle(var x: Float, var y: Float) {
        private var vx = 0f
        private var vy = 0f
        private var repulsionRadius = 2f

        fun move() {
            x += vx
            y += vy
            vx *= friction
            vy *= friction
        }

        fun drawShell() {
            val speed = sqrt(vx * vx + vy * vy)
            val color = Color(1f, 1f - speed / 2f, 1f - speed / 2f, 1f)
            shapeRenderer.color = color
            shapeRenderer.circle(x, y, repulsionRadius)
        }

        fun repel(other: Particle) {
            val dx = x - other.x
            val dy = y - other.y
            if (dx > repulsionRadius * 2) return
            if (dy > repulsionRadius * 2) return
//            val distance = sqrt(dx * dx + dy + dy)//
            val distance = hypot(dx, dy)
            if (distance < repulsionRadius * 2 && distance > 0) {
                val angle = atan2(dy, dx)
                val force = (repulsionRadius * 2 - distance) * 0.05f
                vx += cos(angle) * force
                vy += sin(angle) * force
                other.vx -= cos(angle) * force
                other.vy -= sin(angle) * force
            }
        }

        override fun toString(): String {
            return "Particle(x=$x, y=$y)"
        }


    }

    class Rectangle(val x: Float, val y: Float, val width: Float, val height: Float) {
        fun contains(p: Particle): Boolean {
            return p.x >= x && p.x <= x + width && p.y >= y && p.y <= y + height
        }

        fun intersects(range: Rectangle): Boolean {
            return !(range.x > x + width || range.x + range.width < x || range.y > y + height || range.y + range.height < y)
        }
    }

    inner class Quadtree(val boundary: Rectangle, private val capacity: Int) {
        val particles = mutableListOf<Particle>()
        var divided = false
        var northwest: Quadtree? = null
        var northeast: Quadtree? = null
        var southwest: Quadtree? = null
        var southeast: Quadtree? = null

        private val neighbours = mutableListOf<Quadtree>()

        fun insert(p: Particle): Boolean {
            if (!boundary.contains(p)) return false
            if (particles.size < capacity) {
                particles.add(p)
                return true
            }
            if (!divided) {
                println("subdivide")
                subdivide()


//                particles.clear()
            }
            return (northwest?.insert(p) == true || northeast?.insert(p) == true || southwest?.insert(p) == true || southeast?.insert(p) == true)
        }

        fun insert(insertedParticles: List<Particle>): Boolean {
            val filtered = insertedParticles.filter { p -> boundary.contains(p) }
            if (filtered.isEmpty()) return false

            if (particles.size < capacity && filtered.size < capacity && !divided) {
                particles.addAll(filtered)
                return true
            }

            val new = if (!divided) {
                subdivide()
                (particles + filtered).also { particles.clear() }
            } else {
                filtered
            }

            //TODO Если объект на границе, то нужно его в родительский квадрат запихнуть
            var isInserted = false
            isInserted = northwest?.insert(new) == true || isInserted
            isInserted = northeast?.insert(new) == true || isInserted
            isInserted = southwest?.insert(new) == true || isInserted
            isInserted = southeast?.insert(new) == true || isInserted
            return isInserted
        }

        private fun subdivide() {
            val halfWidth = boundary.width / 2f
            val halfHeight = boundary.height / 2f
            boundariesRects.add(Rectangle(boundary.x, boundary.y, halfWidth, halfHeight))
            boundariesRects.add(Rectangle(boundary.x + halfWidth, boundary.y, halfWidth, halfHeight))
            boundariesRects.add(Rectangle(boundary.x, boundary.y + halfHeight, halfWidth, halfHeight))
            boundariesRects.add(Rectangle(boundary.x + halfWidth, boundary.y + halfHeight, halfWidth, halfHeight))

            northwest = Quadtree(Rectangle(boundary.x, boundary.y, halfWidth, halfHeight), capacity)
            northeast = Quadtree(Rectangle(boundary.x + halfWidth, boundary.y, halfWidth, halfHeight), capacity)
            southwest = Quadtree(Rectangle(boundary.x, boundary.y + halfHeight, halfWidth, halfHeight), capacity)
            southeast = Quadtree(Rectangle(boundary.x + halfWidth, boundary.y + halfHeight, halfWidth, halfHeight), capacity)

            //Нужно каждого соседа уведомить что этот квадрат разделился

            divided = true
        }

//        fun query(range: Rectangle, found: MutableList<Particle>) {
//            if (!boundary.intersects(range)) return
//            for (p in particles) {
//                if (range.contains(p)) found.add(p)
//            }
//            if (!divided) return
//            northwest?.query(range, found)
//            northeast?.query(range, found)
//            southwest?.query(range, found)
//            southeast?.query(range, found)
//        }

        fun goTrough(found: MutableList<Particle>) {
            if (!divided) {
                moveAllInQuad(particles)
                found.addAll(particles)
                return
            } else {
                northwest?.goTrough(found)
                northeast?.goTrough(found)
                southwest?.goTrough(found)
                southeast?.goTrough(found)
            }
        }

        private fun moveAllInQuad(particles: List<Particle>) {
            particles.forEach {
                it.move()
            }
            for (i in particles.indices) {
                for (j in i + 1 until particles.size) {
                    particles[i].repel(particles[j])
                }
            }
        }

        override fun toString(): String {
            return "Quadtree(southwest=$southwest, southeast=$southeast, northeast=$northeast, northwest=$northwest, divided=$divided, particles=$particles)"
        }


    }
}

