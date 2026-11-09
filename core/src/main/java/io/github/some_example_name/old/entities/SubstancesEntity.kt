package io.github.some_example_name.old.entities

import io.github.some_example_name.old.core.SubstrateSettings

class SubstancesEntity(
    startMaxAmount: Int = 5_000,
    private val particleEntity: ParticleEntity,
    val substrateSettings: SubstrateSettings
): Entity(startMaxAmount) {

    var particleIndex = IntArray(maxAmount) { -1 }
    fun getX(index: Int) = particleEntity.x(particleIndex[index])
    fun getY(index: Int) = particleEntity.y(particleIndex[index])
    fun getVx(index: Int) = particleEntity.vx(particleIndex[index])
    fun getVy(index: Int) = particleEntity.vy(particleIndex[index])
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

    var substanceType = ByteArray(maxAmount)

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

    override fun persistFields(state: EntityState) {
        state.ints(::particleIndex)
        state.bytes(::substanceType)
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        particleIndex.clear(-1)
        substanceType.clear(-1)
    }

    override fun onResize(oldMax: Int) {
        particleIndex = particleIndex.resize(-1)
        substanceType = substanceType.resize(-1)
    }

}
