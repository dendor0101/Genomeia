package io.github.some_example_name.old.core

import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.base.CellListBuilder
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DIGameGlobalContainer.genomeJsonReader
import io.github.some_example_name.old.core.DIGameGlobalContainer.shaderManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.EyeEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.entities.SpecialModDataEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.entities.TailEntity
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
import io.github.some_example_name.old.systems.simulation.SimulationSystem
import io.github.some_example_name.old.systems.simulation.ThreadManager
import kotlin.getValue

object DISimulationContainer:  DIContext, Disposable {

    override var gridWith = 256
    override var gridHeight = 256
    var halfChunkHeight = 4 // Also max particle speed
    var chunkHeight = halfChunkHeight * 2
    var gridSize = gridWith * gridHeight
    var threadCount = (gridHeight / chunkHeight) / 2
    var totalChunks = threadCount * 2
    var chunkSize = gridSize / totalChunks
    override val substrateSettings = SubstrateSettings()

    var energyTransportRate = substrateSettings.data.rateOfEnergyTransferInLinks
    var linkMaxLength2 = substrateSettings.data.linkMaxLength * substrateSettings.data.linkMaxLength
    var cellsSettings = substrateSettings.cellsSettings

    init {
        val heightMultiplier = chunkHeight * 2
        if (gridHeight % heightMultiplier != 0) throw Exception("gridHeight should be a multiple of (halfChunkHeight * 2 * 2)")
        println("thread count: $threadCount")
        println("thread count: $heightMultiplier")
    }

    override val gridManager = GridManager(
        gridWidth = gridWith,
        gridHeight = gridHeight
    )
    private val cellListBuilder = CellListBuilder(this)
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
    val specialModDataEntity = SpecialModDataEntity(
        specialModDataStartMaxAmount = 100
    )
    override val specialEntity = SpecialEntity(
        cellsStartMaxAmount = 10_000,
        eyeEntity = eyeEntity,
        tailEntity = tailEntity,
        specialModDataEntity = specialModDataEntity
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
        cellEntity = cellEntity
    )
    override val pheromoneEntity = PheromoneEntity(
        gridManager = gridManager
    )
    override val substancesEntity = SubstancesEntity(
        startMaxAmount = 5_000,
        particleEntity = particleEntity,
        substrateSettings = substrateSettings
    )

    val entityList = listOf(
        tailEntity,
        organEntity,
        particleEntity,
        neuralEntity,
        eyeEntity,
        specialModDataEntity,
        specialEntity,
        cellEntity,
        linkEntity,
//        pheromoneEntity,
        substancesEntity
    )

    override val genomeManager = GenomeManager(
        genomeJsonReader = genomeJsonReader,
        simulationData = simulationData,
        isGenomeEditor = false,
        genomeName = null
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
        particleEntity = particleEntity,
        substrateSettings = substrateSettings,
        genomeManager = genomeManager,
        simulationData = simulationData,
        cellList = cellList,
        substancesEntity = substancesEntity,
        specialEntity = specialEntity,
        threadCount = threadCount
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
        substancesEntity = substancesEntity
    )

    val threadManager = ThreadManager(
        simulationData = simulationData
    )

    val renderBufferManager = RenderBufferManager(
        simulationData = simulationData,
        cellEntity = cellEntity,
        particleEntity = particleEntity,
        linkEntity = linkEntity,
        cellList = cellList
    )

    val renderSystem = RenderSystem(
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        shaderManager = shaderManager,
        particleEntity = particleEntity,
        renderBufferManager = renderBufferManager,
        diContext = this
    )

    val divideManager = DivideManager(
        cellEntity = cellEntity,
        worldCommandsManager = worldCommandsManager,
        particleEntity = particleEntity,
        gridManager = gridManager
    )

    val mutateManager = MutateManager(
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        worldCommandsManager = worldCommandsManager,
        particleEntity = particleEntity,
        gridManager = gridManager,
        specialEntity = specialEntity
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
        threadManager = threadManager
    )

    val linkPhysicsSystem = LinkPhysicsSystem(
        linkEntity = linkEntity,
        substrateSettings = substrateSettings,
        particleEntity = particleEntity,
        cellEntity = cellEntity,
        worldCommandsManager = worldCommandsManager,
        cellSystem = cellSystem
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
        renderSystem = renderSystem
    )

    val simulationSystem by lazy {
        SimulationSystem(
            gridManager = gridManager,
            worldCommandsManager = worldCommandsManager,
            organManager = organManager,
            organEntity = organEntity,
            cellEntity = cellEntity,
            linkEntity = linkEntity,
            particleEntity = particleEntity,
            pheromoneEntity = pheromoneEntity,
            substancesEntity = substancesEntity,
            substrateSettings = substrateSettings,
            threadManager = threadManager,
            genomeManager = genomeManager,
            particlePhysicsSystem = particlePhysicsSystem,
            linkPhysicsSystem = linkPhysicsSystem,
            simulationData = simulationData,
            cellSystem = cellSystem,
            userCommandManager = userCommandManager,
            shaderManager = shaderManager,
            renderSystem = renderSystem,
            entityList = entityList,
            renderBufferManager = renderBufferManager
        )
    }

    override fun dispose() {
        TODO("Not yet implemented")
    }
}
