package io.github.some_example_name.physics

/**
 * ПОРЯДОК ПО ЦВЕТАМ — ЗАКОН С 27.11.2026 (решение автора после стендов, находка 43; ради физики на видеокарте — опыт с
 * CUDA, ветка alpha-0.2.4-cuda-physics).
 *
 * Прежде ограничения набора (блока решателя или швов тела) решались по порядку списка, и у тел игроков они выстроены в
 * цепочки: ограничение ждёт прежних ограничений своих частиц. Мир тел геномов до 1000 клеток: связи — ~100 шагов на блок
 * (у худшего 415), площади — 83 (377), изгибы — 13 (42), створки — 16 (83). Процессору это не мешает, а карте даёт
 * столько же шагов с барьером.
 *
 * Порядок по цветам: ограничения набора жадно красятся по порядку списка (цвет — наименьший, которого ещё нет у прежних
 * ограничений с общей частицей), затем решаются цвет за цветом, внутри цвета — по порядку списка. Ограничения одного
 * цвета частиц не делят, поэтому порядок внутри цвета на биты не влияет, и карта решает цвет разом: 3–10 шагов на набор.
 * Назад (связи при sweepBackwards) — те же цвета с последнего.
 *
 * Это другой порядок Гаусса—Зейделя, чем до 27.11: другие биты. COLOR_ORDER=0 (BodyState.colorOrderOn) — прежний
 * порядок, только для сравнения. Раскраска — производное списка набора: пересобирается при первом обходе после любой
 * правки набора (разрыв, упаковка, перенос номера) и в состояние мира не входит. Стоит один проход по набору, и только
 * после правок; в самом обходе — то же ядро, номера из другого массива.
 */

/**
 * Раскраска [m] номеров из [src] с [from] (пустые места −1 пропускаются) в [dst] с [dstFrom]: по цветам, внутри цвета — в
 * прежнем порядке. [particles] кладёт частицы ограничения в out (до четырёх) и возвращает их число; [mask] — занятые цвета
 * частиц, на входе и на выходе нули. Возвращает число живых номеров. Редкий путь (после правок набора): выделение
 * временных массивов здесь допустимо.
 */
internal inline fun colorOrder(src: IntArray, from: Int, m: Int, dst: IntArray, dstFrom: Int, mask: LongArray,
                               particles: (id: Int, out: IntArray) -> Int): Int {
    val parts = IntArray(4)
    val colorOf = IntArray(m)
    val start = IntArray(MAX_COLORS + 1)
    var colors = 0; var live = 0
    for (k in 0 until m) {
        val id = src[from + k]
        if (id < 0) { colorOf[k] = -1; continue }
        val p = particles(id, parts)
        var busy = 0L
        for (q in 0 until p) busy = busy or mask[parts[q]]
        val free = busy.inv()
        if (free == 0L) colorOverflow(id)
        val bit = free and -free
        for (q in 0 until p) mask[parts[q]] = mask[parts[q]] or bit
        val color = java.lang.Long.numberOfTrailingZeros(bit)
        colorOf[k] = color; start[color + 1]++; live++
        if (color >= colors) colors = color + 1
    }
    for (c in 0 until colors) start[c + 1] += start[c]
    for (k in 0 until m) {
        val color = colorOf[k]
        if (color < 0) continue
        val id = src[from + k]
        dst[dstFrom + start[color]++] = id
        val p = particles(id, parts)
        for (q in 0 until p) mask[parts[q]] = 0L
    }
    return live
}

/** Цветов не больше, чем бит в Long: у частицы тела игрока ограничений одного рода около шести, жадной раскраске хватает 2·6 − 1. */
internal const val MAX_COLORS = 64

internal fun colorOverflow(id: Int): Nothing = error("порядок по цветам: у частиц ограничения $id больше $MAX_COLORS цветов")

/** Связи набора [block] по цветам: номера — layout.links.colorItems с links.start[block], их число — links.colorCount[block]. */
internal fun BodyState.coloredLinks(layout: ConstraintBlocks, block: Int): IntArray {
    val list = layout.links
    if (list.colorStale(block)) {
        val a = conA; val b = conB
        val from = list.start[block]
        val live = colorOrder(list.items, from, list.count[block], list.colorItems, from, layout.colorMask) { c, out ->
            out[0] = a[c]; out[1] = b[c]; 2
        }
        list.colorDone(block, live)
    }
    return list.colorItems
}

/** Площади (треугольники) набора [block] по цветам — как coloredLinks. */
internal fun BodyState.coloredAreas(layout: ConstraintBlocks, block: Int): IntArray {
    val list = layout.areas
    if (list.colorStale(block)) {
        val ta = triA; val tb = triB; val tc = triC
        val from = list.start[block]
        val live = colorOrder(list.items, from, list.count[block], list.colorItems, from, layout.colorMask) { t, out ->
            out[0] = ta[t]; out[1] = tb[t]; out[2] = tc[t]; 3
        }
        list.colorDone(block, live)
    }
    return list.colorItems
}

/** Изгибы набора [block] по цветам: номера — с нуля, их число — layout.bends.colorCount[block]. */
internal fun BodyState.coloredBends(layout: ConstraintBlocks, block: Int): IntArray {
    val list = layout.bends
    if (list.colorStale(block)) {
        val a = bendA; val b = bendB
        val src = list.dense(block); val m = list.denseCount[block]
        val live = colorOrder(src, 0, m, list.colorTarget(block, m), 0, layout.colorMask) { c, out ->
            out[0] = a[c]; out[1] = b[c]; 2
        }
        list.colorDone(block, live)
    }
    return list.colorItems(block)
}

/** Створки набора [block] по цветам. Вершина — тоже частица створки: ядро читает её положение (flapAngleRest). */
internal fun BodyState.coloredFlaps(layout: ConstraintBlocks, block: Int): IntArray {
    val list = layout.flaps
    if (list.colorStale(block)) {
        val a = flapA; val b = flapB; val v = flapV
        val src = list.dense(block); val m = list.denseCount[block]
        val live = colorOrder(src, 0, m, list.colorTarget(block, m), 0, layout.colorMask) { c, out ->
            out[0] = a[c]; out[1] = b[c]; out[2] = v[c]; 3
        }
        list.colorDone(block, live)
    }
    return list.colorItems(block)
}
