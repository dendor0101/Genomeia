package io.github.some_example_name.physics

/**
 * Сетка путей граничных клеток. Число занятых ячеек ограничено текущим проходом,
 * а не предыдущим: при резком ударе длинный путь раньше заполнял таблицу целиком
 * и gridSlot навсегда искал свободное место. Списки внутри ячеек сохраняют порядок.
 */
class SweptContactGrid {
    private var keys = LongArray(64)
    private var stamps = IntArray(64)
    private var heads = IntArray(64)
    private var tails = IntArray(64)
    private var mask = 63
    private var stamp = 0
    private var occupied = 0
    private var entries = 0
    var ids = IntArray(4096)
        private set
    var next = IntArray(4096)
        private set

    fun begin(vertices: Int) {
        val need = maxOf(vertices * 2, entries) * 4
        var size = keys.size
        while (size < need) size = size shl 1
        if (size != keys.size) {
            keys = LongArray(size); stamps = IntArray(size)
            heads = IntArray(size); tails = IntArray(size); mask = size - 1
            stamp = 0
        }
        if (stamp == Int.MAX_VALUE) { stamps.fill(0); stamp = 0 }
        stamp++
        occupied = 0; entries = 0
    }

    private fun slot(key: Long): Int {
        var s = ((key * -0x61c8864680b583ebL) ushr 40).toInt() and mask
        while (stamps[s] == stamp && keys[s] != key) s = (s + 1) and mask
        return s
    }

    private fun grow() {
        val oldKeys = keys; val oldStamps = stamps; val oldHeads = heads; val oldTails = tails
        val size = keys.size shl 1
        keys = LongArray(size); stamps = IntArray(size)
        heads = IntArray(size); tails = IntArray(size); mask = size - 1
        for (i in oldKeys.indices) if (oldStamps[i] == stamp) {
            val s = slot(oldKeys[i])
            keys[s] = oldKeys[i]; stamps[s] = stamp
            heads[s] = oldHeads[i]; tails[s] = oldTails[i]
        }
    }

    fun insert(key: Long, id: Int) {
        var s = slot(key)
        if (stamps[s] != stamp) {
            // Всегда есть свободная ячейка, в том числе при росте посреди одного пути.
            if (occupied >= keys.size / 2) { grow(); s = slot(key) }
            occupied++
            stamps[s] = stamp; keys[s] = key; heads[s] = entries
        } else next[tails[s]] = entries
        if (entries == next.size) { next = next.copyOf(entries * 2); ids = ids.copyOf(entries * 2) }
        ids[entries] = id; next[entries] = -1; tails[s] = entries++
    }

    fun first(key: Long): Int {
        val s = slot(key)
        return if (stamps[s] == stamp) heads[s] else -1
    }
}
