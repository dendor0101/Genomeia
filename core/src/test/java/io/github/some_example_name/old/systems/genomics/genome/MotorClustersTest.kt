package io.github.some_example_name.old.systems.genomics.genome

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotorClustersTest {

    private fun flags(n: Int, vararg on: Int) = BooleanArray(n) { it in on }

    @Test
    fun `кластер — мышцы связанные с мотонейроном, через другую ткань он не идёт`() {
        // 0-1-2 мышцы, 3 мотонейрон на 1, 4 лист, 5 мышца за листом, 6-7 мышцы без мотонейрона.
        val a = intArrayOf(0, 1, 1, 2, 4, 6)
        val b = intArrayOf(1, 2, 3, 4, 5, 7)
        val r = MotorClusters.of(8, a, b, flags(8, 0, 1, 2, 5, 6, 7), flags(8, 3))
        assertEquals(listOf(0, 0, 0, 0, -1, -1, -1, -1), r.clusterOfSlot)
        assertEquals(listOf(3), r.motorNeuronSlots)
    }

    @Test
    fun `мотонейрон соединяет два куска мышц, а два мотонейрона — два кластера`() {
        // 0 и 2 мышцы по обе стороны мотонейрона 1; 3 — второй мотонейрон с мышцей 4.
        val a = intArrayOf(0, 1, 3)
        val b = intArrayOf(1, 2, 4)
        val r = MotorClusters.of(5, a, b, flags(5, 0, 2, 4), flags(5, 1, 3))
        assertEquals(listOf(0, 0, 0, 1, 1), r.clusterOfSlot)
        assertEquals(listOf(1, 3), r.motorNeuronSlots)
    }

    @Test
    fun `без мотонейрона раскладка пустая`() {
        val r = MotorClusters.of(2, intArrayOf(0), intArrayOf(1), flags(2, 0, 1), flags(2))
        assertTrue(r.clusterOfSlot.isEmpty() && r.motorNeuronSlots.isEmpty())
    }

    @Test
    fun `раскладка раздаёт мышцы кластера по возрастанию слота и переносит их с позой`() {
        // Цепочка 0-1-2-3 по cellGenomeId 10..13: мышцы 0, 2, 3, мотонейрон 1.
        val layout = BakedLayout(
            cellGenomeIdsInSlotOrder = listOf(10, 11, 12, 13),
            linkPairsInSlotOrder = listOf(10, 11, 11, 12, 12, 13),
            restX = listOf(0f, 1f, 2f, 3f), restY = List(4) { 0f }, restRadius = List(4) { 0.5f },
            restBone = List(4) { false }
        ).withMotorClusters(flags(4, 0, 2, 3), flags(4, 1))
        assertContentEquals(intArrayOf(1), layout.motorNeuronSlotsArray)
        assertContentEquals(intArrayOf(0), layout.motorNeuronClusterArray)
        assertContentEquals(intArrayOf(0, 3), layout.motorClusterMuscleStart)
        assertContentEquals(intArrayOf(0, 2, 3), layout.motorClusterMuscles)

        // Та же клетка в другом порядке слотов: поза и кластеры переезжают по cellGenomeId.
        val reordered = BakedLayout(
            cellGenomeIdsInSlotOrder = listOf(13, 12, 11, 10),
            linkPairsInSlotOrder = listOf(13, 12, 12, 11, 11, 10)
        ).withRestPoseFrom(layout)
        assertEquals(listOf(2), reordered.motorNeuronSlots)
        assertContentEquals(intArrayOf(0, 1, 3), reordered.motorClusterMuscles)
        // Без направлений при запекании волокон нет.
        assertTrue(layout.fiberBySlot.isEmpty())
    }

    @Test
    fun `волокно — стрелка ближайшего мотонейрона своего кластера и переезжает с позой`() {
        // Цепочка 0..5 по cellGenomeId 20..25: мышцы 0, 2, 3, 5; мотонейроны 1 (стрелка по x) и
        // 4 (стрелка по y, длина не единица — нормируется) в одном кластере.
        val n = 6
        val layout = BakedLayout(
            cellGenomeIdsInSlotOrder = (20 until 26).toList(),
            linkPairsInSlotOrder = listOf(20, 21, 21, 22, 22, 23, 23, 24, 24, 25),
            restX = List(n) { it.toFloat() }, restY = List(n) { 0f }, restRadius = List(n) { 0.5f },
            restBone = List(n) { false }
        ).withMotorClusters(flags(n, 0, 2, 3, 5), flags(n, 1, 4),
            dirX = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f), dirY = floatArrayOf(0f, 0f, 0f, 0f, 2f, 0f))
        assertEquals(listOf(1f, 0f, 0f, 1f), layout.motorNeuronFiber)
        val f = layout.fiberBySlot
        // Слоты 0..2 ближе к мотонейрону 1, слоты 3..5 — к мотонейрону 4.
        for (s in 0..2) assertEquals(1f to 0f, f[2 * s] to f[2 * s + 1], "слот $s")
        for (s in 3..5) assertEquals(0f to 1f, f[2 * s] to f[2 * s + 1], "слот $s")

        val reordered = BakedLayout(
            cellGenomeIdsInSlotOrder = (25 downTo 20).toList(),
            linkPairsInSlotOrder = listOf(25, 24, 24, 23, 23, 22, 22, 21, 21, 20)
        ).withRestPoseFrom(layout)
        // Мотонейрон 4 (id 24) теперь в слоте 1, мотонейрон 1 (id 21) — в слоте 4.
        assertEquals(listOf(1, 4), reordered.motorNeuronSlots)
        assertEquals(listOf(0f, 1f, 1f, 0f), reordered.motorNeuronFiber)
        val g = reordered.fiberBySlot
        for (s in 0..2) assertEquals(0f to 1f, g[2 * s] to g[2 * s + 1], "новый слот $s")
        for (s in 3..5) assertEquals(1f to 0f, g[2 * s] to g[2 * s + 1], "новый слот $s")
    }
}
