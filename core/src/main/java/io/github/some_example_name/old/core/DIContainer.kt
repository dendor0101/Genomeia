package io.github.some_example_name.old.core

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.I18NBundle
import com.badlogic.gdx.utils.Json
import io.github.some_example_name.old.cells.base.CellBuilder
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.EyeEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.SimEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.DivideManager
import io.github.some_example_name.old.systems.genomics.MutateManager
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeJsonReader
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem
import io.github.some_example_name.old.systems.render.RenderSystem
import io.github.some_example_name.old.systems.render.ShaderManager
import io.github.some_example_name.old.systems.render.ShaderManagerLibgdxApi
import io.github.some_example_name.old.systems.render.TripleBufferManager
import io.github.some_example_name.old.systems.simulation.SimulationSystem
import io.github.some_example_name.old.systems.simulation.ThreadManager
import java.util.Locale
import kotlin.getValue

object DIContainer {

    var gridWith = 256
    var gridHeight = 256
    var halfChunkHeight = 4 // Also max particle speed
    var chunkHeight = halfChunkHeight * 2
    var gridSize = gridWith * gridHeight
    var threadCount = (gridHeight / chunkHeight) / 2
    var totalChunks = threadCount * 2
    var chunkSize = gridSize / totalChunks
    val substrateSettings = SubstrateSettings()

    var energyTransportRate = substrateSettings.data.rateOfEnergyTransferInLinks
    var linkMaxLength2 = substrateSettings.data.linkMaxLength * substrateSettings.data.linkMaxLength
    var cellsSettings = substrateSettings.cellsSettings

    init {
        val heightMultiplier = chunkHeight * 2
        if (gridHeight % heightMultiplier != 0) throw Exception("gridHeight should be a multiple of (halfChunkHeight * 2 * 2)")
        println("thread count: $threadCount")
        println("thread count: $heightMultiplier")
    }

    private val cellBuilder = CellBuilder()
    val cellList = cellBuilder.instances

    val json by lazy { Json() }
    val bundle: I18NBundle by lazy {
        I18NBundle.createBundle(
            Gdx.files.internal("ui/i18n/MyBundle"),
            Locale.getDefault()
        )
    }

    val gridManager = GridManager(
        gridWidth = gridWith,
        gridHeight = gridHeight
    )
    val organEntity = OrganEntity(
        organStartMaxAmount = 400
    )
    val simEntity = SimEntity()
    val particleEntity = ParticleEntity(
        particlesStartMaxAmount = 120_000,
        gridManager = gridManager
    )
    private val neuralEntity = NeuralEntity(
        neuralStartMaxAmount = 30_000,
        cellList = cellList
    )
    private val eyeEntity = EyeEntity(
        eyeStartMaxAmount = 3_000
    )

    val cellEntity = CellEntity(
        cellsStartMaxAmount = 10_000,
        particleEntity = particleEntity,
        simEntity = simEntity,
        substrateSettings = substrateSettings,
        cellList = cellList,
        neuralEntity = neuralEntity,
        eyeEntity = eyeEntity
    )
    val linkEntity = LinkEntity(
        20_000,
        cellEntity = cellEntity
    )
    val pheromoneEntity = PheromoneEntity(
        gridManager = gridManager
    )
    val substancesEntity = SubstancesEntity()
    val genomeJsonReader = GenomeJsonReader()
    val genomeManager = GenomeManager(
        genomeJsonReader = genomeJsonReader,
        simEntity = simEntity,
        isGenomeEditor = false,
        genomeName = null
    )
    val organManager = OrganManager(
        organEntity = organEntity,
        genomeManager = genomeManager,
        cellEntity = cellEntity
    )

    val worldCommandsManager = WorldCommandsManager(
        gridManager = gridManager,
        organManager = organManager,
        organEntity = organEntity,
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        particleEntity = particleEntity,
        substrateSettings = substrateSettings,
        genomeManager = genomeManager,
        simEntity = simEntity,
        cellList = cellList
    )

    val particlePhysicsSystem = ParticlePhysicsSystem(
        entity = particleEntity,
        gridManager = gridManager,
        substrateSettings = substrateSettings,
        worldCommandsManager = worldCommandsManager,
        simEntity = simEntity,
        linkEntity = linkEntity
    )

    val threadManager = ThreadManager(
        simEntity = simEntity
    )

    val tripleBufferManager = TripleBufferManager(
        particleEntity
    )

    val shaderManager: ShaderManager = when (Gdx.app.type) {
        Application.ApplicationType.Desktop -> ShaderManagerLibgdxApi()
        Application.ApplicationType.Android -> TODO()
        Application.ApplicationType.HeadlessDesktop -> TODO()
        Application.ApplicationType.Applet -> TODO()
        Application.ApplicationType.WebGL -> TODO()
        Application.ApplicationType.iOS -> TODO()
    }

    val renderSystem = RenderSystem(
        tripleBufferManager = tripleBufferManager,
        simEntity = simEntity,
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        shaderManager = shaderManager,
        particleEntity = particleEntity
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
        gridManager = gridManager
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
        cellList = cellList
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
            simEntity = simEntity,
            tripleBufferManager = tripleBufferManager,
            cellSystem = cellSystem,
            userCommandManager = userCommandManager
        )
    }
}
