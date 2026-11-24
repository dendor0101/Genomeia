package io.github.some_example_name.physics

/**
 * Кусок поштучного прохода: 4096 частиц — ~200 КБ шести массивов double, порядка L2.
 * Мельче — больше кусков на воркер и ровнее нагрузка, но дороже раздача.
 *
 * 1024 ВМЕСТО 4096 (24.11.2026, находка 41). На мире в 6.5 тысячи клеток кусков было два на семь воркеров: проходы по
 * скоростям на Galaxy A21s шли с загрузкой 24% (6.3 мс за тик), учёт импульса — 9–25%. С куском в 1024 частицы
 * (24 КБ шести массивов float) их семь, загрузка лёгкого прохода по скоростям 88% (1.8 мс за тик). Размер куска входит
 * в результат (суммы импульса по организмам копятся кусками — comSums, TickMomentum.cancel), поэтому он постоянный и
 * от числа воркеров не зависит. Как куски ложатся на организмы — см. [PassChunks].
 */
internal const val PASS_CHUNK = 1024

/**
 * КУСКИ ПОШТУЧНЫХ ПРОХОДОВ — ПО ОРГАНИЗМАМ (25.11.2026, находка 42). Одна нарезка на всех: параллельные проходы
 * (ParallelPhysics), однопоточные суммы (comSumsSerial, TickMomentum.cancel) и зеркало SwimSolver берут куски отсюда.
 * Две нарезки — это два порядка сложения, то есть разные биты при разном числе воркеров.
 *
 * Зачем по организмам. Суммы импульса копятся по кускам: у каждого куска своя частичная сумма группы, частичные
 * складываются в порядке кусков. Пока куски резались от начала диапазона бодрствующих частиц, сумма тела зависела от
 * того, что лежит в мире перед ним: в игре перед телом 256 частиц пула, в стенде — ничего, и рез по телу приходился
 * на разные места. Побитовое «стенд = игра» (`standParityCheck`) с куском 1024 было потеряно (толчок расходился с
 * тика 77). Теперь рез зависит только от самого организма:
 *  - организм длиннее [PASS_CHUNK] режется на куски от СВОЕГО начала, каждый кусок только его;
 *  - короткие организмы целиком складываются в общий кусок, пока он не длиннее [PASS_CHUNK]. Группа импульса
 *    короткого организма начинает частичную сумму с нуля на его первой частице в любом соседстве.
 * Чего это не даёт: группа из нескольких организмов в касании складывается из частичных сумм по кускам, а куда
 * упадёт граница между короткими организмами, зависит от соседей перед ними. Для стендов с одним телом не важно.
 *
 * Организм — непрерывный кусок индексов (арена), его конец — [BodySleep.last]. После разрыва у старого организма
 * конец не уменьшается (inheritSplit), и он может накрыть кусок отделившегося: нарезка от этого остаётся разбиением
 * диапазона, а конец лежит в сохранении (BodySleep.write), так что после загрузки куски те же.
 *
 * Цена. Сборка — по организмам, а не по частицам: O(организмов + кусков), только когда сменились сон, топология или
 * число частиц (тот же ключ, что у OrgBuckets). Отвергнуто: искать границы организмов проходом по частицам при каждом
 * поштучном проходе — 100k чтений на проход, их десятки за тик.
 */
internal class PassChunks {
    var from = IntArray(64); private set
    var to = IntArray(64); private set
    var count = 0; private set
    private var builtStructure = -1
    private var builtSleep = -1
    private var builtN = -1

    fun valid(structure: Int, sleepVersion: Int, n: Int): Boolean =
        builtStructure == structure && builtSleep == sleepVersion && builtN == n

    fun markBuilt(structure: Int, sleepVersion: Int, n: Int) {
        builtStructure = structure; builtSleep = sleepVersion; builtN = n
    }

    /**
     * Куски диапазонов [spanStart, spanEnd). [last] — последняя частица организма; частица без организма
     * (номер меньше нуля) — сама себе организм.
     */
    fun build(spanCount: Int, spanStart: IntArray, spanEnd: IntArray, organismOf: IntArray, last: IntArray) {
        count = 0
        for (k in 0 until spanCount) {
            val end = spanEnd[k]
            var p = spanStart[k]
            var open = -1
            while (p < end) {
                val o = organismOf[p]
                var u = if (o >= 0 && o < last.size) last[o] + 1 else p + 1
                if (u <= p) u = p + 1
                if (u > end) u = end
                if (u - p > PASS_CHUNK) {
                    if (open >= 0) { add(open, p); open = -1 }
                    var q = p
                    while (q < u) { val t = minOf(u, q + PASS_CHUNK); add(q, t); q = t }
                } else {
                    if (open >= 0 && u - open > PASS_CHUNK) { add(open, p); open = -1 }
                    if (open < 0) open = p
                }
                p = u
            }
            if (open >= 0) add(open, end)
        }
    }

    /**
     * Без сна (зеркало SwimSolver, тесты): весь мир одним диапазоном, концы организмов — проходом по частицам.
     * Совпадает с нарезкой BodyState, у которого никто не спит: BodySleep.reset считает last так же.
     */
    fun buildWhole(n: Int, organismOf: IntArray, lastScratch: IntArray) {
        java.util.Arrays.fill(lastScratch, -1)
        for (i in 0 until n) { val o = organismOf[i]; if (o >= 0 && o < lastScratch.size) lastScratch[o] = i }
        wholeStart[0] = 0; wholeEnd[0] = n
        build(1, wholeStart, wholeEnd, organismOf, lastScratch)
    }

    private val wholeStart = IntArray(1)
    private val wholeEnd = IntArray(1)

    private fun add(a: Int, b: Int) {
        if (count == from.size) { from = from.copyOf(count * 2); to = to.copyOf(count * 2) }
        from[count] = a; to[count] = b; count++
    }
}

/** Куски поштучных проходов мира: см. [PassChunks]. Диапазоны бодрствующих частиц — свежие. */
internal fun BodyState.passChunks(): PassChunks {
    prepareAwakeSpans()
    val pc = passChunkCache
    val s = sleep
    if (!pc.valid(structureVersion, s.version, n)) {
        pc.build(s.spanCount, s.spanStart, s.spanEnd, organismOf, s.last)
        pc.markBuilt(structureVersion, s.version, n)
    }
    return pc
}
