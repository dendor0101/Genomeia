package io.github.some_example_name.old.cells.base

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.*

class CellBuilder {

//    val instances = Cell::class.sealedSubclasses.mapNotNull { subclass ->
//        subclass.constructors
//            .firstOrNull { it.parameters.isEmpty() }
//            ?.call()
//    }.sortedBy { it.cellTypeId }

    val instances = listOf(
        Leaf(),
        Fat(),
        Bone(),
        Tail(),
        Neuron(),
        Muscle(),
        Sensor(),
        Sucker(),
        Excreta(),
        SuctionCup(),
        Sticky(),
        Pumper(),
        Chameleon(),
        Eye(),
        Compass(),
        Controller(),
        TouchTrigger(),
        Zygote(),
        Producer(),
        Breakaway(),
        Vascular(),
        PheromoneEmitter(),
        PheromoneSensor(),
        Punisher(),
        Mike()
    ).sortedBy { it.cellTypeId }

}
