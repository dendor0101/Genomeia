package io.github.some_example_name.old.editor.system

import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.editor.entities.ReplayEntity
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem.Companion.PARTICLE_MAX_RADIUS
import kotlin.system.measureNanoTime

class EditorSimulationSystem(
    val cellEntity: CellEntity,
    val organEntity: OrganEntity,
    val organManager: OrganManager,
    val worldCommandsManager: WorldCommandsManager,
    val genomeManager: GenomeManager,
    val replayEntity: ReplayEntity,
    val particleEntity: ParticleEntity,
    val cellSystem: CellSystem,
    val gridManager: GridManager,
    val zygote: Zygote
) {

    val baseOrganIndex = 0
    var genome = genomeManager.genomes[0/*TODO сделатьб выбор*/]

    fun simulate() {
        //TODO сделать перезагрузку
        val genomeIndex = 0
        genome = genomeManager.genomes[genomeIndex]
        val organIndex = organEntity.addOrgan(
            genomeIndex = genomeIndex,
            genomeSize = genome.genomeStageInstruction.size,
            dividedTimes = genome.dividedTimes[0],
            mutatedTimes = genome.mutatedTimes[0]
        )
        cellEntity.addCell(
            x = gridManager.gridWidth * 0.5f,
            y = gridManager.gridHeight * 0.5f,
            color = zygote.defaultColor.toIntBits(),
            radius = PARTICLE_MAX_RADIUS,
            cellType = zygote.cellTypeId,
            organIndex = organIndex
        )

        replayEntity.replayCellsCounterInTick.clear()
        replayEntity.tickStartIndices.clear()

        var counter = 0
        val nanoTime = measureNanoTime {
            for (tick in 0..TIME_SIMULATION) {

                updateTick()

                replayEntity.copy()

                if (organEntity.alreadyGrownUp[baseOrganIndex]) break
                if (tick == TIME_SIMULATION) throw Exception("Too long simulation!")
                counter ++
            }
        }
        println("${nanoTime / 1_000_000.0} ms - $counter ticks")
    }

    private fun updateTick() = with(cellEntity) {
        cellEntity.aliveList.forEach { cellIndex ->
            energy[cellIndex] += 1.5f
            if (energy[cellIndex] > maxEnergy[cellIndex]) {
                energy[cellIndex] = maxEnergy[cellIndex]
            }
            cellSystem.genomicTransformations(cellIndex)
        }

        worldCommandsManager.executingCommandsFromTheWorld()
        organManager.performOrgansNextStage()
    }

    companion object {
        const val TIME_SIMULATION = 1_000
    }
}
