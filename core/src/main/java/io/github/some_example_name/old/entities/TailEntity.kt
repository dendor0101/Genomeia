package io.github.some_example_name.old.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class TailEntity(
    @Transient val tailStartMaxAmount: Int = 5_000
) : Entity(tailStartMaxAmount) {

    @ProtoNumber(1)
    var speed = FloatArray(maxAmount)

    fun addTail(speed: Float): Int {
        val tailIndex = add()
        this.speed[tailIndex] = speed
        return tailIndex
    }

    fun deleteTail(tailIndex: Int) {
        delete(tailIndex)
        speed[tailIndex] = 0f
    }

    fun serializeEntity() {
        super.saveSerialize()
    }

    fun loadSerializedEntity() {
        super.loadSerialize()
    }

    fun copyFrom(other: TailEntity) {
        copyBaseFrom(other)
        copyInto(other.speed, speed)
    }

    override fun onCopy() {}
    override fun onPaste() {}

    override fun onClear(bound: Int) {
        speed.clear()
    }

    override fun onResize(oldMax: Int) {
        speed = speed.resize()
    }
}
