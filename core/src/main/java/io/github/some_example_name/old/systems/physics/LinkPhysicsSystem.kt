package io.github.some_example_name.old.systems.physics

import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DIContainer.linkMaxLength2
import io.github.some_example_name.old.core.DIContainer.threadCount
import io.github.some_example_name.old.core.DIContainer.threadManager
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.core.utils.invSqrt
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.CellSystem

class LinkPhysicsSystem(
    val linkEntity: LinkEntity,
    val particleEntity: ParticleEntity,
    val substrateSettings: SubstrateSettings,
    val cellEntity: CellEntity,
    val cellSystem: CellSystem,
    val worldCommandsManager: WorldCommandsManager
) {

    fun iterateLinks() {
        for (chunk in 0..<threadCount) {
            threadManager.futures.add(threadManager.executor.submit {
                for (i in 0..<worldCommandsManager.oddCounter[chunk]) {
                    stretchLinks(worldCommandsManager.oddChunkPositionStack[chunk][i], threadId = chunk)
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()

        for (chunk in 0..<threadCount) {
            threadManager.futures.add(threadManager.executor.submit {
                for (i in 0..<worldCommandsManager.evenCounter[chunk]) {
                    stretchLinks(worldCommandsManager.evenChunkPositionStack[chunk][i], threadId = chunk)
                }
            })
        }
        threadManager.futures.forEach { it.get() }
        threadManager.futures.clear()
    }

    private fun stretchLinks(particleIndex: Int, threadId: Int) = with(linkEntity) {
        if (!particleEntity.isCell[particleIndex]) return@with
        val cellIndex = particleEntity.holderEntityIndex[particleIndex]
        with(cellEntity) {
            val base = cellIndex * MAX_LINK_AMOUNT
            val amount = linksAmount[cellIndex]
            if (amount == 0) return

            for (i in 0 until amount) {
                val idx = base + i
                val linkIndex = links[idx]
                val c1 = links1[linkIndex]
                val c2 = links2[linkIndex]
                if (!cellEntity.isAlive[c1] || !cellEntity.isAlive[c2]) {
                    throw Exception("link $linkIndex exist, but some cell is deleted ($c1 $c2)")
//                    continue
                }

                val otherCellIndex = if (c1 != cellIndex) c1 else if (c2 != cellIndex) c2 else continue
                val gridCellAId = getGridId(cellIndex)
                val gridCellBId = getGridId(otherCellIndex)
                if (gridCellAId < gridCellBId) {
                    processLink(linkIndex, threadId)
                } else if (gridCellAId == gridCellBId) {
                    val yCellA = getY(cellIndex)
                    val yCellB = getY(otherCellIndex)
                    if (yCellA < yCellB) {
                        processLink(linkIndex, threadId)
                    } else if (yCellA == yCellB) {
                        if (getX(cellIndex) < getX(otherCellIndex)) {
                            processLink(linkIndex, threadId)
                        }
                    }
                }
            }
        }
    }

    private fun processLink(linkIndex: Int, threadId: Int) = with(particleEntity) {
        with(cellEntity){
            with(linkEntity) {
                val linkCell1 = links1[linkIndex]
                val linkCell2 = links2[linkIndex]
                val linkParticle1 = particleIndex[linkCell1]
                val linkParticle2 = particleIndex[linkCell2]

                val dx = x[linkParticle1] - x[linkParticle2]
                val dy = y[linkParticle1] - y[linkParticle2]

                cellSystem.transportEnergy(linkCell1, linkCell2)
                cellSystem.transportNeuralSignal(linkIndex, linkCell1, linkCell2)

                val distanceSquared = dx * dx + dy * dy

                if (distanceSquared > linkMaxLength2) {
                    worldCommandsManager.worldCommandBuffer[threadId].push(
                        type = WorldCommandType.DELETE_LINK,
                        ints = intArrayOf(linkIndex)
                    )
                    return
                }
                // TODO: for physical accuracy this should be changed to a harmonic mean
                val stiffness = (cellStiffness[linkParticle1] + cellStiffness[linkParticle2]) / 2

                if (distanceSquared < 0) throw Exception("distanceSquared < 0, distanceSquared = $distanceSquared")
                val dist = 1.0f / invSqrt(distanceSquared)

                val force = (dist - linksNaturalLength[linkIndex] * degreeOfShortening[linkIndex]) * stiffness

                val dirX = dx / dist
                val dirY = dy / dist

                // Spring dampening
                val dvx = vx[linkParticle1] - vx[linkParticle2]
                val dvy = vy[linkParticle1] - vy[linkParticle2]

                val dampeningConstant = 0.3f
                val dampeningForce = dampeningConstant * (dvx * dirX + dvy * dirY)

                val fx = (force + dampeningForce) * dirX
                val fy = (force + dampeningForce) * dirY

                vx[linkParticle2] += fx
                vy[linkParticle2] += fy
                vx[linkParticle1] -= fx
                vy[linkParticle1] -= fy
            }
        }
    }

    companion object {
        const val MAX_LINK_AMOUNT = 10
    }
}
