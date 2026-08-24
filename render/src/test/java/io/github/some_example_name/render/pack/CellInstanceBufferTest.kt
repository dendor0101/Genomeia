package io.github.some_example_name.render.pack

import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверка раскладки инстанса клетки против ТОГО ЖЕ разбора, что делает шейдер.
 *
 * Смысл этих тестов не в том, чтобы «покрыть класс», а в том, что раскладка живёт в двух
 * местах сразу — здесь и в shaders/debug/circle_pc.vert — и разъехаться они могут молча.
 * Поэтому распаковка ниже написана не «как удобно», а буквально повторяет шейдер:
 *
 *   vec2 pos = vec2(uintBitsToFloat(t0.x), uintBitsToFloat(t0.y));
 *   ex_R     = 0.05 + v1.w * 0.7;
 *   cosA     = v1.x * 2.0 - 1.0;
 *   seed     = float((packed2 >> 16u) & 0xFFFFu);
 *
 * Если кто-то поправит формулу с одной стороны — тест упадёт.
 */
class CellInstanceBufferTest {

    // ==================== Разбор, повторяющий шейдер ====================
    //
    // ВНИМАНИЕ: здесь намеренно ЛИТЕРАЛЫ, а не константы из CellInstanceBuffer.
    //
    // Тест сверяет Kotlin с ШЕЙДЕРОМ, а шейдер про константы Kotlin ничего не знает — в
    // нём стоят голые числа. Если бы декодер ниже брал RADIUS_SPAN из кодера, правка
    // этой константы прошла бы тест насквозь: обе стороны поехали бы синхронно, а
    // circle_pc.vert остался бы со старым 0.7 — то есть ровно та поломка, ради которой
    // тест и написан, оказалась бы незамеченной.
    //
    // Меняете число здесь — обязаны поменять его и в шейдере.

    /** unpackRGBA8 из circle_pc.vert: младший байт — x. */
    private fun unpackByte(packed: Int, index: Int): Float =
        ((packed ushr (index * 8)) and 0xFF) / 255f

    /** `cosA = v1.x * 2.0 - 1.0` */
    private fun shaderAngle(packed1: Int, index: Int): Float = unpackByte(packed1, index) * 2f - 1f

    /** `ex_R = 0.05 + v1.w * 0.7` */
    private fun shaderRadius(packed1: Int): Float = 0.05f + unpackByte(packed1, 3) * 0.7f

    /** `int cellType = int(round(v2.y * 255.0))` */
    private fun shaderCellType(packed2: Int): Int = ((packed2 ushr 8) and 0xFF)

    /** `float seed = float((packed2 >> 16u) & 0xFFFFu)` */
    private fun shaderSeed(packed2: Int): Int = (packed2 ushr 16) and 0xFFFF

    private fun readInstance(data: ByteBuffer, index: Int): Instance {
        val base = index * CellInstanceBuffer.STRUCT_SIZE
        return Instance(
            x = data.getFloat(base),
            y = data.getFloat(base + 4),
            color = data.getInt(base + 8),
            packed1 = data.getInt(base + 12),
            packed2 = data.getInt(base + 16)
        )
    }

    private data class Instance(
        val x: Float,
        val y: Float,
        val color: Int,
        val packed1: Int,
        val packed2: Int
    )

    // ==================== Тесты ====================

    @Test
    fun `раскладка инстанса ровно 32 байта и два текселя`() {
        assertEquals(32, CellInstanceBuffer.STRUCT_SIZE, "менять только вместе с circle_pc.vert")
        assertEquals(
            0,
            CellInstanceBuffer.STRUCT_SIZE % 16,
            "инстанс обязан быть целым числом текселей RGBA32UI"
        )
    }

    @Test
    fun `константы диапазонов совпадают с литералами в шейдере`() {
        // Прямая сверка с `ex_R = 0.05 + v1.w * 0.7` из circle_pc.vert.
        // Падение этого теста означает: либо константу поменяли, не тронув шейдер,
        // либо шейдер поменяли, не тронув константу. Оба случая — молча битая картинка.
        assertEquals(0.05f, CellInstanceBuffer.RADIUS_MIN, "circle_pc.vert: ex_R = 0.05 + ...")
        assertEquals(0.7f, CellInstanceBuffer.RADIUS_SPAN, "circle_pc.vert: ... + v1.w * 0.7")
    }

    @Test
    fun `записанная клетка читается обратно теми же формулами, что в шейдере`() {
        val buffer = CellInstanceBuffer(initialCapacity = 4)
        buffer.begin(1)
        buffer.putCell(
            x = 12.5f,
            y = -3.25f,
            color = 0x11223344,
            angleCos = 0.6f,
            angleSin = -0.8f,
            radius = 0.4f,
            energy = 5f,
            cellType = 7,
            noiseSeed = 12345
        )
        val data = buffer.end()

        assertEquals(1, buffer.count)
        assertEquals(CellInstanceBuffer.STRUCT_SIZE, data.remaining())

        val cell = readInstance(data, 0)

        // Координаты и цвет идут как есть, без потерь.
        assertEquals(12.5f, cell.x)
        assertEquals(-3.25f, cell.y)
        assertEquals(0x11223344, cell.color)

        // Углы и радиус — через байт, поэтому с допуском на квантование в 1/255.
        val quantum = 1f / 255f
        assertNear(0.6f, shaderAngle(cell.packed1, 0), 2 * quantum, "cos")
        assertNear(-0.8f, shaderAngle(cell.packed1, 1), 2 * quantum, "sin")
        assertNear(0.4f, shaderRadius(cell.packed1), 2 * quantum * CellInstanceBuffer.RADIUS_SPAN, "radius")

        assertEquals(7, shaderCellType(cell.packed2))
        assertEquals(12345, shaderSeed(cell.packed2))
    }

    @Test
    fun `ключ шума закреплён за клеткой, а не за позицией в буфере`() {
        // Ради этого свойства ключ и завели: он берётся из packed2, а не из gl_InstanceID.
        // Иначе рождение клетки в раннем слоте сдвигало бы все последующие, и текстуры
        // визуально крутились бы у всего тела, пока организм растёт.
        val buffer = CellInstanceBuffer(initialCapacity = 4)

        buffer.begin(2)
        buffer.putCell(0f, 0f, 0, 1f, 0f, 0.3f, 0f, 1, noiseSeed = 777)
        buffer.putCell(1f, 1f, 0, 1f, 0f, 0.3f, 0f, 1, noiseSeed = 999)
        val first = buffer.end()
        val seedAtSlot1 = shaderSeed(readInstance(first, 1).packed2)

        // Та же клетка, но теперь она первая в буфере.
        buffer.begin(1)
        buffer.putCell(1f, 1f, 0, 1f, 0f, 0.3f, 0f, 1, noiseSeed = 999)
        val second = buffer.end()
        val seedAtSlot0 = shaderSeed(readInstance(second, 0).packed2)

        assertEquals(999, seedAtSlot1)
        assertEquals(seedAtSlot1, seedAtSlot0, "ключ не должен зависеть от слота в буфере")
    }

    @Test
    fun `значения за границами диапазона зажимаются, а не переполняют соседнее поле`() {
        // Радиус больше максимума раньше дал бы байт > 255, а сдвиг на 24 затёр бы
        // старший байт packed1 мусором. Проверяем, что зажатие есть.
        val huge = CellInstanceBuffer.radiusByte(1000f)
        val negative = CellInstanceBuffer.radiusByte(-1000f)
        assertEquals(255, huge)
        assertEquals(0, negative)

        assertEquals(255, CellInstanceBuffer.angleByte(10f))
        assertEquals(0, CellInstanceBuffer.angleByte(-10f))
        assertEquals(255, CellInstanceBuffer.energyByte(1e9f))

        // Тип клетки за пределом байта не должен залезть в ключ шума.
        val packed = CellInstanceBuffer.packed2(energyByte = 0, cellType = 9999, noiseSeed = 0)
        assertEquals(0, shaderSeed(packed), "переполнение типа не должно попасть в seed")
        assertEquals(255, shaderCellType(packed))
    }

    @Test
    fun `ключ шума берёт младшие 16 бит и не портит тип клетки`() {
        val packed = CellInstanceBuffer.packed2(
            energyByte = 200,
            cellType = 13,
            noiseSeed = 0x1_2345 // не влезает в 16 бит
        )
        assertEquals(0x2345, shaderSeed(packed))
        assertEquals(13, shaderCellType(packed))
        assertEquals(200, packed and 0xFF)
    }

    @Test
    fun `буфер растёт при переполнении и не теряет уже записанное`() {
        // Редактор генома раньше вообще не проверял ёмкость перед записью: метод роста
        // был объявлен, но не вызывался ни разу. Здесь рост проверяется явно.
        val buffer = CellInstanceBuffer(initialCapacity = 2)
        buffer.begin()

        val total = 50
        repeat(total) { i ->
            buffer.putCell(
                x = i.toFloat(),
                y = 0f,
                color = i,
                angleCos = 1f,
                angleSin = 0f,
                radius = 0.3f,
                energy = 0f,
                cellType = 1,
                noiseSeed = i
            )
        }
        val data = buffer.end()

        assertEquals(total, buffer.count)
        assertTrue(buffer.capacity >= total)
        assertEquals(total * CellInstanceBuffer.STRUCT_SIZE, data.remaining())

        // Каждая клетка, записанная ДО пересоздания буфера, обязана уцелеть.
        repeat(total) { i ->
            val cell = readInstance(data, i)
            assertEquals(i.toFloat(), cell.x, "клетка $i потерялась при росте буфера")
            assertEquals(i, cell.color)
            assertEquals(i, shaderSeed(cell.packed2))
        }
    }

    @Test
    fun `повторный end отдаёт те же данные, а не пустой буфер`() {
        // Редактор генома между тиками буфер не перезаписывает, но кадр рисует каждый раз
        // и буфер запрашивает заново. Голый flip() во второй раз обнулил бы limit,
        // и клетки исчезли бы с экрана.
        val buffer = CellInstanceBuffer(initialCapacity = 4)
        buffer.begin(1)
        buffer.putCell(1f, 2f, 3, 1f, 0f, 0.3f, 0f, 1, noiseSeed = 42)

        val first = buffer.end()
        val bytesFirst = first.remaining()
        val second = buffer.end()

        assertEquals(bytesFirst, second.remaining())
        assertEquals(CellInstanceBuffer.STRUCT_SIZE, second.remaining())
        assertEquals(1f, readInstance(second, 0).x)
    }

    @Test
    fun `put и putCell дают одинаковые байты`() {
        // put — путь GL-потока (packed уже посчитаны потоком симуляции),
        // putCell — путь редактора. Разойтись они не имеют права.
        val viaPutCell = CellInstanceBuffer(initialCapacity = 1).apply {
            begin(1)
            putCell(5f, 6f, 0x778899AA.toInt(), -0.3f, 0.95f, 0.62f, 3.5f, 21, 4242)
        }.end()

        val packed1 = CellInstanceBuffer.packed1(
            cosByte = CellInstanceBuffer.angleByte(-0.3f),
            sinByte = CellInstanceBuffer.angleByte(0.95f),
            radiusByte = CellInstanceBuffer.radiusByte(0.62f)
        )
        val packed2 = CellInstanceBuffer.packed2(
            energyByte = CellInstanceBuffer.energyByte(3.5f),
            cellType = 21,
            noiseSeed = 4242
        )
        val viaPut = CellInstanceBuffer(initialCapacity = 1).apply {
            begin(1)
            put(5f, 6f, 0x778899AA.toInt(), packed1, packed2)
        }.end()

        assertEquals(viaPutCell.remaining(), viaPut.remaining())
        for (i in 0 until viaPutCell.remaining()) {
            assertEquals(viaPutCell.get(i), viaPut.get(i), "байт $i разошёлся")
        }
    }

    @Test
    fun `добивочные байты нулевые`() {
        // Второй тексель — packed2 плюс три нуля. Мусор в добивке в шейдер попадает
        // как t1.y/z/w; сейчас они не читаются, но полагаться на это не стоит.
        val data = CellInstanceBuffer(initialCapacity = 1).apply {
            begin(1)
            putCell(1f, 1f, -1, 1f, 1f, 0.5f, 9f, 255, 0xFFFF)
        }.end()

        assertEquals(0, data.getInt(20))
        assertEquals(0, data.getInt(24))
        assertEquals(0, data.getInt(28))
    }

    private fun assertNear(expected: Float, actual: Float, tolerance: Float, what: String) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$what: ожидалось ~$expected, получено $actual (допуск $tolerance)"
        )
    }
}
