package io.github.some_example_name.old.entities

import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
abstract class Entity(
    @ProtoNumber(500) val startMaxAmount: Int = 10
) {
    // Текущий максимальный размер – сохраняется, потому что может расти
    @ProtoNumber(1000) var maxAmount: Int = startMaxAmount

    // Идентификатор последней выданной сущности
    @ProtoNumber(2000)
    var lastId: Int = -1

    // ------------------ Сериализуемые копии для хранения ------------------
    @ProtoNumber(3000)
    var generationList: List<Int> = emptyList()

    @ProtoNumber(4000)
    var isAliveList: List<Boolean> = emptyList()

    @ProtoNumber(5000)
    var positionInAliveList: List<Int> = emptyList()

    @ProtoNumber(6000)
    var aliveListData: List<Int> = emptyList()

    @ProtoNumber(7000)
    var deadStackData: List<Int> = emptyList()

    // ------------------ Transient-рабочие структуры ------------------
    @Transient
    var deadStack = IntArrayList(startMaxAmount)

    @Transient
    var isAlive = BooleanArray(maxAmount)

    @Transient
    var generation = IntArray(maxAmount)

    @Transient
    var aliveList = IntArrayList(startMaxAmount)

    @Transient
    var positionInAlive = IntArray(maxAmount) { -1 }

    @Transient
    private var cellBoundBeforeClear = 0

    @Transient
    private var oldMaxBeforeResize = 0

    fun getGeneration(index: Int) = generation[index]
    fun isAliveAndSameGen(index: Int, gen: Int) = isAlive[index] && generation[index] == gen

    protected fun add(): Int {
        val cellIndex = if (!deadStack.isEmpty()) {
            deadStack.removeInt(deadStack.size - 1)
        } else {
            ++lastId
        }

        isAlive[cellIndex] = true
        generation[cellIndex]++

        val pos = aliveList.size
        aliveList.add(cellIndex)
        positionInAlive[cellIndex] = pos

        if (maxAmount - 2 < lastId) {
            resize()
        }
        return cellIndex
    }

    protected fun delete(index: Int) {
        if (!isAlive[index]) throw IllegalStateException("Entity $index is already dead")

        isAlive[index] = false
        deadStack.add(index)

        val pos = positionInAlive[index]
        if (pos >= 0) {
            val lastPos = aliveList.size - 1
            val lastEntity = aliveList.getInt(lastPos)

            aliveList.set(pos, lastEntity)
            positionInAlive[lastEntity] = pos

            aliveList.removeInt(lastPos)
            positionInAlive[index] = -1
        }
    }

    fun clear() {
        val cellBound = (lastId + 1).coerceAtLeast(0)
        cellBoundBeforeClear = cellBound
        lastId = -1
        deadStack.clear()
        generation.fill(0, 0, cellBound)
        isAlive.fill(false, 0, cellBound)

        aliveList.clear()
        positionInAlive.fill(-1, 0, cellBound)

        onClear(cellBound)
    }

    fun resize() {
        val oldMax = maxAmount
        oldMaxBeforeResize = oldMax
        maxAmount = (oldMax * 5 / 4).coerceAtLeast(oldMax + 1)

        // Расширяем transient-массивы
        run {
            val old = generation
            generation = IntArray(maxAmount)
            System.arraycopy(old, 0, generation, 0, oldMax)
        }
        run {
            val old = isAlive
            isAlive = BooleanArray(maxAmount)
            System.arraycopy(old, 0, isAlive, 0, oldMax)
        }
        run {
            val old = positionInAlive
            positionInAlive = IntArray(maxAmount) { -1 }
            System.arraycopy(old, 0, positionInAlive, 0, oldMax)
        }

        aliveList.ensureCapacity(maxAmount)

        onResize(oldMax)
    }

    // Вызывается перед сериализацией
    open fun saveSerialize() {
        // Сохраняем только актуальные данные до lastId+1
        val bound = lastId + 1
        generationList = generation.copyOf(bound).toList()
        isAliveList = isAlive.copyOf(bound).toList()
        positionInAliveList = positionInAlive.copyOf(bound).toList()
        // Именно копии: subList у fastutil возвращает view на живой список,
        // который симуляция продолжит менять прямо во время сериализации
        aliveListData = aliveList.toIntArray().toList()
        deadStackData = deadStack.toIntArray().toList()
    }

    // Вызывается сразу после десериализации
    open fun loadSerialize() {
        // Восстанавливаем transient-массивы нужного размера (maxAmount уже восстановлен)
        val safeMaxAmount = maxOf(maxAmount, generationList.size, startMaxAmount)

        // Принудительно обновляем maxAmount, чтобы наследники (SubstancesEntity) видели правильное значение
        maxAmount = safeMaxAmount
        generation = IntArray(maxAmount)
        isAlive = BooleanArray(maxAmount)
        positionInAlive = IntArray(maxAmount) { -1 }
        // Копируем данные из списков (размеры списков могут быть меньше maxAmount)
        generationList.forEachIndexed { index, value -> generation[index] = value }
        isAliveList.forEachIndexed { index, value -> isAlive[index] = value }
        positionInAliveList.forEachIndexed { index, value -> positionInAlive[index] = value }

        // Восстанавливаем fastutil-коллекции
        deadStack = IntArrayList(deadStackData)
        aliveList = IntArrayList(aliveListData)
    }

    /**
     * Наращивает все массивы (через штатный onResize наследника), пока maxAmount не достигнет target.
     * Нужно при загрузке: в сохранении мир мог вырасти больше, чем стартовый размер контейнера.
     */
    fun growTo(target: Int) {
        while (maxAmount < target) resize()
    }

    /**
     * Копирует служебные данные Entity из [other] в существующие массивы.
     *
     * Важно: копируем содержимое, а не подменяем сам объект. Все системы (CellSystem,
     * LinkPhysicsSystem, RenderSystem и т.д.) держат ссылки на сущности, полученные в
     * конструкторе, поэтому замена объекта в DI-контейнере их не затронула бы.
     */
    protected fun copyBaseFrom(other: Entity) {
        growTo(other.maxAmount)

        lastId = other.lastId

        generation.fill(0)
        isAlive.fill(false)
        positionInAlive.fill(-1)

        copyInto(other.generation, generation)
        copyInto(other.isAlive, isAlive)
        copyInto(other.positionInAlive, positionInAlive)

        aliveList.clear()
        aliveList.addAll(other.aliveList)
        deadStack.clear()
        deadStack.addAll(other.deadStack)
    }

    protected fun copyInto(src: IntArray, dst: IntArray) {
        System.arraycopy(src, 0, dst, 0, minOf(src.size, dst.size))
    }

    protected fun copyInto(src: FloatArray, dst: FloatArray) {
        System.arraycopy(src, 0, dst, 0, minOf(src.size, dst.size))
    }

    protected fun copyInto(src: BooleanArray, dst: BooleanArray) {
        System.arraycopy(src, 0, dst, 0, minOf(src.size, dst.size))
    }

    protected fun copyInto(src: ByteArray, dst: ByteArray) {
        System.arraycopy(src, 0, dst, 0, minOf(src.size, dst.size))
    }

    protected abstract fun onCopy()
    protected abstract fun onPaste()
    protected abstract fun onClear(bound: Int)
    protected abstract fun onResize(oldMax: Int)

    // Вспомогательные методы для очистки массивов наследников
    protected fun FloatArray.clear(defaultValue: Float = 0f) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }

    protected fun IntArray.clear(defaultValue: Int = 0) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }

    protected fun BooleanArray.clear(defaultValue: Boolean) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }

    protected fun ByteArray.clear(defaultValue: Byte = 0) {
        this.fill(defaultValue, 0, cellBoundBeforeClear)
    }

    protected fun FloatArray.resize(defaultValue: Float = 0f): FloatArray {
        val old = this
        val newArray = if (defaultValue == 0f) FloatArray(maxAmount)
        else FloatArray(maxAmount) { defaultValue }
        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }

    protected fun IntArray.resize(defaultValue: Int = 0): IntArray {
        val old = this
        val newArray = if (defaultValue == 0) IntArray(maxAmount)
        else IntArray(maxAmount) { defaultValue }
        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }

    protected fun BooleanArray.resize(defaultValue: Boolean): BooleanArray {
        val old = this
        val newArray = BooleanArray(maxAmount) { defaultValue }
        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }

    protected fun ByteArray.resize(defaultValue: Byte = 0): ByteArray {
        val old = this
        val newArray = if (defaultValue == 0.toByte()) ByteArray(maxAmount)
        else ByteArray(maxAmount) { defaultValue }
        System.arraycopy(old, 0, newArray, 0, oldMaxBeforeResize)
        return newArray
    }
}
