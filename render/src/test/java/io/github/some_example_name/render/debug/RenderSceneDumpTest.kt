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
                noiseSeed = i * 31
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
        assertBuffersEqual(frame.overlay!!, scene.overlay, "буфер оверлея")
        assertTrue(scene.overlay.isDirect, "оверлей тоже едет на GPU как есть")
    }

    /** Смещение поля time в заголовке: magic, версия, вьюпорт, камера, блюр, виньетка, режим, K и P. */
    private val timeOffset = 8 + 4 + 4 + 4 + 3 * 4 + 2 * 4 + 1 + 2 * 4

    /**
     * Запись текущей версии → запись версии 2 или 3: клетки по 20 байт (без look), у
     * версии 2 ещё нет времени и оверлея. [paths] — длина секции путей к текстурам в байтах.
     */
    private fun downgrade(current: ByteArray, version: Int, paths: Int, cellCount: Int): ByteArray {
        val cellsAt = timeOffset + 4 + paths + 4
        val cells = ByteArray(cellCount * 20)
        repeat(cellCount) { i ->
            current.copyInto(cells, i * 20, cellsAt + i * 24, cellsAt + i * 24 + 20)
        }
        val head = current.copyOfRange(0, cellsAt)
        val tail = current.copyOfRange(cellsAt + cellCount * 24, current.size)
        var result = head + cells + tail
        if (version == 2) {
            // Без времени после pheromoneP и без счётчика оверлея в самом конце.
            result = result.copyOfRange(0, timeOffset) + result.copyOfRange(timeOffset + 4, result.size - 4)
        }
        result[8] = 0; result[9] = 0; result[10] = 0; result[11] = version.toByte()
        return result
    }

    @Test
    fun `дамп версии 2 читается без оверлея и времени`() {
        // Версия 3 добавила два поля: время после pheromoneP и оверлей в самом конце,
        // версия 4 — look в каждой клетке. Старые снимки — материал для сравнения «до и
        // после», терять их нельзя.
        val (frame, _, _) = sampleFrame(cellCount = 4, pheromoneCount = 2)
        frame.overlay = null
        val file = FileHandle(folder.newFile("v2.scene"))
        RenderSceneDump.write(file, frame, 800, 600, 1f, 2f, 0.5f, listOf("a.png"))
        file.writeBytes(downgrade(file.readBytes(), version = 2, paths = 4 + 4 + 5, cellCount = 4), false)

        val scene = RenderSceneDump.read(file)
        assertEquals(4, scene.cellCount)
        assertEquals(2, scene.pheromoneCount)
        assertEquals(0f, scene.time)
        assertEquals(0, scene.overlayCount)
        assertEquals(0, scene.overlay.remaining())
        // У образца look нулевой — ровно то, чем дамп старой версии и дополняется.
        assertBuffersEqual(frame.cells!!, scene.cells, "буфер клеток")
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
        assertBuffersEqual(frame.cells!!, scene.cells, "буфер клеток")
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
