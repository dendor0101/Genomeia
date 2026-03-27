package io.github.some_example_name.old.entities

class EyeEntity(
    eyeStartMaxAmount: Int
): Entity(eyeStartMaxAmount) {

    var colorDifferentiation = ByteArray(maxAmount) { 7 }
    var visibilityRange = FloatArray(maxAmount) { 170f }

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
        visibilityRange[eyeIndex] = 170f
    }

    override fun onCopy() {
        TODO("Not yet implemented")
    }

    override fun onPaste() {
        TODO("Not yet implemented")
    }

    override fun onClear(bound: Int) {
        colorDifferentiation.fill(7, 0, bound)
        visibilityRange.fill(170f, 0, bound)
    }

    override fun onResize(oldMax: Int) {
        run {
            val old = colorDifferentiation
            colorDifferentiation = ByteArray(maxAmount) { 7 }
            System.arraycopy(old, 0, colorDifferentiation, 0, oldMax)
        }
        run {
            val old = visibilityRange
            visibilityRange = FloatArray(maxAmount) { 170f }
            System.arraycopy(old, 0, visibilityRange, 0, oldMax)
        }
    }
}
