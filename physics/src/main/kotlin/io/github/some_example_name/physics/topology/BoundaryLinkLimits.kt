package io.github.some_example_name.physics

/**
 * Рабочие связи текущего контура, включая края дыр и голые связи.
 *
 * Горячий обход читает только ids: 4 байта на граничную связь. Для решётки 100k
 * частиц это около 5 КБ вместо обхода ~300k связей. Обратный индекс холодный:
 * нужен лишь при разрыве. Обе ёмкости по числу всех связей (8 байт/связь, ~2.4 МБ
 * для 100k частиц), чтобы даже полное разрушение обходилось без аллокаций.
 *
 * Во время прохода новые края дописываются и проверяются в том же проходе.
 * Удаление оставляет дырку до endPass: перестановка последнего элемента могла бы
 * перенести ещё не проверенную связь за курсор и потерять её. Уплотнение нужно
 * только после удаления, а не в каждом из 144 проходов тика.
 */
internal class BoundaryLinkLimits {
    var ids = IntArray(0)
        private set
    private var position = IntArray(0)
    var count = 0
        private set
    private var inPass = false
    private var holes = false

    fun reset(capacity: Int) {
        if (position.size != capacity) {
            ids = IntArray(capacity)
            position = IntArray(capacity)
        }
        position.fill(-1)
        count = 0
        inPass = false
        holes = false
    }

    fun add(c: Int) {
        if (position[c] >= 0) return
        position[c] = count
        ids[count++] = c
    }

    fun remove(c: Int) {
        val slot = position[c]
        if (slot < 0) return
        position[c] = -1
        if (inPass) {
            ids[slot] = -1
            holes = true
        } else {
            val last = ids[--count]
            if (slot < count) {
                ids[slot] = last
                position[last] = slot
            }
        }
    }

    fun beginPass() { inPass = true }

    fun endPass() {
        inPass = false
        if (!holes) return
        var write = 0
        for (read in 0 until count) {
            val c = ids[read]
            if (c < 0) continue
            ids[write] = c
            position[c] = write++
        }
        count = write
        holes = false
    }
}

/** Только загрузка/reset и уже существующее уплотнение рабочих индексов в конце тика. */
internal fun BodyState.rebuildBoundaryLinkLimits() {
    boundaryLinkLimits.reset(conA.size)
    for (c in 0 until conCount)
        if (!conDead[c] && conBoundary[c]) boundaryLinkLimits.add(c)
}

/** Вызывается на затронутых рёбрах сразу после изменения triUse, без обхода тела. */
internal fun BodyState.updateBoundaryLinkLimit(e: Int) {
    val boundary = !baked.edgeDead[e] && baked.triUse[e] <= 1
    var c = conOfEdge[e]
    while (c >= 0) {
        conBoundary[c] = boundary
        if (boundary && !conDead[c]) boundaryLinkLimits.add(c) else boundaryLinkLimits.remove(c)
        constraintBlocks?.let { blocks ->
            if (conDead[c]) blocks.links.remove(c)
            if (boundary && !conDead[c]) blocks.addLimit(c, conA[c], conB[c]) else blocks.limits.remove(c)
        }
        c = conNextForEdge[c]
    }
}
