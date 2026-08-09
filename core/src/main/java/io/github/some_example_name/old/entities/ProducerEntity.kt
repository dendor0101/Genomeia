package io.github.some_example_name.old.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class ProducerEntity(
    @Transient val producerStartMaxAmount: Int = 0
) : Entity(producerStartMaxAmount) {

    @ProtoNumber(1)
    var reproductionRestriction = IntArray(maxAmount)

    fun addProducer(): Int {
        val producerIndex = add()
        this.reproductionRestriction[producerIndex] = 0
        return producerIndex
    }

    fun deleteProducer(producerIndex: Int) {
        delete(producerIndex)
        this.reproductionRestriction[producerIndex] = 0
    }

    fun serializeEntity() {
        super.saveSerialize()
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
    }

    fun copyFrom(other: ProducerEntity) {
        copyBaseFrom(other)
        copyInto(other.reproductionRestriction, reproductionRestriction)
    }

    override fun onCopy() {}
    override fun onPaste() {}

    override fun onClear(bound: Int) {
        reproductionRestriction.clear()
    }

    override fun onResize(oldMax: Int) {
        reproductionRestriction = reproductionRestriction.resize()
    }
}
