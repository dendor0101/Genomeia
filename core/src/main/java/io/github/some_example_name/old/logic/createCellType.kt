package io.github.some_example_name.old.logic

import io.github.some_example_name.attempts.game.physics.*
import io.github.some_example_name.old.good_one.cells.*

fun CellManager.createCellType(cellType: Int, cellId: Int, isUpdateColor: Boolean = false) {
    //TODO Нужно все допустимы свойства прописать в каждом типе клетки
    val color = when (cellType) {
        0 -> {//Leaf
            maxEnergy[cellId] = Leaf.maxEnergy
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            Leaf.getColor()
        }

        1 -> {//Fat
            maxEnergy[cellId] = 10f
            cellStrength[cellId] = 0.5f
            linkStrength[cellId] = 0.0125f
            elasticity[cellId] = 5.5f
            isLooseEnergy[cellId] = false
            yellowColors.random()
        }

        2 -> {//Bone
            maxEnergy[cellId] = 2f
            linkStrength[cellId] = 0.4f
            cellStrength[cellId] = 4f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            whiteColors.random()
        }

        3 -> {//Tail
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            blueColors.random()
        }

        4 -> {//Neuron
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            pinkColors.random()
        }

        5 -> {//Muscle
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            redColors.random()
        }

        6 -> {//Sensor
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            purpleColors.random()
        }

        7 -> {//Sucker
            maxEnergy[cellId] = 8f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            orangeColors.random()
        }

        8 -> {//AccelerationSensor
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            skyBlueColors.random()
        }

        9 -> {//Excreta
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            brownColors.random()
        }

        10 -> {//SkinCell
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            leafColors[3]
        }

        11 -> {//Sticky
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            pinkColors[3]
        }

        12 -> {//Pumper
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            redColors[2]
        }

        13 -> {//Chameleon
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            whiteColors[0]
        }

        14 -> {//Eye
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            skyBlueColors[2]
        }

        15 -> {//Compass
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            blueColors[6]
        }

        16 -> {
            maxEnergy[cellId] = 5f
            energy[cellId] = 5f
            cellStrength[cellId] = 2.0f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = false
            Organic.getColor()
        }

        else -> {//Cell
            maxEnergy[cellId] = 5f
            cellStrength[cellId] = 2f
            linkStrength[cellId] = 0.025f
            elasticity[cellId] = 3.7f
            isLooseEnergy[cellId] = true
            genomeEditorColor.random()
        }
    }
    if (isUpdateColor) {
        colorR[cellId] = color.r
        colorG[cellId] = color.g
        colorB[cellId] = color.b
    }
}

fun CellManager.doSpecific(cellType: Int, cellId: Int) = when (cellType) {
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
    9 -> Excreta.specificToThisType(this, cellId)
    10 -> SkinCell.specificToThisType(this, cellId)
//    11 -> {
////        Sticky.specificToThisType(this, cellId)
//    }
//    12 -> {
//        //Pamper
//    }
    13 -> Chameleon.specificToThisType(this, cellId)
    14 -> Eye.specificToThisType(this, cellId)
    15 -> Compass.specificToThisType(this, cellId)
    16 -> Organic.specificToThisType(this, cellId)
    else -> {

    }
}
