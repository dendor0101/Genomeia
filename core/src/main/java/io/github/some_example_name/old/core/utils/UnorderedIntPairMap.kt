package io.github.some_example_name.old.core.utils

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap

class UnorderedIntPairMap(initialCapacity: Int = 1_000_000) {
    private val map: Long2IntOpenHashMap = Long2IntOpenHashMap(initialCapacity).apply {
        defaultReturnValue(-1) // Возвращаемое значение по умолчанию, если ключ не найден
    }

    fun clear() {
        map.clear()
    }

    fun put(a: Int, b: Int, value: Int) {
        val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        map.put(key, value)
    }

    fun get(a: Int, b: Int): Int {
        val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        return map.get(key)
    }

    fun remove(a: Int, b: Int): Boolean {
        val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
        val oldValue = map.remove(key)
        return oldValue != map.defaultReturnValue()
    }
}
