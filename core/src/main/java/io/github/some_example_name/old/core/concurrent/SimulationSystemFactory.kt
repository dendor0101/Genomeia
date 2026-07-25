package io.github.some_example_name.old.core.concurrent

import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.features.worldeditor.WorldTerrainManager
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem
import io.github.some_example_name.old.systems.physics.MovementManager
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem
import io.github.some_example_name.old.systems.render.RenderBufferManager
import io.github.some_example_name.old.systems.simulation.Simulation
import io.github.some_example_name.old.systems.simulation.SimulationData

interface SimulationSystemFactory {
    fun create(
        gridManager: GridManager,
        worldCommandsManager: WorldCommandsManager,
        organManager: OrganManager,
        cellEntity: CellEntity,
        particlePhysicsSystem: ParticlePhysicsSystem,
        linkPhysicsSystem: LinkPhysicsSystem,
        simulationData: SimulationData,
        cellSystem: CellSystem,
        userCommandManager: UserCommandManager,
        entityList: List<Entity>,
        renderBufferManager: RenderBufferManager,
        pheromonesManager: PheromonesManager,
        movementManager: MovementManager,
        worldTerrainManager: WorldTerrainManager
    ): Simulation
}
