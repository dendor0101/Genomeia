package io.github.some_example_name.old.entities

class EyeEntity(
    eyeStartMaxAmount: Int
): Entity(eyeStartMaxAmount) {

    var colorDifferentiation = ByteArray(maxAmount) { 7 }
    var visibilityRange = FloatArray(maxAmount) { 4.25f }

    fun addEye(
        colorDifferentiation: Byte,
        visibilityRange: Float
    ): Int {
        val eyeIndex = add()
        this.colorDifferentiation[eyeIndex] = colorDifferentiation
        this.visibilityRange[eyeIndex] = visibilityRange
        return eyeIndex
    }

    fun deleteEye(eyeIndex: Int) {
        delete(eyeIndex)
        colorDifferentiation[eyeIndex] = 7
        visibilityRange[eyeIndex] = 4.25f
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        colorDifferentiation.clear(7)
        visibilityRange.clear(4.25f)
    }

    override fun onResize(oldMax: Int) {
        colorDifferentiation = colorDifferentiation.resize(7)
        visibilityRange = visibilityRange.resize(4.25f)
    }
}
