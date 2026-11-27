package io.github.some_example_name.render.pack

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Отсечение по камере: в буфере для GPU — видимые тела и всё непокрытое, в порядке снимка. */
class VisibleCellsTest {

    /** Снимок из [n] инстансов: в первом int инстанса — его номер. */
    private fun cells(n: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(n * CellInstanceBuffer.STRUCT_SIZE).order(ByteOrder.nativeOrder())
        for (i in 0 until n) b.putInt(i * CellInstanceBuffer.STRUCT_SIZE, i)
        return b
    }

    private fun ids(b: ByteBuffer): List<Int> =
        (0 until b.remaining() / CellInstanceBuffer.STRUCT_SIZE).map { b.getInt(b.position() + it * CellInstanceBuffer.STRUCT_SIZE) }

    /** Три тела: [0,3) у x=0, [3,5) у x=100, [6,9) у x=200; инстанс 5 и [9,11) — без тела. */
    private fun ranges() = OpticalCullRanges().apply {
        add(0, 3, -1f, -1f, 1f, 1f)
        add(3, 5, 99f, -1f, 101f, 1f)
        add(6, 9, 199f, -1f, 201f, 1f)
    }

    @Test
    fun hiddenBodiesAreDroppedAndUncoveredKept() {
        val v = VisibleCells()
        val snapshot = cells(11)
        val out = v.select(snapshot, ranges(), 90f, -10f, 110f, 10f, fresh = true)
        assertTrue(v.changed)
        assertEquals(listOf(3, 4, 5, 9, 10), ids(out))
    }

    @Test
    fun everythingVisibleReturnsSnapshotItself() {
        val v = VisibleCells()
        val snapshot = cells(11)
        assertSame(snapshot, v.select(snapshot, ranges(), -10f, -10f, 300f, 10f, fresh = true))
        assertFalse(v.changed)
    }

    @Test
    fun sameSetIsNotRebuiltUntilSnapshotOrSetChanges() {
        val v = VisibleCells()
        val snapshot = cells(11)
        val first = v.select(snapshot, ranges(), 90f, -10f, 110f, 10f, fresh = true)
        assertTrue(v.changed)
        // Камера чуть сдвинулась, набор тот же — заливать нечего.
        assertSame(first, v.select(snapshot, ranges(), 95f, -10f, 115f, 10f, fresh = false))
        assertFalse(v.changed)
        // Новый снимок — собрать заново.
        v.select(snapshot, ranges(), 95f, -10f, 115f, 10f, fresh = true)
        assertTrue(v.changed)
        // В кадр вошло третье тело.
        val wider = v.select(snapshot, ranges(), 95f, -10f, 205f, 10f, fresh = false)
        assertTrue(v.changed)
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 9, 10), ids(wider))
    }

    @Test
    fun snapshotWithOffsetPositionIsReadFromItsPosition() {
        val v = VisibleCells()
        val big = cells(12)
        big.position(CellInstanceBuffer.STRUCT_SIZE)
        val snapshot = big.slice().order(ByteOrder.nativeOrder())
        val out = v.select(snapshot, ranges(), 190f, -10f, 210f, 10f, fresh = true)
        // Номера сдвинуты на один: инстанс k снимка несёт k + 1.
        assertEquals(listOf(6, 7, 8, 9, 10, 11), ids(out))
    }
}
