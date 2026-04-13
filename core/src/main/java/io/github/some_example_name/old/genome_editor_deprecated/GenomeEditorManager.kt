package io.github.some_example_name.old.genome_editor_deprecated

import io.github.some_example_name.old.core.utils.StageTimelineBinarySearch
import io.github.some_example_name.old.core.utils.UnorderedIntPairMap
import io.github.some_example_name.old.core.utils.distanceTo
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem.Companion.PARTICLE_MAX_RADIUS
import kotlin.system.measureNanoTime
/*

class GenomeEditorManager(
    val genomeName: String?,
    val gridManager: GridManager
) {
    val growthProcessor = GenomeEditorGrowthProcessor(genomeName)
    var stages = IntArray(0)
    var stageByTick = StageTimelineBinarySearch(stages)

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
        restartSimulation()
    }

    fun restartSimulation(startFromStage: Int? = null) {
        val nanoTime = measureNanoTime {
//            growthProcessor.clearAll()
            linksPairId.clear()
            growthProcessor.newGenome()
            val replayResult = growthProcessor.simulate(null) // Если null То без фул-реплея
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
        val x = worldX.toInt()
        val y = worldY.toInt()
        val allCells = mutableListOf<Int>()
        for (i in -1..1) {
            for (j in -1..1) {
                allCells.addAll(gridManager.getParticles(x + i, y + j).toList())
            }
        }

        val clickedIndex = allCells.minByOrNull {
            distanceTo(worldX, worldY, editorCells[it].x, editorCells[it].y)
        }?.takeIf {
            distanceTo(worldX, worldY, editorCells[it].x, editorCells[it].y) < PARTICLE_MAX_RADIUS
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
            gridCellWidthSize = gridManager.gridWidth,
            gridCellHeightSize = gridManager.gridHeight,
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
*/
