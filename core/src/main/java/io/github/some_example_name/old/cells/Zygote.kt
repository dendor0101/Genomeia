package io.github.some_example_name.old.cells

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.pinkColors

class Zygote(cellTypeId: Int) : Cell(
    defaultColor = pinkColors[0],
    cellTypeId = cellTypeId
) {

    override fun onStart(cellIndex: Int, threadId: Int) {
        with(cellEntity) {
            val parentOrganIndex = cellIndex
            val genomeIndex = organEntity.genomeIndex[cellEntity.organIndex[parentOrganIndex]]
            val subGenome = 1//TODO subGenome
            val genome = genomeManager.genomes[genomeIndex]
            val genomeSize: Int = genome.genomeStageInstruction.size
            val dividedTimes: Int = genome.dividedTimes[0]
            val mutatedTimes: Int = genome.mutatedTimes[0]

            worldCommandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.ADD_ORGAN,
                ints = intArrayOf(parentOrganIndex, genomeIndex, genomeSize, dividedTimes, mutatedTimes)
            )
        }
    }

    override fun doOnTick(cellIndex: Int, threadId: Int) = with (cellEntity) {
        if (energy[cellIndex] < substrateSettings.cellsSettings[cellType[cellIndex].toInt()].maxEnergy) {
            energy[cellIndex] += substrateSettings.data.amountOfSolarEnergy
        }
    }
}
