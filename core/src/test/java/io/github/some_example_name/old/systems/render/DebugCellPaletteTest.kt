package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.cells.Chameleon
import io.github.some_example_name.old.cells.Excreta
import io.github.some_example_name.old.cells.Eye
import io.github.some_example_name.old.cells.Muscle
import io.github.some_example_name.old.cells.Tail
import io.github.some_example_name.old.cells.base.CellPalette
import io.github.some_example_name.old.cells.base.CellTypes
import io.github.some_example_name.render.pack.CellLook
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Отладочная раскраска без Gdx: рисунки по типам, цвета, фигуры оверлея и буфер снимка.
 */
class DebugCellPaletteTest {

    private val cells = CellTypes.createAll()
    private val palette = DebugCellPalette(cells)

    private fun typeOf(predicate: (Any) -> Boolean): Int = cells.indexOfFirst(predicate)

    @Test
    fun `у каждой клетки, которую можно выбрать в редакторе, свой рисунок`() {
        // Рисунок — то, по чему тип узнают вблизи. Две клетки с одним рисунком
        // различались бы только цветом, а клетка без рисунка выпадала бы из схемы.
        val choosable = cells.filter { CellPalette.FULL.allowsCell(it.key) }
        val patterns = choosable.associate { it.key to palette.pattern(it.cellTypeId) }
        for ((key, pattern) in patterns) {
            assertNotEquals(CellLook.PLAIN, pattern, "у клетки $key нет рисунка")
            assertTrue(pattern in 0 until CellLook.COUNT, "у клетки $key рисунок вне каталога")
        }
        assertEquals(patterns.size, patterns.values.toSet().size, "рисунки повторяются: $patterns")
    }

    @Test
    fun `мышца — цвет своего типа, сжатие едет сигналом в look, а не цветом`() {
        // Темнеть при сжатии и светлеть при растяжении мышца теперь обязана шейдером
        // (cell_common.glsl, bodyColor): цвет из палитры от сигнала не зависит.
        val muscle = typeOf { it is Muscle }
        val ownColor = 0x11223344
        assertEquals(palette.defaultColor(muscle), palette.color(muscle, ownColor))

        val contracted = palette.look(muscle, action = -1f)
        val relaxed = palette.look(muscle, action = 1f)
        assertEquals(CellLook.MUSCLE, contracted and 0xFF)
        assertEquals(CellLook.look(CellLook.MUSCLE, -1f), contracted)
        assertNotEquals(contracted, relaxed)
    }

    @Test
    fun `хамелеон остаётся своего цвета`() {
        val chameleon = typeOf { it is Chameleon }
        assertEquals(0x11223344, palette.color(chameleon, 0x11223344))
        assertEquals(CellLook.PIGMENTS, palette.pattern(chameleon))
    }

    @Test
    fun `хвост получает жгутик, глаз — луч, выделитель — ни стрелки, ни луча`() {
        val tail = typeOf { it is Tail }
        assertTrue(palette.hasFlagellum(tail))
        assertFalse(palette.hasArrow(tail), "у хвоста направление показывает жгутик")

        val eye = typeOf { it is Eye }
        assertTrue(palette.isEye(eye))
        assertFalse(palette.hasArrow(eye))

        // Направление выделителя видно по ядру у мембраны.
        val excreta = typeOf { it is Excreta }
        assertFalse(palette.hasArrow(excreta))
        assertFalse(palette.hasFlagellum(excreta))
    }

    @Test
    fun `жгутик начинается на поверхности клетки и идёт по её направлению`() {
        val tail = typeOf { it is Tail }
        var written: FloatArray? = null
        palette.writeFlagellum(
            { x0, y0, x1, y1, _, _ -> written = floatArrayOf(x0, y0, x1, y1) },
            tail, x = 10f, y = 20f, angleCos = 0f, angleSin = 1f, radius = 0.5f, signal = 0f, seed = 3
        )
        val (x0, y0, x1, y1) = written!!
        assertEquals(10f, x0)
        assertEquals(20.5f, y0, "без зазора: от самой поверхности")
        assertEquals(10f, x1)
        assertEquals(20f + 0.5f * (1f + DebugCellPalette.FLAGELLUM_LENGTH), y1)
    }

    @Test
    fun `стрелка едет и поворачивается вместе со своей клеткой`() {
        // Клетка за тик сдвинулась из (4.7, 7.2) в (5, 7) и повернулась с 0.8 на 1.0 рад.
        // Прошлые концы стрелки обязаны оказаться там, где стрелка была в прошлом тике: в
        // прошлом центре, по прошлому направлению.
        val overlay = OverlayInstanceBuffer(initialCapacity = 1)
        overlay.begin()
        val sink = OverlayMotionSink().apply { target = overlay }

        val angle = 1.0
        val prevAngle = 0.8
        val x = 5f
        val y = 7f
        val prevX = 4.7f
        val prevY = 7.2f
        sink.rigid(
            x, y, cos(angle).toFloat(), sin(angle).toFloat(),
            prevX, prevY, cos(prevAngle).toFloat(), sin(prevAngle).toFloat()
        )
        palette.writeArrow(sink, x, y, cos(angle).toFloat(), sin(angle).toFloat(), radius = 0.5f, signal = 0f)
        val data = overlay.end()

        // Начало — центр клетки: в прошлом тике он был в прошлом центре.
        assertNear(prevX, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_FROM), "начало x")
        assertNear(prevY, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_FROM + 4), "начало y")

        val length = 0.5f * DebugCellPalette.ARROW_LENGTH
        assertNear(prevX + cos(prevAngle).toFloat() * length, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_TO), "конец x тиком раньше")
        assertNear(prevY + sin(prevAngle).toFloat() * length, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_TO + 4), "конец y тиком раньше")
    }

    @Test
    fun `кольца феромонов стоят, связи едут прошлыми позициями своих концов`() {
        val overlay = OverlayInstanceBuffer(initialCapacity = 1)
        overlay.begin()
        val sink = OverlayMotionSink().apply { target = overlay }

        sink.still()
        sink.add(1f, 2f, 3f, 0f, 0, 0)
        sink.points(-0.5f, 0f, 1f, 1.25f)
        palette.writeNeuralLink(sink, 0f, 0f, 1f, 1f, color = 0, signal = 0f, seed = 1)
        val data = overlay.end()

        assertEquals(1f, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_FROM), "кольцо стоит")
        assertEquals(2f, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_FROM + 4))
        assertEquals(3f, data.getFloat(OverlayInstanceBuffer.OFFSET_PREV_TO), "радиус тот же")

        val link = OverlayInstanceBuffer.STRUCT_SIZE
        assertEquals(-0.5f, data.getFloat(link + OverlayInstanceBuffer.OFFSET_PREV_FROM), "начало связи")
        assertEquals(1.25f, data.getFloat(link + OverlayInstanceBuffer.OFFSET_PREV_TO + 4), "конец связи")
    }

    private fun assertNear(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) <= 1e-5f, "$what: ожидалось ~$expected, получено $actual")
    }
}
