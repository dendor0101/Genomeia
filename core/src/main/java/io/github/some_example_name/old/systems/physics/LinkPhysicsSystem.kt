package io.github.some_example_name.old.systems.physics

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DISimulationContainer.linkMaxLength2
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import kotlin.math.sqrt

class LinkPhysicsSystem(
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val substrateSettings: SubstrateSettings,
    val cellEntity: CellEntity,
    val cellSystem: CellSystem,
    val worldCommandsManager: WorldCommandsManager
) {

    fun processLink(linkIndex: Int, threadId: Int = 0) = with(particleEntity) {
        with(cellEntity) {
            with(linkEntity) {
                val linkCellA = links1[linkIndex]
                val linkCellB = links2[linkIndex]

                val linkCellAIsDead = !cellEntity.isAlive[linkCellA] || cellEntity.getGeneration(linkCellA) != linksGeneration1[linkIndex]
                val linkCellBIsDead = !cellEntity.isAlive[linkCellB] || cellEntity.getGeneration(linkCellB) != linksGeneration2[linkIndex]

                if (linkCellAIsDead || linkCellBIsDead) {
                    linkEntity.reinitParentLink(linkIndex)
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_LINK,
                        ints = intArrayOf(linkIndex, linkEntity.getGeneration(linkIndex))
                    )
                    if (linkCellAIsDead && !linkCellBIsDead) {
                        isOnEdge[linkCellB] = true
                        setColor(linkCellB, Color.RED.toIntBits())
                    }
                    if (linkCellBIsDead && !linkCellAIsDead) {
                        isOnEdge[linkCellA] = true
                        setColor(linkCellA, Color.RED.toIntBits())
                    }
                    return@with
                }

                val linkParticleA = getParticleIndex(linkCellA)
                val linkParticleB = getParticleIndex(linkCellB)

                val dx = x[linkParticleA] - x[linkParticleB]
                val dy = y[linkParticleA] - y[linkParticleB]
                val distanceSquared = dx * dx + dy * dy

                if (isLongNeuralLink[linkIndex]) {
                    if (distanceSquared > 16) {
                        worldCommandsManager.worldCommandBuffer[threadId].push(
                            type = WorldCommandType.DELETE_LINK,
                            ints = intArrayOf(linkIndex, linkEntity.getGeneration(linkIndex))
                        )
                        return@with
                    }
                    cellSystem.transportNeuralSignal(linkIndex, linkCellA, linkCellB, threadId)
                    return@with
                }

                cellSystem.transportEnergy(linkCellA, linkCellB)
                cellSystem.transportNeuralSignal(linkIndex, linkCellA, linkCellB, threadId)
                val parentCellA = parentIndex[linkCellA]
                val parentCellB = parentIndex[linkCellB]
                if (linkCellA == parentCellB) {
                    cellSystem.processCellAngle(linkCellB, linkCellA)
                }
                if (linkCellB == parentCellA) {
                    cellSystem.processCellAngle(linkCellA, linkCellB)
                }

                if (distanceSquared > linkMaxLength2) {
                    linkEntity.reinitParentLink(linkIndex)
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_LINK,
                        ints = intArrayOf(linkIndex, linkEntity.getGeneration(linkIndex))
                    )
                    isOnEdge[linkCellB] = true
                    setColor(linkCellB, Color.RED.toIntBits())
                    isOnEdge[linkCellA] = true
                    setColor(linkCellA, Color.RED.toIntBits())
                    return
                }

                val stiffnessA = cellStiffness[linkParticleA]
                val stiffnessB = cellStiffness[linkParticleB]
                val stiffness = 2 * stiffnessA * stiffnessB / (stiffnessA + stiffnessB)

                if (distanceSquared < 0) throw Exception("distanceSquared < 0, distanceSquared = $distanceSquared")
                val dist = sqrt(distanceSquared)

                val degreeOfShorteningA = degreeOfShortening[linkCellA]
                val degreeOfShorteningB = degreeOfShortening[linkCellB]
                val degreeOfShortening = 2 * degreeOfShorteningA * degreeOfShorteningB / (degreeOfShorteningA + degreeOfShorteningB)

                val force = (dist - linksNaturalLength[linkIndex] * degreeOfShortening) * stiffness

                val dirX = dx / dist
                val dirY = dy / dist

                // Spring dampening
                val dvx = vx[linkParticleA] - vx[linkParticleB]
                val dvy = vy[linkParticleA] - vy[linkParticleB]

                val dampeningConstant = 0.3f
                val dampeningForce = dampeningConstant * (dvx * dirX + dvy * dirY)

                val fx = (force + dampeningForce) * dirX
                val fy = (force + dampeningForce) * dirY

                vx[linkParticleB] += fx
                vy[linkParticleB] += fy
                vx[linkParticleA] -= fx
                vy[linkParticleA] -= fy

                if (parentIndex[linkCellA] == -1) reinitParentIndex(linkCellA, linkCellB)
                if (parentIndex[linkCellB] == -1) reinitParentIndex(linkCellB, linkCellA)
            }
        }
    }
}
