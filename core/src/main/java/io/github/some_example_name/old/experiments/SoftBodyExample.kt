package io.github.some_example_name.old.experiments


import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.ScreenUtils

class Particle(var pos: Vector2, var oldPos: Vector2 = pos.cpy(), var acc: Vector2 = Vector2())

abstract class Constraint {
    abstract fun solve(dt: Float)
}

class DistanceConstraint(val p1: Particle, val p2: Particle, val rest: Float, val alpha: Float = 0f) : Constraint() {
    override fun solve(dt: Float) {
        val delta = p2.pos.cpy().sub(p1.pos)
        val dist = delta.len()
        if (dist == 0f) return
        val C = dist - rest
        val nor = delta.cpy().nor()
        val grad1 = nor.cpy().scl(-1f)
        val grad2 = nor
        val sum_sq = grad1.len2() + grad2.len2()
        val denom = sum_sq + alpha / (dt * dt)
        if (denom == 0f) return
        val lambda = -C / denom
        p1.pos.add(grad1.scl(lambda))
        p2.pos.add(grad2.scl(lambda))
    }
}

class AngularConstraint(val pA: Particle, val pB: Particle, val pC: Particle, val alpha: Float = 0f) : Constraint() {
    override fun solve(dt: Float) {
        val u = pA.pos.cpy().sub(pB.pos)
        val v = pC.pos.cpy().sub(pB.pos)
        val C = u.dot(v)
        if (C == 0f) return
        val gradA = v.cpy()
        val gradB = u.cpy().add(v).scl(-1f)
        val gradC = u.cpy()
        val sum_sq = gradA.len2() + gradB.len2() + gradC.len2()
        if (sum_sq == 0f) return
        val denom = sum_sq + alpha / (dt * dt)
        val lambda = -C / denom
        pA.pos.add(gradA.scl(lambda))
        pB.pos.add(gradB.scl(lambda))
        pC.pos.add(gradC.scl(lambda))
    }
}

class SoftbodySimulation : ApplicationAdapter() {
    lateinit var shape: ShapeRenderer
    val particles = ArrayList<Particle>()
    val constraints = ArrayList<Constraint>()
    var draggedParticle: Particle? = null
    val particleRadius = 10f

    override fun create() {
        shape = ShapeRenderer()

        // Initialize particles with B in the center, 90-degree angle
        val centerX = Gdx.graphics.width / 2f
        val centerY = Gdx.graphics.height / 2f
        val d = 100f
        particles.add(Particle(Vector2(centerX - d, centerY))) // A
        particles.add(Particle(Vector2(centerX, centerY))) // B
        particles.add(Particle(Vector2(centerX, centerY + d))) // C

        // Distance constraints (stiff)
        constraints.add(DistanceConstraint(particles[0], particles[1], d, alpha = 0f))
        constraints.add(DistanceConstraint(particles[1], particles[2], d, alpha = 0f))

        // Angular constraint (softer, adjust alpha for stiffness: smaller alpha = stiffer)
        constraints.add(AngularConstraint(particles[0], particles[1], particles[2], alpha = 0.01f))
    }

    override fun render() {
        ScreenUtils.clear(0.2f, 0.2f, 0.2f, 1f) // Gray background for better visibility
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        update(Gdx.graphics.deltaTime)

        shape.setColor(Color.WHITE)

        // Draw particles
        shape.begin(ShapeRenderer.ShapeType.Filled)
        for (p in particles) {
            shape.circle(p.pos.x, p.pos.y, particleRadius)
        }
        shape.end()

        // Draw links
        shape.begin(ShapeRenderer.ShapeType.Line)
        shape.line(particles[0].pos, particles[1].pos)
        shape.line(particles[1].pos, particles[2].pos)
        shape.end()
    }

    private fun update(dt: Float) {
        val h = Gdx.graphics.height.toFloat()

        // Handle mouse input for dragging
        if (Gdx.input.isButtonJustPressed(Input.Buttons.LEFT)) {
            val mouse = Vector2(Gdx.input.x.toFloat(), h - Gdx.input.y.toFloat())
            for (p in particles) {
                if (p.pos.dst(mouse) < particleRadius * 2) {
                    draggedParticle = p
                    break
                }
            }
        }
        val mousePos = Vector2(Gdx.input.x.toFloat(), h - Gdx.input.y.toFloat())

        if (!Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            draggedParticle = null
        }

        // No gravity or other forces
        for (p in particles) {
            p.acc.set(0f, 0f)
        }

        // Integrate (Verlet) for non-dragged particles with damping
        val damping = 0.99f // Slightly increased damping to settle faster
        for (p in particles) {
            if (p != draggedParticle) {
                val temp = p.pos.cpy()
                val velocityTerm = temp.cpy().sub(p.oldPos).scl(damping)
                p.pos.set(temp.cpy().add(velocityTerm).add(p.acc.scl(dt * dt)))
                p.oldPos.set(temp)
            }
        }

        // Set position for dragged particle
        draggedParticle?.let {
            it.pos.set(mousePos)
            it.oldPos.set(mousePos) // Zero velocity during drag
        }

        // Solve constraints (multiple iterations for stiffness)
        val iterations = 20 // Increased for better convergence
        for (i in 0 until iterations) {
            for (c in constraints) {
                c.solve(dt)
            }
        }

        // Handle boundary collisions
        for (p in particles) {
            constrainToBounds(p)
        }

        // Reset acceleration
        for (p in particles) {
            p.acc.set(0f, 0f)
        }
    }

    private fun constrainToBounds(p: Particle) {
        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()

        // Left boundary
        if (p.pos.x - particleRadius < 0) {
            p.pos.x = particleRadius
            val velX = p.pos.x - p.oldPos.x
            p.oldPos.x = p.pos.x + velX // Reflect x velocity (note: this is actually absorbing, for reflect use -velX)
        }
        // Right boundary
        if (p.pos.x + particleRadius > w) {
            p.pos.x = w - particleRadius
            val velX = p.pos.x - p.oldPos.x
            p.oldPos.x = p.pos.x + velX
        }
        // Bottom boundary
        if (p.pos.y - particleRadius < 0) {
            p.pos.y = particleRadius
            val velY = p.pos.y - p.oldPos.y
            p.oldPos.y = p.pos.y + velY
        }
        // Top boundary
        if (p.pos.y + particleRadius > h) {
            p.pos.y = h - particleRadius
            val velY = p.pos.y - p.oldPos.y
            p.oldPos.y = p.pos.y + velY
        }
    }

    override fun dispose() {
        shape.dispose()
    }
}
