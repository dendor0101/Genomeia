package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.SpecialModData

class SpecialModDataEntity(
    specialModDataStartMaxAmount: Int
): Entity(specialModDataStartMaxAmount) {

    var specialModData = Array<SpecialModData?>(maxAmount) { null }

    fun addModData(
        modData: SpecialModData
    ): Int {
        val modDataIndex = add()
        specialModData[modDataIndex] = modData
        return modDataIndex
    }

    fun deleteModData(modDataIndex: Int) {
        delete(modDataIndex)
        specialModData[modDataIndex] = null
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        specialModData.fill(null, 0, bound)
    }

    override fun onResize(oldMax: Int) {
        run {
            val old = specialModData
            specialModData = arrayOfNulls(maxAmount)
            System.arraycopy(old, 0, specialModData, 0, oldMax)
        }
    }
}
