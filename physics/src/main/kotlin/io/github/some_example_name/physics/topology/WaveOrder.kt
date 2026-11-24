package io.github.some_example_name.physics

/**
 * ПОРЯДОК «ВОЛНАМИ»: связи и треугольники тела лежат в памяти так, что соседние не делят частиц (24.11.2026,
 * решение автора: «делаем … связи „волнами“»; замеры — docs/physics/reference.md, находки 39 и 40).
 *
 * ЗАЧЕМ. Ядро связи — корень, три деления и четыре записи положений. В описании тела связи идут по номерам частиц, и
 * две трети соседних связей делят частицу (у кругов толпы и у настоящих геномов одинаково — 67%): следующая связь ждёт,
 * пока предыдущая запишет положение, и процессор, умеющий считать несколько связей разом, считает их по одной. Проба
 * ядра отдельно от решателя: связи ×2.1 на ПК, ×2.5 на большом ядре S24 Ultra, ×1.5 на среднем; треугольники ×2.3.
 *
 * ПОЧЕМУ БИТЫ ТЕ ЖЕ. Проход Гаусса—Зейделя зависит только от взаимного порядка ограничений с ОБЩЕЙ частицей. Волна
 * ограничения — на единицу больше наибольшей волны прежних ограничений его частиц; ограничения сортируются по волне
 * устойчиво. Любые два ограничения с общей частицей остаются в прежнем порядке — и при обходе вперёд, и назад, —
 * а переставляются только те, что друг друга не видят.
 *
 * ПОЧЕМУ ПЕРЕСТАВЛЯЮТСЯ САМИ ДАННЫЕ, А НЕ НОМЕРА В СПИСКЕ БЛОКА. План обхода через номера (связи на прежних местах,
 * список блока в порядке волн) пробовали дважды, 10.11 и 23.11: связи выходят МЕДЛЕННЕЕ на 8–14% — шесть массивов
 * связи читаются вразброс, и промахи съедают выигрыш. Когда переставлено само описание тела, массивы связей читаются
 * потоком, как и раньше.
 *
 * УЧАСТКИ. Волны считаются участками по [WAVE_WINDOW] ограничений: перестановка не уводит чтения частиц дальше
 * участка (около трёхсот клеток), и блок решателя по-прежнему читает свои частицы из кэша.
 *
 * Игра и стенды к связям физики по номеру не обращаются (разрывы и рёбра контура отдаются парами частиц), поэтому
 * перестановка делается один раз — на входе тела в мир (PhysicsWorld.addBody, load).
 */
const val WAVE_WINDOW = 1024

/**
 * Порядок «волнами» для ограничений с частицами [a], [b] и, у треугольников, [c] (null у связей): на месте k нового
 * порядка стоит ограничение order[k] прежнего. [n] — число частиц тела.
 */
fun waveOrder(n: Int, a: IntArray, b: IntArray, c: IntArray?, window: Int = WAVE_WINDOW): IntArray {
    val count = a.size
    val last = IntArray(n); val level = IntArray(count); val sizes = IntArray(count + 1)
    var height = 0; var base = 0
    for (k in 0 until count) {
        if (k % window == 0) base = height
        var l = maxOf(base, last[a[k]], last[b[k]])
        if (c != null && last[c[k]] > l) l = last[c[k]]
        level[k] = l
        last[a[k]] = l + 1; last[b[k]] = l + 1
        if (c != null) last[c[k]] = l + 1
        sizes[l]++
        if (l + 1 > height) height = l + 1
    }
    val cursor = IntArray(height + 1)
    for (l in 0 until height) cursor[l + 1] = cursor[l] + sizes[l]
    val order = IntArray(count)
    for (k in 0 until count) order[cursor[level[k]]++] = k
    return order
}

/** То же описание тела со связями и треугольниками в порядке «волнами»; клетки, кости и мышцы не тронуты. */
fun BodyDefinition.inWaveOrder(): BodyDefinition {
    val lo = waveOrder(count, linkA, linkB, null)
    val to = waveOrder(count, triA, triB, triC)
    var retireStart = linkRetireStart; var retireCells = linkRetireCells
    if (hasTempLinks) {
        // Временные связи роста: у каждой связи свой промежуток пар клеток — переезжает вместе со связью.
        retireStart = IntArray(linkCount + 1); retireCells = IntArray(linkRetireCells.size)
        var w = 0
        for (k in 0 until linkCount) {
            val l = lo[k]
            retireStart[k] = w
            for (s in linkRetireStart[l] until linkRetireStart[l + 1]) {
                retireCells[2 * w] = linkRetireCells[2 * s]; retireCells[2 * w + 1] = linkRetireCells[2 * s + 1]
                w++
            }
        }
        retireStart[linkCount] = w
    }
    return BodyDefinition(x, y, radius, isBone, isMuscle,
        IntArray(lo.size) { linkA[lo[it]] }, IntArray(lo.size) { linkB[lo[it]] },
        IntArray(to.size) { triA[to[it]] }, IntArray(to.size) { triB[to[it]] }, IntArray(to.size) { triC[to[it]] },
        FloatArray(to.size) { triRestArea2[to[it]] },
        boneClusters, muscleClusters, scale, retireStart, retireCells)
}
