package io.github.some_example_name.old.systems.render

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Тройной буфер снимков: читатель всегда получает целый и самый свежий снимок, а слот,
 * который он держит, писатель не трогает — при любом соотношении частот.
 */
class TripleBufferTest {

    /** Снимок из двух половин: целый, если обе половины из одного тика. */
    private class Slot {
        @JvmField var first = 0L
        @JvmField var second = 0L
    }

    private fun buffer() = TripleBuffer(Slot(), Slot(), Slot())

    @Test
    fun `до первой публикации нового нет`() {
        val buffer = buffer()
        assertFalse(buffer.acquire())
    }

    @Test
    fun `читатель получает последний опубликованный, промежуточные пропадают`() {
        val buffer = buffer()
        for (tick in 1L..5L) {
            buffer.back().first = tick
            buffer.publish()
        }
        assertTrue(buffer.acquire())
        assertEquals(5L, buffer.front().first, "читатель обязан взять самый свежий снимок")
        assertFalse(buffer.acquire(), "второй раз тот же снимок не новый")
    }

    @Test
    fun `писатель никогда не пишет в слот читателя`() {
        val buffer = buffer()
        buffer.back().first = 1
        buffer.publish()
        buffer.acquire()
        val held = buffer.front()

        // Сколько бы тиков ни прошло, пока читатель рисует свой кадр, — его слот цел.
        repeat(10) {
            assertNotSame(held, buffer.back())
            buffer.back().first = 100L + it
            buffer.publish()
        }
        assertEquals(1L, held.first)
        assertSame(held, buffer.front())
    }

    @Test
    fun `под нагрузкой двух потоков снимок никогда не рвётся`() {
        // Ровно та беда, от которой ушли с двойного буфера: половина байт одного тика и
        // половина другого. Писатель гонит тики без пауз, читатель хватает снимки и проверяет,
        // что обе половины из одного тика и тики только растут.
        val buffer = buffer()
        val ticks = 200_000L
        val start = CountDownLatch(1)
        var torn = 0L
        var backwards = 0L
        var seen = 0L

        val writer = thread(name = "writer") {
            start.await()
            for (tick in 1L..ticks) {
                val slot = buffer.back()
                slot.first = tick
                slot.second = tick
                buffer.publish()
            }
        }
        val reader = thread(name = "reader") {
            start.await()
            var last = 0L
            while (last < ticks) {
                if (!buffer.acquire()) continue
                val slot = buffer.front()
                val a = slot.first
                // Пока читатель держит слот, писатель обязан его не трогать.
                repeat(20) { Thread.onSpinWait() }
                val b = slot.second
                if (a != b) torn++
                if (a <= last) backwards++
                last = a
                seen++
            }
        }
        start.countDown()
        writer.join(TimeUnit.SECONDS.toMillis(30))
        reader.join(TimeUnit.SECONDS.toMillis(30))

        assertEquals(0L, torn, "читатель видел снимок из двух разных тиков")
        assertEquals(0L, backwards, "читатель получил снимок старее уже виденного")
        assertTrue(seen > 0)
    }
}
