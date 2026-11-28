package io.github.some_example_name.old.systems.physics

import io.github.some_example_name.old.systems.genomics.genome.BakedLayout

/**
 * Нарезка запечённого тела на куски для фазы связей: чётные куски считаются параллельно,
 * потом нечётные (LinkPhysicsSystem.iterateLinksInParallel).
 *
 * ЗАЧЕМ
 * -----
 * Раньше организм целиком был одной работой. Крупное тело («Megalodon», 5120 клеток и
 * 17880 связей) считал один поток, пока остальные ждали барьера: замер 19.10 — фаза связей
 * с одним таким телом 0.79 мс при четырёх воркерах, почти как с четырьмя (1.00 мс).
 *
 * ПОЧЕМУ ЧЁТНЫЕ И НЕЧЁТНЫЕ НЕ ГОНЯЮТСЯ
 * -----------------------------------
 * Связь и треугольник пишут только в свои клетки (скорости частиц, энергия, углы,
 * parentIndex). Связи в арене лежат по возрастанию меньшего слота своих концов, и разница
 * слотов концов не больше ширины ленты [band] — её RCM и держит маленькой (у «Megalodon»
 * 99). Кусок k — связи с меньшим слотом в [k·W, (k+1)·W), значит он трогает клетки только
 * в [k·W, (k+1)·W + band). При W ≥ band куски k и k+2 не пересекаются, и одновременно
 * идущие чётные куски не делят ни одной клетки. Треугольники — то же самое: их вершины
 * попарно связаны, поэтому размах треугольника тоже не больше [band].
 *
 * Порядок записей в клетку на стыке кусков — сначала чётный, потом нечётный — не зависит от
 * того, кто какой кусок взял, поэтому результат детерминирован при любом числе воркеров.
 *
 * Считается один раз на раскладку (при её чтении), в мире только читается.
 */
class LayoutChunks private constructor(
    /** W: сколько слотов клеток в куске. */
    val slotsPerChunk: Int,
    /** Сколько кусков; 1 — тело не режется. */
    val count: Int,
    /** Кусок k — связи арены [linkStart[k], linkStart[k + 1]) (смещения от начала арены). */
    val linkStart: IntArray,
    /** То же для треугольников раскладки. */
    val triangleStart: IntArray,
    /** Наибольшая разница слотов концов связи и вершин треугольника. */
    val band: Int
) {
    companion object {
        /**
         * Меньше куска не бывает, даже если лента уже. Кусок в 256 клеток — это ~900
         * связей и ~550 треугольников, десятки микросекунд: раздача работы (один общий
         * курсор) на этом фоне незаметна, а у «Megalodon» таких кусков 20 — хватает на
         * любое число воркеров.
         */
        const val MIN_CHUNK_SLOTS = 256

        private val SINGLE = LayoutChunks(Int.MAX_VALUE, 1, IntArray(0), IntArray(0), 0)

        fun of(layout: BakedLayout): LayoutChunks {
            val cells = layout.cellsInLayout
            if (cells < 2 * MIN_CHUNK_SLOTS) return SINGLE

            val links = layout.linksInLayout
            val pairs = layout.linkPairsInSlotOrder
            val slotOf = layout.slotByCellGenomeId
            val linkLow = IntArray(links)
            var band = 0
            for (link in 0 until links) {
                val a = slotOf.get(pairs[2 * link])
                val b = slotOf.get(pairs[2 * link + 1])
                if (a == -1 || b == -1) return SINGLE
                linkLow[link] = minOf(a, b)
                band = maxOf(band, maxOf(a, b) - linkLow[link])
                // Нарезка держится на порядке связей по меньшему слоту (RCMSort.orderLinks).
                if (link > 0 && linkLow[link] < linkLow[link - 1]) return SINGLE
            }

            val triangles = layout.trianglesInLayout
            val slots = layout.triangleSlotsArray
            val triangleLow = IntArray(triangles)
            for (t in 0 until triangles) {
                val s0 = slots[3 * t]; val s1 = slots[3 * t + 1]; val s2 = slots[3 * t + 2]
                triangleLow[t] = minOf(s0, s1, s2)
                band = maxOf(band, maxOf(s0, s1, s2) - triangleLow[t])
                if (t > 0 && triangleLow[t] < triangleLow[t - 1]) return SINGLE
            }

            val width = maxOf(band, MIN_CHUNK_SLOTS)
            val count = (cells + width - 1) / width
            if (count < 2) return SINGLE

            return LayoutChunks(
                slotsPerChunk = width,
                count = count,
                linkStart = starts(linkLow, width, count),
                triangleStart = starts(triangleLow, width, count),
                band = band
            )
        }

        /** Для каждого куска — первый элемент, чей меньший слот уже в нём; плюс конец. */
        private fun starts(low: IntArray, width: Int, count: Int): IntArray {
            val start = IntArray(count + 1)
            var element = 0
            for (chunk in 0 until count) {
                while (element < low.size && low[element] < chunk * width) element++
                start[chunk] = element
            }
            start[count] = low.size
            return start
        }
    }
}
