package io.github.some_example_name.physics

/** Перенос состояния между итерациями одного подшага без таблиц и аллокаций на пару. */
class ContactPassCache {
    private var keys = LongArray(0)
    private var lambda = DoubleArray(0)
    private var initial = DoubleArray(0)
    private var approach = DoubleArray(0)
    private var count = 0
    private var cursor = 0

    fun save(n: Int, a: IntArray, b: IntArray, l: DoubleArray, init: DoubleArray, vn: DoubleArray) {
        if (keys.size < n) {
            keys = LongArray(n * 2); lambda = DoubleArray(n * 2)
            initial = DoubleArray(n * 2); approach = DoubleArray(n * 2)
        }
        for (i in 0 until n) {
            keys[i] = key(a[i], b[i]); lambda[i] = l[i]
            initial[i] = init[i]; approach[i] = vn[i]
        }
        count = n; cursor = 0
    }

    fun restore(i: Int, a: Int, b: Int, l: DoubleArray, init: DoubleArray, vn: DoubleArray) {
        val k = key(a, b)
        while (cursor < count && keys[cursor] < k) cursor++
        if (cursor < count && keys[cursor] == k) {
            l[i] = lambda[cursor]; init[i] = initial[cursor]; vn[i] = approach[cursor]
        }
    }

    private fun key(a: Int, b: Int): Long = (a.toLong() shl 32) or b.toLong()
}
