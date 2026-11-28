package io.github.some_example_name.physics

/**
 * СЕТКА КАНДИДАТОВ С ПРИВЯЗКОЙ У КАЖДОЙ ВЕРШИНЫ: вершину можно вынуть и переложить,
 * не трогая остальных.
 *
 * Зачем. Прежняя широкая фаза держала один общий запас: стоило любой вершине мира
 * уехать от места постройки дальше половины запаса, перестраивался весь мир. На 100k
 * при гребке это 12 перестроек за тик по ~10 мс, хотя за подшаг уезжает около 11%
 * вершин. Здесь у каждой вершины своя точка привязки и свой след — ячейки пути
 * подшага на момент привязки, как и раньше. Уехавшая вершина перекладывается одна.
 *
 * Гарантия полноты та же: пока каждая вершина не дальше половины запаса от своей
 * привязки, две касающиеся вершины на привязках ближе стороны ячейки (сумма радиусов
 * плюс запас), то есть их следы в соседних ячейках, и перебор 3x3 пару найдёт.
 *
 * Устройство: открытая адресация «ключ ячейки → голова списка» и записи (вершина,
 * ячейка) со ссылками вперёд и назад, чтобы вынуть запись за O(1). Опустевшая ячейка
 * удаляется сразу (deleteCell). Таблица растёт удвоением; при росте и сдвиге номера
 * ячеек у записей переписываются по спискам. Порядок записей в ячейке на
 * итог не влияет: список пар потом упорядочивается канонически (BoundaryContacts).
 *
 * Память: 16 байт на ячейку таблицы и 16 байт на запись; на 100k (~26k вершин
 * контура, след 1–2 ячейки) около 2 МБ — порядка L2, обращения случайные только у
 * перекладываемых вершин.
 */
internal class AnchoredContactGrid(n: Int, capacity: Int = 1024) {
    private var keys = LongArray(capacity)
    private var used = BooleanArray(capacity)
    private var heads = IntArray(capacity)
    private var mask = capacity - 1
    private var occupied = 0

    private var entVert = IntArray(capacity)
    private var entCell = IntArray(capacity)
    private var entNext = IntArray(capacity)
    private var entPrev = IntArray(capacity)
    /** Следующая запись той же вершины (её след) или свободный список. */
    private var entOwnNext = IntArray(capacity)
    private var entCount = 0
    private var freeHead = -1

    /** Живых записей. */
    var live = 0; private set

    /** Первая запись следа вершины (по номеру вершины или её месту — [slots]), -1 — вершины в сетке нет. */
    private var footHead = IntArray(n) { -1 }

    /**
     * МЕСТНЫЕ НОМЕРА ВЕРШИН — у сетки острова (IslandRun.kt, широкая фаза острова между тиками). Сетка мира держит след
     * по номеру вершины (массив на весь мир), а сеток островов сотни, и держать каждой массив на весь мир нельзя.
     * У сетки острова след — по месту вершины в списке острова: slots[v] (общий массив мира, его пишет разбиение на
     * острова, и острова не делят вершин). null — сетка мира.
     */
    private var slots: IntArray? = null

    @Suppress("NOTHING_TO_INLINE")
    private inline fun foot(v: Int): Int { val s = slots; return if (s == null) v else s[v] }

    /** Пустая сетка острова на [size] вершин с местными номерами [slots]. */
    fun resetLocal(slots: IntArray, size: Int) {
        this.slots = slots
        if (footHead.size < size) footHead = IntArray(maxOf(size, footHead.size * 2))
        java.util.Arrays.fill(footHead, 0, size, -1)
        used.fill(false); occupied = 0
        entCount = 0; freeHead = -1; live = 0
    }

    /** Та же сетка острова на следующем тике: местные номера те же (тот же список вершин), массив [slots] — нынешний. */
    fun useSlots(slots: IntArray) { this.slots = slots }

    val ids: IntArray get() = entVert
    val next: IntArray get() = entNext

    /**
     * Все записи (вершина, ключ ячейки) в [outV]/[outK]; возвращает их число. Для
     * перераскладки по частям (BoundaryContacts.reshard): набор записей тот же.
     */
    fun liveEntries(outV: IntArray, outK: LongArray): Int {
        var c = 0
        for (s in keys.indices) {
            if (!used[s]) continue
            var e = heads[s]
            while (e >= 0) { outV[c] = entVert[e]; outK[c] = keys[s]; c++; e = entNext[e] }
        }
        return c
    }

    fun clear() {
        used.fill(false); occupied = 0
        // Следы — только у вершин, что лежат в сетке: проход по всем частицам мира (footHead.fill) на каждой полной
        // перестройке копии касаний острова (IslandRun.kt) стоил бы O(мира) на остров. Записи после freeHead — свободные.
        for (e in 0 until entCount) footHead[foot(entVert[e])] = -1
        entCount = 0; freeHead = -1; live = 0
    }

    private fun home(key: Long): Int = ((key * -0x61c8864680b583ebL) ushr 40).toInt() and mask

    private fun slot(key: Long): Int {
        var s = home(key)
        while (used[s] && keys[s] != key) s = (s + 1) and mask
        return s
    }

    fun first(key: Long): Int {
        val s = slot(key)
        return if (used[s]) heads[s] else -1
    }

    fun insert(v: Int, key: Long) {
        var s = slot(key)
        if (!used[s]) {
            if (occupied >= keys.size / 2) { grow(); s = slot(key) }
            used[s] = true; keys[s] = key; heads[s] = -1; occupied++
        }
        val e = takeEntry()
        entVert[e] = v; entCell[e] = s
        entPrev[e] = -1; entNext[e] = heads[s]
        if (heads[s] >= 0) entPrev[heads[s]] = e
        heads[s] = e
        val f = foot(v)
        entOwnNext[e] = footHead[f]; footHead[f] = e
        live++
    }

    /** Вынуть весь след вершины. */
    fun remove(v: Int) {
        val f = foot(v)
        var e = footHead[f]
        while (e >= 0) {
            val own = entOwnNext[e]
            val s = entCell[e]
            if (entPrev[e] >= 0) entNext[entPrev[e]] = entNext[e] else heads[s] = entNext[e]
            if (entNext[e] >= 0) entPrev[entNext[e]] = entPrev[e]
            entOwnNext[e] = freeHead; freeHead = e
            live--
            if (heads[s] < 0) deleteCell(s)
            e = own
        }
        footHead[f] = -1
    }

    /**
     * ОПУСТЕВШАЯ ЯЧЕЙКА УДАЛЯЕТСЯ СРАЗУ, со сдвигом хвоста цепочки назад (линейное
     * пробирование без надгробий). Без этого таблица копила ячейки, где тела давно
     * уплыли, и поиск соседей на 100k вышел вдвое дороже прежней сетки: 470 нс на
     * вершину против 250 — сплошные промахи по разросшейся таблице.
     */
    private fun deleteCell(start: Int) {
        var i = start
        used[i] = false; occupied--
        var j = i
        while (true) {
            j = (j + 1) and mask
            if (!used[j]) return
            val h = home(keys[j])
            // Ячейка j остаётся, если её законное место циклически в (i, j].
            val stays = if (i <= j) h in (i + 1)..j else h > i || h <= j
            if (stays) continue
            keys[i] = keys[j]; heads[i] = heads[j]; used[i] = true
            var e = heads[i]
            while (e >= 0) { entCell[e] = i; e = entNext[e] }
            used[j] = false
            i = j
        }
    }

    private fun takeEntry(): Int {
        if (freeHead >= 0) { val e = freeHead; freeHead = entOwnNext[e]; return e }
        if (entCount == entVert.size) {
            val cap = entCount * 2
            entVert = entVert.copyOf(cap); entCell = entCell.copyOf(cap)
            entNext = entNext.copyOf(cap); entPrev = entPrev.copyOf(cap)
            entOwnNext = entOwnNext.copyOf(cap)
        }
        return entCount++
    }

    private fun grow() {
        val oldKeys = keys; val oldUsed = used; val oldHeads = heads
        val size = keys.size shl 1
        keys = LongArray(size); used = BooleanArray(size); heads = IntArray(size); mask = size - 1
        for (i in oldKeys.indices) if (oldUsed[i]) {
            val s = slot(oldKeys[i])
            used[s] = true; keys[s] = oldKeys[i]; heads[s] = oldHeads[i]
            var e = heads[s]
            while (e >= 0) { entCell[e] = s; e = entNext[e] }
        }
    }
}
