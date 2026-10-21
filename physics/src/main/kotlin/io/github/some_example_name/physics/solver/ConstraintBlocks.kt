package io.github.some_example_name.physics

/** Индексы по блокам без объектов на ограничение. В горячем обходе читается один
 * Int на запись; ещё два Int нужны удалению/переносу по месту. 12 байт/ограничение,
 * около 3,1 МиБ на 269k связей; сами списки блока из 512 клеток — единицы КиБ.
 * Порядок внутри блока соответствует исходному списку до локальных изменений.
 */
class BlockIndex(capacity: Int, blocks: Int) {
    val head = IntArray(blocks) { -1 }
    val tail = IntArray(blocks) { -1 }
    var next = IntArray(capacity) { -1 }; private set
    var prev = IntArray(capacity) { -1 }; private set
    var owner = IntArray(capacity) { -1 }; private set

    fun clear() { head.fill(-1); tail.fill(-1); owner.fill(-1) }

    fun add(id: Int, block: Int) {
        if (id >= owner.size) grow(id + 1)
        if (owner[id] >= 0) return
        owner[id] = block; next[id] = -1; prev[id] = tail[block]
        if (tail[block] < 0) head[block] = id else next[tail[block]] = id
        tail[block] = id
    }

    fun remove(id: Int) {
        val block = owner[id]
        if (block < 0) return
        val p = prev[id]; val q = next[id]
        if (p < 0) head[block] = q else next[p] = q
        if (q < 0) tail[block] = p else prev[q] = p
        owner[id] = -1
    }

    /** Уплотнение существующих рабочих массивов: меняется ID, не место в обходе. */
    fun move(from: Int, to: Int) {
        if (from == to) return
        remove(to)
        val block = owner[from]
        if (block < 0) return
        owner[to] = block; prev[to] = prev[from]; next[to] = next[from]
        if (prev[to] < 0) head[block] = to else next[prev[to]] = to
        if (next[to] < 0) tail[block] = to else prev[next[to]] = to
        owner[from] = -1
    }

    private fun grow(need: Int) {
        val old = owner.size
        val size = maxOf(need, maxOf(16, old * 2))
        owner = owner.copyOf(size); owner.fill(-1, old, size)
        next = next.copyOf(size); prev = prev.copyOf(size)
    }
}

/** Блоки непрерывных исходных индексов внутри организма. Новых индексов частиц
 * и копий координат нет. Ограничение целиком принадлежит одному блоку либо
 * общей стадии швов; соседние блоки не пишут друг другу во внутренней стадии.
 * 1000 клеток: px/py/invMass — 23,4 КиБ, вместе со связями/площадями больше L1,
 * но меньше обычного L2. Контактные барьеры остаются общими.
 */
class ConstraintBlocks(n: Int, organismOf: IntArray, blockSize: Int,
                       linkCapacity: Int, triangleCapacity: Int, boneCount: Int) {
    val particleBlock = IntArray(n)
    val blockCount: Int
    val seam: Int
    val links: DenseBlockIndex
    val areas: DenseBlockIndex
    val limits: BlockIndex
    val bones: BlockIndex
    val bends: BlockIndex
    val flaps: BlockIndex
    val triangleSlot = IntArray(triangleCapacity) { -1 }
    private val limitQueue = IntArray(linkCapacity)
    private var activeLimitBlock = -1
    private var queueSize = 0

    init {
        var block = -1; var size = blockSize; var organism = -1
        for (i in 0 until n) {
            if (size == blockSize || organismOf[i] != organism) {
                block++; size = 0; organism = organismOf[i]
            }
            particleBlock[i] = block; size++
        }
        blockCount = block + 1; seam = blockCount
        links = DenseBlockIndex(linkCapacity, blockCount + 1)
        limits = BlockIndex(linkCapacity, blockCount + 1)
        areas = DenseBlockIndex(triangleCapacity, blockCount + 1)
        bones = BlockIndex(boneCount, blockCount + 1)
        bends = BlockIndex(maxOf(n, 16), blockCount + 1)
        flaps = BlockIndex(maxOf(n, 16), blockCount + 1)
    }

    fun hasSeams(): Boolean = links.liveCount[seam] > 0 || areas.liveCount[seam] > 0 ||
        limits.head[seam] >= 0 || bones.head[seam] >= 0 || bends.head[seam] >= 0 || flaps.head[seam] >= 0

    fun blockOf(a: Int, b: Int): Int =
        if (particleBlock[a] == particleBlock[b]) particleBlock[a] else seam

    fun blockOf(a: Int, b: Int, c: Int): Int {
        val block = blockOf(a, b)
        return if (block == particleBlock[c]) block else seam
    }

    fun addLimit(c: Int, a: Int, b: Int) {
        if (limits.owner[c] >= 0) return
        val block = blockOf(a, b)
        limits.add(c, block)
        if (activeLimitBlock == block) limitQueue[queueSize++] = c
    }

    /** Очередь не портится от удаления/добавления краёв внутри текущего прохода. */
    fun beginLimits(block: Int) {
        activeLimitBlock = block; queueSize = 0
        var c = limits.head[block]
        while (c >= 0) { limitQueue[queueSize++] = c; c = limits.next[c] }
    }

    fun limitCount(): Int = queueSize
    fun limitAt(slot: Int): Int = limitQueue[slot]
    fun endLimits() { activeLimitBlock = -1 }

    fun removeTriangle(id: Int) {
        val slot = triangleSlot[id]
        if (slot >= 0) { areas.remove(slot); triangleSlot[id] = -1 }
    }

    fun moveTriangle(from: Int, to: Int, id: Int) {
        areas.move(from, to); triangleSlot[id] = to
    }

    fun moveConstraint(from: Int, to: Int) {
        links.move(from, to); limits.move(from, to)
    }
}
