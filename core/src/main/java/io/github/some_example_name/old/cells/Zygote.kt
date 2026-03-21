package io.github.some_example_name.old.cells

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.utils.pinkColors

class Zygote : Cell(
    defaultColor = pinkColors[0],
    cellTypeId = 17
) {

    override fun onStart(index: Int, threadId: Int) {
        with(cellEntity) {
            val parentOrganIndex = index
            val genomeIndex = organEntity.genomeIndex[cellEntity.organIndex[parentOrganIndex]]
            val subGenome = 1//TODO subGenome
            val genome = genomeManager.genomes[genomeIndex]
            val genomeSize: Int = genome.genomeStageInstruction.size
            val dividedTimes: Int = genome.dividedTimes[0]
            val mutatedTimes: Int = genome.mutatedTimes[0]

            commandsManager.worldCommandBuffer[threadId].push(
                type = WorldCommandType.ADD_ORGAN,
                ints = intArrayOf(parentOrganIndex, genomeIndex, genomeSize, dividedTimes, mutatedTimes)
            )
        }
    }

    override fun doOnTick(index: Int, threadId: Int) = with (cellEntity) {
        if (energy[index] < substrateSettings.cellsSettings[cellType[index].toInt()].maxEnergy) {
            energy[index] += substrateSettings.data.amountOfSolarEnergy
        }
    }
}
