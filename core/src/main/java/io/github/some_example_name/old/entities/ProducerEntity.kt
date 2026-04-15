package io.github.some_example_name.old.entities

class ProducerEntity(
    producerStartMaxAmount: Int
): Entity(producerStartMaxAmount) {

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

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        reproductionRestriction.clear()
    }

    override fun onResize(oldMax: Int) {
        reproductionRestriction = reproductionRestriction.resize()
    }
}
