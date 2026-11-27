package io.github.some_example_name.render.pack

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Отсечение клеток по камере для отладочного режима (27.11): на GPU уходят только диапазоны, чья
 * рамка задевает кадр. Рамки собирает снимок вместе с инстансами (OpticalCullRanges): у клеток
 * диапазон — тело, у не-клеток — кусок их списка (террейн создаётся построчно, кусок — отрезок
 * ряда); рамка покрывает обе позы интерполяции. Что ни одним диапазоном не покрыто (клетки вне
 * арен), едет всегда.
 *
 * ЗАЧЕМ КОПИЯ, А НЕ ДИАПАЗОНЫ ПРИ ОТРИСОВКЕ
 * ----------------------------------------
 * В GLES 3.0 нет baseInstance, поэтому нарисовать диапазон инстансов можно, только переставив
 * указатели всех инстансных атрибутов, — десяток вызовов GL на тело и проход, при сотнях тел
 * это дороже выигрыша. Видимые диапазоны копируются в свой буфер — одной копией на тело, и
 * только когда пришёл новый снимок или поменялся набор видимых тел (камера сдвинулась так, что
 * тело вошло в кадр или вышло). Пока набор тот же, на GPU уже лежат ровно эти байты.
 *
 * Выигрыш — вершины на GPU (два прохода по клеткам с раздутым корпусом) и байты заливки при
 * приближении; на отдалении, когда видно всё, отдаётся сам буфер снимка — ни копии, ни цены.
 */
class VisibleCells {

    private var buffer: ByteBuffer = allocate(1024)
    private var hidden = BooleanArray(64)

    /** Из чего собран [buffer] и какие тела в нём скрыты: подпись набора скрытых и их число. */
    private var source: ByteBuffer? = null
    private var signature = 0L
    private var hiddenCount = -1

    /**
     * true — [select] вернул буфер, которого на GPU ещё нет (собран заново): залить.
     */
    var changed = false
        private set

    /**
     * Клетки кадра: [cells] (весь снимок, position..limit) без тел из [ranges], чья рамка не
     * задевает прямоугольник [left]..[right] × [bottom]..[top] (уже расширенный на корпус клетки).
     * [fresh] — содержимое [cells] новое с прошлого кадра. Возвращает сам [cells], если скрывать
     * нечего, иначе свой буфер.
     */
    fun select(
        cells: ByteBuffer,
        ranges: OpticalCullRanges,
        left: Float,
        bottom: Float,
        right: Float,
        top: Float,
        fresh: Boolean
    ): ByteBuffer {
        val n = ranges.count
        if (hidden.size < n) hidden = BooleanArray(maxOf(n, hidden.size * 2))
        var h = FNV_OFFSET
        var count = 0
        for (r in 0 until n) {
            val skip = !ranges.intersects(r, left, bottom, right, top)
            hidden[r] = skip
            if (skip) {
                count++
                h = (h xor (r + 1).toLong()) * FNV_PRIME
            }
        }
        if (count == 0) {
            // Видно всё — отдаём снимок как есть; следующий отсечённый кадр соберётся заново.
            source = null
            hiddenCount = -1
            changed = false
            return cells
        }
        if (!fresh && source === cells && count == hiddenCount && h == signature) {
            changed = false
            return buffer
        }
        build(cells, ranges)
        source = cells
        signature = h
        hiddenCount = count
        changed = true
        return buffer
    }

    /** Видимые тела и всё непокрытое диапазонами — подряд, в порядке снимка. */
    private fun build(cells: ByteBuffer, ranges: OpticalCullRanges) {
        val base = cells.position()
        val total = cells.remaining() / CellInstanceBuffer.STRUCT_SIZE
        var out = buffer
        if (out.capacity() < total * CellInstanceBuffer.STRUCT_SIZE) {
            out = allocate(maxOf(total, out.capacity() / CellInstanceBuffer.STRUCT_SIZE * 3 / 2))
            buffer = out
        }
        (out as Buffer).clear()
        val src = cells.duplicate()
        var from = 0
        for (r in 0 until ranges.count) {
            if (!hidden[r]) continue
            copy(src, base, from, ranges.first(r), out)
            from = ranges.end(r)
        }
        copy(src, base, from, total, out)
        (out as Buffer).flip()
    }

    private fun copy(src: ByteBuffer, base: Int, from: Int, to: Int, out: ByteBuffer) {
        if (to <= from) return
        (src as Buffer).limit(base + to * CellInstanceBuffer.STRUCT_SIZE)
        (src as Buffer).position(base + from * CellInstanceBuffer.STRUCT_SIZE)
        out.put(src)
    }

    private fun allocate(instances: Int): ByteBuffer =
        ByteBuffer.allocateDirect(instances.coerceAtLeast(1) * CellInstanceBuffer.STRUCT_SIZE).order(ByteOrder.nativeOrder())

    private companion object {
        const val FNV_OFFSET = -0x340d631b7bdddcdbL
        const val FNV_PRIME = 0x100000001b3L
    }
}
