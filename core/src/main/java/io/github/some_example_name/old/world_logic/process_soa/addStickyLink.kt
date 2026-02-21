package io.github.some_example_name.old.world_logic.process_soa

import io.github.some_example_name.old.organisms.AddLink
import io.github.some_example_name.old.world_logic.CellManager

fun CellManager.addStickyLink(cellId: Int, otherCellId: Int, distance: Float, threadId: Int) {
    addLinks[threadId].add(
        AddLink(
            cellIndex = cellId,
            otherCellIndex = otherCellId,
            linksLength = distance,
            degreeOfShortening = 1f,
            isStickyLink = true,
            isNeuronLink = false,
            isLink1NeuralDirected = false
        )
    )
}
