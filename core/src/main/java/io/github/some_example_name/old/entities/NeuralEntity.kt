package io.github.some_example_name.old.entities

import io.github.some_example_name.old.cells.Cell

class NeuralEntity(
    neuralStartMaxAmount: Int,
    val cellList: List<Cell>
): Entity(neuralStartMaxAmount) {

    var isNeuronTransportable = BooleanArray(maxAmount)
    var activationFuncType = ByteArray(maxAmount)
    var a = FloatArray(maxAmount) { 1f }
    var b = FloatArray(maxAmount)
    var c = FloatArray(maxAmount)
    var dTime = FloatArray(maxAmount) { -1f }
    var remember = FloatArray(maxAmount)
    var isSum = BooleanArray(maxAmount)

    fun addNeural(
        cellType: Int,
        a: Float = 1f,
        b: Float = 0f,
        c: Float = 0f,
        isSum: Boolean,
        activationFuncType: Byte
    ): Int {
        val neuralIndex = add()

        this.isNeuronTransportable[neuralIndex] = cellList[cellType].isNeuronTransportable
        this.activationFuncType[neuralIndex] = activationFuncType
        this.a[neuralIndex] = a
        this.b[neuralIndex] = b
        this.c[neuralIndex] = c
        this.dTime[neuralIndex] = -1f
        this.remember[neuralIndex]  = 0f
        this.isSum[neuralIndex] = isSum
        return neuralIndex
    }

    fun deleteNeural(neuralIndex: Int) {
        delete(neuralIndex)

        isNeuronTransportable[neuralIndex] = true
        activationFuncType[neuralIndex] = 0
        a[neuralIndex] = 1f
        b[neuralIndex] = 0f
        c[neuralIndex] = 0f
        dTime[neuralIndex] = -1f
        remember[neuralIndex] = 0f
        isSum[neuralIndex] = true
    }

    /**
     * После загрузки мира: номера формул из реестра того запуска, что сохранял мир, в номера
     * текущего (см. CellEntity.remapCellTypes). [newIndexByOld] — новый номер по старому
     * (-1 — формулы больше нет), [savedKeys] — ключи по старым номерам, для сообщения.
     */
    fun remapFormulas(newIndexByOld: IntArray, savedKeys: List<String>) {
        if (newIndexByOld.withIndex().all { (old, new) -> old == new }) return
        for (i in 0 until aliveList.size) {
            val neuralIndex = aliveList.getInt(i)
            val old = activationFuncType[neuralIndex].toInt()
            val new = newIndexByOld.getOrElse(old) { -1 }
            check(new != -1) { "неизвестная формула '${savedKeys.getOrElse(old) { "#$old" }}': мир от мода, которого нет" }
            activationFuncType[neuralIndex] = new.toByte()
        }
    }

    override fun persistFields(state: EntityState) {
        state.booleans(::isNeuronTransportable)
        state.bytes(::activationFuncType)
        state.floats(::a)
        state.floats(::b)
        state.floats(::c)
        state.floats(::dTime)
        state.floats(::remember)
        state.booleans(::isSum)
    }

    override fun onCopy() {

    }

    override fun onPaste() {

    }

    override fun onClear(bound: Int) {
        isNeuronTransportable.clear(true)
        activationFuncType.clear()
        a.clear(1f)
        b.clear()
        c.clear()
        dTime.clear(-1f)
        remember.clear()
        isSum.clear(true)
    }

    override fun onResize(oldMax: Int) {
        isNeuronTransportable = isNeuronTransportable.resize(true)
        activationFuncType = activationFuncType.resize()
        a = a.resize(1f)
        b = b.resize()
        c = c.resize()
        dTime = dTime.resize(-1f)
        remember = remember.resize()
        isSum = isSum.resize(true)
    }
}
