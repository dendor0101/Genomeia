package io.github.some_example_name.old.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class PheromoneEmitterEntity(
    @Transient val pheromoneEmitterStartMaxAmount: Int = 0
) : Entity(pheromoneEmitterStartMaxAmount) {

    @ProtoNumber(1)
    var lastImpulse = FloatArray(maxAmount)

    fun addPheromoneEmitter(): Int {
        val pheromoneEmitterIndex = add()
        this.lastImpulse[pheromoneEmitterIndex] = 0f
        return pheromoneEmitterIndex
    }

    fun deletePheromoneEmitter(pheromoneEmitterIndex: Int) {
        delete(pheromoneEmitterIndex)
        this.lastImpulse[pheromoneEmitterIndex] = 0f
    }

    fun serializeEntity() {
        super.saveSerialize()
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
    }

    fun copyFrom(other: PheromoneEmitterEntity) {
        copyBaseFrom(other)
        copyInto(other.lastImpulse, lastImpulse)
    }

    override fun onCopy() {}
    override fun onPaste() {}

    override fun onClear(bound: Int) {
        lastImpulse.clear()
    }

    override fun onResize(oldMax: Int) {
        lastImpulse = lastImpulse.resize()
    }
}
