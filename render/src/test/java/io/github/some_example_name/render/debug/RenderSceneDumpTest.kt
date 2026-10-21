package io.github.some_example_name.render.debug

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Дамп сцены обязан переживать запись и чтение БЕЗ ЕДИНОГО изменённого байта.
 *
 * Иначе весь стенд теряет смысл: он существует, чтобы показывать ровно тот кадр, который
 * был в игре. Если формат где-то теряет или переставляет байты, стенд будет рисовать
 * похожую, но другую сцену — и отладка шейдера превратится в поиск несуществующих ошибок.
 *
 * FileHandle(File) работает без запущенного приложения: это тип Absolute, он ходит прямо
 * в java.io. Поэтому GL-контекст этим тестам не нужен.
 */
class RenderSceneDumpTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun sampleFrame(
        cellCount: Int = 5,
        pheromoneCount: Int = 3
    ): Triple<RenderFrame, CellInstanceBuffer, PheromoneInstanceBuffer> {
        val cells = CellInstanceBuffer(initialCapacity = 2)
        cells.begin()
        repeat(cellCount) { i ->
            cells.putCell(
                x = i * 1.5f,
                y = -i.toFloat(),
                color = 0x11223300 or i,
                angleCos = 1f - i * 0.1f,
                angleSin = i * 0.1f,
                radius = 0.1f + i * 0.05f,
                energy = i.toFloat(),
                cellType = i,
                noiseSeed = i * 31,
                prevX = i * 1.5f - i * 0.25f,
                prevY = -i.toFloat() + i * 0.125f,
                prevCos = 1f,
                prevSin = 0f
            )
        }

        val pheromones = PheromoneInstanceBuffer(initialCapacity = 2)
        pheromones.begin()
        repeat(pheromoneCount) { i ->
            pheromones.put(x = i.toFloat(), y = i * 2f, a = 0.1f * i, color = 0x00FF00FF.toInt())
        }

        val overlay = OverlayInstanceBuffer(initialCapacity = 1)
        overlay.begin()
        overlay.putRing(1f, 2f, 3f, 0x7F00FF00)
        overlay.putArrow(0f, 0f, 1f, 1f, -1, signal = 0.5f)
        overlay.putNeuralLink(-1f, -2f, 4f, 5f, 0x01020304, signal = -0.75f, seed = 9)

        val frame = RenderFrame().apply {
            cameraProjection = Matrix4()
            this.cells = cells.end()
            this.pheromones = pheromones.end()
            this.overlay = overlay.end()
            time = 123.25f
            interpolationLag = 0.375f
            pheromoneK = 0.4f
            pheromoneP = 0.01f
            blurAmount = 2.5f
            zoom = 0.08f
            vignetteEnabled = 1f
            mode = RenderMode.NOSTALGIC
        }
        return Triple(frame, cells, pheromones)
    }

    @Test
    fun `байт режима совместим со старым байтом usePostProcess`() {
        // До выпадающего списка в дампе лежал usePostProcess: 0 — без постпроцесса
        // (отладка), 1 — с ним. Номера режимов обязаны с ним совпадать, иначе старые
        // дампы молча откроются не в том режиме.
        assertEquals(RenderMode.DEBUG, RenderMode.fromDumpCode(0))
        assertEquals(RenderMode.REALISTIC, RenderMode.fromDumpCode(1))
        assertEquals(RenderMode.NOSTALGIC, RenderMode.fromDumpCode(2))
        assertEquals(RenderMode.REALISTIC, RenderMode.fromDumpCode(77), "неизвестный — по умолчанию")
    }

    @Test
    fun `кадр переживает запись и чтение байт в байт`() {
        val (frame, _, _) = sampleFrame(cellCount = 7, pheromoneCount = 4)
        val file = FileHandle(folder.newFile("scene.${RenderSceneDump.EXTENSION}"))

        RenderSceneDump.write(
            file = file,
            frame = frame,
            viewportWidth = 1300,
            viewportHeight = 720,
            cameraX = 128.5f,
            cameraY = -64.25f,
            zoom = 0.08f,
            texturePaths = listOf("cell_textures/eye.png", "cell_textures/not_cell.png")
        )

        val scene = RenderSceneDump.read(file)

        assertEquals(1300, scene.viewportWidth)
        assertEquals(720, scene.viewportHeight)
        assertEquals(128.5f, scene.cameraX)
        assertEquals(-64.25f, scene.cameraY)
        assertEquals(0.08f, scene.zoom)
        assertEquals(2.5f, scene.blurAmount)
        assertEquals(1f, scene.vignetteEnabled)
        assertEquals(RenderMode.NOSTALGIC, scene.mode)
        assertEquals(0.4f, scene.pheromoneK)
        assertEquals(0.01f, scene.pheromoneP)
        assertEquals(listOf("cell_textures/eye.png", "cell_textures/not_cell.png"), scene.texturePaths)

        assertEquals(7, scene.cellCount)
        assertEquals(4, scene.pheromoneCount)

        assertBuffersEqual(frame.cells!!, scene.cells, "буфер клеток")
        assertBuffersEqual(frame.pheromones!!, scene.pheromones, "буфер феромонов")

        assertEquals(3, scene.overlayCount)
        assertEquals(123.25f, scene.time)
        assertEquals(0.375f, scene.interpolationLag)
        assertBuffersEqual(frame.overlay!!, scene.overlay, "буфер оверлея")
        assertTrue(scene.overlay.isDirect, "оверлей тоже едет на GPU как есть")
    }

    /** Смещение поля time в заголовке: magic, версия, вьюпорт, камера, блюр, виньетка, режим, K и P. */
    private val timeOffset = 8 + 4 + 4 + 4 + 3 * 4 + 2 * 4 + 1 + 2 * 4

    private val native = java.nio.ByteOrder.nativeOrder()

    /**
     * Запись текущей версии (5) → запись версии 2, 3 или 4, ровно в той раскладке, какой
     * писала старая игра:
     *   - нет отставания кадра после time (версии до 5);
     *   - клетка 24 байта (4) или 20 байт (2–3): shape с направлением по байту на cos и
     *     sin, без dir и прошлой позы;
     *   - фигура оверлея 24 байта, без смещений концов (3–4);
     *   - у версии 2 ещё нет ни времени, ни оверлея.
     * [paths] — длина секции путей к текстурам в байтах.
     */
    private fun downgrade(current: ByteArray, version: Int, paths: Int, cellCount: Int): ByteArray {
        val src = java.nio.ByteBuffer.wrap(current)
        val cellStruct = if (version == 4) 24 else 20
        val out = java.io.ByteArrayOutputStream()

        // Заголовок до time включительно; у версии 2 time нет.
        out.write(current, 0, if (version == 2) timeOffset else timeOffset + 4)
        // Пропускаем отставание кадра: его нет до версии 5.
        val pathsAt = timeOffset + 4 + 4
        out.write(current, pathsAt, paths + 4)

        val cellsAt = pathsAt + paths + 4
        val cellBytes = java.nio.ByteBuffer.allocate(cellCount * cellStruct).order(native)
        val cells = src.duplicate().order(native)
        repeat(cellCount) { i ->
            val base = cellsAt + i * CellInstanceBuffer.STRUCT_SIZE
            val shape = cells.getInt(base + CellInstanceBuffer.OFFSET_SHAPE)
            val dir = cells.getInt(base + CellInstanceBuffer.OFFSET_DIR)
            cellBytes.putFloat(cells.getFloat(base))
            cellBytes.putFloat(cells.getFloat(base + 4))
            cellBytes.putInt(cells.getInt(base + CellInstanceBuffer.OFFSET_COLOR))
            // Прежний shape: cos | sin<<8 | радиус<<16 | энергия<<24; радиус и энергия на своих местах.
            cellBytes.putInt(oldAngleByte(dir, 0) or (oldAngleByte(dir, 1) shl 8) or (shape and 0xFFFF0000.toInt()))
            cellBytes.putInt(cells.getInt(base + CellInstanceBuffer.OFFSET_TYPE))
            if (version == 4) cellBytes.putInt(cells.getInt(base + CellInstanceBuffer.OFFSET_LOOK))
        }
        out.write(cellBytes.array())

        var at = cellsAt + cellCount * CellInstanceBuffer.STRUCT_SIZE
        val pheromoneCount = src.getInt(at)
        out.write(current, at, 4 + pheromoneCount * PheromoneInstanceBuffer.STRUCT_SIZE)
        at += 4 + pheromoneCount * PheromoneInstanceBuffer.STRUCT_SIZE

        if (version >= 3) {
            val overlayCount = src.getInt(at)
            out.write(current, at, 4)
            repeat(overlayCount) { i ->
                out.write(current, at + 4 + i * OverlayInstanceBuffer.STRUCT_SIZE, 24)
            }
        }

        val result = out.toByteArray()
        result[8] = 0; result[9] = 0; result[10] = 0; result[11] = version.toByte()
        return result
    }

    /** Направление из 16 бит в прежний байт: (v + 1) / 2 * 255. */
    private fun oldAngleByte(dir: Int, index: Int): Int {
        val value = ((dir ushr (index * 16)) and 0xFFFF).toShort() / 32767f
        return ((value * 0.5f + 0.5f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /**
     * Клетки старого дампа против исходных: всё, что старая раскладка хранила, — ровно;
     * направление — с точностью прежнего байта; прошлая поза у старых — нынешняя.
     */
    private fun assertLegacyCells(expected: java.nio.ByteBuffer, actual: java.nio.ByteBuffer, cellCount: Int, withLook: Boolean) {
        assertEquals(cellCount * CellInstanceBuffer.STRUCT_SIZE, actual.remaining())
        val e = expected.duplicate().order(native)
        val a = actual.duplicate().order(native)
        repeat(cellCount) { i ->
            val eb = e.position() + i * CellInstanceBuffer.STRUCT_SIZE
            val ab = a.position() + i * CellInstanceBuffer.STRUCT_SIZE
            assertEquals(e.getFloat(eb), a.getFloat(ab), "x клетки $i")
            assertEquals(e.getFloat(eb + 4), a.getFloat(ab + 4), "y клетки $i")
            assertEquals(e.getInt(eb + CellInstanceBuffer.OFFSET_COLOR), a.getInt(ab + CellInstanceBuffer.OFFSET_COLOR))
            assertEquals(e.getInt(eb + CellInstanceBuffer.OFFSET_TYPE), a.getInt(ab + CellInstanceBuffer.OFFSET_TYPE))
            val expectedLook = if (withLook) e.getInt(eb + CellInstanceBuffer.OFFSET_LOOK) else 0
            assertEquals(expectedLook, a.getInt(ab + CellInstanceBuffer.OFFSET_LOOK))

            val eShape = e.getInt(eb + CellInstanceBuffer.OFFSET_SHAPE)
            val aShape = a.getInt(ab + CellInstanceBuffer.OFFSET_SHAPE)
            assertEquals(eShape ushr 16, aShape ushr 16, "радиус и энергия клетки $i")
            assertEquals(0, aShape and 0xFFFF, "младшие байты shape свободны")
            assertEquals(a.getFloat(ab), a.getFloat(ab + CellInstanceBuffer.OFFSET_PREV_CENTER), "старый дамп стоит: x")
            assertEquals(a.getFloat(ab + 4), a.getFloat(ab + CellInstanceBuffer.OFFSET_PREV_CENTER + 4), "старый дамп стоит: y")
            assertEquals(a.getInt(ab + CellInstanceBuffer.OFFSET_DIR), a.getInt(ab + CellInstanceBuffer.OFFSET_PREV_DIR), "старый дамп не вращается")

            val eDir = e.getInt(eb + CellInstanceBuffer.OFFSET_DIR)
            val aDir = a.getInt(ab + CellInstanceBuffer.OFFSET_DIR)
            for (k in 0..1) {
                val ev = ((eDir ushr (k * 16)) and 0xFFFF).toShort() / 32767f
                val av = ((aDir ushr (k * 16)) and 0xFFFF).toShort() / 32767f
                assertTrue(kotlin.math.abs(ev - av) <= 1.01f / 255f, "направление клетки $i: $ev против $av")
            }
        }
    }

    @Test
    fun `дамп версии 2 читается без оверлея и времени`() {
        // Версия 3 добавила два поля: время после pheromoneP и оверлей в самом конце,
        // версия 4 — look в каждой клетке, версия 5 — направление по 16 бит и смещения.
        // Старые снимки — материал для сравнения «до и после», терять их нельзя.
        val (frame, _, _) = sampleFrame(cellCount = 4, pheromoneCount = 2)
        frame.overlay = null
        val file = FileHandle(folder.newFile("v2.scene"))
        RenderSceneDump.write(file, frame, 800, 600, 1f, 2f, 0.5f, listOf("a.png"))
        file.writeBytes(downgrade(file.readBytes(), version = 2, paths = 4 + 4 + 5, cellCount = 4), false)

        val scene = RenderSceneDump.read(file)
        assertEquals(4, scene.cellCount)
        assertEquals(2, scene.pheromoneCount)
        assertEquals(0f, scene.time)
        assertEquals(0f, scene.interpolationLag)
        assertEquals(0, scene.overlayCount)
        assertEquals(0, scene.overlay.remaining())
        assertLegacyCells(frame.cells!!, scene.cells, 4, withLook = false)
        assertBuffersEqual(frame.pheromones!!, scene.pheromones, "буфер феромонов")
    }

    @Test
    fun `дамп версии 3 читается с клетками по 20 байт`() {
        val (frame, _, _) = sampleFrame(cellCount = 3, pheromoneCount = 1)
        val file = FileHandle(folder.newFile("v3.scene"))
        RenderSceneDump.write(file, frame, 800, 600, 1f, 2f, 0.5f, listOf("a.png"))
        file.writeBytes(downgrade(file.readBytes(), version = 3, paths = 4 + 4 + 5, cellCount = 3), false)

        val scene = RenderSceneDump.read(file)
        assertEquals(3, scene.cellCount)
        assertEquals(123.25f, scene.time)
        assertEquals(3, scene.overlayCount)
        assertLegacyCells(frame.cells!!, scene.cells, 3, withLook = false)
        // У образца фигуры стоят — ровно то, чем дополняется старый оверлей.
        assertBuffersEqual(frame.overlay!!, scene.overlay, "буфер оверлея")
    }

    @Test
    fun `дамп версии 4 читается с клетками по 24 байта и направлением по байту`() {
        val (frame, _, _) = sampleFrame(cellCount = 5, pheromoneCount = 2)
        val file = FileHandle(folder.newFile("v4.scene"))
        RenderSceneDump.write(file, frame, 800, 600, 1f, 2f, 0.5f, listOf("a.png"))
        file.writeBytes(downgrade(file.readBytes(), version = 4, paths = 4 + 4 + 5, cellCount = 5), false)

        val scene = RenderSceneDump.read(file)
        assertEquals(5, scene.cellCount)
        assertEquals(123.25f, scene.time)
        assertEquals(0f, scene.interpolationLag, "старый дамп снят без интерполяции")
        assertLegacyCells(frame.cells!!, scene.cells, 5, withLook = true)
        assertBuffersEqual(frame.pheromones!!, scene.pheromones, "буфер феромонов")
        assertBuffersEqual(frame.overlay!!, scene.overlay, "буфер оверлея")
    }

    @Test
    fun `запись не сдвигает позицию исходных буферов`() {
        // Тот же буфер сразу после дампа уезжает на GPU. Сдвинь ему позицию — и в кадре
        // окажется часть инстансов или вообще ничего.
        val (frame, _, _) = sampleFrame()
        val cells = frame.cells!!
        val pheromones = frame.pheromones!!

        val cellPosition = cells.position()
        val cellLimit = cells.limit()
        val pheromonePosition = pheromones.position()
        val pheromoneLimit = pheromones.limit()

        RenderSceneDump.write(
            file = FileHandle(folder.newFile("untouched.scene")),
            frame = frame,
            viewportWidth = 800,
            viewportHeight = 600,
            cameraX = 0f,
            cameraY = 0f,
            zoom = 1f,
            texturePaths = listOf("a.png")
        )

        assertEquals(cellPosition, cells.position(), "позиция буфера клеток сдвинулась")
        assertEquals(cellLimit, cells.limit(), "предел буфера клеток сдвинулся")
        assertEquals(pheromonePosition, pheromones.position(), "позиция буфера феромонов сдвинулась")
        assertEquals(pheromoneLimit, pheromones.limit(), "предел буфера феромонов сдвинулся")
    }

    @Test
    fun `прочитанные буферы годятся для загрузки на GPU`() {
        // glTexSubImage2D принимает только DIRECT-буфер, и байты внутри инстанса писались
        // нативным порядком. Прочитай мы в обычный heap-буфер — упало бы уже на GPU.
        val (frame, _, _) = sampleFrame()
        val file = FileHandle(folder.newFile("direct.scene"))
        RenderSceneDump.write(file, frame, 800, 600, 0f, 0f, 1f, listOf("a.png"))

        val scene = RenderSceneDump.read(file)

        assertTrue(scene.cells.isDirect, "буфер клеток обязан быть direct")
        assertTrue(scene.pheromones.isDirect, "буфер феромонов обязан быть direct")
        assertEquals(java.nio.ByteOrder.nativeOrder(), scene.cells.order())
        assertEquals(java.nio.ByteOrder.nativeOrder(), scene.pheromones.order())
    }

    @Test
    fun `пустая сцена читается как пустая, а не как ошибка`() {
        val frame = RenderFrame().apply {
            cameraProjection = Matrix4()
            cells = CellInstanceBuffer(initialCapacity = 1).apply { begin() }.end()
            pheromones = null
        }
        val file = FileHandle(folder.newFile("empty.scene"))
        RenderSceneDump.write(file, frame, 640, 480, 0f, 0f, 1f, emptyList())

        val scene = RenderSceneDump.read(file)
        assertEquals(0, scene.cellCount)
        assertEquals(0, scene.pheromoneCount)
        assertEquals(0, scene.cells.remaining())
        assertEquals(0, scene.pheromones.remaining())
        assertTrue(scene.texturePaths.isEmpty())
    }

    @Test
    fun `чужой файл отвергается понятным сообщением`() {
        val file = FileHandle(folder.newFile("garbage.scene"))
        file.writeBytes(ByteArray(64) { it.toByte() }, false)

        val error = assertFailsWith<IllegalArgumentException> { RenderSceneDump.read(file) }
        assertTrue(
            error.message!!.contains("дамп"),
            "сообщение должно объяснять, что это не дамп: ${error.message}"
        )
    }

    @Test
    fun `дамп чужой версии отвергается, а не читается криво`() {
        val (frame, _, _) = sampleFrame()
        val file = FileHandle(folder.newFile("version.scene"))
        RenderSceneDump.write(file, frame, 800, 600, 0f, 0f, 1f, listOf("a.png"))

        // Портим только номер версии — он лежит сразу за восьмибайтовой сигнатурой.
        val bytes = file.readBytes()
        bytes[8] = 0
        bytes[9] = 0
        bytes[10] = 0
        bytes[11] = 99
        file.writeBytes(bytes, false)

        val error = assertFailsWith<IllegalArgumentException> { RenderSceneDump.read(file) }
        assertTrue(
            error.message!!.contains("99"),
            "сообщение должно называть версию файла: ${error.message}"
        )
    }

    @Test
    fun `пути к текстурам с кириллицей и пробелами не портятся`() {
        val paths = listOf("cell_textures/глаз.png", "cell textures/not cell.png", "a/б/в.png")
        val (frame, _, _) = sampleFrame()
        val file = FileHandle(folder.newFile("utf.scene"))
        RenderSceneDump.write(file, frame, 800, 600, 0f, 0f, 1f, paths)

        assertEquals(paths, RenderSceneDump.read(file).texturePaths)
    }

    private fun assertBuffersEqual(
        expected: java.nio.ByteBuffer,
        actual: java.nio.ByteBuffer,
        what: String
    ) {
        assertEquals(expected.remaining(), actual.remaining(), "$what: разный размер")
        val from = expected.position()
        for (i in 0 until expected.remaining()) {
            assertEquals(
                expected.get(from + i),
                actual.get(actual.position() + i),
                "$what: байт $i разошёлся"
            )
        }
    }
}
