package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.SpecialModData
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class SpecialModDataEntity(
    @Transient val specialModDataStartMaxAmount: Int = 0
) : Entity(specialModDataStartMaxAmount) {

    @Transient
    var specialModData = Array<SpecialModData?>(maxAmount) { null }

    @ProtoNumber(1)
    var specialModDataList: List<SpecialModData?> = emptyList()

    fun addModData(modData: SpecialModData): Int {
        val modDataIndex = add()
        specialModData[modDataIndex] = modData
        return modDataIndex
    }

    fun deleteModData(modDataIndex: Int) {
        delete(modDataIndex)
        specialModData[modDataIndex] = null
    }

    fun serializeEntity() {
        super.saveSerialize()
        val bound = lastId + 1
        specialModDataList = specialModData.take(bound).toList()
    }

    fun loadEntity() {
        super.loadSerialize()
        rebuildTransient()
    }

    fun copyFrom(other: SpecialModDataEntity) {
        copyBaseFrom(other)
        specialModDataList = other.specialModDataList
        rebuildTransient()
    }

    private fun rebuildTransient() {
        specialModData = Array(maxAmount) { null }
        specialModDataList.forEachIndexed { index, modData ->
            if (index < maxAmount) specialModData[index] = modData
        }
    }

    override fun onCopy() {}
    override fun onPaste() {}

    override fun onClear(bound: Int) {
        specialModData.fill(null, 0, bound)
    }

    override fun onResize(oldMax: Int) {
        val old = specialModData
        specialModData = arrayOfNulls(maxAmount)
        System.arraycopy(old, 0, specialModData, 0, oldMax)
    }
}
