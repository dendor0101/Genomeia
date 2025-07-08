package io.github.some_example_name.old.logic

import io.github.some_example_name.old.logic.CellManager.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.logic.GridManager.Companion.CELL_SIZE

class addCellUseCase(private val cm: CellManager) {
    @Volatile
    var add_cellMaxAmount = 1000

    @Volatile
    var add_CellSize = -1

    //Cell
    var add_id = Array<String>(add_cellMaxAmount) { "" }
    var add_x = FloatArray(add_cellMaxAmount) { 0f }
    var add_y = FloatArray(add_cellMaxAmount) { 0f }
    var add_vx = FloatArray(add_cellMaxAmount) { 0f }
    var add_vy = FloatArray(add_cellMaxAmount) { 0f }
    var add_ax = FloatArray(add_cellMaxAmount) { 0f }
    var add_ay = FloatArray(add_cellMaxAmount) { 0f }
    var add_colorR = FloatArray(add_cellMaxAmount) { 1f }
    var add_colorG = FloatArray(add_cellMaxAmount) { 1f }
    var add_colorB = FloatArray(add_cellMaxAmount) { 1f }
    var add_energyNecessaryToDivide = FloatArray(add_cellMaxAmount) { 2f }
    var add_energyNecessaryToMutate = FloatArray(add_cellMaxAmount) { 2f }
    var add_cellStrength = FloatArray(add_cellMaxAmount) { 2f }
    var add_linkStrength = FloatArray(add_cellMaxAmount) { 0.025f }
    var add_neuronImpulseImport = FloatArray(add_cellMaxAmount) { 0f }
    var add_frictionLevel = FloatArray(add_cellMaxAmount) { 0.5f }
    var add_isAliveWithoutEnergy = IntArray(add_cellMaxAmount) { 200 }
    var add_elasticity = FloatArray(add_cellMaxAmount) { 3.7f }
    var add_isLooseEnergy = BooleanArray(add_cellMaxAmount) { true }
    var add_isDividedInThisStage = BooleanArray(add_cellMaxAmount) { true }
    var add_isMutateInThisStage = BooleanArray(add_cellMaxAmount) { true }
    var add_cellType = IntArray(add_cellMaxAmount) { 0 }
    var add_energy = FloatArray(add_cellMaxAmount) { 0f }
    var add_maxEnergy = FloatArray(add_cellMaxAmount) { 5f }

    var add_neuronAmount = IntArray(add_cellMaxAmount) { 0 }
    var add_neuronLinks = IntArray(add_cellMaxAmount * MAX_LINK_AMOUNT) { -1 }

    //Neural
    var add_activationFuncType = IntArray(add_cellMaxAmount) { 0 }
    var add_a = FloatArray(add_cellMaxAmount) { 0f }
    var add_b = FloatArray(add_cellMaxAmount) { 0f }
    var add_c = FloatArray(add_cellMaxAmount) { 0f }
    var add_dTime = FloatArray(add_cellMaxAmount) { -1f }

    //Directed
    var add_angle = FloatArray(add_cellMaxAmount) { 0f }

    //Muscle
    var add_muscleContractionStep = FloatArray(add_cellMaxAmount) { 1f }

    //Tail
    var add_speed = FloatArray(add_cellMaxAmount) { 0f }


    fun add_Cells() {
        if (add_CellSize == -1) return
        for (i in 0..add_CellSize) {
            cm.cellLastId++
            cm.id[cm.cellLastId] = add_id[i]
            cm.isPhantom[cm.cellLastId] = false
            cm.x[cm.cellLastId] = add_x[i]
            cm.y[cm.cellLastId] = add_y[i]
            cm.vx[cm.cellLastId] = add_vx[i]
            cm.vy[cm.cellLastId] = add_vy[i]
            cm.ax[cm.cellLastId] = add_ax[i]
            cm.ay[cm.cellLastId] = add_ay[i]
            cm.colorR[cm.cellLastId] = add_colorR[i]
            cm.colorG[cm.cellLastId] = add_colorG[i]
            cm.colorB[cm.cellLastId] = add_colorB[i]
            cm.energyNecessaryToDivide[cm.cellLastId] = add_energyNecessaryToDivide[i]
            cm.energyNecessaryToMutate[cm.cellLastId] = add_energyNecessaryToMutate[i]
            cm.cellStrength[cm.cellLastId] = add_cellStrength[i]
            cm.linkStrength[cm.cellLastId] = add_linkStrength[i]
            cm.neuronImpulseImport[cm.cellLastId] = add_neuronImpulseImport[i]
            cm.frictionLevel[cm.cellLastId] = add_frictionLevel[i]
            cm.isAliveWithoutEnergy[cm.cellLastId] = add_isAliveWithoutEnergy[i]
            cm.elasticity[cm.cellLastId] = add_elasticity[i]
            cm.isLooseEnergy[cm.cellLastId] = add_isLooseEnergy[i]
            cm.isDividedInThisStage[cm.cellLastId] = add_isDividedInThisStage[i]
            cm.isMutateInThisStage[cm.cellLastId] = add_isMutateInThisStage[i]
            cm.cellType[cm.cellLastId] = add_cellType[i]
            cm.energy[cm.cellLastId] = add_energy[i]
            cm.maxEnergy[cm.cellLastId] = add_maxEnergy[i]
            //TODO сделать правильно добавление ссылок
//            val start = cm.cellLastId
//            for (l in )
//            cm.links[cm.cellLastId] = add_links[i]
//            cm.linksAmount[cm.cellLastId] = add_linksAmount[i]
//
//            cm.neuronAmount[cm.cellLastId] = add_neuronAmount[i]
//            cm.neuronLinks[cm.cellLastId] = add_neuronLinks[i]

            cm.activationFuncType[cm.cellLastId] = add_activationFuncType[i]
            cm.a[cm.cellLastId] = add_a[i]
            cm.b[cm.cellLastId] = add_b[i]
            cm.c[cm.cellLastId] = add_c[i]
            cm.dTime[cm.cellLastId] = add_dTime[i]
            cm.angle[cm.cellLastId] = add_angle[i]
            cm.muscleContractionStep[cm.cellLastId] = add_muscleContractionStep[i]
            cm.speed[cm.cellLastId] = add_speed[i]
            cm.gridManager.addCell(
                (cm.x[cm.cellLastId] / CELL_SIZE).toInt(),
                (cm.y[cm.cellLastId] / CELL_SIZE).toInt(),
                cm.cellLastId
            )
        }
        add_CellSize = -1
    }
}
