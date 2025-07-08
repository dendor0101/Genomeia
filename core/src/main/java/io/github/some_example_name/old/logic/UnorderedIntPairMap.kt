package io.github.some_example_name.old.logic

class UnorderedIntPair(val a: Int, val b: Int) {
    private val min = minOf(a, b)
    private val max = maxOf(a, b)
    private val hash = 31 * min + max

    override fun equals(other: Any?): Boolean {
        return other is UnorderedIntPair && other.min == min && other.max == max
    }

    override fun hashCode(): Int = hash
}

fun main() {
//    val N = 1_000_0
//    val data = List(N) { it to (it + 7) }
//
//    val customMap = UnorderedIntPairMap(N)
//    for ((a, b) in data) customMap.put(a, b, a + b)
//    val start1 = System.nanoTime()
//    for ((a, b) in data) customMap.get(a, b)
//    val time1 = System.nanoTime() - start1
//
//    val stdMap = HashMap<UnorderedIntPair, Int>()
//    val start2 = System.nanoTime()
//    for ((a, b) in data) stdMap[UnorderedIntPair(a, b)] = a + b
//    for ((a, b) in data) stdMap[UnorderedIntPair(a, b)]
//    val time2 = System.nanoTime() - start2
//
//    println("Custom map: ${time1 / 1_000_000} ms")
//    println("Standard map: ${time2 / 1_000_000} ms")

    val map = UnorderedIntPairMap()

//    map.put(2, 9, 100)
//    map.put(9, 2, 200) // перезапишет
//
//    println(map.get(2, 9)) // 200
//    println(map.get(9, 2)) // 200
//    println(map.get(1, 1)) // null

//    put 15, 8, 14
//    put 15, 3, 15
//    put 15, 4, 16
//    put 15, 7, 17
//    map.put(15, 8, 14)
//    map.put(15, 3, 15)
//    map.put(15, 4, 16)
//    map.put(15, 7, 17)
//
//    map.remove(15, 8)
//
//    println(map.get(15, 8))
//    println(map.get(15, 3))
//    println(map.get(15, 4))
//    println(map.get(15, 7))
//    println(map.get( 8, 15))
//    println(map.get( 3, 15))
//    println(map.get( 4, 15))
//    println(map.get( 7, 15))
}


class UnorderedIntPairMap(initialCapacity: Int = 1_000_000) {
    private var keys = IntArray(initialCapacity) { EMPTY }
    private var values = IntArray(initialCapacity)
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

    fun put(a: Int, b: Int, value: Int) {
//        println("kekekekeke put $a $b $value")
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
//        println("put $a $b")
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
        val newCapacity = oldKeys.size * 2

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

