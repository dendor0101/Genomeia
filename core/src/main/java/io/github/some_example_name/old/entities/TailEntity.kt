package io.github.some_example_name.old.entities

class TailEntity(
    tailStartMaxAmount: Int = 5_000
): Entity(tailStartMaxAmount) {

    var speed = FloatArray(maxAmount)

    fun addTail(
        speed: Float
    ): Int {
        val tailIndex = add()
        this.speed[tailIndex] = speed
        return tailIndex
    }

    fun deleteTail(tailIndex: Int) {
        delete(tailIndex)
        speed[tailIndex] = 0f
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        speed.clear()
    }

    override fun onResize(oldMax: Int) {
        speed = speed.resize()
    }
}
