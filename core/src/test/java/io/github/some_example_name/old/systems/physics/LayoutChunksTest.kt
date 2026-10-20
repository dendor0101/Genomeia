package io.github.some_example_name.old.systems.physics

import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Куски чётные и нечётные: одновременно идущие куски не делят ни одной клетки. */
class LayoutChunksTest {

    /**
     * Полоса из [cells] клеток: связи i–i+1 и i–i+2 (ширина ленты 2), треугольники
     * (i, i+1, i+2). Всё уже в порядке слотов, как его кладёт RCMSort.
     */
    private fun strip(cells: Int): BakedLayout {
        val pairs = ArrayList<Int>()
        for (i in 0 until cells) {
            if (i + 1 < cells) { pairs += i; pairs += i + 1 }
            if (i + 2 < cells) { pairs += i; pairs += i + 2 }
        }
        val triangles = ArrayList<Int>()
        val areas = ArrayList<Float>()
        for (i in 0 until cells - 2) { triangles += i; triangles += i + 1; triangles += i + 2; areas += 1f }
        return BakedLayout(
            cellGenomeIdsInSlotOrder = List(cells) { it },
            linkPairsInSlotOrder = pairs,
            triangleSlots = triangles,
            triangleRestArea2 = areas
        )
    }

    @Test
    fun smallBodyIsNotSplit() {
        assertEquals(1, strip(LayoutChunks.MIN_CHUNK_SLOTS).chunks.count)
    }

    @Test
    fun chunksCoverEverythingAndEvenOnesNeverShareCells() {
        val layout = strip(1000)
        val chunks = layout.chunks
        assertEquals(2, chunks.band)
        assertEquals(LayoutChunks.MIN_CHUNK_SLOTS, chunks.slotsPerChunk)
        assertEquals(4, chunks.count)
        assertEquals(0, chunks.linkStart[0])
        assertEquals(layout.linksInLayout, chunks.linkStart[chunks.count])
        assertEquals(layout.trianglesInLayout, chunks.triangleStart[chunks.count])

        // Какие клетки трогает каждый кусок — связями и треугольниками.
        val touched = Array(chunks.count) { HashSet<Int>() }
        for (chunk in 0 until chunks.count) {
            for (link in chunks.linkStart[chunk] until chunks.linkStart[chunk + 1]) {
                touched[chunk] += layout.linkPairsInSlotOrder[2 * link]
                touched[chunk] += layout.linkPairsInSlotOrder[2 * link + 1]
            }
            for (t in chunks.triangleStart[chunk] until chunks.triangleStart[chunk + 1]) {
                for (k in 0..2) touched[chunk] += layout.triangleSlotsArray[3 * t + k]
            }
        }
        for (chunk in 0 until chunks.count - 2) {
            assertTrue(touched[chunk].intersect(touched[chunk + 2]).isEmpty(), "куски $chunk и ${chunk + 2}")
        }
    }

    @Test
    fun trianglesKnowTheirLinksAndLinksKnowTheirTriangles() {
        val layout = strip(10)
        // У треугольника (i, i+1, i+2) все три стороны — связи раскладки.
        for (t in 0 until layout.trianglesInLayout) {
            val edges = (0..2).map { layout.triangleLinkSlots[3 * t + it] }
            assertTrue(edges.all { it != -1 }, "треугольник $t")
            for (e in edges) {
                val on = (layout.trianglesOfLinkStart[e] until layout.trianglesOfLinkStart[e + 1])
                    .map { layout.trianglesOfLink[it] }
                assertTrue(t in on, "треугольник $t на связи $e")
            }
        }
        // Связь i–i+1 внутри полосы — сторона двух треугольников, крайняя — одного.
        val pairs = layout.linkPairsInSlotOrder
        val middle = (0 until layout.linksInLayout).first { pairs[2 * it] == 4 && pairs[2 * it + 1] == 5 }
        assertEquals(2, layout.trianglesOfLinkStart[middle + 1] - layout.trianglesOfLinkStart[middle])
        // Вокруг клетки посередине — три треугольника, на краю — один.
        assertEquals(3, layout.trianglesAroundSlot[5])
        assertEquals(1, layout.trianglesAroundSlot[0])
    }

    @Test
    fun unsortedLinksAreNotSplit() {
        val sorted = strip(1000)
        val pairs = sorted.linkPairsInSlotOrder.toMutableList()
        // Последнюю связь — в начало: порядок по меньшему слоту нарушен.
        val a = pairs.removeAt(pairs.size - 2)
        val b = pairs.removeAt(pairs.size - 1)
        pairs.add(0, b); pairs.add(0, a)
        assertEquals(1, sorted.copy(linkPairsInSlotOrder = pairs).chunks.count)
    }
}
