package io.github.some_example_name.physics

/** Плотные сегменты постоянных ограничений. 12 байт/элемент: горячий ID,
 * холодные обратный индекс и владелец. После разрыва остаётся дырка до конца
 * тика; пакуются только затронутые блоки. Сам проход — счётный цикл без next[id].
 */
class DenseBlockIndex(capacity: Int, blocks: Int) {
    val start = IntArray(blocks)
    val count = IntArray(blocks)
    val liveCount = IntArray(blocks)
    val items = IntArray(capacity)
    val owner = IntArray(capacity) { -1 }
    private val position = IntArray(capacity) { -1 }
    private val dirty = BooleanArray(blocks)
    private val dirtyBlocks = IntArray(blocks)
    private var dirtyCount = 0

    fun clear() {
        owner.fill(-1); position.fill(-1); count.fill(0); liveCount.fill(0)
        dirty.fill(false); dirtyCount = 0
    }

    /** Только построение/reset, до finishBuild(). В рантайме новые связи не создаются. */
    fun add(id: Int, block: Int) { owner[id] = block }

    fun finishBuild(order: IntArray? = null) {
        count.fill(0)
        for (b in owner) if (b >= 0) count[b]++
        var offset = 0
        for (b in count.indices) { start[b] = offset; offset += count[b]; count[b] = 0 }
        // Для связей сохраняем прежний план независимых перестановок. Отбор в
        // блок не меняет относительный порядок ограничений одной частицы.
        for (slot in 0 until (order?.size ?: owner.size)) {
            val id = order?.get(slot) ?: slot
            val b = owner[id]
            if (b < 0) continue
            val dst = start[b] + count[b]++
            items[dst] = id; position[id] = dst
        }
        count.copyInto(liveCount)
    }

    fun remove(id: Int) {
        val b = owner[id]
        if (b < 0) return
        items[position[id]] = -1; position[id] = -1; owner[id] = -1; liveCount[b]--
        if (!dirty[b]) { dirty[b] = true; dirtyBlocks[dirtyCount++] = b }
    }

    /** Меняется рабочий ID при существующей упаковке SoA, порядок блока прежний. */
    fun move(from: Int, to: Int) {
        if (from == to) return
        remove(to)
        val b = owner[from]
        if (b < 0) return
        val slot = position[from]
        items[slot] = to; owner[to] = b; position[to] = slot
        owner[from] = -1; position[from] = -1
    }

    fun packDirty() {
        for (k in 0 until dirtyCount) {
            val b = dirtyBlocks[k]
            var write = start[b]
            val end = write + count[b]
            for (read in write until end) {
                val id = items[read]
                if (id >= 0) { items[write] = id; position[id] = write++ }
            }
            count[b] = write - start[b]; dirty[b] = false
        }
        dirtyCount = 0
    }
}
