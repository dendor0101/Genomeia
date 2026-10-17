package io.github.some_example_name.physics

/** Минимальный положительный радиус без обхода мира после локального изменения поверхности. */
class ContactRadiusMinimum(n: Int) {
    private val base = Integer.highestOneBit(maxOf(1, n - 1)) shl 1
    private val tree = DoubleArray(base * 2) { Double.POSITIVE_INFINITY }
    val value: Double get() = if (tree[1].isFinite()) tree[1] else 0.0

    fun reset(radius: DoubleArray) {
        tree.fill(Double.POSITIVE_INFINITY)
        for (i in radius.indices) if (radius[i] > 0.0) tree[base + i] = radius[i]
        for (i in base - 1 downTo 1) tree[i] = minOf(tree[2 * i], tree[2 * i + 1])
    }

    fun update(v: Int, radius: Double) {
        var i = base + v
        val value = if (radius > 0.0) radius else Double.POSITIVE_INFINITY
        if (tree[i] == value) return
        tree[i] = value
        while (i > 1) {
            i = i ushr 1
            val next = minOf(tree[2 * i], tree[2 * i + 1])
            if (tree[i] == next) break
            tree[i] = next
        }
    }
}
