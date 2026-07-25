package io.github.some_example_name.old.core

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.base.CellListBuilder
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DIGameGlobalContainer.genomeJsonReader
import io.github.some_example_name.old.core.DIGameGlobalContainer.shaderManager
import io.github.some_example_name.old.core.DIGameGlobalContainer.substrateSettings
import io.github.some_example_name.old.core.concurrent.Platform.simulationFactory
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.EyeEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEmitterEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.ProducerEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.entities.SpecialModDataEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.entities.TailEntity
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.DivideManager
import io.github.some_example_name.old.systems.genomics.MutateManager
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem
import io.github.some_example_name.old.systems.render.RenderBufferManager
import io.github.some_example_name.old.systems.render.RenderSystem
import io.github.some_example_name.old.features.settings.GlobalSettings.GRID_HEIGHT
import io.github.some_example_name.old.features.settings.GlobalSettings.GRID_WIDTH
import io.github.some_example_name.old.features.worldeditor.WorldTerrainManager
import io.github.some_example_name.old.systems.physics.CollisionManager
import io.github.some_example_name.old.systems.physics.MovementManager
import io.github.some_example_name.old.systems.simulation.SingleThreadSimulationSystem
import kotlin.getValue

object DISimulationContainer:  DIContext, Disposable {

    override var gridWidth = 128
    override var gridHeight = 128
    const val HALF_CHUNK_HEIGHT = 4 // Also max particle speed
    var chunkHeight = HALF_CHUNK_HEIGHT * 2
    var heightMultiplier = chunkHeight * 2
    var gridSize = gridWidth * gridHeight
    override var threadCount = (gridHeight / chunkHeight) / 2
    override var totalChunks = threadCount * 2
    override var chunkSize = gridSize / totalChunks

    var energyTransportRate = substrateSettings.data.rateOfEnergyTransferInLinks
    var linkMaxLength2 = 3f * 3f
    var cellsSettings = substrateSettings.cellsSettings

    init {
        if (gridHeight % heightMultiplier != 0) throw Exception("gridHeight should be a multiple of (halfChunkHeight * 2 * 2)")
        println("thread count: $threadCount")
        println("thread count: $heightMultiplier")
    }

    override val gridManager = GridManager(
        gridWidth = gridWidth,
        gridHeight = gridHeight,
        diContext = this,
        maxAmountOfParticles = 4
    )
    private val cellListBuilder = CellListBuilder().apply {
        bindToDIContext(this@DISimulationContainer)
    }
    val cellList = cellListBuilder.instances
    val zygote = cellListBuilder.zygote

    val tailEntity = TailEntity(
        tailStartMaxAmount = 1_000
    )
    override val organEntity = OrganEntity(
        organStartMaxAmount = 400
    )
    val simulationData = SimulationData()
    override val particleEntity = ParticleEntity(
        particlesStartMaxAmount = 30_000,
        gridManager = gridManager
    )
    private val neuralEntity = NeuralEntity(
        neuralStartMaxAmount = 10_000,
        cellList = cellList
    )
    private val eyeEntity = EyeEntity(
        eyeStartMaxAmount = 3_000
    )
    private val producerEntity = ProducerEntity(
        producerStartMaxAmount = 100
    )
    val specialModDataEntity = SpecialModDataEntity(
        specialModDataStartMaxAmount = 100
    )
    val pheromoneEmitterEntity = PheromoneEmitterEntity(
        pheromoneEmitterStartMaxAmount = 100
    )

    override val specialEntity = SpecialEntity(
        cellsStartMaxAmount = 10_000,
        eyeEntity = eyeEntity,
        tailEntity = tailEntity,
        specialModDataEntity = specialModDataEntity,
        producerEntity = producerEntity,
        pheromoneEmitterEntity = pheromoneEmitterEntity
    )
    override val cellEntity = CellEntity(
        cellsStartMaxAmount = 10_000,
        particleEntity = particleEntity,
        simulationData = simulationData,
        substrateSettings = substrateSettings,
        cellList = cellList,
        neuralEntity = neuralEntity,
        specialEntity = specialEntity
    )
    override val linkEntity = LinkEntity(
        20_000,
        cellEntity = cellEntity,
        gridManager = gridManager,
        particleEntity = particleEntity,
        diContext = this
    )
    override val pheromoneEntity = PheromoneEntity(
        gridManager = gridManager
    )
    override val substancesEntity = SubstancesEntity(
        startMaxAmount = 5_000,
        particleEntity = particleEntity,
        substrateSettings = substrateSettings
    )

    override val entityList = listOf(
        tailEntity,
        organEntity,
        particleEntity,
        neuralEntity,
        eyeEntity,
        specialModDataEntity,
        specialEntity,
        cellEntity,
        linkEntity,
        pheromoneEntity,
        substancesEntity,
        producerEntity,
        pheromoneEmitterEntity
    )

    override val genomeManager = GenomeManager(
        genomeJsonReader = genomeJsonReader,
        simulationData = simulationData
    )

    override val organManager = OrganManager(
        organEntity = organEntity,
        genomeManager = genomeManager,
        cellEntity = cellEntity
    )

    val renderBufferManager = RenderBufferManager(
        simulationData = simulationData,
        cellEntity = cellEntity,
        particleEntity = particleEntity,
        linkEntity = linkEntity,
        cellList = cellList,
        specialEntity = specialEntity,
        pheromoneEntity = pheromoneEntity
    )

    val renderSystem = RenderSystem(
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        shaderManager = shaderManager,
        particleEntity = particleEntity,
        renderBufferManager = renderBufferManager,
        pheromoneEntity = pheromoneEntity
    )

    val userCommandManager = UserCommandManager(
        organEntity = organEntity,
        cellEntity = cellEntity,
        genomeManager = genomeManager,
        cellList = cellList,
        simulationData = simulationData,
        gridManager = gridManager,
        particleEntity = particleEntity,
        zygote = zygote,
        isEditor = false
    )

    override val worldCommandsManager = WorldCommandsManager(
        gridManager = gridManager,
        organManager = organManager,
        organEntity = organEntity,
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        particleEntity = particleEntity,
        pheromoneEntity = pheromoneEntity,
        substrateSettings = substrateSettings,
        genomeManager = genomeManager,
        simulationData = simulationData,
        cellList = cellList,
        substancesEntity = substancesEntity,
        specialEntity = specialEntity,
        userCommandManager = userCommandManager,
        diContext = this,
        isEditor = false
    )

    override val pheromonesManager = PheromonesManager(
        pheromoneEntity = pheromoneEntity,
        worldCommandsManager = worldCommandsManager,
        particleEntity = particleEntity,
        cellEntity = cellEntity
    )
    val worldTerrainManager = WorldTerrainManager(
        particleEntity = particleEntity,
        substancesEntity = substancesEntity
    )

    val collisionManager = CollisionManager(
        entity = particleEntity,
        worldCommandsManager = worldCommandsManager,
        linkEntity = linkEntity,
        cellList = cellList,
        cellEntity = cellEntity,
        substancesEntity = substancesEntity,
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
        worldCommandsManager = worldCommandsManager,
        particleEntity = particleEntity,
        gridManager = gridManager,
        specialEntity = specialEntity,
        organEntity = organEntity,
        isEditor = false
    )

    val cellSystem = CellSystem(
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        organEntity = organEntity,
        genomeManager = genomeManager,
        worldCommandsManager = worldCommandsManager,
        gridManager = gridManager,
        divideManager = divideManager,
        mutateManager = mutateManager
    )

    val linkPhysicsSystem = LinkPhysicsSystem(
        linkEntity = linkEntity,
        substrateSettings = substrateSettings,
        particleEntity = particleEntity,
        cellEntity = cellEntity,
        worldCommandsManager = worldCommandsManager,
        cellSystem = cellSystem
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


    val simulationSystem by lazy {
        if (Gdx.app.type != Application.ApplicationType.WebGL) {
            SingleThreadSimulationSystem(
                gridManager = gridManager,
                worldCommandsManager = worldCommandsManager,
                organManager = organManager,
                cellEntity = cellEntity,
                linkEntity = linkEntity,
                particleEntity = particleEntity,
                particlePhysicsSystem = particlePhysicsSystem,
                linkPhysicsSystem = linkPhysicsSystem,
                simulationData = simulationData,
                cellSystem = cellSystem,
                userCommandManager = userCommandManager,
                entityList = entityList,
                renderBufferManager = renderBufferManager,
                pheromonesManager = pheromonesManager,
                movementManager = movementManager
            )
        } else {
            simulationFactory.create(
                gridManager = gridManager,
                worldCommandsManager = worldCommandsManager,
                organManager = organManager,
                cellEntity = cellEntity,
                particlePhysicsSystem = particlePhysicsSystem,
                linkPhysicsSystem = linkPhysicsSystem,
                simulationData = simulationData,
                cellSystem = cellSystem,
                userCommandManager = userCommandManager,
                entityList = entityList,
                renderBufferManager = renderBufferManager,
                pheromonesManager = pheromonesManager,
                movementManager = movementManager,
                worldTerrainManager = worldTerrainManager
            )
        }
    }

    override fun dispose() {
        TODO("Not yet implemented")
    }

    fun resizeWorld() {
        if (GRID_WIDTH == gridWidth && GRID_HEIGHT == gridHeight) return
        gridWidth = GRID_WIDTH
        gridHeight = GRID_HEIGHT

        chunkHeight = HALF_CHUNK_HEIGHT * 2
        heightMultiplier = chunkHeight * 2
        gridSize = gridWidth * gridHeight
        threadCount = (gridHeight / chunkHeight) / 2
        totalChunks = threadCount * 2
        chunkSize = gridSize / totalChunks
        if (gridHeight % heightMultiplier != 0) throw Exception("gridHeight should be a multiple of (halfChunkHeight * 2 * 2)")
        gridManager.resize()
        cellListBuilder.resize()
        simulationSystem.resize()
        worldCommandsManager.resize()
    }
}
