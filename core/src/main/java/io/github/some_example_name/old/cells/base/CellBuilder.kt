package io.github.some_example_name.old.cells.base

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.*

class CellBuilder {

//    val instances = Cell::class.sealedSubclasses.mapNotNull { subclass ->
//        subclass.constructors
//            .firstOrNull { it.parameters.isEmpty() }
//            ?.call()
//    }.sortedBy { it.cellTypeId }
    val zygote = Zygote(18)

    val instances = listOf(
        Leaf(0),
        Fat(1),
        Bone(2),
        Tail(3),
        Neuron(4),
        Muscle(5),
        Sensor(6),
        Sucker(7),
        Mike(8),
        Excreta(9),
        SuctionCup(10),
        Sticky(11),
        Pumper(12),
        Chameleon(13),
        Eye(14),
        Compass(15),
        Controller(16),
        TouchTrigger(17),
        zygote,
        Producer(19),
        Breakaway(20),
        Vascular(21),
        PheromoneEmitter(22),
        PheromoneSensor(23),
        Punisher(24)
    ).sortedBy { it.cellTypeId }

}
