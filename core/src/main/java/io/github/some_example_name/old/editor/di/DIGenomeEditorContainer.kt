package io.github.some_example_name.old.editor.di

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.base.CellListBuilder
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DIContext
import io.github.some_example_name.old.core.DIGameGlobalContainer
import io.github.some_example_name.old.editor.system.command.CommandEditorStackManager
import io.github.some_example_name.old.editor.entities.CellReplay
import io.github.some_example_name.old.editor.entities.EyeReplay
import io.github.some_example_name.old.editor.entities.NeuralLinkReplay
import io.github.some_example_name.old.editor.entities.NeuralReplay
import io.github.some_example_name.old.editor.system.CellSearchManager
import io.github.some_example_name.old.editor.system.control.LeftRightClickManager
import io.github.some_example_name.old.editor.system.logic.EditorLogicSystem
import io.github.some_example_name.old.editor.system.render.EditorRenderSystem
import io.github.some_example_name.old.editor.system.simulation.EditorSimulationSystem
import io.github.some_example_name.old.editor.system.SymmetryManager
import io.github.some_example_name.old.editor.system.control.TryActionManager
import io.github.some_example_name.old.editor.system.logic.MoveCellManager
import io.github.some_example_name.old.editor.system.logic.ToEditorDataMapper
import io.github.some_example_name.old.editor.system.logic.UiScreenCommands
import io.github.some_example_name.old.editor.system.render.DrawingHelperElements
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.EyeEntity
import io.github.some_example_name.old.editor.baking.BodyExport
import io.github.some_example_name.old.editor.baking.RCMSort
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEmitterEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.entities.ProducerEntity
import io.github.some_example_name.old.entities.SpecialEntity
import io.github.some_example_name.old.entities.SpecialModDataEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.entities.TailEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.DivideManager
import io.github.some_example_name.old.systems.genomics.MutateManager
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.simulation.SimulationData

object DIGenomeEditorContainer: DIContext, Disposable, EditorVariables {
    override var gridWidth = 128
    override var gridHeight = 128
    override var threadCount = 1
    override var chunkSize = gridWidth * gridHeight
    override var totalChunks = 1

    override var currentTick = 0
    override var lastTick = 0
    override var grabbedCellIndex = -1
    override var lastGrabbedCellX = 0.0f
    override var lastGrabbedCellY = 0.0f
    override var isDruggingCamera = false
    override var isRightClick = false
    var showPhysicalLink = true
    var previousCtrlClicked = -1
    var linkColor: Color = Color.CYAN

    override val gridManager = GridManager(
        gridWidth = gridWidth,
        gridHeight = gridHeight,
        diContext = this,
        maxAmountOfParticles = 8
    )

    private val cellListBuilder = CellListBuilder().apply {
        bindToDIContext(this@DIGenomeEditorContainer)
    }
    val cellsTypeNames = cellListBuilder.instances.map { it.name }.toTypedArray()
    val cellList = cellListBuilder.instances
    val zygote = cellListBuilder.zygote

    val simulationData = SimulationData()

    override val genomeManager = GenomeManager(
        genomeJsonReader = DIGameGlobalContainer.genomeJsonReader,
        simulationData = simulationData
    )

    override val organEntity = OrganEntity(
        organStartMaxAmount = 1,
        // В редакторе арены не нужны: один организм, параллельных фаз нет, а ёмкость
        // арены (рассчитанная на взрослое тело) растянула бы все массивы редактора
        // на порядок. С false всё создание идёт прежним путём, через общий аллокатор.
        arenasEnabled = false
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

    val pheromoneEmitterEntity = PheromoneEmitterEntity(
        pheromoneEmitterStartMaxAmount = 1
    )

    override val specialEntity = SpecialEntity(
        cellsStartMaxAmount = 10,
        eyeEntity = eyeEntity,
        tailEntity = tailEntity,
        specialModDataEntity = specialModDataEntity,
        producerEntity = producerEntity,
        pheromoneEmitterEntity = pheromoneEmitterEntity
    )

    override val cellEntity = CellEntity(
        cellsStartMaxAmount = 100,
        particleEntity = particleEntity,
        simulationData = simulationData,
        substrateSettings = DIGameGlobalContainer.substrateSettings,
        cellList = cellList,
        neuralEntity = neuralEntity,
        specialEntity = specialEntity,
        organEntity = organEntity
    )

    override val linkEntity = LinkEntity(
        100,
        cellEntity = cellEntity,
        gridManager = gridManager,
        particleEntity = particleEntity,
        diContext = this,
        organEntity = organEntity
    )

    override val neuralLinkEntity = NeuralLinkEntity(
        50,
        cellEntity = cellEntity,
        isEditor = true
    )

    /**
     * Запекание раскладки организма. Живёт только в редакторе: считает порядок один раз
     * при сохранении генома, когда тело уже выращено целиком. В симуляции этот порядок
     * просто читается из генома.
     */
    val rcmSort = RCMSort(
        linkEntity = linkEntity,
        cellEntity = cellEntity,
        neuralLinkEntity = neuralLinkEntity
    )

    /**
     * Выгрузка топологии выращенного тела в текстовый файл. Как и запекание, живёт только
     * в редакторе и срабатывает в тот же момент — при сохранении генома.
     */
    val bodyExport = BodyExport(
        cellEntity = cellEntity,
        linkEntity = linkEntity
    )

    override val substancesEntity = SubstancesEntity(
        startMaxAmount = 1,
        particleEntity = particleEntity,
        substrateSettings = DIGameGlobalContainer.substrateSettings
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
        neuralLinkEntity = neuralLinkEntity,
        particleEntity = particleEntity,
        substrateSettings = DIGameGlobalContainer.substrateSettings,
        genomeManager = genomeManager,
        simulationData = simulationData,
        cellList = cellList,
        substancesEntity = substancesEntity,
        specialEntity = specialEntity,
        diContext = this,
        isEditor = true,
        pheromoneEntity = pheromoneEntity
    )

    override val pheromonesManager = PheromonesManager(
        pheromoneEntity = pheromoneEntity,
        particleEntity = particleEntity,
        worldCommandsManager = worldCommandsManager,
        cellEntity = cellEntity
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
        organEntity = organEntity,
        isEditor = true
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

    val cellReplay = CellReplay(
        startCapacity = 1_000,
        particleEntity = particleEntity,
        cellEntity = cellEntity
    )

    val neuralLinkReplay = NeuralLinkReplay(
        startCapacity = 300,
        neuralLinkEntity = neuralLinkEntity
    )

    val eyeReplay = EyeReplay(
        startCapacity = 300,
        specialEntity = specialEntity,
        eyeEntity = eyeEntity
    )

    val neuralReplay = NeuralReplay(
        startCapacity = 300,
        neuralEntity = neuralEntity
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
        neuralLinkEntity,
        substancesEntity,
        producerEntity,
        pheromoneEntity,
        pheromoneEmitterEntity
    )

    private val replays = listOf(
        cellReplay,
        neuralLinkReplay,
        eyeReplay,
        neuralReplay
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
        isEditor = true
    )

    val editorSimulationSystem = EditorSimulationSystem(
        cellEntity = cellEntity,
        organEntity = organEntity,
        organManager = organManager,
        worldCommandsManager = worldCommandsManager,
        genomeManager = genomeManager,
        replays = replays,
        cellSystem = cellSystem,
        gridManager = gridManager,
        particleEntity = particleEntity,
        zygote = zygote,
        entityList = entityList,
        userCommandManager = userCommandManager
    )

    val nextStageTick get() = (currentTick + 1).coerceIn(0, lastTick)

    val commandEditorStackManager = CommandEditorStackManager()

    val toEditorDataMapper = ToEditorDataMapper(
        cellEntity = cellEntity,
        cellReplay = cellReplay,
        editorSimulationSystem = editorSimulationSystem,
        particleEntity = particleEntity,
        neuralReplay = neuralReplay,
        eyeReplay = eyeReplay
    )

    val cellSearchManager = CellSearchManager(
        cellReplay = cellReplay,
        particleEntity = particleEntity,
        gridManager = gridManager,
        toEditorDataMapper = toEditorDataMapper
    )

    val symmetryManager = SymmetryManager(
        particleEntity = particleEntity,
        cellSearchManager = cellSearchManager
    )

    val tryActionManager = TryActionManager(
        commandEditorStackManager = commandEditorStackManager,
        editorSimulationSystem = editorSimulationSystem,
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        cellSearchManager = cellSearchManager,
        toEditorDataMapper = toEditorDataMapper,
    )

    val leftRightClickManager = LeftRightClickManager(
        commandEditorStackManager = commandEditorStackManager,
        editorSimulationSystem = editorSimulationSystem,
        cellEntity = cellEntity,
        linkEntity = linkEntity,
        neuralLinkEntity = neuralLinkEntity,
        neuralLinkReplay = neuralLinkReplay,
        symmetryManager = symmetryManager,
        cellSearchManager = cellSearchManager,
        toEditorDataMapper = toEditorDataMapper,
        tryActionManager = tryActionManager
    )

    var uiScreenCommands: UiScreenCommands? = null

    val moveCellManager = MoveCellManager(
        commandEditorStackManager = commandEditorStackManager,
        editorSimulationSystem = editorSimulationSystem,
        cellEntity = cellEntity,
        particleEntity = particleEntity,
        symmetryManager = symmetryManager,
        cellSearchManager = cellSearchManager,
        toEditorDataMapper = toEditorDataMapper
    )

    val editorLogicSystem = EditorLogicSystem(
        commandEditorStackManager = commandEditorStackManager,
        editorSimulationSystem = editorSimulationSystem,
        cellReplay = cellReplay,
        cellEntity = cellEntity,
        particleEntity = particleEntity,
        linkEntity = linkEntity,
        gridManager = gridManager,
        cellSearchManager = cellSearchManager,
        toEditorDataMapper = toEditorDataMapper,
        leftRightClickManager = leftRightClickManager,
        moveCellManager = moveCellManager,
        tryActionManager = tryActionManager
    )

    val drawingHelperElements = DrawingHelperElements(
        cellReplay = cellReplay,
        neuralLinkReplay = neuralLinkReplay,
        cellEntity = cellEntity,
        particleEntity = particleEntity,
        editorSimulationSystem = editorSimulationSystem,
        symmetryManager = symmetryManager,
        cellList = cellList,
        cellSearchManager = cellSearchManager,
    )

    val editorRenderSystem = EditorRenderSystem(
        worldRenderer = DIGameGlobalContainer.worldRenderer,
        cellReplay = cellReplay,
        particleEntity = particleEntity,
        editorSimulationSystem = editorSimulationSystem,
        drawingHelperElements = drawingHelperElements
    )

    override fun dispose() {

    }

}
