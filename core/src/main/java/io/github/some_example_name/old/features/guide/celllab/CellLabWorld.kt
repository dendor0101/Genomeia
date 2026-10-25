package io.github.some_example_name.old.features.guide.celllab

import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.core.GlobalSimulationSettings

import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.base.CellListBuilder
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.TriangleEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.entities.SpecialModDataEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.DivideManager
import io.github.some_example_name.old.systems.genomics.MutateManager
import io.github.some_example_name.old.systems.genomics.NeuralLinkManager
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.physics.CollisionManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem
import io.github.some_example_name.old.systems.physics.MovementManager
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.systems.simulation.SingleThreadSimulationSystem

/** Свой мир 8×8, одна очередь команд и никаких рабочих потоков. */
internal class CellLabWorld : DIContext, Disposable {
    companion object { const val SIZE = 8 }
    private val defaults = io.github.some_example_name.old.cells.base.CellTypes.createAll()
        .associate { it.name to it.defaultCellSettings.copy() }
    val substrateSettings = SubstrateSettings(GlobalSimulationSettings(cellsSettings = defaults), defaults)


    override var gridWidth = SIZE
    override var gridHeight = SIZE

    var gridSize = gridWidth * gridHeight
    override var threadCount = 1
    override var totalChunks = 1
    override var chunkSize = gridSize / totalChunks

    override val gridManager = GridManager(
        gridWidth = gridWidth,
        gridHeight = gridHeight,
        diContext = this,
        maxAmountOfParticles = 4
    )
    private val cellListBuilder = CellListBuilder().apply {
        bindToDIContext(this@CellLabWorld)
    }
    val cellList = cellListBuilder.instances
    val zygote = cellListBuilder.zygote

    override val organEntity = OrganEntity(
        organStartMaxAmount = 10
    )
    val simulationData = SimulationData()
    override val particleEntity = ParticleEntity(
        particlesStartMaxAmount = 128,
        gridManager = gridManager
    )
    private val neuralEntity = NeuralEntity(
        neuralStartMaxAmount = 32,
        cellList = cellList
    )
    val specialModDataEntity = SpecialModDataEntity(
        specialModDataStartMaxAmount = 32
    )

    override val specialEntity = SpecialEntity(
        cellsStartMaxAmount = 32,
        specialModDataEntity = specialModDataEntity
    )
    override val cellEntity = CellEntity(
        cellsStartMaxAmount = 64,
        particleEntity = particleEntity,
        simulationData = simulationData,
        substrateSettings = substrateSettings,
        cellList = cellList,
        neuralEntity = neuralEntity,
        specialEntity = specialEntity,
        organEntity = organEntity
    )
    override val linkEntity = LinkEntity(
        128,
        cellEntity = cellEntity,
        gridManager = gridManager,
        particleEntity = particleEntity,
        diContext = this,
        maximumLinkLengthSquared = 9f,
        organEntity = organEntity
    )
    override val neuralLinkEntity = NeuralLinkEntity(
        32,
        cellEntity = cellEntity,
        isEditor = false
    )

    /** Треугольники тел по аренам организмов, см. TriangleEntity. */
    val triangleEntity = TriangleEntity(64)

    init {
        // См. комментарий в DISimulationContainer: organEntity создаётся раньше всех.
        organEntity.bindEntities(cellEntity, particleEntity, linkEntity, neuralLinkEntity, triangleEntity)
    }
    override val pheromoneEntity = PheromoneEntity(
        gridManager = gridManager
    )
    override val substancesEntity = SubstancesEntity(
        startMaxAmount = 32,
        particleEntity = particleEntity,
        substrateSettings = substrateSettings
    )

    override val entityList = listOf(
        organEntity,
        particleEntity,
        neuralEntity,
        specialModDataEntity,
        specialEntity,
        cellEntity,
        linkEntity,
        neuralLinkEntity,
        pheromoneEntity,
        substancesEntity,
        triangleEntity
    )

    override val genomeManager = GenomeManager(
        simulationData = simulationData
    )

    override val organManager = OrganManager(
        organEntity = organEntity,
        genomeManager = genomeManager,
        cellEntity = cellEntity
    )

    override val worldCommandsManager = WorldCommandsManager(
        gridManager = gridManager,
        organManager = organManager,
        organEntity = organEntity,
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        neuralLinkEntity = neuralLinkEntity,
        particleEntity = particleEntity,
        pheromoneEntity = pheromoneEntity,
        substrateSettings = substrateSettings,
        genomeManager = genomeManager,
        simulationData = simulationData,
        cellList = cellList,
        substancesEntity = substancesEntity,
        specialEntity = specialEntity,
        diContext = this,
        isEditor = false
    )

    override val pheromonesManager = PheromonesManager(
        pheromoneEntity = pheromoneEntity,
        worldCommandsManager = worldCommandsManager,
        particleEntity = particleEntity,
        cellEntity = cellEntity
    )

    val collisionManager = CollisionManager(
        entity = particleEntity,
        worldCommandsManager = worldCommandsManager,
        linkEntity = linkEntity,
        cellList = cellList,
        cellEntity = cellEntity,
        substancesEntity = substancesEntity
    )

    val particlePhysicsSystem = ParticlePhysicsSystem(
        entity = particleEntity,
        gridManager = gridManager,
        substrateSettings = substrateSettings,
        worldCommandsManager = worldCommandsManager,
        simulationData = simulationData,
        linkEntity = linkEntity,
        cellList = cellList,
        cellEntity = cellEntity,
        substancesEntity = substancesEntity,
        pheromonesManager = pheromonesManager,
        collisionManager = collisionManager
    )

    val divideManager = DivideManager(
        cellEntity = cellEntity,
        worldCommandsManager = worldCommandsManager,
        particleEntity = particleEntity,
        gridManager = gridManager,
        cellList = cellList
    )

    val mutateManager = MutateManager(
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        neuralLinkEntity = neuralLinkEntity,
        worldCommandsManager = worldCommandsManager,
        particleEntity = particleEntity,
        gridManager = gridManager,
        specialEntity = specialEntity,
        organEntity = organEntity
    )

    val cellSystem = CellSystem(
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        organEntity = organEntity,
        genomeManager = genomeManager,
        worldCommandsManager = worldCommandsManager,
        gridManager = gridManager,
        divideManager = divideManager,
        mutateManager = mutateManager,
        threadManager = null,
        linkEnergyTransfer = substrateSettings.data.rateOfEnergyTransferInLinks * 8f
    )

    val linkPhysicsSystem = LinkPhysicsSystem(
        linkEntity = linkEntity,
        substrateSettings = substrateSettings,
        particleEntity = particleEntity,
        cellEntity = cellEntity,
        worldCommandsManager = worldCommandsManager,
        cellSystem = cellSystem,
        maximumLinkLengthSquared = 9f,
        simulationData = simulationData,
        diContext = this
    )

    val neuralLinkManager = NeuralLinkManager(
        neuralLinkEntity = neuralLinkEntity,
        cellEntity = cellEntity
    )

    val movementManager = MovementManager(
        entity = particleEntity,
        gridManager = gridManager,
        substrateSettings = substrateSettings,
        worldCommandsManager = worldCommandsManager,
        simulationData = simulationData,
        linkEntity = linkEntity,
        cellList = cellList,
        cellEntity = cellEntity,
        substancesEntity = substancesEntity,
        pheromonesManager = pheromonesManager
    )

    // Тот же цикл, что у однопоточного мира игры; у стенда нет очереди игрового
    // ввода и снимков основного рендера. Состав стадий больше не копируется сюда.
    private val simulation = SingleThreadSimulationSystem(
        gridManager, worldCommandsManager, organManager, organEntity, cellEntity,
        linkEntity, neuralLinkEntity, neuralLinkManager, particleEntity, linkPhysicsSystem,
        simulationData, cellSystem, null, null, pheromonesManager, movementManager,
        particlePhysicsSystem, entityList
    )

    fun tick() = simulation.updateTick()

    fun rebuildGrid() = gridManager.rebuild(particleEntity.isAlive, particleEntity.gridId, particleEntity.isInGrid)

    override fun dispose() = simulation.dispose()
}
