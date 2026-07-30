package io.github.some_example_name.old.editor.system.simulation

import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.commands.PlayerCommand
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.prettyPrint
import io.github.some_example_name.old.editor.entities.EditorReplay
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.Genome
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeStage
import io.github.some_example_name.old.systems.genomics.genome.getEmptyGenome
import io.github.some_example_name.old.systems.genomics.genome.loadGenome
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

    private val baseOrganIndex = 0
    private var genome = getEmptyGenome()
    val genomeStageInstruction: MutableList<GenomeStage> = genome.stageInstruction.toMutableList()
    var mapCellGenomeIdToIndex = Int2IntOpenHashMap().apply { defaultReturnValue(-1) }

    var maxCellId = 0

    fun reinitGenome(genomeName: String?) {
        genomeManager.genomes.clear()
        genome = if (genomeName != null) {
            loadGenome(genomeName)
        } else {
            getEmptyGenome()
        }
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

        replays.forEach { it.reset() }

        for (tick in 0..TIME_SIMULATION) {
            updateTick()
            replays.forEach { it.copy() }

            if (organEntity.alreadyGrownUp[baseOrganIndex]) break

            if (tick == TIME_SIMULATION) throw Exception("Too long simulation!")
        }

        mapCellGenomeIdToIndex.putAll(worldCommandsManager.mapCellGenomeIdToIndex)
    }

    private fun updateTick() = with(cellEntity) {
        genomeManager.genomes[0].stageInstruction[organEntity.stage[0]].cellActions.forEach { id, _ ->
            cellSystem.genomicTransformations(worldCommandsManager.mapCellGenomeIdToIndex[id])
        }

        worldCommandsManager.executingCommandsFromTheWorld()
        organManager.performOrgansNextStage()
        worldCommandsManager.executingLastCommandsFromTheWorld()

        cellEntity.aliveList.forEach { cellIndex ->
            energy[cellIndex] = 5.0f
        }
    }

    companion object {
        const val TIME_SIMULATION = 1_000_000
    }
}
