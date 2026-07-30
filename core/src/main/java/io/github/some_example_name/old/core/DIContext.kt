package io.github.some_example_name.old.core

import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager

interface DIContext {
    var gridWidth: Int
    var gridHeight: Int
    var chunkSize: Int
    var totalChunks: Int
    var threadCount: Int
    val particleEntity: ParticleEntity
    val cellEntity: CellEntity
    val linkEntity: LinkEntity
    val neuralLinkEntity: NeuralLinkEntity
    val substancesEntity: SubstancesEntity
    val specialEntity: SpecialEntity
    val worldCommandsManager: WorldCommandsManager
    val organEntity: OrganEntity
    val genomeManager: GenomeManager
    val pheromonesManager: PheromonesManager
    val pheromoneEntity: PheromoneEntity

    val gridManager: GridManager
    val organManager: OrganManager
    val entityList: List<Entity>
}
