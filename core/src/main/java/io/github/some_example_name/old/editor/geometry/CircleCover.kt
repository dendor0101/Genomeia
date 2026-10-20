package io.github.some_example_name.old.editor.geometry

import kotlin.math.PI

/**
 * Какие дуги одной окружности закрыты дисками соседей и какие остались свободны.
 *
 * Общая часть двух задач редактора: поиска места для новой клетки ([CellPlacement]) и
 * внешнего контура тела (baking/InteriorCells). Обе сводятся к одному: на окружности вокруг
 * клетки каждый сосед закрывает одну дугу, и нужно знать, что осталось открытым. Это точный
 * ответ, а не выборка по углам, поэтому щель любой ширины видна, а не только шире шага.
 *
 * Углы — в радианах, double: код работает только в редакторе (клик, сохранение генома),
 * а запас точности избавляет от волосяных щелей на стыках дуг.
 *
 * Свободная дуга помнит, ЧЕЙ диск её ограничивает с каждого конца ([freeStartOwner],
 * [freeEndOwner]): по этому контур тела переходит с окружности на окружность. -1 — конец
 * без хозяина (окружность свободна целиком).
 */
internal class CircleCover(capacity: Int) {

    private var starts = DoubleArray(capacity * 2)
    private var ends = DoubleArray(capacity * 2)
    private var owners = IntArray(capacity * 2)
    private var count = 0
    private var full = false

    var freeCount = 0
        private set
    var freeStart = DoubleArray(capacity + 1)
        private set
    var freeEnd = DoubleArray(capacity + 1)
        private set
    var freeStartOwner = IntArray(capacity + 1)
        private set
    var freeEndOwner = IntArray(capacity + 1)
        private set

    fun clear() {
        count = 0
        full = false
    }

    /** Окружность закрыта целиком (она внутри диска соседа). */
    fun blockAll() {
        full = true
    }

    /**
     * Сосед [owner] закрывает дугу [center] ± [half]. Дуга через ноль хранится двумя
     * кусками: так обход ниже идёт по прямой [0, 2π), без разворота по кругу.
     */
    fun block(center: Double, half: Double, owner: Int) {
        if (half >= PI) { full = true; return }
        if (half <= 0.0) return
        var start = center - half
        start -= TWO_PI * Math.floor(start / TWO_PI)
        val end = start + 2.0 * half
        ensureCapacity(count + 2)
        push(start, minOf(end, TWO_PI), owner)
        if (end > TWO_PI) push(0.0, end - TWO_PI, owner)
    }

    /**
     * Считает свободные дуги. Конец свободной дуги может быть больше 2π: дуга через ноль
     * склеивается в одну, а не режется.
     *
     * [minGap] — свободная дуга короче этого не считается свободной.
     */
    fun computeFree(minGap: Double) {
        freeCount = 0
        if (full) return
        if (count == 0) {
            ensureFreeCapacity(1)
            addFree(0.0, TWO_PI, -1, -1)
            return
        }
        sort()
        ensureFreeCapacity(count + 1)

        // Прямой проход по [0, 2π): reach — докуда уже закрыто от нуля.
        var reach = 0.0
        var reachOwner = -1
        for (k in 0 until count) {
            if (starts[k] > reach) addFree(reach, starts[k], reachOwner, owners[k])
            if (ends[k] > reach) {
                reach = ends[k]
                reachOwner = owners[k]
            }
        }
        if (reach < TWO_PI) addFree(reach, TWO_PI, reachOwner, -1)

        // Ноль — разрез, а не граница: дуги по обе стороны от него — одна дуга.
        if (freeCount >= 2 && freeStart[0] == 0.0 && freeEnd[freeCount - 1] == TWO_PI) {
            val last = freeCount - 1
            freeEnd[last] = freeEnd[0] + TWO_PI
            freeEndOwner[last] = freeEndOwner[0]
            removeFree(0)
        }

        // Волосяные щели убираются уже после склейки: дуга у разреза могла быть короткой
        // только наполовину.
        var w = 0
        for (k in 0 until freeCount) {
            if (freeEnd[k] - freeStart[k] <= minGap) continue
            freeStart[w] = freeStart[k]; freeEnd[w] = freeEnd[k]
            freeStartOwner[w] = freeStartOwner[k]; freeEndOwner[w] = freeEndOwner[k]
            w++
        }
        freeCount = w
    }

    private fun push(start: Double, end: Double, owner: Int) {
        starts[count] = start; ends[count] = end; owners[count] = owner
        count++
    }

    private fun addFree(start: Double, end: Double, startOwner: Int, endOwner: Int) {
        freeStart[freeCount] = start; freeEnd[freeCount] = end
        freeStartOwner[freeCount] = startOwner; freeEndOwner[freeCount] = endOwner
        freeCount++
    }

    private fun removeFree(index: Int) {
        for (k in index until freeCount - 1) {
            freeStart[k] = freeStart[k + 1]; freeEnd[k] = freeEnd[k + 1]
            freeStartOwner[k] = freeStartOwner[k + 1]; freeEndOwner[k] = freeEndOwner[k + 1]
        }
        freeCount--
    }

    /** Вставками по началу дуги: дуг — десяток-другой. */
    private fun sort() {
        for (i in 1 until count) {
            val s = starts[i]; val e = ends[i]; val o = owners[i]
            var j = i - 1
            while (j >= 0 && starts[j] > s) {
                starts[j + 1] = starts[j]; ends[j + 1] = ends[j]; owners[j + 1] = owners[j]
                j--
            }
            starts[j + 1] = s; ends[j + 1] = e; owners[j + 1] = o
        }
    }

    private fun ensureCapacity(size: Int) {
        if (size <= starts.size) return
        val n = maxOf(size, starts.size * 2)
        starts = starts.copyOf(n); ends = ends.copyOf(n); owners = owners.copyOf(n)
    }

    private fun ensureFreeCapacity(size: Int) {
        if (size <= freeStart.size) return
        val n = maxOf(size, freeStart.size * 2)
        freeStart = freeStart.copyOf(n); freeEnd = freeEnd.copyOf(n)
        freeStartOwner = freeStartOwner.copyOf(n); freeEndOwner = freeEndOwner.copyOf(n)
    }

    companion object {
        const val TWO_PI = 2.0 * PI
    }
}
