package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.NeuralLinkManager
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem
import io.github.some_example_name.old.systems.physics.MovementManager
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem
import io.github.some_example_name.old.systems.render.RenderBufferManager

class SingleThreadSimulationSystem(
    val gridManager: GridManager,
    val worldCommandsManager: WorldCommandsManager,
    val organManager: OrganManager,
    val organEntity: OrganEntity,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val neuralLinkEntity: NeuralLinkEntity,
    val neuralLinkManager: NeuralLinkManager,
    val particleEntity: ParticleEntity,
    val linkPhysicsSystem: LinkPhysicsSystem,
    val simulationData: SimulationData,
    val cellSystem: CellSystem,
    val userCommandManager: UserCommandManager,
    val renderBufferManager: RenderBufferManager,
    val pheromonesManager: PheromonesManager,
    val movementManager: MovementManager,
    val particlePhysicsSystem: ParticlePhysicsSystem,
    val entityList: List<Entity>
) {

    fun updateTick() {
        simulationData.tickCounter++
        simulationData.timeSimulation += DELTA_SIM_TICK_TIME

        organEntity.aliveList.forEach { it ->
            val from = organEntity.linkArenaBase[it]
            val to = organEntity.linkArenaEnd(it)

            val alive = linkEntity.isAlive
            for (linkIndex in from until to) {
                // Дырки в арене: связь могла умереть, а слот не переиспользуется.
                if (!alive[linkIndex]) continue
                linkPhysicsSystem.processLink(linkIndex)
            }
        }
        neuralLinkManager.iterate()

        processGridChunkPhysics()

        cellEntity.aliveList.forEach {
            cellSystem.processCell(it)
        }
        pheromonesManager.iterate()

        particleEntity.aliveList.forEach {
            movementManager.moveParticle(it)
        }

        worldCommandsManager.executingCommandsFromTheWorld()
        organManager.performOrgansNextStage()
        userCommandManager.processingCommandsFromUser()
        worldCommandsManager.executingLastCommandsFromTheWorld()
        organEntity.deleteEmptiedOrgans()

        // Пересборка сетки: после движения и всех команд, один раз за тик.
        gridManager.rebuild(particleEntity.isAlive, particleEntity.gridId, particleEntity.isInGrid)

        renderBufferManager.updateBuffer()

    }

    private fun processGridChunkPhysics() {
        particlePhysicsSystem.processGridRangePhysics(
            start = 0,
            end = gridManager.gridSize,
            threadId = 0
        )
    }

    fun dispose() {
        gridManager.clearAll()
        entityList.forEach { it.clear() }
        simulationData.clear()
        worldCommandsManager.dispose()
    }

    companion object {
        const val DELTA_SIM_TICK_TIME = 0.016666666f
    }
}
