package io.github.some_example_name.old.entities

import io.github.some_example_name.old.core.SubstrateSettings

class SubstancesEntity(
    startMaxAmount: Int = 5_000,
    private val particleEntity: ParticleEntity,
    val substrateSettings: SubstrateSettings
): Entity(startMaxAmount) {

    var particleIndex = IntArray(maxAmount) { -1 }
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
            cellStiffness = 0.2f,
            isCell = false,
            holderEntityIndex = subIndex
        )

        substanceType[subIndex] = subType

        return subIndex
    }

    fun deleteSubstance(subIndex: Int) {
        delete(subIndex)
        particleEntity.deleteParticle(particleIndex[subIndex])
        particleIndex[subIndex] = -1
        substanceType[subIndex] = -1
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        particleIndex.fill(-1, 0, bound)
        substanceType.fill(-1, 0, bound)
    }

    override fun onResize(oldMax: Int) {
        run {
            val old = particleIndex
            particleIndex = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, particleIndex, 0, oldMax)
        }
        run {
            val old = substanceType
            substanceType = ByteArray(maxAmount) { -1 }
            System.arraycopy(old, 0, substanceType, 0, oldMax)
        }
    }

}
