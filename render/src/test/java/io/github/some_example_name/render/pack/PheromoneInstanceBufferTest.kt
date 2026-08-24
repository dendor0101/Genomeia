package io.github.some_example_name.render.pack

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Раскладка инстанса феромона против разбора из shaders/pheromone/pheromone_pc.vert.
 *
 * Ключевое свойство здесь — БИТОВАЯ точность. Данные едут в целочисленной текстуре
 * (RGBA32UI) именно потому, что во float-текстуре драйвер вправе канонизировать NaN,
 * а рядом с координатами лежит цвет: его биты вполне могут сложиться в NaN-паттерн.
 */
class PheromoneInstanceBufferTest {

    @Test
    fun `раскладка инстанса ровно 16 байт — один тексель`() {
        assertEquals(16, PheromoneInstanceBuffer.STRUCT_SIZE, "менять только вместе с pheromone_pc.vert")
    }

    @Test
    fun `float-поля переживают запись побитово`() {
        // uintBitsToFloat на GPU обязан вернуть ровно то, что положил putFloat.
        val buffer = PheromoneInstanceBuffer(initialCapacity = 2)
        buffer.begin(1)
        buffer.put(x = 123.456f, y = -0.000123f, a = 0.5f, color = 0xAABBCCDD.toInt())
        val data = buffer.end()

        assertEquals(123.456f, data.getFloat(0))
        assertEquals(-0.000123f, data.getFloat(4))
        assertEquals(0.5f, data.getFloat(8))
        assertEquals(0xAABBCCDD.toInt(), data.getInt(12))
    }

    @Test
    fun `биты цвета не трогаются, даже если складываются в NaN`() {
        // 0x7FC00000 — это канонический NaN, если прочитать его как float. Цвет обязан
        // доехать до шейдера ровно этими битами, поэтому путь и целочисленный.
        val nanLikeColor = 0x7FC00000
        val data = PheromoneInstanceBuffer(initialCapacity = 1).apply {
            begin(1)
            put(x = 1f, y = 2f, a = 3f, color = nanLikeColor)
        }.end()

        assertEquals(nanLikeColor, data.getInt(12))
    }

    @Test
    fun `буфер растёт и не теряет записанное`() {
        val buffer = PheromoneInstanceBuffer(initialCapacity = 2)
        buffer.begin()

        val total = 40
        repeat(total) { i -> buffer.put(i.toFloat(), 0f, 1f, i) }
        val data = buffer.end()

        assertEquals(total, buffer.count)
        assertTrue(buffer.capacity >= total)
        assertEquals(total * PheromoneInstanceBuffer.STRUCT_SIZE, data.remaining())

        repeat(total) { i ->
            val base = i * PheromoneInstanceBuffer.STRUCT_SIZE
            assertEquals(i.toFloat(), data.getFloat(base), "феромон $i потерялся при росте")
            assertEquals(i, data.getInt(base + 12))
        }
    }

    @Test
    fun `повторный end отдаёт те же данные`() {
        val buffer = PheromoneInstanceBuffer(initialCapacity = 2)
        buffer.begin(1)
        buffer.put(1f, 2f, 3f, 4)

        val first = buffer.end().remaining()
        assertEquals(first, buffer.end().remaining())
    }

    @Test
    fun `пустой кадр даёт пустой буфер, а не остатки прошлого`() {
        val buffer = PheromoneInstanceBuffer(initialCapacity = 4)
        buffer.begin(2)
        buffer.put(1f, 1f, 1f, 1)
        buffer.put(2f, 2f, 2f, 2)
        assertEquals(2, buffer.end().remaining() / PheromoneInstanceBuffer.STRUCT_SIZE)

        buffer.begin(0)
        assertEquals(0, buffer.end().remaining(), "прошлый кадр не должен просачиваться")
        assertEquals(0, buffer.count)
    }
}
