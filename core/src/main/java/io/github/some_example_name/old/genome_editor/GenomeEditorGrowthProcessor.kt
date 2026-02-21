package io.github.some_example_name.old.genome_editor

import io.github.some_example_name.old.genome.Genome
import io.github.some_example_name.old.substances.SubstancePlugImpl
import io.github.some_example_name.old.world_logic.CellManager
import io.github.some_example_name.old.world_logic.GridManager
import io.github.some_example_name.old.world_logic.genomic_transformations.divideCell
import io.github.some_example_name.old.world_logic.genomic_transformations.mutateCell
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.system.measureNanoTime

const val TIME_SIMULATION = 15000

class GenomeEditorGrowthProcessor(genomeName: String?) {

    private var cellManager = CellManager(
        map = null,
        cellsStartMaxAmount = 20000,
        linksStartMaxAmount = 35000,
        isGenomeEditor = true,
        gridManager = GridManager(gridCellWidthSize = 126, gridCellHeightSize = 126),
        subManager = SubstancePlugImpl(),
        genomeName = genomeName
    )

    private val genomeReplay = mutableListOf<GenomeStageReplayStructure>()
    private var stagesTimeTick = IntArray(0)
    val simulationFullReplay = mutableListOf<FullReplayStructure>()
    var currentGenome = cellManager.genomeManager.genomeForEditor
    var maxCellId = 0

    fun getStage(stageIndex: Int) = currentGenome.genomeStageInstruction[stageIndex]
    val gridManager = cellManager.gridManager

    init {
        cellManager.addCell(START_EDITOR_CELL_X, START_EDITOR_CELL_Y, 18, false, genomeIndex = 0)
        cellManager.cellGenomeId[0] = 0
    }

    fun clearAll() {
        cellManager.gridManager.clearAll()
        cellManager.clear()
        cellManager.linkIndexMap.clear()
//        cellManager.getOrganism(0).linkIdMap.clear()
        cellManager.organismManager.organisms.clear()
    }

    fun newGenome() {
        val genomeStageInstruction = currentGenome.genomeStageInstruction
        val dividedTimes = IntArray(genomeStageInstruction.size)
        val mutatedTimes = IntArray(genomeStageInstruction.size)

        genomeStageInstruction.forEachIndexed { index, stage ->
            stage.cellActions.forEach { (_, action) ->
                if (action.divide != null) {
                    dividedTimes[index]++
                    if (action.divide!!.id > maxCellId) {
                        maxCellId = action.divide!!.id
                    }
                }
                if (action.mutate != null) mutatedTimes[index] ++
            }
        }
        currentGenome = Genome(
            genomeStageInstruction = genomeStageInstruction,
            dividedTimes = dividedTimes,
            mutatedTimes = mutatedTimes,
            name = currentGenome.name
        )
        val genomeForPhysics = currentGenome.deepCopy()

        genomeForPhysics.genomeStageInstruction.forEach { stage ->
            val invertedDivide = hashMapOf<Int, Int>()
            for ((key, action) in stage.cellActions) {
                action.divide?.id?.let { divideId ->
                    invertedDivide[divideId] = key
                }
            }

            for ((key, action) in stage.cellActions) {
                action.divide?.physicalLink?.forEach { linkKey, linkData ->
                    action.divide?.id?.let { id ->
                        invertedDivide[linkKey]?.let { divideKey ->
                            val cellAction = stage.cellActions[divideKey]
                            cellAction?.divide?.physicalLink?.put(id, linkData)
                        }
                    }
                }
                action.mutate?.physicalLink?.forEach { linkKey, linkData ->
                    invertedDivide[linkKey]?.let { divideKey ->
                        val cellAction = stage.cellActions[divideKey]
                        cellAction?.divide?.physicalLink?.put(key, linkData)
                    }
                }
            }
        }

        cellManager.genomeManager.genomes[0] = genomeForPhysics
        cellManager.addCell(START_EDITOR_CELL_X, START_EDITOR_CELL_Y, 18, false, genomeIndex = 0)
        cellManager.cellGenomeId[0] = 0
    }

    private fun initFromStage(stageCounter: Int, stagesAmount: Int): Int {
        var startTick = 0
        startTick = stagesTimeTick[stageCounter] + 1
        val currentFullReplay = simulationFullReplay[stageCounter]
        genomeReplay.subList(startTick, genomeReplay.size).clear()
        val stagesTimeTickPart1 = stagesTimeTick.copyOfRange(0, stageCounter + 1)
        val stagesTimeTickPart2 = IntArray(stagesAmount - stageCounter)
        stagesTimeTick = stagesTimeTickPart1 + stagesTimeTickPart2

        simulationFullReplay.subList(stageCounter + 1, simulationFullReplay.size).clear()

        val organism = cellManager.getOrganism(0)
        cellManager.restoreFromFullReplayStructure(currentFullReplay)

        organism.stage = stageCounter
        organism.justChangedStage = true
//        organism.timerToGrowAfterStage =

        for (i in 0..currentFullReplay.linksLastId) {
            val links1 = cellManager.links1[i]
            val links2 = cellManager.links2[i]
            cellManager.linkIndexMap.put(links1, links2, i)

//            cellManager.getOrganism(0).linkIdMap.put(
//                cellManager.cellGenomeId[links1],
//                cellManager.cellGenomeId[links2],
//                i
//            )
        }

        //TODO поярдок растановки id в сетке может влиять на результат в дальнейшем
        for (i in 0..currentFullReplay.cellLastId) {
            val xGrid = (currentFullReplay.x[i] / CELL_SIZE).toInt()
            val yGrid = (currentFullReplay.y[i] / CELL_SIZE).toInt()
            cellManager.gridManager.addCell(xGrid, yGrid, i) {

            }
        }

        return startTick
    }

    fun simulate(startFromStage: Int? = null): Pair<MutableList<GenomeStageReplayStructure>, IntArray> {
        val stagesAmount = currentGenome.genomeStageInstruction.size
        var stageCounter = startFromStage ?: 0

        var startTick = 0
        if (startFromStage == null) {
            simulationFullReplay.clear()
            genomeReplay.clear()
            stagesTimeTick = IntArray(stagesAmount + 1)
            simulationFullReplay.add(cellManager.createFullReplayStructure())
            stageCounter++
        } else {
            startTick = initFromStage(stageCounter, stagesAmount)
            if (stageCounter == cellManager.getOrganism(0).genomeSize) return Pair(
                genomeReplay,
                stagesTimeTick
            )
            stageCounter++
        }


        val nanoTime = measureNanoTime {
            for (tick in startTick..TIME_SIMULATION) {

                for (cellId in 0..cellManager.cellLastId) {
                    cellManager.processCellGenomeEditor(cellId)
                }

                val stageResult = cellManager.updateAfterCycleGenomeEditor()

                //Сохранение реплея каждого тика
                val realCellAmount = cellManager.cellLastId + 1
                genomeReplay.add(
                    GenomeStageReplayStructure(
                        cellManager.energy.copyOfRange(0, realCellAmount),
                        realCellAmount,
                    )
                )

                if (stageResult == true) {
                    stagesTimeTick[stageCounter] = tick
                    simulationFullReplay.add(cellManager.createFullReplayStructure())
                    stageCounter ++
                } else if (stageResult == null) {
                    stagesTimeTick[stageCounter] = tick
                    simulationFullReplay.add(cellManager.createFullReplayStructure())
                    break
                }
            }
        }

        println("${nanoTime / 1_000_000.0} ms")
        return Pair(genomeReplay, stagesTimeTick)
    }

    fun CellManager.processCellGenomeEditor(
        cellIndex: Int,
//        gridX: Int,
//        gridY: Int,
//        threadId: Int
    ) {
//        processPhysics(cellId, gridX, gridY, threadId)
//        processCellFriction(cellId)
//        vx[cellId] *= 0f
//        vy[cellId] *= 0f
        energy[cellIndex] += 1.5f
        if (energy[cellIndex] > cellsSettings[cellType[cellIndex] + 1].maxEnergy) energy[cellIndex] = cellsSettings[cellType[cellIndex] + 1].maxEnergy
        val organism = organismManager.organisms[organismIndex[cellIndex]]
        if (!organism.alreadyGrownUp) {
            TODO("тут думать надо")
            if (organism.justChangedStage) {
                isDividedInThisStage[cellIndex] = false
                isMutateInThisStage[cellIndex] = false
            }
            mutateCell(cellIndex, 0/*, organism*/)
            divideCell(cellIndex, 0/*, organism*/)
        }
//        processCellAngel(cellId)
    }

    fun CellManager.updateAfterCycleGenomeEditor(): Boolean? {
        /*
        * Растоновка позиций
        * */
//        for (i in 0..cellLastId) {
//            moveToCell(i)
//        }

        /*
        * Выполнение команд от мира
        * */
        performWorldCommands()

        /*
        * Переход на следющую стадию генома
        * */
        return performOrganismNextStage(cellManager.organismManager.organisms.first())
    }

    fun dispose() {
        clearAll()
    }

    companion object {
        const val START_EDITOR_CELL_X = 2520f
        const val START_EDITOR_CELL_Y = 2520f
    }
}
