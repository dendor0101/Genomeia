package io.github.some_example_name.render.pack

import io.github.some_example_name.render.RenderFrame
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Буфер фигур оверлея обязан совпадать с тем, как его читают OverlayGeometry и
 * shaders/debug_view/overlay.vert. Расшифровка ниже повторяет шейдер буквально, как и в
 * CellInstanceBufferTest:
 *
 *   GL_UNSIGNED_BYTE + normalized  →  float = байт / 255.0
 *   kind   = int(byteValue(a_params.x))
 *   steps  = int(byteValue(a_params.y)) - 127;  signal = float(steps) / 127.0
 *   seed   = byteValue(a_params.z) / 256.0
 */
class OverlayInstanceBufferTest {

    private fun byteValue(packed: Int, index: Int): Int = (packed ushr (index * 8)) and 0xFF

    private fun shaderKind(params: Int): Int = byteValue(params, 0)

    private fun shaderSignal(params: Int): Float = (byteValue(params, 1) - 127) / 127f

    private class Figure(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val color: Int, val params: Int)

    private fun read(data: ByteBuffer, index: Int): Figure {
        val base = data.position() + index * OverlayInstanceBuffer.STRUCT_SIZE
        return Figure(
            x0 = data.getFloat(base + OverlayInstanceBuffer.OFFSET_FROM),
            y0 = data.getFloat(base + OverlayInstanceBuffer.OFFSET_FROM + 4),
            x1 = data.getFloat(base + OverlayInstanceBuffer.OFFSET_TO),
            y1 = data.getFloat(base + OverlayInstanceBuffer.OFFSET_TO + 4),
            color = data.getInt(base + OverlayInstanceBuffer.OFFSET_COLOR),
            params = data.getInt(base + OverlayInstanceBuffer.OFFSET_PARAMS)
        )
    }

    @Test
    fun `раскладка фигуры ровно 40 байт`() {
        assertEquals(40, OverlayInstanceBuffer.STRUCT_SIZE)
        assertEquals(0, OverlayInstanceBuffer.OFFSET_FROM)
        assertEquals(8, OverlayInstanceBuffer.OFFSET_TO)
        assertEquals(16, OverlayInstanceBuffer.OFFSET_COLOR)
        assertEquals(20, OverlayInstanceBuffer.OFFSET_PARAMS)
        assertEquals(24, OverlayInstanceBuffer.OFFSET_PREV_FROM)
        assertEquals(32, OverlayInstanceBuffer.OFFSET_PREV_TO)
    }

    @Test
    fun `прошлые концы лежат после params, по умолчанию — нынешние`() {
        val data = OverlayInstanceBuffer(initialCapacity = 1).apply {
            begin(2)
            put(0f, 0f, 1f, 1f, color = 7, params = 9, prevX0 = -1f, prevY0 = -2f, prevX1 = 3f, prevY1 = 4f)
            putRing(1f, 2f, 3f, 0)
        }.end()
        assertEquals(-1f, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_FROM))
        assertEquals(-2f, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_FROM + 4))
        assertEquals(3f, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_TO))
        assertEquals(4f, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_TO + 4))
        assertEquals(9, read(data, 0).params, "прошлые концы не задели params")

        // Кольцо стоит: прошлые центр и радиус — те же.
        val ring = OverlayInstanceBuffer.STRUCT_SIZE
        assertEquals(1f, data.getFloat(ring + OverlayInstanceBuffer.OFFSET_PREV_FROM))
        assertEquals(2f, data.getFloat(ring + OverlayInstanceBuffer.OFFSET_PREV_FROM + 4))
        assertEquals(3f, data.getFloat(ring + OverlayInstanceBuffer.OFFSET_PREV_TO), "радиус")
    }

    @Test
    fun `нулевой сигнал кодируется точно`() {
        // Ради этого смещение 127, а не 127.5: молчащая связь не должна получить ни одного
        // импульса. На Intel ноль однажды уже превращался в -6e-8 — см. overlay.vert.
        assertEquals(127, OverlayInstanceBuffer.signalByte(0f))
        assertEquals(0f, shaderSignal(OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_NEURAL, 0f, 5)))
        assertEquals(127, OverlayInstanceBuffer.signalByte(-0f))
        assertEquals(127, OverlayInstanceBuffer.signalByte(Float.NaN), "NaN — тишина, а не мусор")
        // Меньше половины шага — тоже тишина.
        assertEquals(127, OverlayInstanceBuffer.signalByte(0.003f))
        assertEquals(127, OverlayInstanceBuffer.signalByte(-0.003f))
    }

    @Test
    fun `сигнал зажимается в -1 1 и сохраняет знак`() {
        assertEquals(254, OverlayInstanceBuffer.signalByte(1f))
        assertEquals(0, OverlayInstanceBuffer.signalByte(-1f))
        assertEquals(254, OverlayInstanceBuffer.signalByte(250f), "огромный сигнал — полный, а не переполнение")
        assertEquals(0, OverlayInstanceBuffer.signalByte(Float.NEGATIVE_INFINITY))
        for (signal in listOf(-0.9f, -0.35f, 0.1f, 0.5f, 0.77f)) {
            val decoded = shaderSignal(OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_NEURAL, signal, 0))
            assertTrue(kotlin.math.abs(decoded - signal) <= 0.5f / 127f + 1e-6f, "$signal → $decoded")
        }
    }

    @Test
    fun `вид, сигнал и ключ не перетекают друг в друга`() {
        val params = OverlayInstanceBuffer.params(OverlayInstanceBuffer.KIND_RAY, -1f, 0x1FF)
        assertEquals(OverlayInstanceBuffer.KIND_RAY, shaderKind(params))
        assertEquals(-1f, shaderSignal(params))
        assertEquals(0xFF, byteValue(params, 2), "ключ берётся младшим байтом")
        assertEquals(0, byteValue(params, 3), "старший байт — резерв, пустой")
    }

    @Test
    fun `фигуры пишутся в порядке вызовов и читаются обратно`() {
        val buffer = OverlayInstanceBuffer(initialCapacity = 1)
        buffer.begin()
        buffer.putRing(1f, 2f, 3.5f, 0x11223344)
        buffer.putRay(0f, 0f, 5f, 0f, 0x55667788, signal = 0.5f)
        buffer.putArrow(-1f, -2f, -3f, -4f, -1, signal = 0f)
        buffer.putNeuralLink(10f, 11f, 12f, 13f, 0x0A0B0C0D, signal = -0.25f, seed = 42)
        val data = buffer.end()

        assertEquals(4 * OverlayInstanceBuffer.STRUCT_SIZE, data.remaining())

        val ring = read(data, 0)
        assertEquals(OverlayInstanceBuffer.KIND_RING, shaderKind(ring.params))
        assertEquals(3.5f, ring.x1, "у кольца в x1 — радиус")
        assertEquals(0x11223344, ring.color)

        val ray = read(data, 1)
        assertEquals(OverlayInstanceBuffer.KIND_RAY, shaderKind(ray.params))
        assertEquals(5f, ray.x1)

        val arrow = read(data, 2)
        assertEquals(OverlayInstanceBuffer.KIND_ARROW, shaderKind(arrow.params))
        assertEquals(-4f, arrow.y1)

        val link = read(data, 3)
        assertEquals(OverlayInstanceBuffer.KIND_NEURAL, shaderKind(link.params))
        assertEquals(10f, link.x0, "связь начинается у источника")
        assertEquals(13f, link.y1, "и кончается у приёмника")
        assertEquals(42, byteValue(link.params, 2))
    }

    @Test
    fun `рост не теряет записанное, повторный end отдаёт те же данные`() {
        val buffer = OverlayInstanceBuffer(initialCapacity = 2)
        buffer.begin()
        repeat(50) { i -> buffer.putArrow(i.toFloat(), 0f, i + 1f, 0f, i) }
        val first = buffer.end()
        val second = buffer.end()
        assertEquals(50 * OverlayInstanceBuffer.STRUCT_SIZE, second.remaining())
        assertEquals(first.remaining(), second.remaining())
        for (i in 0 until 50) assertEquals(i.toFloat(), read(second, i).x0)
    }

    @Test
    fun `виды и шаг импульсов совпадают с шейдером`() {
        val glsl = shaderSource("overlay_common.glsl")

        fun constInt(name: String): Int =
            Regex("""const int $name = (\d+);""").find(glsl)?.groupValues?.get(1)?.toInt()
                ?: error("в overlay_common.glsl нет $name")

        assertEquals(OverlayInstanceBuffer.KIND_RING, constInt("KIND_RING"))
        assertEquals(OverlayInstanceBuffer.KIND_RAY, constInt("KIND_RAY"))
        assertEquals(OverlayInstanceBuffer.KIND_ARROW, constInt("KIND_ARROW"))
        assertEquals(OverlayInstanceBuffer.KIND_NEURAL, constInt("KIND_NEURAL"))
        assertEquals(OverlayInstanceBuffer.KIND_FLAGELLUM, constInt("KIND_FLAGELLUM"))

        // Часы анимации идут по кругу с периодом TIME_WRAP. Если за период импульсы не
        // делают целого числа шагов, на обороте они прыгают.
        val hz = Regex("""const float PULSE_HZ = ([\d.]+);""").find(glsl)?.groupValues?.get(1)?.toFloat()
            ?: error("в overlay_common.glsl нет PULSE_HZ")
        val steps = hz * RenderFrame.TIME_WRAP
        assertEquals(kotlin.math.round(steps), steps, "PULSE_HZ * TIME_WRAP обязано быть целым")

        val flagellumHz = Regex("""const float FLAGELLUM_HZ = ([\d.]+);""").find(glsl)?.groupValues?.get(1)?.toFloat()
            ?: error("в overlay_common.glsl нет FLAGELLUM_HZ")
        val turns = flagellumHz * RenderFrame.TIME_WRAP
        assertEquals(kotlin.math.round(turns), turns, "FLAGELLUM_HZ * TIME_WRAP обязано быть целым")
    }

    private fun shaderSource(name: String): String {
        // Тесты модуля запускаются из его каталога, но из IDE — бывает, что из корня.
        val candidates = listOf("../assets/shaders/debug_view", "assets/shaders/debug_view")
            .map { File(it, name) }
        val file = candidates.firstOrNull { it.isFile }
            ?: error("не найден $name, искал: ${candidates.joinToString { it.absolutePath }}")
        return file.readText()
    }
}
