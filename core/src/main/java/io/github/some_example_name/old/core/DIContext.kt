package io.github.some_example_name.old.core

import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.simulation.SimulationData

interface DIContext {
    var gridWith: Int
    var gridHeight: Int
    val particleEntity: ParticleEntity
    val cellEntity: CellEntity
    val linkEntity: LinkEntity
    val substancesEntity: SubstancesEntity
    val specialEntity: SpecialEntity
    val substrateSettings: SubstrateSettings
    val worldCommandsManager: WorldCommandsManager
    val organEntity: OrganEntity
    val genomeManager: GenomeManager
    val pheromoneEntity: PheromoneEntity

    val gridManager: GridManager
    val organManager: OrganManager
}
