package io.github.some_example_name.old.entities

import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem.Companion.PARTICLE_MAX_RADIUS
import java.util.BitSet
import kotlin.collections.fill
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
        cellStiffness[particleIndex] = 0.5f
        isCell[particleIndex] = false
        holderEntityIndex[particleIndex] = -1
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        gridId.fill(-1, 0, bound)
        x.fill(0f, 0, bound)
        y.fill(0f, 0, bound)
        vx.fill(0f, 0, bound)
        vy.fill(0f, 0, bound)
        radius.fill(PARTICLE_MAX_RADIUS, 0, bound)
        mass.fill(0f, 0, bound)
        color.fill(0, 0, bound)
        dragCoefficient.fill(0.03f, 0, bound)
        effectOnContact.fill(false, 0, bound)
        cellStiffness.fill(0f, 0, bound)
        isCell.fill(false, 0, bound)
        holderEntityIndex.fill(-1, 0, bound)
    }

    override fun onResize(oldMax: Int) {
        run {
            val old = gridId
            gridId = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, gridId, 0, oldMax)
        }
        run {
            val old = x
            x = FloatArray(maxAmount)
            System.arraycopy(old, 0, x, 0, oldMax)
        }
        run {
            val old = y
            y = FloatArray(maxAmount)
            System.arraycopy(old, 0, y, 0, oldMax)
        }
        run {
            val old = vx
            vx = FloatArray(maxAmount)
            System.arraycopy(old, 0, vx, 0, oldMax)
        }
        run {
            val old = vy
            vy = FloatArray(maxAmount)
            System.arraycopy(old, 0, vy, 0, oldMax)
        }
        run {
            val old = radius
            radius = FloatArray(maxAmount)
            System.arraycopy(old, 0, radius, 0, oldMax)
        }
        run {
            val old = mass
            mass = FloatArray(maxAmount)
            System.arraycopy(old, 0, mass, 0, oldMax)
        }
        run {
            val old = color
            color = IntArray(maxAmount)
            System.arraycopy(old, 0, color, 0, oldMax)
        }
        run {
            val old = dragCoefficient
            dragCoefficient = FloatArray(maxAmount) { 0.03f }
            System.arraycopy(old, 0, dragCoefficient, 0, oldMax)
        }
        run {
            val old = effectOnContact
            effectOnContact = BooleanArray(maxAmount)
            System.arraycopy(old, 0, effectOnContact, 0, oldMax)
        }
        run {
            val old = cellStiffness
            cellStiffness = FloatArray(maxAmount)
            System.arraycopy(old, 0, cellStiffness, 0, oldMax)
        }
        run {
            val old = isCell
            isCell = BooleanArray(maxAmount)
            System.arraycopy(old, 0, isCell, 0, oldMax)
        }
        run {
            val old = holderEntityIndex
            holderEntityIndex = IntArray(maxAmount)
            System.arraycopy(old, 0, holderEntityIndex, 0, oldMax)
        }
    }
}
