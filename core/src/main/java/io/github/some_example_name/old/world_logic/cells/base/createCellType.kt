package io.github.some_example_name.old.world_logic.cells.base

import io.github.some_example_name.old.world_logic.cells.*
import io.github.some_example_name.old.good_one.utils.*
import io.github.some_example_name.old.world_logic.CellManager


val cellsType = arrayOf(
    "Leaf",
    "Fat",
    "Bone",
    "Tail",
    "Neuron",
    "Muscle",
    "Sensor",
    "Sucker",
    "AccelerationSensor",
    "Excreta",
    "SkinCell",
    "Sticky",
    "Pumper",
    "Chameleon",
    "Eye",
    "Compass",
    "Controller",
    "TouchTrigger",
    "Zygote",
    "Producer",
    "Breakaway",
    "Vascular",
    "PheromoneEmitter",
    "PheromoneSensor",
    "Punisher",
)

fun CellManager.createCellType(
    cellType: Int,
    cellId: Int,
    isUpdateColor: Boolean = false,
    genomeIndex: Int = -1,
    threadId: Int = -1
) {
    //TODO Нужно все допустимы свойства прописать в каждом типе клетки
    // TODO: All permissible properties must be specified in each cell type.

    val dragCoefficientSettings = 1f - globalSettings.viscosityOfTheEnvironment

    val color = when (cellType) {
        -1 -> { //Organic - Walls
            energy[cellId] = 5f
            isNeuronTransportable[cellId] = false
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            Organic.getColor()
        }

        0 -> {//Leaf
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            Leaf.getColor()
        }

        1 -> {//Fat
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            yellowColors.first()
        }

        2 -> {//Bone
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            whiteColors.first()
        }

        3 -> {//Tail
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            blueColors.first()
        }

        4 -> {//Neuron
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            pinkColors.first()
        }

        5 -> {//Muscle
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            redColors[3]
        }

        6 -> {//Sensor
            isNeuronTransportable[cellId] = false
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            purpleColors.first()
        }

        7 -> {//Sucker
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            orangeColors.first()
        }

        8 -> {//AccelerationSensor
            isNeuronTransportable[cellId] = false
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            skyBlueColors.first()
        }

        9 -> {//Excreta
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            brownColors.first()
        }

        10 -> {//SkinCell
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = 0.97f
            effectOnContact[cellId] = false
            leafColors[3]
        }

        11 -> {//Sticky
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = true
            pinkColors[3]
        }

        12 -> {//Pumper
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = true
            redColors[2]
        }

        13 -> {//Chameleon
            isNeuronTransportable[cellId] = false
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            whiteColors[0]
        }

        14 -> {//Eye
            isNeuronTransportable[cellId] = false
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            skyBlueColors[2]
        }

        15 -> {//Compass
            isNeuronTransportable[cellId] = false
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            blueColors[6]
        }

        16 -> {//Controller
            isNeuronTransportable[cellId] = false
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            skyBlueColors.last()
        }

        17 -> {//TouchTrigger
            isNeuronTransportable[cellId] = false
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            genomeEditorColor[6]
        }

        18 -> {//Zygote
            energy[cellId] = 0.01f
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            Zygote.specificWhenSpawned(this, cellId, genomeIndex, threadId)
            pinkColors[0]
        }

        19 -> {//Producer
            energy[cellId] = 0f
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            redColors[4]
        }

        20 -> {//Breakaway
            energy[cellId] = 0f
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            pinkColors[1]
        }

        21 -> {//Vascular
            energy[cellId] = 0f
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            yellowColors[3]
        }

        22 -> {//PheromoneEmitter
            energy[cellId] = 0f
            isNeuronTransportable[cellId] = true
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            blueColors[3]
        }

        23 -> {//PheromoneSensor
            energy[cellId] = 0f
            isNeuronTransportable[cellId] = false
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = false
            blueColors[2]
        }


        24 -> {//Punisher
            energy[cellId] = 0f
            isNeuronTransportable[cellId] = false
            dragCoefficient[cellId] = dragCoefficientSettings
            effectOnContact[cellId] = true
            redColors[0]
        }

        else -> {//Cell
            genomeEditorColor.random()
        }
    }
    if (isUpdateColor) {
        colorR[cellId] = color.r
        colorG[cellId] = color.g
        colorB[cellId] = color.b
    }
}

fun getCellColor(cellType: Int) = when (cellType) {
    -1 -> {//Organic
        Organic.getColor()
    }

    0 -> {//Leaf
        Leaf.getColor()
    }

    1 -> {//Fat
        yellowColors.first()
    }

    2 -> {//Bone
        whiteColors.first()
    }

    3 -> {//Tail
        blueColors.first()
    }

    4 -> {//Neuron
        pinkColors.first()
    }

    5 -> {//Muscle
        redColors[3]
    }

    6 -> {//Sensor
        purpleColors.first()
    }

    7 -> {//Sucker
        orangeColors.first()
    }

    8 -> {//AccelerationSensor
        skyBlueColors.first()
    }

    9 -> {//Excreta
        brownColors.first()
    }

    10 -> {//SkinCell
        leafColors[3]
    }

    11 -> {//Sticky
        pinkColors[3]
    }

    12 -> {//Pumper
        redColors[2]
    }

    13 -> {//Chameleon
        whiteColors[0]
    }

    14 -> {//Eye
        skyBlueColors[2]
    }

    15 -> {//Compass
        blueColors[6]
    }

    16 -> {//Controller
        skyBlueColors.last()
    }

    17 -> {
        genomeEditorColor[6]
    }

    18 -> {
        pinkColors[0]
    }

    19 -> {
        redColors[4]
    }

    20 -> {
        pinkColors[1]
    }

    21 -> {
        yellowColors[3]
    }

    22 -> {
        blueColors[3]
    }

    23 -> {
        blueColors[2]
    }

    24 -> {
        redColors[0]
    }

    else -> {
        genomeEditorColor.random()
    }
}

fun CellManager.doSpecific(cellType: Int, cellId: Int, threadId: Int) = when (cellType) {
    //TODO Тяжелая операция на каждой клетке вызывать этот when
    // Todo: Heavy operation on each cell cause this when???
    -1 -> Organic.specificToThisType(this, cellId)
    0 -> Leaf.specificToThisType(this, cellId)
//    1 -> {
//        //Fat
//    }
//    2 -> {
//        //Bone
//    }
    3 -> Tail.specificToThisType(this, cellId)
    4 -> Neuron.specificToThisType(this, cellId)
    5 -> Muscle.specificToThisType(this, cellId)
    6 -> Sensor.specificToThisType(this, cellId)
    7 -> Sucker.specificToThisType(this, cellId)
    8 -> AccelerationSensor.specificToThisType(this, cellId)
    9 -> Excreta.specificToThisType(this, cellId, threadId)
    10 -> SkinCell.specificToThisType(this, cellId)
//    11 -> {
//        Sticky.specificToThisType(this, cellId)
//    }
//    12 -> {
//        //Pumper
//    }
    13 -> Chameleon.specificToThisType(this, cellId)
    14 -> Eye.specificToThisType(this, cellId, threadId)
    15 -> Compass.specificToThisType(this, cellId)
    16 -> Controller.specificToThisType(this, cellId)
    17 -> TouchTrigger.specificToThisType(this, cellId)
    18 -> Zygote.specificToThisType(this, cellId)
    19 -> Producer.specificToThisType(this, cellId, threadId)
    20 -> Breakaway.specificToThisType(this, cellId, threadId)
    21 -> Vascular.specificToThisType(this, cellId)
    22 -> PheromoneEmitter.specificToThisType(this, cellId)
    23 -> PheromoneSensor.specificToThisType(this, cellId)
    //24 -> Punisher
    else -> {

    }
}


fun Int.isEye() = this == 14
fun Int.isController() = this == 16
fun Int.isDirected() = when (this) {
    3, 9, 14, 15, 19, 21 -> true
    else -> false
}
fun Int.isNeural() = when (this) {
    3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 15, 17, 19, 20, 21, 22, 23 -> true
    else -> false
}
