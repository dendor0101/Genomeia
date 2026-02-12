package io.github.some_example_name.old.good_one.utils.primitive_hash_map

class OrderedIntPairMap(initialCapacity: Int = 1_000_000) {
    private var keysA = IntArray(initialCapacity) { EMPTY }
    private var keysB = IntArray(initialCapacity) { EMPTY }
    private var values = IntArray(initialCapacity)
    private var size = 0
    private val loadFactor = 0.75f
    private val threshold get() = (keysA.size * loadFactor).toInt()

    companion object {
        private const val EMPTY = Int.MIN_VALUE
        private const val DELETED = Int.MIN_VALUE + 1

        private fun orderedPairHash(a: Int, b: Int): Int {
            val aLong = a.toLong() and 0xFFFFFFFF
            val bLong = b.toLong() and 0xFFFFFFFF
            val paired = (aLong + bLong) * (aLong + bLong + 1) / 2 + bLong // Cantor pairing with order
            return (paired xor (paired shr 32)).toInt()
        }

        private fun indexFor(hash: Int, capacity: Int): Int {
            return (hash and 0x7FFFFFFF) % capacity
        }
    }

    fun put(a: Int, b: Int, value: Int) {
        if (size >= threshold) resize()

        val key = orderedPairHash(a, b)
        var idx = indexFor(key, keysA.size)

        while (keysA[idx] != EMPTY && keysA[idx] != DELETED &&
            !(keysA[idx] == a && keysB[idx] == b)) {
            idx = (idx + 1) % keysA.size
        }

        if (keysA[idx] != a || keysB[idx] != b) size++
        keysA[idx] = a
        keysB[idx] = b
        values[idx] = value
    }

    fun get(a: Int, b: Int): Int {
        val key = orderedPairHash(a, b)
        var idx = indexFor(key, keysA.size)

        while (keysA[idx] != EMPTY) {
            if (keysA[idx] == a && keysB[idx] == b) return values[idx]
            idx = (idx + 1) % keysA.size
        }
        return -1
    }

    fun remove(a: Int, b: Int): Boolean {
        val key = orderedPairHash(a, b)
        var idx = indexFor(key, keysA.size)

        while (keysA[idx] != EMPTY) {
            if (keysA[idx] == a && keysB[idx] == b) {
                keysA[idx] = DELETED
                keysB[idx] = DELETED
                size--
                return true
            }
            idx = (idx + 1) % keysA.size
        }
        return false
    }

    fun clear() {
        keysA.fill(EMPTY)
        keysB.fill(EMPTY)
        values.fill(0)
        size = 0
    }

    private fun resize() {
        val oldKeysA = keysA
        val oldKeysB = keysB
        val oldValues = values
        val newCapacity = oldKeysA.size * 2

        keysA = IntArray(newCapacity) { EMPTY }
        keysB = IntArray(newCapacity) { EMPTY }
        values = IntArray(newCapacity)
        size = 0

        for (i in oldKeysA.indices) {
            val keyA = oldKeysA[i]
            val keyB = oldKeysB[i]
            if (keyA != EMPTY && keyA != DELETED) {
                val keyHash = orderedPairHash(keyA, keyB)
                var idx = indexFor(keyHash, newCapacity)

                while (keysA[idx] != EMPTY) {
                    idx = (idx + 1) % newCapacity
                }

                keysA[idx] = keyA
                keysB[idx] = keyB
                values[idx] = oldValues[i]
                size++
            }
        }
    }
}
