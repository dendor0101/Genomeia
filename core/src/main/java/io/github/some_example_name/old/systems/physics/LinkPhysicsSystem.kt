package io.github.some_example_name.old.systems.physics

import com.badlogic.gdx.graphics.Color
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.DISimulationContainer.linkMaxLength2
import io.github.some_example_name.old.core.DISimulationContainer.threadManager
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.core.utils.invSqrt
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.simulation.SimulationData
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlin.math.sqrt

class LinkPhysicsSystem(
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val substrateSettings: SubstrateSettings,
    val cellEntity: CellEntity,
    val cellSystem: CellSystem,
    val worldCommandsManager: WorldCommandsManager,
    val diContext: DIContext,
    val simulationData: SimulationData
) {

    fun iterateLinksInParallel() {
        val isEnergy = simulationData.tickCounter % 3 == 0
        processPhase(worldCommandsManager.oddLinkLists, isEnergy)
        processPhase(worldCommandsManager.evenLinkLists, isEnergy)
    }

    private fun processPhase(lists: Array<IntArrayList>, isEnergy: Boolean) {
        threadManager.futures.clear()
        for (t in 0 until diContext.threadCount) {
            threadManager.futures.add(
                threadManager.executor.submit {
                    val list = lists[t]
                    for (i in list.indices) {
                        val linkIndex = list.getInt(i)
                        processLink(linkIndex, isEnergy, t)
                    }
                }
            )
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()
    }

    fun processLink(linkIndex: Int, isEnergy: Boolean, threadId: Int = 0) = with(particleEntity) {
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

                if (isEnergy) cellSystem.transportEnergy(linkCellA, linkCellB)

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
                val invDist = invSqrt(distanceSquared)
                val dist = distanceSquared * invDist

                val dirX = dx * invDist
                val dirY = dy * invDist

                val degreeOfShorteningA = degreeOfShortening[linkCellA]
                val degreeOfShorteningB = degreeOfShortening[linkCellB]
                val degreeOfShortening = 2f * degreeOfShorteningA * degreeOfShorteningB / (degreeOfShorteningA + degreeOfShorteningB)

                val force = (dist - linksNaturalLength[linkIndex] * degreeOfShortening) * stiffness
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
