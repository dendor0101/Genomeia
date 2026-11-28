package io.github.some_example_name.render.pack

import kotlin.math.max
import kotlin.math.min

/** Заведомо ПОЛНЫЕ оптические группы в упакованном снимке, по возрастанию индексов.
 * Границы собираются при уже существующей записи клеток, не новым обходом мира в GL.
 * 24 байта/группу: 1000 тел занимают 24 КиБ; запрос читает последовательные массивы.
 * Пропуски между диапазонами разрешены: редактор, сироты и частицы проходят обычное
 * отсечение. Нельзя помечать половину группы: потеряется рельеф её видимой половины. */
class OpticalCullRanges {
    var count = 0; private set
    private var first = IntArray(16)
    private var end = IntArray(16)
    private var bounds = FloatArray(64)
    private var start = 0
    private var minX = 0f; private var minY = 0f
    private var maxX = 0f; private var maxY = 0f

    fun clear() { count = 0 }
    fun begin(firstIndex: Int) {
        start = firstIndex
        minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY
        maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY
    }
    /** Оба конца интерполяции: вошедший в кадр между тиками организм не пропадёт. */
    fun include(x: Float, y: Float, previousX: Float, previousY: Float) {
        minX = min(minX, min(x, previousX)); minY = min(minY, min(y, previousY))
        maxX = max(maxX, max(x, previousX)); maxY = max(maxY, max(y, previousY))
    }
    fun finish(endIndex: Int) {
        if (endIndex == start) return
        if (count == first.size) {
            first = first.copyOf(count * 2); end = end.copyOf(count * 2); bounds = bounds.copyOf(count * 8)
        }
        first[count] = start; end[count] = endIndex
        val o = count * 4
        bounds[o] = minX; bounds[o + 1] = minY; bounds[o + 2] = maxX; bounds[o + 3] = maxY
        count++
    }
    /** Готовая рамка участка [firstIndex, endIndex): так участки собираются после записи по воркерам. */
    fun add(firstIndex: Int, endIndex: Int, minX: Float, minY: Float, maxX: Float, maxY: Float) {
        if (count == first.size) {
            first = first.copyOf(count * 2); end = end.copyOf(count * 2); bounds = bounds.copyOf(count * 8)
        }
        first[count] = firstIndex; end[count] = endIndex
        val o = count * 4
        bounds[o] = minX; bounds[o + 1] = minY; bounds[o + 2] = maxX; bounds[o + 3] = maxY
        count++
    }
    fun first(range: Int) = first[range]
    fun end(range: Int) = end[range]
    /** Координаты камеры уже расширены на максимальный радиус и отростки. */
    fun intersects(range: Int, left: Float, bottom: Float, right: Float, top: Float): Boolean {
        val o = range * 4
        return bounds[o + 2] >= left && bounds[o] <= right && bounds[o + 3] >= bottom && bounds[o + 1] <= top
    }
}
