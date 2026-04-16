package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.core.DISimulationContainer.cellsSettings
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.systems.genomics.genome.CellAction
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem.Companion.MAX_LINK_AMOUNT
import io.github.some_example_name.old.systems.simulation.SimulationData

class CellEntity(
    cellsStartMaxAmount: Int,
    private val particleEntity: ParticleEntity,
    val simulationData: SimulationData,
    val substrateSettings: SubstrateSettings,
    val cellList: List<Cell>,
    private val neuralEntity: NeuralEntity,
    private val specialEntity: SpecialEntity
) : Entity(cellsStartMaxAmount) {
    //Particle entity
    private var particleIndexes = IntArray(maxAmount) { -1 }
    fun getParticleIndex(index: Int) = particleIndexes[index]
    fun getX(index: Int) = particleEntity.x[particleIndexes[index]]
    fun getY(index: Int) = particleEntity.y[particleIndexes[index]]
    fun setX(index: Int, value: Float) { particleEntity.x[particleIndexes[index]] = value }
    fun setY(index: Int, value: Float) { particleEntity.y[particleIndexes[index]] = value }
    fun getVx(index: Int) = particleEntity.vx[particleIndexes[index]]
    fun getVy(index: Int) = particleEntity.vy[particleIndexes[index]]
    fun setVx(index: Int, value: Float) { particleEntity.vx[particleIndexes[index]] = value }
    fun setVy(index: Int, value: Float) { particleEntity.vy[particleIndexes[index]] = value }
    fun getDragCoefficient(index: Int) = particleEntity.dragCoefficient[particleIndexes[index]]
    fun setDragCoefficient(index: Int, value: Float) { particleEntity.dragCoefficient[particleIndexes[index]] = value }
    fun getEffectOnContact(index: Int) = particleEntity.effectOnContact[particleIndexes[index]]
    fun setEffectOnContact(index: Int, value: Boolean) { particleEntity.effectOnContact[particleIndexes[index]] = value }
    fun getIsCollidable(index: Int) = particleEntity.isCollidable[particleIndexes[index]]
    fun setIsCollidable(index: Int, value: Boolean) { particleEntity.isCollidable[particleIndexes[index]] = value }
    fun getCellStiffness(index: Int) = particleEntity.cellStiffness[particleIndexes[index]]
    fun setCellStiffness(index: Int, value: Float) { particleEntity.cellStiffness[particleIndexes[index]] = value }
    fun getRadius(index: Int) = particleEntity.radius[particleIndexes[index]]
    fun seRadius(index: Int, value: Float) { particleEntity.radius[particleIndexes[index]] = value }
    fun getGridId(index: Int) = particleEntity.gridId[particleIndexes[index]]
    fun seGridId(index: Int, value: Int) { particleEntity.gridId[particleIndexes[index]] = value }
    fun getSimTime(index: Int) = simulationData.timeSimulation
    fun getColor(index: Int) = particleEntity.color[particleIndexes[index]]
    fun setColor(index: Int, value: Int) { particleEntity.color[particleIndexes[index]] = value }
    var cellGenomeId = IntArray(maxAmount) { -1 }
    var cellActions: Array<CellAction?> = arrayOfNulls(maxAmount)
    var organIndex = IntArray(maxAmount) { -1 }
    var parentIndex = IntArray(maxAmount) { -1 }
    var angleCos = FloatArray(maxAmount)
    var angleSin = FloatArray(maxAmount)
    var angleDiffCos = FloatArray(maxAmount)
    var angleDiffSin = FloatArray(maxAmount)
    var energyNecessaryToDivide = FloatArray(maxAmount) { 2f }
    var energyNecessaryToMutate = FloatArray(maxAmount) { 1f }
    var isDividedInThisStage = BooleanArray(maxAmount)
    var isMutateInThisStage = BooleanArray(maxAmount)
    var cellType = ByteArray(maxAmount)
    var energy = FloatArray(maxAmount)
    var maxEnergy = FloatArray(maxAmount)
    var isNeural = BooleanArray(maxAmount)
    var neuronImpulseInput = FloatArray(maxAmount)
    var neuronImpulseOutput = FloatArray(maxAmount)

    //Neural entity
    private var neuralIndexes = IntArray(maxAmount) { -1 }
    fun getNeuralGeneration(index: Int) = neuralEntity.getGeneration(neuralIndexes[index])
    fun getIsNeuronTransportable(index: Int) = neuralEntity.isNeuronTransportable[neuralIndexes[index]]
    fun setIsNeuronTransportable(index: Int, value: Boolean) { neuralEntity.isNeuronTransportable[neuralIndexes[index]] = value }
    fun getActivationFuncType(index: Int) = neuralEntity.activationFuncType[neuralIndexes[index]].toInt()
    fun setActivationFuncType(index: Int, value: Byte) { neuralEntity.activationFuncType[neuralIndexes[index]] = value }
    fun getA(index: Int) = neuralEntity.a[neuralIndexes[index]]
    fun setA(index: Int, value: Float) { neuralEntity.a[neuralIndexes[index]] = value }
    fun getB(index: Int) = neuralEntity.b[neuralIndexes[index]]
    fun setB(index: Int, value: Float) { neuralEntity.b[neuralIndexes[index]] = value }
    fun getC(index: Int) = neuralEntity.c[neuralIndexes[index]]
    fun setC(index: Int, value: Float) { neuralEntity.c[neuralIndexes[index]] = value }
    fun getDTime(index: Int) = neuralEntity.dTime[neuralIndexes[index]]
    fun setDTime(index: Int, value: Float) { neuralEntity.dTime[neuralIndexes[index]] = value}
    fun getRemember(index: Int) = neuralEntity.remember[neuralIndexes[index]]
    fun setRemember(index: Int, value: Float) { neuralEntity.remember[neuralIndexes[index]] = value }
    fun getIsSum(index: Int) = neuralEntity.isSum[neuralIndexes[index]]
    fun setIsSum(index: Int, value: Boolean) {neuralEntity.isSum.set(neuralIndexes[index], value)}

    fun deleteNeural(cellIndex: Int, neuralGeneration: Int? = null) {
        val neuralIndex = neuralIndexes[cellIndex]
        if (neuralIndex == -1) return
        if (neuralEntity.isAlive[neuralIndex] && (neuralGeneration == null
                || neuralEntity.getGeneration(neuralIndex) == neuralGeneration)) {
            neuralEntity.deleteNeural(neuralIndex)
            neuralIndexes[cellIndex] -= -1
        }
    }

    fun addNeural(
        index: Int,
        cellType: Int,
        a: Float = 1f,
        b: Float = 0f,
        c: Float = 0f,
        isSum: Boolean = true,
        activationFuncType: Byte = 0
    ) {
        neuronImpulseInput[index] = 0f
        neuronImpulseOutput[index] = 0f
        isNeural[index] = true
        neuralIndexes[index] = neuralEntity.addNeural(cellType, a, b, c, isSum, activationFuncType)
    }


    //Link
    var linksAmount = IntArray(maxAmount)
    var links = IntArray(maxAmount * MAX_LINK_AMOUNT) { -1 }

    fun addLink(cellId: Int, linkId: Int) {
        val base = cellId * MAX_LINK_AMOUNT
        if (cellId < 0) return
        val amount = linksAmount[cellId]
        if (amount >= MAX_LINK_AMOUNT) {
            links[base + MAX_LINK_AMOUNT - 1] = linkId
        } else {
            links[base + amount] = linkId
            linksAmount[cellId] += 1
        }
    }

    fun deleteLinkedCellLink(cellId: Int, linkId: Int) {
        val base = cellId * MAX_LINK_AMOUNT
        val amount = linksAmount[cellId]
        if (amount == 0) return

        for (i in 0 until amount) {
            val idx = base + i
            if (links[idx] == linkId) {
                links[idx] = links[base + amount - 1]
                links[base + amount - 1] = -1
                linksAmount[cellId] -= 1
                return
            }
        }
    }

    fun addCell(
        x: Float,
        y: Float,
        color: Int,
        radius: Float = 0.5f,
        cellGenomeId: Int = 0,
        cellType: Int,
        organIndex: Int,
        parentIndex: Int = -1,
        angleCos: Float = 1f,
        angleSin: Float = 0f,
        angleDiffCos: Float = 1f,
        angleDiffSin: Float = 0f,
        colorDifferentiation: Int = 7,
        visibilityRange: Float = 4.25f,
        a: Float = 1f,
        b: Float = 0f,
        c: Float = 0f,
        isSum: Boolean = true,
        activationFuncType: Byte = 7,
        speed: Float = 0f
    ): Int {
        val cellIndex = add()

        particleIndexes[cellIndex] = particleEntity.addParticle(
            x = x,
            y = y,
            color = color,
            radius = radius,
            dragCoefficient = substrateSettings.data.viscosityOfTheEnvironment,
            effectOnContact = cellList[cellType].effectOnContact,
            isCollidable = cellList[cellType].isCollidable,
            cellStiffness = cellsSettings[cellType].cellStiffness,
            isCell = true,
            holderEntityIndex = cellIndex
        )
        this.cellGenomeId[cellIndex] = cellGenomeId
        cellActions[cellIndex] = null
        this.organIndex[cellIndex] = organIndex
        this.parentIndex[cellIndex] = parentIndex
        this.angleCos[cellIndex] = angleCos
        this.angleSin[cellIndex] = angleSin
        this.angleDiffCos[cellIndex] = angleDiffCos
        this.angleDiffSin[cellIndex] = angleDiffSin
        energyNecessaryToDivide[cellIndex] = 2f
        energyNecessaryToMutate[cellIndex] = 1f
        isDividedInThisStage[cellIndex] = false
        isMutateInThisStage[cellIndex] = false
        this.cellType[cellIndex] = cellType.toByte()
        energy[cellIndex] = 0f
        maxEnergy[cellIndex] = cellsSettings[cellType].maxEnergy
        linksAmount[cellIndex] = 0
        val cell = cellList[cellType]

        if (cell.isNeural) {
            addNeural(cellIndex, cellType, a, b, c, isSum, activationFuncType)
        } else {
            neuronImpulseInput[cellIndex] = 0f
            neuronImpulseOutput[cellIndex] = 0f
            isNeural[cellIndex] = false
            neuralIndexes[cellIndex] = -1
        }

        specialEntity.addSpecial(
            cell = cell,
            colorDifferentiation = colorDifferentiation,
            visibilityRange = visibilityRange,
            speed = speed
        )

//        if (cellList[cellType] is Eye) {
//            addEye(cellIndex, colorDifferentiation, visibilityRange)
//        } else if (cellList[cellType] is Controller) {
//            //TODO addController
//            specialTypeIndexes[cellIndex] = -1
//        } else {
//            specialTypeIndexes[cellIndex] = -1
//        }
        return cellIndex
    }

    fun deleteCell(cellIndex: Int) {
        delete(cellIndex)

        particleEntity.deleteParticle(particleIndexes[cellIndex])
        particleIndexes[cellIndex] = -1

        cellGenomeId[cellIndex] = -1
        cellActions[cellIndex] = null
        organIndex[cellIndex] = -1
        parentIndex[cellIndex] = -1
        this.angleCos[cellIndex] = 1f
        this.angleSin[cellIndex] = 0f
        this.angleDiffCos[cellIndex] = 1f
        this.angleDiffSin[cellIndex] = 0f
        energyNecessaryToDivide[cellIndex] = 2f
        energyNecessaryToMutate[cellIndex] = 1f
        isDividedInThisStage[cellIndex] = true
        isMutateInThisStage[cellIndex] = true
        val cellType = cellType[cellIndex]
        val cell = cellList[cellType.toInt()]
        this.cellType[cellIndex] = 0
        energy[cellIndex] = 0f
        maxEnergy[cellIndex] = 0f
        isNeural[cellIndex] = false
        neuronImpulseInput[cellIndex] = 0f
        neuronImpulseOutput[cellIndex] = 0f
        linksAmount[cellIndex] = 0
        val base = cellIndex * MAX_LINK_AMOUNT
        links.fill(-1, base, base + MAX_LINK_AMOUNT)

        deleteNeural(cellIndex = cellIndex)

        specialEntity.delete(cell = cell, cellIndex = cellIndex)

//        if (specialTypeIndexes[cellIndex] != -1) {
//            if (cellList[cellType.toInt()] is Eye) {
//                deleteEye(cellIndex)
//            } else if (cellList[cellType.toInt()] is Controller) {
//                //TODO deleteController
//            }
//            specialTypeIndexes[cellIndex] = -1
//        }
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        particleIndexes.clear(-1)
        cellGenomeId.clear(-1)
        cellActions.fill(null, 0, bound)
        organIndex.clear(-1)
        parentIndex.clear(-1)
        angleCos.clear(1f)
        angleSin.clear()
        angleDiffCos.clear(1f)
        angleDiffSin.clear()
        energyNecessaryToDivide.clear(2f)
        energyNecessaryToMutate.clear(1f)
        isDividedInThisStage.clear(false)
        isMutateInThisStage.clear(false)
        cellType.clear()
        energy.clear()
        maxEnergy.clear()
        isNeural.clear(false)
        neuronImpulseInput.clear()
        neuronImpulseOutput.clear()
        neuralIndexes.clear()
        linksAmount.clear()
        links.fill(-1, 0, bound * MAX_LINK_AMOUNT)
    }

    override fun onResize(oldMax: Int) {
        particleIndexes = particleIndexes.resize(-1)
        cellGenomeId = cellGenomeId.resize(-1)
        run {
            val old = cellActions
            cellActions = arrayOfNulls(maxAmount)
            System.arraycopy(old, 0, cellActions, 0, oldMax)
        }
        organIndex = organIndex.resize(-1)
        parentIndex = parentIndex.resize(-1)
        angleCos = angleCos.resize(1f)
        angleSin = angleSin.resize()
        angleDiffCos = angleDiffCos.resize(1f)
        angleDiffSin = angleDiffSin.resize()
        energyNecessaryToDivide = energyNecessaryToDivide.resize(2f)
        energyNecessaryToMutate = energyNecessaryToMutate.resize(1f)
        isDividedInThisStage = isDividedInThisStage.resize(false)
        isMutateInThisStage = isMutateInThisStage.resize(false)
        cellType = cellType.resize()
        energy = energy.resize()
        maxEnergy = maxEnergy.resize()
        isNeural = isNeural.resize(false)
        neuronImpulseInput = neuronImpulseInput.resize()
        neuronImpulseOutput = neuronImpulseOutput.resize()
        neuralIndexes = neuralIndexes.resize()
        linksAmount = linksAmount.resize()
        run {
            val oldLinks = links
            links = IntArray(maxAmount * MAX_LINK_AMOUNT) { -1 }
            for (i in 0 until oldMax) {
                System.arraycopy(oldLinks, i * MAX_LINK_AMOUNT, links, i * MAX_LINK_AMOUNT, MAX_LINK_AMOUNT)
            }
        }
    }
}
