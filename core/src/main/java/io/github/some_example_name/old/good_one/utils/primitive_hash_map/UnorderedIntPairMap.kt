package io.github.some_example_name.old.good_one.utils.primitive_hash_map

class UnorderedIntPairMap(initialCapacity: Int = 1_000_000) {
    private val minCapacity = 16 // Reasonable minimum to avoid zero or tiny tables.
    private var keys = IntArray(maxOf(initialCapacity, minCapacity)) { EMPTY }
    private var values = IntArray(keys.size)
    private var size = 0
    private val loadFactor = 0.75f
    private val threshold get() = (keys.size * loadFactor).toInt()

    companion object {
        private const val EMPTY = Int.MIN_VALUE
        private const val DELETED = Int.MIN_VALUE + 1

        private fun unorderedPairHash(a: Int, b: Int): Int {
            val min = minOf(a, b).toLong() and 0xFFFFFFFF
            val max = maxOf(a, b).toLong() and 0xFFFFFFFF
            val paired = (min + max) * (min + max + 1) / 2 + max // Cantor pairing
            return (paired xor (paired shr 32)).toInt()
        }

        private fun indexFor(hash: Int, capacity: Int): Int {
            return (hash and 0x7FFFFFFF) % capacity
        }
    }

    fun clear() {
        keys.fill(EMPTY)
        values.fill(0)
        size = 0 // Reset size to avoid bugs post-clear.
    }

    fun put(a: Int, b: Int, value: Int) {
        if (size >= threshold) resize()

        val key = unorderedPairHash(a, b)
        var idx = indexFor(key, keys.size)

        while (keys[idx] != EMPTY && keys[idx] != DELETED && keys[idx] != key) {
            idx = (idx + 1) % keys.size
        }

        if (keys[idx] != key) size++
        keys[idx] = key
        values[idx] = value
    }

    //TODO упаковывать данные так, чтобы те, которые вызываются часто через while находились с как можно меньшим количество итераций
    fun get(a: Int, b: Int): Int {
        val key = unorderedPairHash(a, b)
        var idx = indexFor(key, keys.size)

        while (keys[idx] != EMPTY) {
            if (keys[idx] == key) return values[idx]
            idx = (idx + 1) % keys.size
        }
        return -1
    }

    fun remove(a: Int, b: Int): Boolean {
        val key = unorderedPairHash(a, b)
        var idx = indexFor(key, keys.size)

        while (keys[idx] != EMPTY) {
            if (keys[idx] == key) {
                keys[idx] = DELETED
                size--
                return true
            }
            idx = (idx + 1) % keys.size
        }
        return false
    }

    private fun resize() {
        val oldKeys = keys
        val oldValues = values
        val newCapacity = maxOf(oldKeys.size * 2, minCapacity)

        keys = IntArray(newCapacity) { EMPTY }
        values = IntArray(newCapacity)
        size = 0

        for (i in oldKeys.indices) {
            val key = oldKeys[i]
            if (key != EMPTY && key != DELETED) {
                var idx = indexFor(key, newCapacity)
                while (keys[idx] != EMPTY) {
                    idx = (idx + 1) % newCapacity
                }
                keys[idx] = key
                values[idx] = oldValues[i]
                size++
            }
        }
    }
}
