package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.utils.pinkColors

class Sticky(cellTypeId: Int) : Cell(
    key = "sticky",
    defaultColor = pinkColors[3],
    cellTypeId = cellTypeId,
    textureName = "sticky.png",
    isNeural = true,
    effectOnContact = true
) {


    override fun onContact(
        cellIndex: Int,
        particleIndexCollided: Int,
        distance: Float,
        threadId: Int
    ) = with(particleEntity) {
        //TODO тут полное повторение кода с приоипанием частиц стен и еды
        val particleAId = cellEntity.particleIndexes[cellIndex]
        val particleBId = particleIndexCollided
        val distanceSquared = distance * distance
        val particleRadius = radius[particleAId] + radius[particleBId]
        val radiusSquared = particleRadius * particleRadius

        val stiffness = 0.009f * cellEntity.neuronImpulseOutput[cellIndex].coerceIn(0f, 1f)

        val force = (distance - 0.35f) * stiffness
        val dx = x(particleAId) - x(particleBId)
        val dy = y(particleAId) - y(particleBId)
        val dirX = dx / distance
        val dirY = dy / distance

        // Spring dampening
        val dvx = vx(particleAId) - vx(particleBId)
        val dvy = vy(particleAId) - vy(particleBId)

        val dampeningConstant = 0.3f
        val dampeningForce = dampeningConstant * (dvx * dirX + dvy * dirY)

        val cellStrengthAverage = 0.01f
        val forceRepulsion = cellStrengthAverage - cellStrengthAverage * distanceSquared / radiusSquared

        val fx = (force + dampeningForce - forceRepulsion) * dirX
        val fy = (force + dampeningForce - forceRepulsion) * dirY

        addVelocity(particleBId, fx, fy)
        addVelocity(particleAId, -fx, -fy)
    }

}
