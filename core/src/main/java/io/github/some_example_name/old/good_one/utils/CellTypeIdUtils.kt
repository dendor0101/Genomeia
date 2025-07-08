package io.github.some_example_name.old.good_one.utils

import io.github.some_example_name.old.good_one.cells.*
import io.github.some_example_name.old.good_one.cells.base.Cell
import io.github.some_example_name.old.good_one.cells.base.Directed
import io.github.some_example_name.old.good_one.ui.UiResult


fun addCell(cellTypeId: Int, result: UiResult? = null): Cell {
    val cell = when (cellTypeId) {
        0 -> Leaf()
        1 -> Fat()
        2 -> Bone()
        3 -> Tail()
        4 -> Neuron()
        5 -> Muscle()
        6 -> Sensor()
        7 -> Sucker()
        8 -> AccelerationSensor()
        9 -> Excreta()
        10 -> SkinCell()
        11 -> Sticky()
        12 -> Pumper()
        13 -> Chameleon()
        14 -> Eye()
        15 -> Compass()
        else -> Cell()
    }

    result?.let {
        if (cell is Neuron) {
            cell.apply {
                this.activationFuncType = it.funActivation ?: 0
                this.a = it.a ?: 1f
                this.b = it.b ?: 0f
                this.c = it.c ?: 0f
            }
        }
        if (cell is Directed) {
            cell.apply {
                this.angle = it.angle ?: 0f
            }
        }
    }
    return cell
}

fun getCellTypeId(cell: Cell): Int {
    return when (cell) {
        is Leaf -> 0
        is Fat -> 1
        is Bone -> 2
        is Tail -> 3
        is Neuron -> 4
        is Muscle -> 5
        is Sensor -> 6
        is Sucker -> 7
        is AccelerationSensor -> 8
        is Excreta -> 9
        is SkinCell -> 10
        is Sticky -> 11
        is Pumper -> 12
        is Chameleon -> 13
        is Eye -> 14
        is Compass -> 15
        else -> 0
    }
}

val cellsType = arrayOf(
    " Leaf",
    " Fat",
    " Bone",
    " Tail",
    " Neuron",
    " Muscle",
    " Sensor",
    " Sucker",
    " AccelerationSensor",
    " Excreta",
    " SkinCell",
    " Sticky",
    " Pumper",
    " Chameleon",
    " Eye",
    " Compass"
)
