package io.github.some_example_name.old.systems.physics

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import kotlin.math.sqrt

class CollisionManager(
    val entity: ParticleEntity,
    val worldCommandsManager: WorldCommandsManager,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val cellList: List<Cell>,
    val substancesEntity: SubstancesEntity
) {

    fun repulse(particleAId: Int, particleBId: Int, threadId: Int = 0) = with(entity) {
        val dx = x[particleAId] - x[particleBId]
        val dy = y[particleAId] - y[particleBId]
        val dx2 = dx * dx
        if (dx2 > MAX_RADIUS_SQUARED) return
        val dy2 = dy * dy
        if (dy2 > MAX_RADIUS_SQUARED) return

        val particleRadius = radius[particleAId] + radius[particleBId]
        val radiusSquared = particleRadius * particleRadius

        val distanceSquared = dx2 + dy2
        if (distanceSquared < radiusSquared) {

            val isParticleAIsCell = isCell[particleAId]
            val isParticleBIsCell = isCell[particleBId]
            if (isParticleAIsCell && isParticleBIsCell) {
                val linkIndex = linkEntity.linkIndexMap.get(
                    holderEntityIndex[particleAId],
                    holderEntityIndex[particleBId]
                )
                if (linkIndex != -1) {
                    return@with
                }
            }

            val distance = sqrt(distanceSquared)
            if (isParticleAIsCell) {
                if (effectOnContact[particleAId]) {
                    val cellAIndex = holderEntityIndex[particleAId]
                    val cellType = cellEntity.cellType[cellAIndex].toInt()
                    cellList[cellType].onContact(
                        cellIndex = cellAIndex,
                        particleIndexCollided = particleBId,
                        distance = distance,
                        threadId = threadId
                    )
                }
            }
            if (isParticleBIsCell) {
                if (effectOnContact[particleBId]) {
                    val cellBIndex = holderEntityIndex[particleBId]
                    val cellType = cellEntity.cellType[cellBIndex].toInt()
                    cellList[cellType].onContact(
                        cellIndex = cellBIndex,
                        particleIndexCollided = particleAId,
                        distance = distance,
                        threadId = threadId
                    )
                }
            }

            if (!isParticleAIsCell && !isParticleBIsCell) {
                //TODO вынести в SubManager
                val rA2 = radius[particleAId] * radius[particleAId]
                val rB2 = radius[particleBId] * radius[particleBId]
                val radiusSumSquared = rA2 + rB2
                if (radiusSumSquared < PARTICLE_MAX_RADIUS_SQUARED) {

                    val maxRadius = maxOf(radius[particleAId], radius[particleBId])
                    if (distance < maxRadius && isSub[particleAId] && isSub[particleBId]) {
                        val subAIndex = holderEntityIndex[particleAId]
                        val subBIndex = holderEntityIndex[particleBId]
                        val radius = sqrt(radiusSumSquared)
                        val deleteIndex = if (this.radius[particleAId] < this.radius[particleBId]) {
                            this.radius[particleBId] = radius
                            subAIndex
                        } else {
                            this.radius[particleAId] = radius
                            subBIndex
                        }

                        worldCommandsManager.worldCommandBuffer[threadId].push(
                            type = WorldCommandType.DELETE_SUBSTANCE,
                            ints = intArrayOf(
                                deleteIndex,
                                substancesEntity.getGeneration(deleteIndex)
                            )
                        )
                    } else {
                        val force = 0.02f * rA2 * rB2 / distanceSquared
                        val dirX = dx / distance
                        val dirY = dy / distance
                        val fx = force * dirX
                        val fy = force * dirY
                        vx[particleBId] += fx
                        vy[particleBId] += fy
                        vx[particleAId] -= fx
                        vy[particleAId] -= fy
                    }
                } else {

                    val stiffness = 0.009f

                    if (distanceSquared < 0) throw Exception("distanceSquared < 0, distanceSquared = $distanceSquared")

                    val force = (distance - 0.35f) * stiffness

                    val dirX = dx / distance
                    val dirY = dy / distance

                    // Spring dampening
                    val dvx = vx[particleAId] - vx[particleBId]
                    val dvy = vy[particleAId] - vy[particleBId]

                    val dampeningConstant = 0.3f
                    val dampeningForce = dampeningConstant * (dvx * dirX + dvy * dirY)

                    val cellStrengthAverage = 0.01f
                    val forceRepulsion =
                        cellStrengthAverage - cellStrengthAverage * distanceSquared / radiusSquared

                    val fx = (force + dampeningForce - forceRepulsion) * dirX
                    val fy = (force + dampeningForce - forceRepulsion) * dirY

                    vx[particleBId] += fx
                    vy[particleBId] += fy
                    vx[particleAId] -= fx
                    vy[particleAId] -= fy
                }

                return@with
            }

            if (isCollidable[particleAId] && isCollidable[particleBId]) {
                // Квадратичная зависимость силы
                val stiffnessA = cellStiffness[particleAId]
                val stiffnessB = cellStiffness[particleBId]
                val cellStrengthAverage = 2 * stiffnessA * stiffnessB / (stiffnessA + stiffnessB)

                val force =
                    cellStrengthAverage - cellStrengthAverage * distanceSquared / radiusSquared
                // Нормализация вектора расстояния
                val normX = dx / distance
                val normY = dy / distance
                val vectorX = normX * force
                val vectorY = normY * force

                vx[particleAId] += vectorX
                vy[particleAId] += vectorY
                vx[particleBId] -= vectorX
                vy[particleBId] -= vectorY
            }
        }
    }

    companion object {
        const val PARTICLE_MAX_RADIUS = 0.5f
        const val PARTICLE_MAX_RADIUS_SQUARED = 0.25f
        const val MAX_RADIUS_SQUARED = 4
    }
}
