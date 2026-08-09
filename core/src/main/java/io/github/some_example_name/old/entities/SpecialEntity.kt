package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Eye
import io.github.some_example_name.old.cells.PheromoneEmitter
import io.github.some_example_name.old.cells.Producer
import io.github.some_example_name.old.cells.SpecialModData
import io.github.some_example_name.old.cells.Tail
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.Transient

@Serializable
class SpecialEntity(
    @Transient val cellsStartMaxAmount: Int = 0
) : Entity(cellsStartMaxAmount) {

    @Transient lateinit var eyeEntity: EyeEntity
    @Transient lateinit var tailEntity: TailEntity
    @Transient lateinit var specialModDataEntity: SpecialModDataEntity
    @Transient lateinit var producerEntity: ProducerEntity
    @Transient lateinit var pheromoneEmitterEntity: PheromoneEmitterEntity

    constructor(
        cellsStartMaxAmount: Int,
        eyeEntity: EyeEntity,
        tailEntity: TailEntity,
        specialModDataEntity: SpecialModDataEntity,
        producerEntity: ProducerEntity,
        pheromoneEmitterEntity: PheromoneEmitterEntity
    ) : this(cellsStartMaxAmount) {
        loadEntity(eyeEntity, tailEntity, specialModDataEntity, producerEntity, pheromoneEmitterEntity)
    }

    @ProtoNumber(1) var specialTypeIndexes = IntArray(maxAmount) { -1 }

    fun getTailGeneration(index: Int) = tailEntity.getGeneration(specialTypeIndexes[index])
    fun getSpeed(index: Int) = tailEntity.speed[specialTypeIndexes[index]]
    fun setSpeed(index: Int, value: Float) { tailEntity.speed[specialTypeIndexes[index]] = value }

    fun deleteTail(cellIndex: Int, tailGeneration: Int? = null) {
        val tailIndex = specialTypeIndexes[cellIndex]
        if (tailIndex == -1) return
        if (tailEntity.isAlive[tailIndex] && (tailGeneration == null
                || tailEntity.getGeneration(tailIndex) == tailGeneration)) {
            tailEntity.deleteTail(tailIndex)
            specialTypeIndexes[cellIndex] = -1
        }
    }

    fun addTail(index: Int, speed: Float = 0f) {
        specialTypeIndexes[index] = tailEntity.addTail(speed)
    }

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
            specialTypeIndexes[cellIndex] = -1
        }
    }

    fun addEye(index: Int, colorDifferentiation: Int = 7, visibilityRange: Float = 4.25f) {
        specialTypeIndexes[index] = eyeEntity.addEye(colorDifferentiation.toByte(), visibilityRange)
    }

    fun getProducerGeneration(index: Int) = producerEntity.getGeneration(specialTypeIndexes[index])
    fun getReproductionRestriction(index: Int) = producerEntity.reproductionRestriction[specialTypeIndexes[index]]
    fun setReproductionRestriction(index: Int, value: Int) { producerEntity.reproductionRestriction[specialTypeIndexes[index]] = value }

    fun deleteProducer(cellIndex: Int, producerGeneration: Int? = null) {
        val producerIndex = specialTypeIndexes[cellIndex]
        if (producerIndex == -1) return
        if (producerEntity.isAlive[producerIndex] && (producerGeneration == null
                || producerEntity.getGeneration(producerIndex) == producerGeneration)) {
            producerEntity.deleteProducer(producerIndex)
            specialTypeIndexes[cellIndex] = -1
        }
    }

    fun addProducer(index: Int) {
        specialTypeIndexes[index] = producerEntity.addProducer()
    }

    fun getPheromoneEmitterGeneration(index: Int) = pheromoneEmitterEntity.getGeneration(specialTypeIndexes[index])
    fun getPheromoneEmitterLastImpulse(index: Int) = pheromoneEmitterEntity.lastImpulse[specialTypeIndexes[index]]
    fun setPheromoneEmitterLastImpulse(index: Int, value: Float) { pheromoneEmitterEntity.lastImpulse[specialTypeIndexes[index]] = value }

    fun deletePheromoneEmitter(cellIndex: Int, pheromoneEmitterGeneration: Int? = null) {
        val pheromoneEmitterIndex = specialTypeIndexes[cellIndex]
        if (pheromoneEmitterIndex == -1) return
        if (pheromoneEmitterEntity.isAlive[pheromoneEmitterIndex] && (pheromoneEmitterGeneration == null
                || pheromoneEmitterEntity.getGeneration(pheromoneEmitterIndex) == pheromoneEmitterGeneration)) {
            pheromoneEmitterEntity.deletePheromoneEmitter(pheromoneEmitterIndex)
            specialTypeIndexes[cellIndex] = -1
        }
    }

    fun addPheromoneEmitter(index: Int) {
        specialTypeIndexes[index] = pheromoneEmitterEntity.addPheromoneEmitter()
    }

    fun getSpecialData(index: Int) = specialModDataEntity.specialModData[specialTypeIndexes[index]]

    fun addSpecial(
        cell: Cell,
        colorDifferentiation: Int = 7,
        visibilityRange: Float = 4.25f,
        speed: Float = 0f,
        specialModData: SpecialModData? = null
    ): Int {
        val cellIndex = add()
        when (cell) {
            is Tail -> addTail(cellIndex, speed)
            is Eye -> addEye(cellIndex, colorDifferentiation, visibilityRange)
            is Producer -> addProducer(cellIndex)
            is PheromoneEmitter -> addPheromoneEmitter(cellIndex)
            else -> specialTypeIndexes[cellIndex] = -1
        }
        if (cell.doesItHasSpecialModData) {
            // specialModDataEntity.addModData(specialModData) – при необходимости раскомментировать
        }
        return cellIndex
    }

    fun delete(cell: Cell, cellIndex: Int) {
        delete(cellIndex)
        when (cell) {
            is Tail -> deleteTail(cellIndex)
            is Eye -> deleteEye(cellIndex)
            is Producer -> deleteProducer(cellIndex)
            is PheromoneEmitter -> deletePheromoneEmitter(cellIndex)
            else -> {}
        }
        if (cell.doesItHasSpecialModData) {
            specialModDataEntity.deleteModData(cellIndex)
        }
    }

    fun loadEntity(
        eyeEntity: EyeEntity,
        tailEntity: TailEntity,
        specialModDataEntity: SpecialModDataEntity,
        producerEntity: ProducerEntity,
        pheromoneEmitterEntity: PheromoneEmitterEntity
    ) {
        this.eyeEntity = eyeEntity
        this.tailEntity = tailEntity
        this.specialModDataEntity = specialModDataEntity
        this.producerEntity = producerEntity
        this.pheromoneEmitterEntity = pheromoneEmitterEntity
    }

    fun serializeEntity() {
        super.saveSerialize()
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
    }

    fun copyFrom(other: SpecialEntity) {
        copyBaseFrom(other)
        copyInto(other.specialTypeIndexes, specialTypeIndexes)
    }

    override fun onCopy() {}
    override fun onPaste() {}

    override fun onClear(bound: Int) {
        // Дефолт -1, как у инициализатора поля и у delete*: 0 — валидный индекс в eyeEntity/tailEntity
        specialTypeIndexes.clear(-1)
    }

    override fun onResize(oldMax: Int) {
        specialTypeIndexes = specialTypeIndexes.resize(-1)
    }
}
