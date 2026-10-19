package io.github.some_example_name.old.editor.system.simulation

import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.editor.entities.EditorReplay
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeStage
import io.github.some_example_name.old.systems.genomics.genome.getEmptyGenome
import io.github.some_example_name.old.systems.genomics.library.GenomeCatalog
import io.github.some_example_name.old.systems.physics.GridManager
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap

class EditorSimulationSystem(
    val cellEntity: CellEntity,
    val organEntity: OrganEntity,
    val organManager: OrganManager,
    val worldCommandsManager: WorldCommandsManager,
    val genomeManager: GenomeManager,
    val replays: List<EditorReplay>,
    val cellSystem: CellSystem,
    val gridManager: GridManager,
    val particleEntity: ParticleEntity,
    val zygote: Zygote,
    val entityList: List<Entity>,
    val neuralLinkEntity: NeuralLinkEntity,
    val userCommandManager: UserCommandManager
) {

    private val baseOrganIndex = 0
    private var genome = getEmptyGenome()
    val genomeStageInstruction: MutableList<GenomeStage> = genome.stageInstruction.toMutableList()
    var mapCellGenomeIdToIndex = Int2IntOpenHashMap().apply { defaultReturnValue(-1) }

    var maxCellId = 0

    /**
     * Пик нейросвязей последнего выращивания — Genome.neuralLinksPeak. На каждой стадии берётся
     * «живых на её начало + созданных за неё»: внутри стадии мир может создать все её
     * нейросвязи раньше, чем удалит отменённые (порядок там задаёт энергия клеток, а не
     * редактор), и арена обязана вместить и такой порядок. Само число живых в редакторе
     * этого не видит — он удаляет и создаёт в своём, одном порядке.
     */
    var neuralLinksPeak = 0
        private set

    /** [genomeName] — свой или встроенный геном (см. GenomeCatalog), null — новый. */
    fun reinitGenome(genomeName: String?) {
        useGenome(if (genomeName != null) GenomeCatalog.load(genomeName) else getEmptyGenome())
    }

    /** Геном не из папки: импорт и перенос старых JSON пекут его здесь (GenomeBaker). */
    fun useGenome(genome: Genome) {
        genomeManager.genomes.clear()
        this.genome = genome
        genomeStageInstruction.clear()
        genomeStageInstruction.addAll(genome.genomeStageInstruction)
        genomeManager.genomes.add(genome)
    }

    fun getGenome() = genome

    private fun newGenome() {
        genome = Genome(
            stageInstruction = genomeStageInstruction,
            version = 24,
            name = genome.name,
            subGenomes = hashMapOf()
        ).apply {
            dividedTimes = IntArray(genomeStageInstruction.size)
            mutatedTimes = IntArray(genomeStageInstruction.size)
        }

        genome.genomeStageInstruction.forEachIndexed { index, stage ->
            stage.cellActions.forEach { (_, action) ->
                if (action.divide != null) {
                    genome.dividedTimes[index]++
                    if (action.divide.id > maxCellId) {
                        maxCellId = action.divide.id
                    }
                }
                if (action.mutate != null) genome.mutatedTimes[index]++
            }
        }

        genomeManager.genomes[0] = genome
    }

    fun simulate() {
        maxCellId = 0
        mapCellGenomeIdToIndex.clear()
        // И карта мира редактора: её только дописывали, и в ней оставались клетки прошлого
        // расчёта — другого генома или до правки. Действие для клетки, которой сейчас нет,
        // находило по ней чужой индекс (видно на переводе старых JSON подряд: «Tardigrade»
        // после «Megalodon» падал на organIndex = -1).
        worldCommandsManager.mapCellGenomeIdToIndex.clear()
        gridManager.clearAll()
        entityList.forEach { it.clear() }
        newGenome()

        userCommandManager.pushInternal(
            PlayerCommand.Tap(
                gridManager.gridWidth * 0.5f,
                gridManager.gridHeight * 0.5f
            )
        )
        userCommandManager.processingCommandsFromUser()
        worldCommandsManager.mapCellGenomeIdToIndex.put(0, 0)

        replays.forEach { it.reset() }

        var stage = organEntity.stage[baseOrganIndex]
        var stageStartedAt = 0
        var stageStartAlive = neuralLinkEntity.aliveList.size
        var stageStartCreated = neuralLinkEntity.createdCount
        neuralLinksPeak = stageStartAlive
        for (tick in 0..TIME_SIMULATION) {
            updateTick()
            replays.forEach { it.copy() }

            // Созданное в тике, где стадия сменилась, — ещё её: смена стадии идёт после команд.
            val bound = stageStartAlive + (neuralLinkEntity.createdCount - stageStartCreated).toInt()
            if (bound > neuralLinksPeak) neuralLinksPeak = bound

            if (organEntity.alreadyGrownUp[baseOrganIndex]) break

            if (organEntity.stage[baseOrganIndex] != stage) {
                stage = organEntity.stage[baseOrganIndex]
                stageStartedAt = tick
                stageStartAlive = neuralLinkEntity.aliveList.size
                stageStartCreated = neuralLinkEntity.createdCount
            } else if (tick - stageStartedAt >= STUCK_STAGE_TICKS) {
                throw IllegalStateException("рост застрял на стадии $stage: $STUCK_STAGE_TICKS тиков без перехода")
            }

            if (tick == TIME_SIMULATION) throw Exception("Too long simulation!")
        }

        mapCellGenomeIdToIndex.putAll(worldCommandsManager.mapCellGenomeIdToIndex)
    }

    private fun updateTick() = with(cellEntity) {
        genomeManager.genomes[0].stageInstruction[organEntity.stage[0]].cellActions.forEach { id, _ ->
            // Действие для клетки, которой на этой стадии нет, пропускается — как в мире: там
            // действия раздаются живым клеткам, и лишнее просто никому не достаётся (стадия
            // всё равно закрывается, см. OrganManager). Такие есть в старых геномах из JSON
            // («Tapeworm», «Tardigrade»), и на них редактор падал на индексе -1.
            val cellIndex = worldCommandsManager.mapCellGenomeIdToIndex[id]
            if (cellIndex != -1) cellSystem.genomicTransformations(cellIndex)
        }

        worldCommandsManager.executingCommandsFromTheWorld()
        organManager.performOrgansNextStage()
        worldCommandsManager.executingLastCommandsFromTheWorld()

        // Редактор не двигает частицы, но создаёт их командами, поэтому сетку нужно
        // пересобрать: поиск клетки под курсором читает cellStart/particleIdx.
        //
        // Вместо isInGrid — массив из одних true: в редакторе нет столкновений, поэтому
        // выкидывать внутренние клетки незачем, а вот курсор до них дотягиваться обязан
        // (выбор клетки, проверка наложения при переносе, место под новую клетку).
        gridManager.rebuild(particleEntity.isAlive, particleEntity.gridId, allInGrid())

        cellEntity.aliveList.forEach { cellIndex ->
            energy[cellIndex] = 5.0f
        }
    }

    private var allInGridBuffer = BooleanArray(0)

    /** Массив «все частицы в сетке» под текущую ёмкость частиц; пересоздаётся только при её смене. */
    private fun allInGrid(): BooleanArray {
        val size = particleEntity.isInGrid.size
        if (allInGridBuffer.size != size) allInGridBuffer = BooleanArray(size) { true }
        return allInGridBuffer
    }

    companion object {
        const val TIME_SIMULATION = 1_000_000

        /**
         * Сколько тиков стадия может идти в редакторе, прежде чем рост считается застрявшим.
         *
         * В редакторе энергия клеток пополняется каждый тик, и стадия закрывается за один тик:
         * замер :lwjgl3:genomeJsonCheck 13.10 на 18 старых геномах (до 2134 стадий и 5120
         * клеток) — ровно 1 тик на стадию у всех. Без предела застрявший геном шёл до
         * TIME_SIMULATION, копируя тело в повтор (replays) на каждом тике, и память кончалась
         * раньше. При переводе старых JSON на запуске игры (GenomeLibrary) это повторялось бы
         * при каждом запуске: такой JSON не переводится и остаётся на месте.
         */
        const val STUCK_STAGE_TICKS = 1_000
    }
}
