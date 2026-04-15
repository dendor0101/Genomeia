package io.github.some_example_name.old.entities

import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem.Companion.PARTICLE_MAX_RADIUS
import kotlin.math.PI

class ParticleEntity(
    particlesStartMaxAmount: Int,
    val gridManager: GridManager
): Entity(particlesStartMaxAmount) {
    var gridId = IntArray(maxAmount) { -1 }
    var x = FloatArray(maxAmount)
    var y = FloatArray(maxAmount)
    var vx = FloatArray(maxAmount)
    var vy = FloatArray(maxAmount)
    var radius = FloatArray(maxAmount) { PARTICLE_MAX_RADIUS }
    var mass = FloatArray(maxAmount)
    var color = IntArray(maxAmount)
    var dragCoefficient = FloatArray(maxAmount) { 0.003f }
    var effectOnContact = BooleanArray(maxAmount)
    var isCollidable = BooleanArray(maxAmount)
    var cellStiffness = FloatArray(maxAmount) { 0.5f }
    var isCell = BooleanArray(maxAmount) { false }
    var holderEntityIndex = IntArray(maxAmount) { -1 }

    fun addParticle(
        x: Float,
        y: Float,
        radius: Float,
        color: Int,
        vx: Float = 0f,
        vy: Float = 0f,
        dragCoefficient: Float = 0.03f,
        effectOnContact: Boolean = false,
        isCollidable: Boolean = true,
        cellStiffness: Float = 0.02f,
        isCell: Boolean,
        holderEntityIndex: Int
    ): Int {
        val particleIndex = add()

        gridId[particleIndex] = gridManager.addParticle(x.toInt(), y.toInt(), particleIndex)
        this.x[particleIndex] = x
        this.y[particleIndex] = y
        this.vx[particleIndex] = vx
        this.vy[particleIndex] = vy
        this.radius[particleIndex] = radius
        this.mass[particleIndex] = radius * radius * PI.toFloat()
        this.color[particleIndex] = color
        this.dragCoefficient[particleIndex] = dragCoefficient
        this.effectOnContact[particleIndex] = effectOnContact
        this.isCollidable[particleIndex] = isCollidable
        this.cellStiffness[particleIndex] = cellStiffness
        this.isCell[particleIndex] = isCell
        this.holderEntityIndex[particleIndex] = holderEntityIndex
        return particleIndex
    }

    fun deleteParticle(particleIndex: Int) {
        delete(particleIndex)

        gridManager.removeParticle(gridId[particleIndex], particleIndex)
        gridId[particleIndex] = -1
        x[particleIndex] = 0f
        y[particleIndex] = 0f
        vx[particleIndex] = 0f
        vy[particleIndex] = 0f
        radius[particleIndex] = PARTICLE_MAX_RADIUS
        mass[particleIndex] = 0f
        color[particleIndex] = 0
        dragCoefficient[particleIndex] = 0.93f
        effectOnContact[particleIndex] = false
        isCollidable[particleIndex] = true
        cellStiffness[particleIndex] = 0.5f
        isCell[particleIndex] = false
        holderEntityIndex[particleIndex] = -1
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        gridId.clear(-1)
        x.clear()
        y.clear()
        vx.clear()
        vy.clear()
        radius.clear(PARTICLE_MAX_RADIUS)
        mass.clear()
        color.clear()
        dragCoefficient.clear(0.03f)
        effectOnContact.clear(false)
        isCollidable.clear(true)
        cellStiffness.clear()
        isCell.clear(false)
        holderEntityIndex.clear(-1)
    }

    override fun onResize(oldMax: Int) {
        gridId = gridId.resize(-1)
        x = x.resize()
        y = y.resize()
        vx = vx.resize()
        vy = vy.resize()
        radius = radius.resize(PARTICLE_MAX_RADIUS)
        mass = mass.resize()
        color = color.resize()
        dragCoefficient = dragCoefficient.resize(0.03f)
        effectOnContact = effectOnContact.resize(false)
        isCollidable = isCollidable.resize(true)
        cellStiffness = cellStiffness.resize()
        isCell = isCell.resize(false)
        holderEntityIndex = holderEntityIndex.resize(-1)
    }
}
