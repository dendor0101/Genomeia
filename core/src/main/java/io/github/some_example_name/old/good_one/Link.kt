package io.github.some_example_name.old.good_one

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.Chameleon
import io.github.some_example_name.old.good_one.cells.Neuron
import io.github.some_example_name.old.good_one.cells.base.Neural
import kotlin.math.sqrt

class Link(var c1: Cell, var c2: Cell, val isPhysicalLink: Boolean) {
    var restLength = 36f
    var degreeOfShortening = 1f

    init {
        if (isPhysicalLink) {
            c1.physicalLinks.add(this)
            c2.physicalLinks.add(this)
        } else {
            c1.neuronLinks.add(this)
            c2.neuronLinks.add(this)
        }
    }

    fun applyForce(changes: PlayGround.ChangeUnits): Boolean {
        //TODO убивать, если ядра клеток оказались слишком близко
        val dx = c2.x - c1.x
        val dy = c2.y - c1.y
        val sqrt = dx * dx + dy * dy
        if (sqrt <= 0) return false
        val dist = sqrt(sqrt)
//        if (dist > 55f) return true changes
        if (dist.isNaN()) throw Exception("TODO потом убрать")

        val elasticity = (c1.elasticity + c2.elasticity) / 2
//        if (dist > restLength * elasticity) return true
        val stiffness = (c1.linkStrength + c2.linkStrength) / 2

        val force = (dist - restLength * degreeOfShortening) * stiffness
        val fx = force * dx / dist
        val fy = force * dy / dist

        c1.vx += fx
        c1.vy += fy
        c2.vx -= fx
        c2.vy -= fy
        return false
    }

    fun transportEnergy() {
        if (c1.maxEnergy == 0f || c2.maxEnergy == 0f) throw Exception("TODO потом убрать")

        if (c1.energy / c1.maxEnergy < c2.energy / c2.maxEnergy) {
            c1.energy += 0.01f
            c2.energy -= 0.01f
        } else if (c1.energy / c1.maxEnergy != c2.energy / c2.maxEnergy) {
            c1.energy -= 0.01f
            c2.energy += 0.01f
        }
    }

    fun transportNeuronImpulse() {
        if (c1 is Neuron || c1 is Chameleon) return
        if (c2.neuronImpulseImport != c1.neuronImpulseImport) {
            c1.neuronImpulseImport = c2.neuronImpulseImport
        }
    }

    fun draw(renderer: ShapeRenderer) {
        renderer.color = Color.RED
        renderer.line(c1.x, c1.y, c2.x, c2.y)
    }

    fun distanceTo(): Float {
        val dx = c1.x - c2.x
        val dy = c1.y - c2.y
        val result =  sqrt(dx * dx + dy * dy)
        if (result.isNaN()) throw Exception("TODO потом убрать")
        return result
    }
}
