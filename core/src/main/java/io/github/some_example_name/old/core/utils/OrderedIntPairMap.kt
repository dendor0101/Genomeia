package io.github.some_example_name.old.core.utils

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap

class OrderedIntPairMap(initialCapacity: Int = 30) {
    private val map: Long2IntOpenHashMap = Long2IntOpenHashMap(initialCapacity).apply {
        defaultReturnValue(-1)
    }

    fun clear() {
        map.clear()
    }

    fun put(a: Int, b: Int, value: Int) {
        val key = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
        map.put(key, value)
    }

    fun get(a: Int, b: Int): Int {
        val key = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
        return map.get(key)
    }

    fun remove(a: Int, b: Int): Boolean {
        val key = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
        val oldValue = map.remove(key)
        return oldValue != map.defaultReturnValue()
    }

    fun getOrNull(a: Int, b: Int): Int? {
        val value = get(a, b)
        return if (value != -1) value else null
    }

    fun contains(a: Int, b: Int): Boolean =
        get(a, b) != -1

    fun putIfAbsent(a: Int, b: Int, value: Int): Boolean {
        val key = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
        return map.putIfAbsent(key, value) == map.defaultReturnValue()
    }
}
