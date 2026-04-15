package io.github.some_example_name.old.core

import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.base.CellListBuilder
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DIGameGlobalContainer.genomeJsonReader
import io.github.some_example_name.old.core.DIGameGlobalContainer.shaderManager
import io.github.some_example_name.old.editor.commands.CommandEditorStackManager
import io.github.some_example_name.old.editor.system.EditorLogicSystem
import io.github.some_example_name.old.editor.system.EditorRenderSystem
import io.github.some_example_name.old.editor.system.EditorSimulationSystem
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.EyeEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.entities.SpecialModDataEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.entities.TailEntity
import io.github.some_example_name.old.editor.entities.ReplayEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.ProducerEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.DivideManager
import io.github.some_example_name.old.systems.genomics.MutateManager
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.simulation.SimulationData

object DIGenomeEditorContainer: DIContext, Disposable {
    override var gridWith = 128
    override var gridHeight = 128
    const val THREAD_COUNT = 1

    override val gridManager = GridManager(
        gridWidth = gridWith,
        gridHeight = gridHeight
    )

    private val cellListBuilder = CellListBuilder(this)
    val cellList = cellListBuilder.instances
    val zygote = cellListBuilder.zygote

    val simulationData = SimulationData()
    override val substrateSettings = SubstrateSettings()

    override val genomeManager = GenomeManager(
        genomeJsonReader = genomeJsonReader,
        simulationData = simulationData,
        isGenomeEditor = true,
        genomeName = "AAA.json"
    )

    override val organEntity = OrganEntity(
        organStartMaxAmount = 1
    )

    override val particleEntity = ParticleEntity(
        particlesStartMaxAmount = 100,
        gridManager = gridManager
    )

    private val neuralEntity = NeuralEntity(
        neuralStartMaxAmount = 30,
        cellList = cellList
    )
    private val eyeEntity = EyeEntity(
        eyeStartMaxAmount = 15
    )
    private val producerEntity = ProducerEntity(
        producerStartMaxAmount = 3
    )
    val specialModDataEntity = SpecialModDataEntity(
        specialModDataStartMaxAmount = 100
    )

    val tailEntity = TailEntity(
        tailStartMaxAmount = 5
    )

    override val specialEntity = SpecialEntity(
        cellsStartMaxAmount = 10,
        eyeEntity = eyeEntity,
        tailEntity = tailEntity,
        specialModDataEntity = specialModDataEntity,
        producerEntity = producerEntity
    )

    override val cellEntity = CellEntity(
        cellsStartMaxAmount = 100,
        particleEntity = particleEntity,
        simulationData = simulationData,
        substrateSettings = substrateSettings,
        cellList = cellList,
        neuralEntity = neuralEntity,
        specialEntity = specialEntity
    )

    override val linkEntity = LinkEntity(
        1,
        cellEntity = cellEntity
    )
    override val substancesEntity = SubstancesEntity(
        startMaxAmount = 1,
        particleEntity = particleEntity,
        substrateSettings = substrateSettings
    )
    override val pheromoneEntity = PheromoneEntity(gridManager)

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
        threadCount = THREAD_COUNT
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
        threadManager = null
    )

    val replayEntity = ReplayEntity(
        startCapacity = 1_000,
        particleEntity = particleEntity,
        cellEntity = cellEntity
    )

    val editorSimulationSystem = EditorSimulationSystem(
        cellEntity = cellEntity,
        organEntity = organEntity,
        organManager = organManager,
        worldCommandsManager = worldCommandsManager,
        genomeManager = genomeManager,
        replayEntity = replayEntity,
        particleEntity = particleEntity,
        cellSystem = cellSystem,
        gridManager = gridManager,
        zygote = zygote
    )

    val commandEditorStackManager = CommandEditorStackManager()

    val editorLogicSystem = EditorLogicSystem(
        commandEditorStackManager = commandEditorStackManager,
        editorSimulationSystem = editorSimulationSystem,
        replayEntity = replayEntity
    )

    val editorRenderSystem = EditorRenderSystem(
        shaderManager = shaderManager,
        replayEntity = replayEntity,
        editorLogicSystem = editorLogicSystem
    )

    override fun dispose() {

    }

}
