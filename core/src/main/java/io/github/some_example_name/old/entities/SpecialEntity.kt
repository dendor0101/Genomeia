package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Controller
import io.github.some_example_name.old.cells.ControllerData
import io.github.some_example_name.old.cells.Eye
import io.github.some_example_name.old.cells.Producer
import io.github.some_example_name.old.cells.Tail

class SpecialEntity(
    cellsStartMaxAmount: Int,
    private val eyeEntity: EyeEntity,
    private val tailEntity: TailEntity,
    private val specialModDataEntity: SpecialModDataEntity,
    private val producerEntity: ProducerEntity
): Entity(cellsStartMaxAmount) {

    //Special type entities
    private var specialTypeIndexes = IntArray(maxAmount) { -1 }

    //Special Tail
    fun getTailGeneration(index: Int) = tailEntity.getGeneration(specialTypeIndexes[index])
    fun getSpeed(index: Int) = tailEntity.speed[specialTypeIndexes[index]]
    fun setSpeed(index: Int, value: Float) { tailEntity.speed[specialTypeIndexes[index]] = value }

    fun deleteTail(cellIndex: Int, tailGeneration: Int? = null) {
        val tailIndex = specialTypeIndexes[cellIndex]
        if (tailIndex == -1) return
        if (tailEntity.isAlive[tailIndex] && (tailGeneration == null
                || tailEntity.getGeneration(tailIndex) == tailGeneration)) {
            tailEntity.deleteTail(tailIndex)
            specialTypeIndexes[cellIndex] -= -1
        }
    }

    fun addTail(
        index: Int,
        speed: Float = 0f
    ) {
        specialTypeIndexes[index] = tailEntity.addTail(speed)
    }

    //Special Eye
    fun getEyeGeneration(index: Int) = eyeEntity.getGeneration(specialTypeIndexes[index])
    fun getColorDifferentiation(index: Int) = eyeEntity.colorDifferentiation[specialTypeIndexes[index]]
    fun setColorDifferentiation(index: Int, value: Byte) { eyeEntity.colorDifferentiation[specialTypeIndexes[index]] = value }
    fun getVisibilityRange(index: Int) = eyeEntity.visibilityRange[specialTypeIndexes[index]]
    fun setVisibilityRange(index: Int, value: Float) { eyeEntity.visibilityRange[specialTypeIndexes[index]] = value }

    fun deleteEye(cellIndex: Int, eyeGeneration: Int? = null) {
        val eyeIndex = specialTypeIndexes[cellIndex]
        if (eyeIndex == -1) return
        if (eyeEntity.isAlive[eyeIndex] && (eyeGeneration == null
                || eyeEntity.getGeneration(eyeIndex) == eyeGeneration)) {
            eyeEntity.deleteEye(eyeIndex)
            specialTypeIndexes[cellIndex] -= -1
        }
    }

    fun addEye(
        index: Int,
        colorDifferentiation: Int = 7,
        visibilityRange: Float = 4.25f,
    ) {
        specialTypeIndexes[index] = eyeEntity.addEye(colorDifferentiation.toByte(), visibilityRange)
    }


    //Special Producer
    fun getProducerGeneration(index: Int) = producerEntity.getGeneration(specialTypeIndexes[index])
    fun getReproductionRestriction(index: Int) = producerEntity.reproductionRestriction[specialTypeIndexes[index]]
    fun setReproductionRestriction(index: Int, value: Int) { producerEntity.reproductionRestriction[specialTypeIndexes[index]] = value }

    fun deleteProducer(cellIndex: Int, producerGeneration: Int? = null) {
        val producerIndex = specialTypeIndexes[cellIndex]
        if (producerIndex == -1) return
        if (producerEntity.isAlive[producerIndex] && (producerGeneration == null
                || producerEntity.getGeneration(producerIndex) == producerGeneration)) {
            producerEntity.deleteProducer(producerIndex)
            specialTypeIndexes[cellIndex] -= -1
        }
    }

    fun addProducer(
        index: Int
    ) {
        specialTypeIndexes[index] = producerEntity.addProducer()
    }


    fun addSpecial(
        cell: Cell,
        colorDifferentiation: Int = 7,
        visibilityRange: Float = 4.25f,
        speed: Float = 0f
    ): Int {
        val cellIndex = add()
        when (cell) {
            is Tail -> {
                addTail(cellIndex, speed)
            }
            is Eye -> {
                addEye(cellIndex, colorDifferentiation, visibilityRange)
            }
            is Producer -> {
                addProducer(cellIndex)
            }
            else -> {
                specialTypeIndexes[cellIndex] = -1
            }
        }

        if (cell.doesItHasSpecialModData) {
            specialTypeIndexes[cellIndex] = specialModDataEntity.addModData(ControllerData('a'))
            //TODO додлетаь контроллер и вместе с ним додумать как сделать, универсальные данные для модов
        }
        return cellIndex
    }

    fun delete(
        cell: Cell,
        cellIndex: Int
    ) {
        delete(cellIndex)
        when (cell) {
            is Tail -> {
                deleteTail(cellIndex)
            }
            is Eye -> {
                deleteEye(cellIndex)
            }
            is Producer -> {
                deleteProducer(cellIndex)
            }
            else -> {}
        }

        if (cell.doesItHasSpecialModData) {
            specialModDataEntity.deleteModData(cellIndex)
        }
    }


    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        specialTypeIndexes.clear()
    }

    override fun onResize(oldMax: Int) {
        specialTypeIndexes = specialTypeIndexes.resize()
    }
}
