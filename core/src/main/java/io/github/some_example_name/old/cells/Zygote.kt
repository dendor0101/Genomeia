package io.github.some_example_name.old.cells

import io.github.some_example_name.old.core.CellSettings
import io.github.some_example_name.old.core.utils.pinkColors

class Zygote(cellTypeId: Int) : Cell(
    key = "zygote",
    defaultColor = pinkColors[0],
    cellTypeId = cellTypeId,
    textureName = "zygote.png",
    isDirected = true,
    defaultCellSettings = CellSettings(
        maxEnergy = 10f,
        cellStiffness = 0.02f,
        linkStiffness = 0.0125f,
        energyActionCost = 0f
    )
) {

    // Организм зиготе заводят до неё самой — OrganismSpawner (игрок, продюсер).

    override fun doOnTick(cellIndex: Int, threadId: Int) = with (cellEntity) {
        // Пустышка (зигота от деления или мутации) не копит энергию: иначе она работала бы
        // листом, которого в геноме нет. См. CellEntity.isDormantZygote.
        if (cellGenomeId[cellIndex] == ROOT_CELL_GENOME_ID &&
            energy[cellIndex] < substrateSettings.cellsSettings[cellType[cellIndex].toInt()].maxEnergy
        ) {
            energy[cellIndex] += substrateSettings.data.amountOfSolarEnergy
        }
    }

    companion object {
        /**
         * Номер корня в геноме. Корень сажают с ним игрок и продюсер (OrganismSpawner,
         * Producer), а номера клеток от деления начинаются с единицы (maxCellId + 1 в
         * редакторе), поэтому зигота с другим номером — заведомо не корень.
         */
        const val ROOT_CELL_GENOME_ID = 0
    }
}
