package io.github.some_example_name.old.entities

import io.github.some_example_name.old.core.SubstrateSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class SubstancesEntity(
    @Transient private val initialMaxAmount: Int = 5_000
) : Entity(initialMaxAmount) {

    @Transient lateinit var particleEntity: ParticleEntity
    @Transient lateinit var substrateSettings: SubstrateSettings

    // Вторичный конструктор для обычного использования
    constructor(
        startMaxAmount: Int = 5_000,
        particleEntity: ParticleEntity,
        substrateSettings: SubstrateSettings
    ) : this(startMaxAmount) {
        loadEntity(particleEntity, substrateSettings)
    }

    @ProtoNumber(1) var particleIndex = IntArray(maxAmount) { -1 }
    @ProtoNumber(2) var substanceType = ByteArray(maxAmount)

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
    fun getEffectOnContact(index: Int) = particleEntity.effectOnContact[particleIndex[index]]
    fun setEffectOnContact(index: Int, value: Boolean) { particleEntity.effectOnContact[particleIndex[index]] = value }
    fun getRadius(index: Int) = particleEntity.radius[particleIndex[index]]
    fun seRadius(index: Int, value: Float) { particleEntity.radius[particleIndex[index]] = value }
    fun getGridId(index: Int) = particleEntity.gridId[particleIndex[index]]
    fun seGridId(index: Int, value: Int) { particleEntity.gridId[particleIndex[index]] = value }
    fun getColor(index: Int) = particleEntity.color[particleIndex[index]]
    fun setColor(index: Int, value: Int) { particleEntity.color[particleIndex[index]] = value }

    fun addSubstance(
        x: Float,
        y: Float,
        color: Int,
        radius: Float = 0.1f,
        subType: Byte
    ): Int {
        val subIndex = add()

        particleIndex[subIndex] = particleEntity.addParticle(
            x = x,
            y = y,
            color = color,
            radius = radius,
            dragCoefficient = substrateSettings.data.viscosityOfTheEnvironment,
            effectOnContact = false,
            cellStiffness = 0.05f,
            isCell = false,
            isSub = true,
            isPheromoneEmitter = true,
            holderEntityIndex = subIndex
        )

        substanceType[subIndex] = subType
        return subIndex
    }

    fun deleteSubstance(subIndex: Int, subGeneration: Int) {
        if (isAlive[subIndex] && getGeneration(subIndex) == subGeneration) {
            delete(subIndex)
            particleEntity.deleteParticle(particleIndex[subIndex])
            particleIndex[subIndex] = -1
            substanceType[subIndex] = -1
        }
    }

    fun loadEntity(
        particleEntity: ParticleEntity,
        substrateSettings: SubstrateSettings
    ) {
        this.particleEntity = particleEntity
        this.substrateSettings = substrateSettings
    }

    // Методы для процесса сохранения
    fun serializeEntity() {
        super.saveSerialize()
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
    }

    fun copyFrom(other: SubstancesEntity) {
        copyBaseFrom(other)
        copyInto(other.particleIndex, particleIndex)
        copyInto(other.substanceType, substanceType)
    }

    override fun onCopy() {}
    override fun onPaste() {}

    override fun onClear(bound: Int) {
        particleIndex.clear(-1)
        substanceType.clear(-1)
    }

    override fun onResize(oldMax: Int) {
        particleIndex = particleIndex.resize(-1)
        substanceType = substanceType.resize(-1)
    }
}
