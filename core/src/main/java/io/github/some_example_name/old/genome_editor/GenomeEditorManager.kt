package io.github.some_example_name.old.genome_editor

import io.github.some_example_name.old.good_one.utils.distanceTo
import io.github.some_example_name.old.good_one.utils.primitive_hash_map.UnorderedIntPairMap
import io.github.some_example_name.old.world_logic.CellManager.Companion.CELL_RADIUS
import io.github.some_example_name.old.world_logic.GridManager
import io.github.some_example_name.old.world_logic.GridManager.Companion.CELL_SIZE
import kotlin.system.measureNanoTime

class GenomeEditorManager(
    val genomeName: String?
) {
    val growthProcessor = GenomeEditorGrowthProcessor(genomeName)
    var stages = IntArray(0)
    var stageByTick = StageTimelineBinarySearch(stages)
    var gridManager: GridManager
    var replay: List<GenomeStageReplayStructure> = emptyList()
    val linksPairId = UnorderedIntPairMap(10_000)

    var editorCells: List<EditorCell> = emptyList()
    var editorLinks: List<EditorLinks> = emptyList()
    var specialCells: List<SpecialCell> = emptyList()

    var state = GenomeEditorData(
        currentTick = 0,
        currentStage = 0
    )

    init {
        gridManager = growthProcessor.gridManager
        restartSimulation()
    }

    fun restartSimulation(startFromStage: Int? = null) {
        val nanoTime = measureNanoTime {
            growthProcessor.clearAll()
            linksPairId.clear()
            growthProcessor.newGenome()
            val replayResult = growthProcessor.simulate(startFromStage)
            replay = replayResult.first
            stages = replayResult.second
            stageByTick = StageTimelineBinarySearch(stages)
            updateGrid()
            if (replay.size - 1 < state.currentTick) {
                state.currentTick = replay.size - 1
                state.currentStage = stageByTick.getStage(state.currentTick)
            }
            stateChanged(false)
        }
        println("all simulation ${nanoTime / 1_000_000.0} ms")
    }

    private fun updateGrid() {
        //Добавления всех клеток реплея в ячейку сеток
        // Adding all replay cells to a grid cell
        gridManager.clearAll()
        editorCells.forEach {
            gridManager.addCell(it.gridId, it.index)
        }
    }

    fun getClickedCellIndex(worldX: Float, worldY: Float): Int? {
        val x = (worldX / CELL_SIZE).toInt()
        val y = (worldY / CELL_SIZE).toInt()
        val allCells = mutableListOf<Int>()
        for (i in -1..1) {
            for (j in -1..1) {
                allCells.addAll(gridManager.getCells(x + i, y + j).toList())
            }
        }

        val clickedIndex = allCells.minByOrNull {
            distanceTo(worldX, worldY, editorCells[it].x, editorCells[it].y)
        }?.takeIf {
            distanceTo(worldX, worldY, editorCells[it].x, editorCells[it].y) < CELL_RADIUS
        } ?: return null

        return clickedIndex
    }

    fun stateChanged(isFast: Boolean) {
        val currentStage = if (state.currentStage < growthProcessor.currentGenome.genomeStageInstruction.size) {
            growthProcessor.getStage(state.currentStage)
        } else null

        val replayCurrentStage = growthProcessor.simulationFullReplay[state.currentStage]
        val nextStage = if (growthProcessor.simulationFullReplay.size <= state.currentStage + 1) growthProcessor.simulationFullReplay.size - 1 else state.currentStage + 1
        val replayNextStage = growthProcessor.simulationFullReplay[nextStage]

        editorCells = toEditorCells(
            replayTick = replay[state.currentTick],
            genomeStage = currentStage,
            gridCellWidthSize = gridManager.gridCellWidthSize,
            gridCellHeightSize = gridManager.gridCellHeightSize,
            isFast = isFast,
            replayStage = replayCurrentStage,
            replayNextStage = replayNextStage
        )

        specialCells = toEditorSpecialCell(replayNextStage)

        editorLinks = toEditorLinks(replayNextStage, linksPairId)
        updateGrid()
    }

    fun dispose() {
        linksPairId.clear()
    }

}
