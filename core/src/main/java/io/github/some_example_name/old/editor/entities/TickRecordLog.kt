package io.github.some_example_name.old.editor.entities

import it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * Запись роста в редакторе по тикам: на каждом тике — ряд записей одной ширины (по записи
 * на клетку, нейрон, нейросвязь), запись — [width] чисел Int (Float — битами, Byte и
 * Boolean — числом).
 *
 * Почему не снимок всего ряда на каждом тике, как было. Отчёт игрока 11.11 (Samsung
 * SM-S948U, куча 256 МБ): открытие Megalodon в редакторе падало с OutOfMemoryError.
 * Megalodon растёт 2135 тиков до 5120 клеток, снимки давали 7.3 млн записей клеток — 273 МБ
 * (замер :lwjgl3:editorMemoryCheck). А за тик роста в редакторе меняется несколько клеток
 * (деление, мутация), остальные остаются как были. Поэтому здесь хранятся:
 *  - опорные кадры — ряд целиком;
 *  - между ними — только изменившиеся записи: номер в ряду и сама запись.
 * Опорный кадр пишется, когда изменений с прошлого кадра набралось больше, чем записей в
 * ряду. Тогда сборка любого тика стоит не больше двух рядов, а кадры по памяти не дороже
 * самих изменений: всё вместе — не больше трёх рядов на каждое изменение.
 *
 * Чтение — через [view]: ряд тика собирается в буфер при первом обращении и запоминается.
 * Буферов три: редактор читает два тика сразу (текущий и следующий шаг, см.
 * DIGenomeEditorContainer.nextStageTick), а третий — запас, чтобы чтение тика внутри
 * обхода другого не затёрло ряд, который ещё обходят.
 *
 * Запись идёт в два шага: [beginTick] отдаёт буфер под ряд, его заполняют, [endTick]
 * сравнивает его с прошлым тиком и сохраняет разницу.
 */
class TickRecordLog(val width: Int) {

    private var current = IntArray(0)
    private var previous = IntArray(0)
    private var previousCount = 0
    private var pendingCount = -1

    /** По тику: записей в ряду, начало его изменений, номер его опорного кадра. */
    private val counts = IntArrayList()
    private val deltaStart = IntArrayList()
    private val keyframeOfTick = IntArrayList()

    private var deltaSlots = IntArray(0)
    private var deltaData = IntArray(0)
    private var deltaSize = 0

    /** По опорному кадру: его тик и начало в [keyframeData] (в записях). */
    private val keyframeTick = IntArrayList()
    private val keyframeStart = IntArrayList()
    private var keyframeData = IntArray(0)
    private var keyframeSize = 0

    private var changesSinceKeyframe = 0
    private val changed = IntArrayList()

    private val viewTick = intArrayOf(-1, -1, -1)
    private val viewAge = LongArray(3)
    private val viewData = arrayOf(IntArray(0), IntArray(0), IntArray(0))
    private var age = 0L

    val tickCount: Int get() = counts.size

    fun count(tick: Int): Int = counts.getInt(tick)

    fun reset() {
        previousCount = 0
        pendingCount = -1
        counts.clear()
        deltaStart.clear()
        keyframeOfTick.clear()
        deltaSize = 0
        keyframeTick.clear()
        keyframeStart.clear()
        keyframeSize = 0
        changesSinceKeyframe = 0
        viewTick.fill(-1)
    }

    /** Буфер под ряд из [count] записей: запись r занимает [r * width, (r + 1) * width). */
    fun beginTick(count: Int): IntArray {
        val need = count * width
        if (current.size < need) current = IntArray(grow(current.size, need))
        pendingCount = count
        return current
    }

    fun endTick() {
        val count = pendingCount
        check(count >= 0) { "endTick без beginTick" }
        pendingCount = -1
        val tick = counts.size

        changed.clear()
        for (r in 0 until count) if (r >= previousCount || !sameAsPrevious(r)) changed.add(r)

        counts.add(count)
        deltaStart.add(deltaSize)
        if (tick == 0 || changesSinceKeyframe + changed.size > count) {
            writeKeyframe(tick, count)
        } else {
            appendChanged()
            changesSinceKeyframe += changed.size
        }
        keyframeOfTick.add(keyframeTick.size - 1)

        val swap = previous
        previous = current
        current = swap
        previousCount = count
    }

    /**
     * Ряд тика [tick]: запись r — с r * width, записей [count]. null — такого тика нет.
     * Буфер принадлежит записи: его нельзя менять и держать дольше, чем до следующего [view].
     */
    fun view(tick: Int): IntArray? {
        if (tick < 0 || tick >= counts.size) return null
        age++
        var slot = 0
        for (s in 0 until 3) {
            if (viewTick[s] == tick) {
                viewAge[s] = age
                return viewData[s]
            }
            if (viewAge[s] < viewAge[slot]) slot = s
        }
        materialize(tick, slot)
        viewTick[slot] = tick
        viewAge[slot] = age
        return viewData[slot]
    }

    /** Байты массивов записи, по ёмкости. */
    fun byteSize(): Long {
        var ints = current.size.toLong() + previous.size + deltaSlots.size + deltaData.size + keyframeData.size
        ints += counts.elements().size + deltaStart.elements().size + keyframeOfTick.elements().size
        ints += keyframeTick.elements().size + keyframeStart.elements().size + changed.elements().size
        for (d in viewData) ints += d.size
        return ints * 4
    }

    private fun sameAsPrevious(r: Int): Boolean {
        val o = r * width
        for (k in o until o + width) if (current[k] != previous[k]) return false
        return true
    }

    private fun writeKeyframe(tick: Int, count: Int) {
        val need = (keyframeSize + count) * width
        if (keyframeData.size < need) keyframeData = keyframeData.copyOf(grow(keyframeData.size, need))
        System.arraycopy(current, 0, keyframeData, keyframeSize * width, count * width)
        keyframeTick.add(tick)
        keyframeStart.add(keyframeSize)
        keyframeSize += count
        changesSinceKeyframe = 0
    }

    private fun appendChanged() {
        val n = changed.size
        if (deltaSlots.size < deltaSize + n) deltaSlots = deltaSlots.copyOf(grow(deltaSlots.size, deltaSize + n))
        val need = (deltaSize + n) * width
        if (deltaData.size < need) deltaData = deltaData.copyOf(grow(deltaData.size, need))
        for (i in 0 until n) {
            val r = changed.getInt(i)
            deltaSlots[deltaSize] = r
            System.arraycopy(current, r * width, deltaData, deltaSize * width, width)
            deltaSize++
        }
    }

    private fun materialize(tick: Int, slot: Int) {
        val count = counts.getInt(tick)
        if (viewData[slot].size < count * width) viewData[slot] = IntArray(grow(viewData[slot].size, count * width))
        val data = viewData[slot]

        val keyframe = keyframeOfTick.getInt(tick)
        val fromTick = keyframeTick.getInt(keyframe)
        val keyframeCount = minOf(counts.getInt(fromTick), count)
        System.arraycopy(keyframeData, keyframeStart.getInt(keyframe) * width, data, 0, keyframeCount * width)

        // Изменения после кадра — по порядку тиков, позднее перекрывает раннее. Записи за
        // пределом ряда этого тика пропускаются: ряд мог вырасти и снова сжаться. Каждая
        // запись ряда, которой нет в кадре, есть в изменениях: [endTick] пишет всё, что
        // выходит за ряд прошлого тика.
        for (d in deltaEnd(fromTick) until deltaEnd(tick)) {
            val r = deltaSlots[d]
            if (r < count) System.arraycopy(deltaData, d * width, data, r * width, width)
        }
    }

    /** Конец изменений тика [tick]: изменения тиков (a, b] лежат в [deltaEnd(a), deltaEnd(b)). */
    private fun deltaEnd(tick: Int): Int = if (tick + 1 < counts.size) deltaStart.getInt(tick + 1) else deltaSize

    private fun grow(size: Int, need: Int): Int = maxOf(need, size * 2, 16)
}
