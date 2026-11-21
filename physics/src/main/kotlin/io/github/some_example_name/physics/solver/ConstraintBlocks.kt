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

    /**
     * Плотная копия списка блока для счётного обхода (пределы длины, четыре прохода за подшаг):
     * пересобирается только после правок блока. Блок обходит один воркер, поэтому гонки нет.
     */
    private val denseItems = Array(blocks) { IntArray(0) }
    private val denseDirty = BooleanArray(blocks) { true }
    val denseCount = IntArray(blocks)

    fun dense(block: Int): IntArray {
        if (denseDirty[block]) {
            var size = 0
            var c = head[block]
            while (c >= 0) { size++; c = next[c] }
            if (denseItems[block].size < size) denseItems[block] = IntArray(size * 2)
            val items = denseItems[block]
            var k = 0
            c = head[block]
            while (c >= 0) { items[k++] = c; c = next[c] }
            denseCount[block] = size
            denseDirty[block] = false
        }
        return denseItems[block]
    }

    fun clear() { head.fill(-1); tail.fill(-1); owner.fill(-1); denseDirty.fill(true) }

    fun add(id: Int, block: Int) {
        if (id >= owner.size) grow(id + 1)
        if (owner[id] >= 0) return
        owner[id] = block; next[id] = -1; prev[id] = tail[block]
        denseDirty[block] = true
        if (tail[block] < 0) head[block] = id else next[tail[block]] = id
        tail[block] = id
    }

    fun remove(id: Int) {
        // Номера за концом ещё не было в индексе (add наращивает, remove — нет): так новое
        // ребро контура вынимается перед вставкой у мира, чьё первое тело — пул без рёбер.
        if (id >= owner.size) return
        val block = owner[id]
        if (block < 0) return
        denseDirty[block] = true
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
        denseDirty[block] = true
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
class ConstraintBlocks(n: Int, bodyOf: IntArray, blockSize: Int,
                       linkCapacity: Int, triangleCapacity: Int, boneCount: Int) {
    val particleBlock = IntArray(n)
    /** Частицы блока b — [firstParticle[b], firstParticle[b + 1]). Шов своих частиц не имеет. */
    val firstParticle: IntArray
    val blockCount: Int
    /** Первый набор швов; наборы швов — [seam, buckets), свой на организм из нескольких блоков. */
    val seam: Int
    /** Всего наборов ограничений: блоки и наборы швов. */
    val buckets: Int
    /** Набор швов тела, которому принадлежит блок. */
    val seamBucket: IntArray
    /** Порядковый номер тела по блокам: блоки одного тела идут подряд. */
    val bodyRunOf: IntArray
    val bodyRuns: Int
    /** Блоки тел, которым нельзя параллельный обход (BodyState.computeSerialBlocks): их считает один поток, остальных — воркеры. */
    val serialBlock: BooleanArray
    var serialBlocks = 0
    val links: DenseBlockIndex
    val areas: DenseBlockIndex
    val limits: BlockIndex
    val bones: BlockIndex
    val bends: BlockIndex
    val flaps: BlockIndex
    /** Рёбра контура (LocalBoundaryConstraints) по блокам: ориентации со спящими. */
    val edges: BlockIndex
    val triangleSlot = IntArray(triangleCapacity) { -1 }
    private val limitQueue = IntArray(linkCapacity)
    private var activeLimitBlock = -1
    private var queueSize = 0

    init {
        // Организмы из одной частицы (каждая частица стенки, свободные частицы) идут подряд
        // в общий блок до blockSize. Раньше каждый был своим блоком: в мире 256×256 с
        // девятью листьями 2075 блоков вместо 30, тысяча крошечных задач на чётность через
        // общий курсор 64 раза за тик. Замер 05.11 (perfCheck -Psleep=0, 9 листьев, 8 воркеров):
        // 28.4 → 22.6–23.2 мс/тик, один лист 11.5 → 7.7, один поток 97 → 87. Связей внутри
        // одиночного организма нет, спящая одиночная частица в integrateRange — пустая работа.
        // Числа мира меняются только сдвигом чётности следующих блоков: старая запись,
        // проигранная с подогнанной чётностью, сошлась побитово (bugReplayCheck, 10 361 сверка).
        var maxBody = -1
        for (i in 0 until n) if (bodyOf[i] > maxBody) maxBody = bodyOf[i]
        val bodySize = IntArray(maxBody + 1)
        for (i in 0 until n) if (bodyOf[i] >= 0) bodySize[bodyOf[i]]++
        var block = -1; var size = blockSize; var body = -1; var prevSingle = false
        for (i in 0 until n) {
            val o = bodyOf[i]
            val single = o >= 0 && bodySize[o] == 1
            if (size == blockSize || (o != body && !(single && prevSingle))) { block++; size = 0 }
            body = o; prevSingle = single
            particleBlock[i] = block; size++
        }
        blockCount = block + 1; seam = blockCount
        // Блок — непрерывный диапазон индексов: номер растёт только вперёд по частицам.
        firstParticle = IntArray(blockCount + 1)
        for (i in n - 1 downTo 0) firstParticle[particleBlock[i]] = i
        firstParticle[blockCount] = n
        // Швы — свой набор у каждого тела из нескольких блоков: тела не делят частицы,
        // поэтому их швы считаются параллельно, а порядок внутри тела прежний.
        seamBucket = IntArray(blockCount)
        bodyRunOf = IntArray(blockCount)
        var seams = 0; var runs = 0; var b = 0
        while (b < blockCount) {
            val o = bodyOf[firstParticle[b]]
            var e = b + 1
            while (e < blockCount && bodyOf[firstParticle[e]] == o) e++
            val bucket = if (e - b > 1) seam + seams++ else seam
            for (k in b until e) { seamBucket[k] = bucket; bodyRunOf[k] = runs }
            runs++
            b = e
        }
        bodyRuns = runs
        serialBlock = BooleanArray(blockCount)
        buckets = seam + maxOf(1, seams)
        links = DenseBlockIndex(linkCapacity, buckets)
        limits = BlockIndex(linkCapacity, buckets)
        areas = DenseBlockIndex(triangleCapacity, buckets)
        bones = BlockIndex(boneCount, buckets)
        bends = BlockIndex(maxOf(n, 16), buckets)
        flaps = BlockIndex(maxOf(n, 16), buckets)
        edges = BlockIndex(maxOf(n, 16), buckets)
    }

    /**
     * Блоки каждой чётности: сначала самые тяжёлые. Блоки одной чётности независимы, так что
     * порядок раздачи не влияет на результат, а воркер, освободившийся последним, берёт
     * маленький блок вместо большого и барьер ждёт его меньше. Вес — частицы и ограничения
     * на момент сборки (разрывы его почти не меняют).
     */
    var dispatchOrder: Array<IntArray> = arrayOf(IntArray(0), IntArray(0))
        private set

    fun buildDispatchOrder() {
        dispatchOrder = Array(2) { parity ->
            val keys = LongArray((blockCount - parity + 1) / 2) { k ->
                val block = parity + 2 * k
                val weight = firstParticle[block + 1] - firstParticle[block] + links.liveCount[block] + areas.liveCount[block]
                ((Int.MAX_VALUE - weight).toLong() shl 32) or block.toLong()
            }
            keys.sort()
            IntArray(keys.size) { keys[it].toInt() }
        }
    }

    /**
     * Сколько бодрствующих частиц в блоке. Ноль — блок целиком спит, и решатель его
     * пропускает: все его частицы заморожены, двигать нечего. Ведёт BodySleep при
     * засыпании и пробуждении организма; шов не считается и не пропускается.
     */
    val awake = IntArray(blockCount + 1)

    fun hasSeams(): Boolean {
        for (s in seam until buckets) if (links.liveCount[s] > 0 || areas.liveCount[s] > 0 ||
            limits.head[s] >= 0 || bones.head[s] >= 0 || bends.head[s] >= 0 || flaps.head[s] >= 0) return true
        return false
    }

    fun blockOf(a: Int, b: Int): Int =
        if (particleBlock[a] == particleBlock[b]) particleBlock[a] else seamBucket[particleBlock[a]]

    fun blockOf(a: Int, b: Int, c: Int): Int {
        val block = blockOf(a, b)
        return if (block == particleBlock[c]) block else seamBucket[particleBlock[a]]
    }

    /**
     * СВЯЗИ КОНТУРА У ПОРОГА ПРЕДЕЛА ДЛИНЫ, по наборам: nearItems[набор][0 until nearCount[набор]].
     *
     * Предел длины проверялся трижды за подшаг по всем связям контура: в блоке сразу после связей, в блоке
     * после площадей и костей, общий проход после швов. Замер 10.11, 34 червя: на раннем проходе 79% связей
     * короче 0.97 предела, и из них к позднему проходу блока за предел уходит 0.1% нарушений. Поэтому ранний
     * проход (он идёт по всем) складывает сюда связи ближе запаса к порогу (LIMIT_NEAR_*), а поздний проход
     * блока идёт только по списку: впятеро меньше чтений. Пропущенное им нарушение снимает общий проход.
     *
     * ОБЩИЙ ПРОХОД ПО СПИСКУ — ОТВЕРГНУТО (10.11). К нему за предел уходит 0.3% нарушений, а на таране
     * медузы (physicsCheck) тела проходили друг сквозь друга: 11.9 связи за встречное тело при пороге 1, по
     * всему контуру — 0. Швы и кости через границу блока тянут связи уже после проходов блока, а общий
     * проход — последний перед контактами.
     *
     * Список собирается заново каждым ранним проходом блока и читается только поздним проходом того же блока
     * в том же подшаге. В состояние мира не входит, но его состав и порядок одинаковы при любом числе
     * воркеров: критерий в однопоточном (solveBlockLimits) и параллельном (solveBlockLimitsParallel)
     * сборщиках записан одинаково, связь события вносит доработка. Блок обходит один воркер; счётчик он пишет
     * один раз в конце прохода: соседние блоки одной чётности лежат в одной кэш-линии. Объём: ~6 тыс. связей
     * на 100k клеток — 25 КБ на весь мир.
     */
    val nearItems = Array(buckets) { IntArray(0) }
    val nearCount = IntArray(buckets)

    /** Вдвое больше места в списке у порога набора [bucket]; первые [n] записей переносятся. */
    fun growNear(bucket: Int, n: Int): IntArray {
        val items = nearItems[bucket].copyOf(maxOf(16, n * 2))
        nearItems[bucket] = items
        return items
    }

    fun addNear(bucket: Int, c: Int) {
        val n = nearCount[bucket]
        val items = if (n == nearItems[bucket].size) growNear(bucket, n) else nearItems[bucket]
        items[n] = c; nearCount[bucket] = n + 1
    }

    fun addLimit(c: Int, a: Int, b: Int) {
        if (limits.owner[c] >= 0) return
        val block = blockOf(a, b)
        limits.add(c, block)
        // Новый край контура открыт разрывом и, скорее всего, натянут. Ранний проход своего блока проверит его
        // из очереди; поздний (очереди у него нет) — из списка у порога. Запись в список другого блока лишняя,
        // но безвредна: этот список либо соберёт заново его ранний проход, либо до следующего подшага не читают.
        if (activeLimitBlock == block) limitQueue[queueSize++] = c else addNear(block, c)
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
