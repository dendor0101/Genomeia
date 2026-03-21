package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Controller
import io.github.some_example_name.old.cells.Eye
import io.github.some_example_name.old.core.DIContainer.cellsSettings
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.systems.genomics.genome.CellAction
import java.util.BitSet
import kotlin.collections.set

class CellEntity(
    cellsStartMaxAmount: Int,
    private val particleEntity: ParticleEntity,
    val simEntity: SimEntity,
    val substrateSettings: SubstrateSettings,
    val cellList: List<Cell>,
    private val neuralEntity: NeuralEntity,
    private val eyeEntity: EyeEntity
) : Entity(cellsStartMaxAmount) {
    private var particleIndex = IntArray(maxAmount) { -1 }
    fun getX(index: Int) = particleEntity.x[particleIndex[index]]
    fun getY(index: Int) = particleEntity.y[particleIndex[index]]
    fun setX(index: Int, value: Float) { particleEntity.x[particleIndex[index]] = value }
    fun setY(index: Int, value: Float) { particleEntity.y[particleIndex[index]] = value }
    fun getVx(index: Int) = particleEntity.vx[particleIndex[index]]
    fun getVy(index: Int) = particleEntity.vy[particleIndex[index]]
    fun setVx(index: Int, value: Float) { particleEntity.vx[particleIndex[index]] = value }
    fun setVy(index: Int, value: Float) { particleEntity.vy[particleIndex[index]] = value }
    fun getDragCoefficient(index: Int) = particleEntity.dragCoefficient[particleIndex[index]]
    fun setDragCoefficient(index: Int, value: Float) { particleEntity.dragCoefficient[particleIndex[index]] = value }
    //effectOnContact
    fun getEffectOnContact(index: Int) = particleEntity.effectOnContact[particleIndex[index]]
    fun setEffectOnContact(index: Int, value: Boolean) { particleEntity.effectOnContact[particleIndex[index]] = value }
    fun getRadius(index: Int) = particleEntity.radius[particleIndex[index]]
    fun seRadius(index: Int, value: Float) { particleEntity.radius[particleIndex[index]] = value }
    fun getGridId(index: Int) = particleEntity.gridId[particleIndex[index]]
    fun seGridId(index: Int, value: Int) { particleEntity.gridId[particleIndex[index]] = value }
    fun getSimTime(index: Int) = simEntity.timeSimulation
    fun getColor(index: Int) = particleEntity.color
    fun setColor(index: Int, value: Int) { particleEntity.color[particleIndex[index]] = value }
    var cellGenomeId = IntArray(maxAmount) { -1 }
    var cellActions: Array<CellAction?> = arrayOfNulls(maxAmount)
    var organIndex = IntArray(maxAmount) { -1 }
    var parentIndex = IntArray(maxAmount) { -1 }
    var angle = FloatArray(maxAmount)
    var angleDiff = FloatArray(maxAmount)
    var energyNecessaryToDivide = FloatArray(maxAmount) { 2f }
    var energyNecessaryToMutate = FloatArray(maxAmount) { 1f }
    var isDividedInThisStage = BitSet(maxAmount)
    var isMutateInThisStage = BitSet(maxAmount)
    var cellType = ByteArray(maxAmount)
    var energy = FloatArray(maxAmount)
    var maxEnergy = FloatArray(maxAmount)
    var isNeural = BooleanArray(maxAmount)
    var neuronImpulseInput = FloatArray(maxAmount)
    var neuronImpulseOutput = FloatArray(maxAmount)

    private var neuralIndex = IntArray(maxAmount) { -1 }
    fun getIsNeuronTransportable(index: Int) = neuralEntity.isNeuronTransportable[neuralIndex[index]]
    fun setIsNeuronTransportable(index: Int, value: Boolean) { neuralEntity.isNeuronTransportable.set(neuralIndex[index], value) }
    fun getActivationFuncType(index: Int) = neuralEntity.activationFuncType[neuralIndex[index]].toInt()
    fun setActivationFuncType(index: Int, value: Byte) { neuralEntity.activationFuncType[neuralIndex[index]] = value }
    fun getA(index: Int) = neuralEntity.a[neuralIndex[index]]
    fun setA(index: Int, value: Float) { neuralEntity.a[neuralIndex[index]] = value }
    fun getB(index: Int) = neuralEntity.b[neuralIndex[index]]
    fun setB(index: Int, value: Float) { neuralEntity.b[neuralIndex[index]] = value }
    fun getC(index: Int) = neuralEntity.c[neuralIndex[index]]
    fun setC(index: Int, value: Float) { neuralEntity.c[neuralIndex[index]] = value }
    fun getDTime(index: Int) = neuralEntity.dTime[neuralIndex[index]]
    fun setDTime(index: Int, value: Float) { neuralEntity.dTime[neuralIndex[index]] = value}
    fun getRemember(index: Int) = neuralEntity.remember[neuralIndex[index]]
    fun setRemember(index: Int, value: Float) { neuralEntity.remember[neuralIndex[index]] = value }
    fun getIsSum(index: Int) = neuralEntity.isSum[neuralIndex[index]]
    fun setIsSum(index: Int, value: Boolean) {neuralEntity.isSum.set(neuralIndex[index], value)}

    fun deleteNeural(index: Int) {
        neuralEntity.deleteNeural(neuralIndex[index])
        isNeural[index] = false
        neuralIndex[index] -= -1
        neuronImpulseInput[index] = 0f
        neuronImpulseOutput[index] = 0f
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
        neuralIndex[index] = neuralEntity.addNeural(cellType, a, b, c, isSum, activationFuncType)
        isNeural[index] = true
    }

    private var specialTypeIndex = IntArray(maxAmount) { -1 }
    fun getColorDifferentiation(index: Int) = eyeEntity.colorDifferentiation[specialTypeIndex[index]]
    fun setColorDifferentiation(index: Int, value: Byte) { eyeEntity.colorDifferentiation[specialTypeIndex[index]] = value }
    fun getVisibilityRange(index: Int) = eyeEntity.visibilityRange[specialTypeIndex[index]]
    fun setVisibilityRange(index: Int, value: Float) { eyeEntity.visibilityRange[specialTypeIndex[index]] = value }

    fun deleteEye(index: Int) {
        eyeEntity.deleteEye(specialTypeIndex[index])
        specialTypeIndex[index] -= -1
    }

    fun addEye(
        index: Int,
        colorDifferentiation: Int = 7,
        visibilityRange: Float = 170f,
    ) {
        specialTypeIndex[index] = eyeEntity.addEye(colorDifferentiation.toByte(), visibilityRange)
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
        angle: Float = 0f,
        angleDiff: Float = 0f,
        colorDifferentiation: Int = 7,
        visibilityRange: Float = 170f,
        a: Float = 1f,
        b: Float = 0f,
        c: Float = 0f,
        isSum: Boolean = true,
        activationFuncType: Byte = 7
    ): Int {
        val cellIndex = add()

        particleIndex[cellIndex] = particleEntity.addParticle(
            x = x,
            y = y,
            color = color,
            radius = radius,
            dragCoefficient = substrateSettings.data.viscosityOfTheEnvironment,
            effectOnContact = cellList[cellType].effectOnContact,
            cellStiffness = cellsSettings[cellType].cellStiffness
        )
        this.cellGenomeId[cellIndex] = cellGenomeId
        cellActions[cellIndex] = null
        this.organIndex[cellIndex] = organIndex
        this.parentIndex[cellIndex] = parentIndex
        this.angle[cellIndex] = angle
        this.angleDiff[cellIndex] = angleDiff
        energyNecessaryToDivide[cellIndex] = 2f
        energyNecessaryToMutate[cellIndex] = 1f
        isDividedInThisStage[cellIndex] = false
        isMutateInThisStage[cellIndex] = false
        this.cellType[cellIndex] = cellType.toByte()
        energy[cellIndex] = 0f
        maxEnergy[cellIndex] = cellsSettings[cellType].maxEnergy
        neuronImpulseInput[cellIndex] = 0f
        neuronImpulseOutput[cellIndex] = 0f

        neuralIndex[cellIndex] = if (cellList[cellType].isNeural) {
            isNeural[cellIndex] = true
            neuralEntity.addNeural(cellType, a, b, c, isSum, activationFuncType)
        } else {
            isNeural[cellIndex] = false
            -1
        }

        specialTypeIndex[cellIndex] = if (cellList[cellType] is Eye) {
            eyeEntity.addEye(colorDifferentiation.toByte(), visibilityRange)
        } else if (cellList[cellType] is Controller) {
            //TODO addController
            -1
        } else -1
        return cellIndex
    }

    fun deleteCell(cellIndex: Int) {
        delete(cellIndex)

        particleEntity.deleteParticle(particleIndex[cellIndex])
        particleIndex[cellIndex] = -1

        cellGenomeId[cellIndex] = -1
        cellActions[cellIndex] = null
        organIndex[cellIndex] = -1
        parentIndex[cellIndex] = -1
        angle[cellIndex] = 0f
        angleDiff[cellIndex] = 0f
        energyNecessaryToDivide[cellIndex] = 2f
        energyNecessaryToMutate[cellIndex] = 1f
        isDividedInThisStage[cellIndex] = true
        isMutateInThisStage[cellIndex] = true
        val cellType = cellType[cellIndex]
        this.cellType[cellIndex] = 0
        energy[cellIndex] = 0f
        maxEnergy[cellIndex] = 0f
        isNeural[cellIndex] = false
        neuronImpulseInput[cellIndex] = 0f
        neuronImpulseOutput[cellIndex] = 0f

        if (neuralIndex[cellIndex] != -1) {
            neuralEntity.deleteNeural(neuralIndex[cellIndex])
            neuralIndex[cellIndex] = -1
        }

        if (specialTypeIndex[cellIndex] != -1) {
            if (cellList[cellType.toInt()] is Eye) {
                eyeEntity.deleteEye(specialTypeIndex[cellIndex])
            } else if (cellList[cellType.toInt()] is Controller) {
                //TODO deleteController
            }
            specialTypeIndex[cellIndex] = -1
        }
    }

    override fun onCopy() {
        TODO("Not yet implemented")
    }

    override fun onPaste() {
        TODO("Not yet implemented")
    }

    override fun onClear(bound: Int) {
        particleIndex.fill(-1, 0, bound)
        cellGenomeId.fill(-1, 0, bound)
        cellActions.fill(null, 0, bound)
        organIndex.fill(-1, 0, bound)
        parentIndex.fill(-1, 0, bound)
        angle.fill(0f, 0, bound)
        angleDiff.fill(0f, 0, bound)
        energyNecessaryToDivide.fill(2f, 0, bound)
        energyNecessaryToMutate.fill(1f, 0, bound)
        isDividedInThisStage.clear()
        isMutateInThisStage.clear()
        cellType.fill(0, 0, bound)
        energy.fill(0f, 0, bound)
        maxEnergy.fill(0f, 0, bound)
        isNeural.fill(false, 0, bound)
        neuronImpulseInput.fill(0f, 0, bound)
        neuronImpulseOutput.fill(0f, 0, bound)
        neuralIndex.fill(0, 0, bound)
        specialTypeIndex.fill(0, 0, bound)
    }

    override fun onResize(oldMax: Int) {
        run {
            val old = particleIndex
            particleIndex = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, particleIndex, 0, oldMax)
        }
        run {
            val old = cellGenomeId
            cellGenomeId = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, cellGenomeId, 0, oldMax)
        }
        run {
            val old = cellActions
            cellActions = arrayOfNulls(maxAmount)
            System.arraycopy(old, 0, cellActions, 0, oldMax)
        }
        run {
            val old = organIndex
            organIndex = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, organIndex, 0, oldMax)
        }
        run {
            val old = parentIndex
            parentIndex = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, parentIndex, 0, oldMax)
        }
        run {
            val old = angle
            angle = FloatArray(maxAmount)
            System.arraycopy(old, 0, angle, 0, oldMax)
        }
        run {
            val old = angleDiff
            angleDiff = FloatArray(maxAmount)
            System.arraycopy(old, 0, angleDiff, 0, oldMax)
        }
        run {
            val old = energyNecessaryToDivide
            energyNecessaryToDivide = FloatArray(maxAmount) { 2f }
            System.arraycopy(old, 0, energyNecessaryToDivide, 0, oldMax)
        }
        run {
            val old = energyNecessaryToMutate
            energyNecessaryToMutate = FloatArray(maxAmount) { 1f }
            System.arraycopy(old, 0, energyNecessaryToMutate, 0, oldMax)
        }
        run {
            val old = isDividedInThisStage
            isDividedInThisStage = BitSet(maxAmount)
            isDividedInThisStage.or(old)
        }
        run {
            val old = isMutateInThisStage
            isMutateInThisStage = BitSet(maxAmount)
            isMutateInThisStage.or(old)
        }
        run {
            val old = cellType
            cellType = ByteArray(maxAmount)
            System.arraycopy(old, 0, cellType, 0, oldMax)
        }
        run {
            val old = energy
            energy = FloatArray(maxAmount)
            System.arraycopy(old, 0, energy, 0, oldMax)
        }
        run {
            val old = maxEnergy
            maxEnergy = FloatArray(maxAmount)
            System.arraycopy(old, 0, maxEnergy, 0, oldMax)
        }
        run {
            val old = isNeural
            isNeural = BooleanArray(maxAmount)
            System.arraycopy(old, 0, isNeural, 0, oldMax)
        }
        run {
            val old = neuronImpulseInput
            neuronImpulseInput = FloatArray(maxAmount)
            System.arraycopy(old, 0, neuronImpulseInput, 0, oldMax)
        }
        run {
            val old = neuronImpulseOutput
            neuronImpulseOutput = FloatArray(maxAmount)
            System.arraycopy(old, 0, neuronImpulseOutput, 0, oldMax)
        }
        run {
            val old = neuralIndex
            neuralIndex = IntArray(maxAmount)
            System.arraycopy(old, 0, neuralIndex, 0, oldMax)
        }
        run {
            val old = specialTypeIndex
            specialTypeIndex = IntArray(maxAmount)
            System.arraycopy(old, 0, specialTypeIndex, 0, oldMax)
        }
    }
}
