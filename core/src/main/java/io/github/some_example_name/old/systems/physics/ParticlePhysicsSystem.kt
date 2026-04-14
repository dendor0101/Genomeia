package io.github.some_example_name.old.systems.physics

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DISimulationContainer.halfChunkHeight
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.core.utils.invSqrt
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import kotlin.math.sqrt

class ParticlePhysicsSystem(
    val entity: ParticleEntity,
    val gridManager: GridManager,
    val substrateSettings: SubstrateSettings,
    val worldCommandsManager: WorldCommandsManager,
    val simulationData: SimulationData,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val cellList: List<Cell>,
    val substancesEntity: SubstancesEntity
) {

    val halfChunkHeight2 = halfChunkHeight * halfChunkHeight

    fun processGridChunkPhysics(start: Int, end: Int, threadId: Int, isOdd: Boolean) {
        for (i in start until end) {
            val x = i % gridManager.gridWidth
            val y = i / gridManager.gridWidth

            if (gridManager.particleCounts[i] > 0) {
                val particles = gridManager.getParticlesIndex(i)
                processCollisionsInTheSameCell(particles, threadId)
                for (particleIndex in particles) {
                    processNeighborsCellsCollision(particleIndex, x, y, threadId)
                    distributeParticleIndicesAcrossChunks(particleIndex, threadId, isOdd)
                }
            }
        }
    }

    private fun distributeParticleIndicesAcrossChunks(
        cellIndex: Int,
        threadId: Int,
        isOdd: Boolean
    ) {
        val stacks = if (isOdd) worldCommandsManager.oddChunkPositionStack
        else worldCommandsManager.evenChunkPositionStack
        val counters = if (isOdd) worldCommandsManager.oddCounter
        else worldCommandsManager.evenCounter

        val index = counters[threadId]
        var arr = stacks[threadId]

        if (index >= arr.size) {
            arr = arr.copyOf(arr.size + (arr.size shr 1))
            stacks[threadId] = arr
        }

        arr[index] = cellIndex
        counters[threadId] = index + 1
    }

    private fun processNeighborsCellsCollision(cellId: Int, gridX: Int, gridY: Int, threadId: Int) {
        gridManager.getParticles(gridX - 1, gridY + 1).also { ids ->
            for (id in ids) repulse(cellId, id, threadId)
        }
        gridManager.getParticles(gridX, gridY + 1).also { ids ->
            for (id in ids) repulse(cellId, id, threadId)
        }
        gridManager.getParticles(gridX + 1, gridY + 1).also { ids ->
            for (id in ids) repulse(cellId, id, threadId)
        }

        gridManager.getParticles(gridX + 1, gridY).also { ids ->
            for (id in ids) repulse(cellId, id, threadId)
        }
    }

    private fun processCollisionsInTheSameCell(cells: IntArray, threadId: Int) {
        for (i in cells.indices) {
            for (j in i + 1 until cells.size) {
                repulse(cells[i], cells[j], threadId)
            }
        }
    }

    private fun repulse(particleAId: Int, particleBId: Int, threadId: Int) = with(entity) {
        val isParticleAIsCell = isCell[particleAId]
        val isParticleBIsCell = isCell[particleBId]
        if (isParticleAIsCell && isParticleBIsCell) {
            if (linkEntity.linkIndexMap.get(holderEntityIndex[particleAId], holderEntityIndex[particleBId]) != -1) return@with
        }

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
            val distance = 1.0f / invSqrt(distanceSquared)
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
                val subAIndex = holderEntityIndex[particleAId]
                val subBIndex = holderEntityIndex[particleBId]
                if (subAIndex != -1 && subBIndex != -1) {
                    //TODO вынести в SubManager, добавить притягивание, проверять типы, соединять вещества при более близком контакте, сохранять общий импуль
                    val rA2 = radius[particleAId] * radius[particleAId]
                    val rB2 = radius[particleBId] * radius[particleBId]
                    val radiusSumSquared = rA2 + rB2
                    if (radiusSumSquared < PARTICLE_MAX_RADIUS_SQUARED) {

                        val maxRadius = maxOf(radius[particleAId], radius[particleBId])
                        if (distance < maxRadius) {
                            val radius = 1.0f / invSqrt(radiusSumSquared)
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
                        val forceRepulsion = cellStrengthAverage - cellStrengthAverage * distanceSquared / radiusSquared

                        val fx = (force + dampeningForce - forceRepulsion) * dirX
                        val fy = (force + dampeningForce - forceRepulsion) * dirY

                        vx[particleBId] += fx
                        vy[particleBId] += fy
                        vx[particleAId] -= fx
                        vy[particleAId] -= fy
                    }

                    return@with
                }
            }


            // Квадратичная зависимость силы
            val stiffnessA = cellStiffness[particleAId]
            val stiffnessB = cellStiffness[particleBId]
            val cellStrengthAverage = 2 * stiffnessA * stiffnessB / (stiffnessA + stiffnessB)

            val force = cellStrengthAverage - cellStrengthAverage * distanceSquared / radiusSquared
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

    private fun processWorldBorders(cellId: Int) = with(entity) {
        if (x[cellId] < radius[cellId]) {
            x[cellId] = radius[cellId]
            vx[cellId] *= -0.8f
        } else if (x[cellId] > gridManager.gridWidth - radius[cellId]) {
            x[cellId] = gridManager.gridWidth - radius[cellId]
            vx[cellId] *= -0.8f
        }

        if (y[cellId] < radius[cellId]) {
            y[cellId] = radius[cellId]
            vy[cellId] *= -0.8f
        } else if (y[cellId] > gridManager.gridHeight - radius[cellId]) {
            y[cellId] = gridManager.gridHeight - radius[cellId]
            vy[cellId] *= -0.8f
        }
    }

    fun moveParticle(particleIndex: Int) = with(entity) {
        val oldX = x[particleIndex].toInt()
        val oldY = y[particleIndex].toInt()
        val gridCellIndex = gridId[particleIndex]

        processCellFrictionOld(particleIndex)

//        vx[particleIndex] -= 0.04f * sin((500f - particleIndex) * simulationData.timeSimulation)
//        vy[particleIndex] -= 0.04f * cos((500f - particleIndex) * simulationData.timeSimulation)

        val vxv = vx[particleIndex]
        val vyv = vy[particleIndex]

        val speed2 = vxv * vxv + vyv * vyv
        if (speed2 > halfChunkHeight2) {
            val invLen = halfChunkHeight / sqrt(speed2)
            vx[particleIndex] *= invLen
            vy[particleIndex] *= invLen
        }

        x[particleIndex] += vx[particleIndex]
        y[particleIndex] += vy[particleIndex]

        processWorldBorders(particleIndex)
        val newX = x[particleIndex].toInt()
        val newY = y[particleIndex].toInt()
        if (newX != oldX || newY != oldY) {
            gridManager.removeParticle(gridCellIndex, particleIndex)
            gridId[particleIndex] = gridManager.addParticle(newX, newY, particleIndex)
        }
    }

    private fun processCellFrictionOld(cellId: Int) = with(entity) {
        vx[cellId] *= 1f - dragCoefficient[cellId]
        vy[cellId] *= 1f - dragCoefficient[cellId]
    }

    companion object {
        const val PARTICLE_MAX_RADIUS = 0.5f
        const val PARTICLE_MAX_RADIUS_SQUARED = 0.25f
        const val MAX_RADIUS_SQUARED = 4
    }
}
