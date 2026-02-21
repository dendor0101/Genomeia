package io.github.some_example_name.old.world_logic.commands

class WorldCommandBuffer (initialCapacity: Int = 1000) {  // Начальный размер — на 1000 команд
    // Массив типов команд (int — ordinal enum)
    var commandTypes = IntArray(initialCapacity) { -1 }  // -1 = пусто

    // Параметры: большие массивы, где для каждой команды — фиксированный слот (type.ordinal * MAX_PARAMS + offset)
    var intParams = IntArray(initialCapacity * WorldCommandType.MAX_INT_PARAMS) { 0 }
    var floatParams = FloatArray(initialCapacity * WorldCommandType.MAX_FLOAT_PARAMS) { 0f }
    var booleanParams = BooleanArray(initialCapacity * WorldCommandType.MAX_BOOLEAN_PARAMS) { false }

    // Текущий размер (кол-во команд в буфере)
    var size = 0

    // Добавление команды (push)
    fun push(type: WorldCommandType, ints: IntArray? = null, floats: FloatArray? = null, booleans: BooleanArray? = null) {
        if (size >= commandTypes.size) resize()  // Авто-ресайз

        val baseIndex = size
        commandTypes[baseIndex] = type.ordinal

        // Копируем параметры в слоты (только нужное кол-во, остальное игнорируем)
        ints?.let { System.arraycopy(it, 0, intParams, baseIndex * WorldCommandType.MAX_INT_PARAMS, minOf(it.size, type.intParamsCount)) }
        floats?.let { System.arraycopy(it, 0, floatParams, baseIndex * WorldCommandType.MAX_FLOAT_PARAMS, minOf(it.size, type.floatParamsCount)) }
        booleans?.let { System.arraycopy(it, 0, booleanParams, baseIndex * WorldCommandType.MAX_BOOLEAN_PARAMS, minOf(it.size, type.booleanParamsCount)) }

        size++
    }

    // Обработка всех команд (consume) — итерация и вызов обработчика
    inline fun consume(processor: (WorldCommandType, IntArray, FloatArray, BooleanArray) -> Unit) {
        val tempInts = IntArray(WorldCommandType.MAX_INT_PARAMS)
        val tempFloats = FloatArray(WorldCommandType.MAX_FLOAT_PARAMS)
        val tempBooleans = BooleanArray(WorldCommandType.MAX_BOOLEAN_PARAMS)

        for (i in 0 until size) {
            val typeOrdinal = commandTypes[i]
            if (typeOrdinal == -1) continue  // Пусто

            val type = WorldCommandType.entries[typeOrdinal]
            val baseInt = i * WorldCommandType.MAX_INT_PARAMS
            val baseFloat = i * WorldCommandType.MAX_FLOAT_PARAMS
            val baseBool = i * WorldCommandType.MAX_BOOLEAN_PARAMS

            // Копируем параметры в темповые массивы (для безопасности, если processor мутирует)
            System.arraycopy(intParams, baseInt, tempInts, 0, type.intParamsCount)
            System.arraycopy(floatParams, baseFloat, tempFloats, 0, type.floatParamsCount)
            System.arraycopy(booleanParams, baseBool, tempBooleans, 0, type.booleanParamsCount)

            processor(type, tempInts, tempFloats, tempBooleans)
        }
        clear()
    }

    // Очистка буфера
    fun clear() {
        size = 0
        // Не нужно fill(-1), т.к. при push перезапишем
    }

    private fun resize() {
        val newCapacity = commandTypes.size * 2
        commandTypes = commandTypes.copyOf(newCapacity).apply { fill(-1, size, newCapacity) }
        intParams = intParams.copyOf(newCapacity * WorldCommandType.MAX_INT_PARAMS)
        floatParams = floatParams.copyOf(newCapacity * WorldCommandType.MAX_FLOAT_PARAMS)
        booleanParams = booleanParams.copyOf(newCapacity * WorldCommandType.MAX_BOOLEAN_PARAMS)
    }
}
