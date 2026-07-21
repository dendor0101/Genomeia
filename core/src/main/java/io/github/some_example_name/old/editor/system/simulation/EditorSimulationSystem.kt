package io.github.some_example_name.old.editor.system.simulation

import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.utils.StageTimelineBinarySearch
import io.github.some_example_name.old.editor.entities.EditorReplay
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
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
    val zygote: Zygote,
    val entityList: List<Entity>,
    val userCommandManager: UserCommandManager
) {

    val baseOrganIndex = 0
    var genome = genomeManager.genomes[0]
    var tickByStage = IntArray(0)
    var stageByTick = StageTimelineBinarySearch(tickByStage)
    var mapCellGenomeIdToIndex = Int2IntOpenHashMap().apply { defaultReturnValue(-1) }

    var maxCellId = 0

    fun newGenome() {
        genome = Genome(
            genomeStageInstruction = genome.genomeStageInstruction,
            dividedTimes = IntArray(genome.genomeStageInstruction.size),
            mutatedTimes = IntArray(genome.genomeStageInstruction.size),
            name = genome.name,
            subGenomes = hashMapOf()
        )

        genome.genomeStageInstruction.forEachIndexed { index, stage ->
            stage.cellActions.forEach { (_, action) ->
                if (action.divide != null) {
                    genome.dividedTimes[index]++
                    if (action.divide!!.id > maxCellId) {
                        maxCellId = action.divide!!.id
                    }
                }
                if (action.mutate != null) genome.mutatedTimes[index]++
            }
        }

        genomeManager.genomes[0] = genome
    }

    fun simulate() {
        mapCellGenomeIdToIndex.clear()
        gridManager.clearAll()
        entityList.forEach { it.clear() }
        newGenome()

        userCommandManager.push(
            PlayerCommand.Tap(
                gridManager.gridWidth * 0.5f,
                gridManager.gridHeight * 0.5f
            )
        )
        userCommandManager.processingCommandsFromUser()
        worldCommandsManager.mapCellGenomeIdToIndex.put(0, 0)

        val stagesAmount = genome.genomeStageInstruction.size
        var stageCounter = 1
        tickByStage = IntArray(stagesAmount + 1)

        replays.forEach { it.reset() }

        for (tick in 0..TIME_SIMULATION) {
            updateTick()
            replays.forEach { it.copy() }

            if (organEntity.alreadyGrownUp[baseOrganIndex]) {
                tickByStage[stageCounter] = tick
                break
            }

            if (organEntity.justChangedStage[baseOrganIndex]) {
                tickByStage[stageCounter] = tick
                stageCounter++
            }
            if (tick == TIME_SIMULATION) throw Exception("Too long simulation!")
        }

        stageByTick = StageTimelineBinarySearch(tickByStage)

        mapCellGenomeIdToIndex.putAll(worldCommandsManager.mapCellGenomeIdToIndex)
    }

    private fun updateTick() = with(cellEntity) {
        genomeManager.genomes[0].genomeStageInstruction[organEntity.stage[0]].cellActions.forEach { id, action ->
            cellSystem.genomicTransformations(worldCommandsManager.mapCellGenomeIdToIndex[id])
        }

        worldCommandsManager.executingCommandsFromTheWorld()
        organManager.performOrgansNextStage()
        worldCommandsManager.executingLastCommandsFromTheWorld()

        cellEntity.aliveList.forEach { cellIndex ->
            energy[cellIndex] += 3.5f
            if (energy[cellIndex] > maxEnergy[cellIndex]) {
                energy[cellIndex] = maxEnergy[cellIndex]
            }
        }
    }

    companion object {
        const val TIME_SIMULATION = 1_000
    }
}
