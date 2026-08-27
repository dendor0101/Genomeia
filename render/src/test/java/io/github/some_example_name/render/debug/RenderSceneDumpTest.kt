package io.github.some_example_name.render.debug

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.math.Matrix4
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.pack.CellInstanceBuffer
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

        val frame = RenderFrame().apply {
            cameraProjection = Matrix4()
            this.cells = cells.end()
            this.pheromones = pheromones.end()
            pheromoneK = 0.4f
            pheromoneP = 0.01f
            blurAmount = 2.5f
            zoom = 0.08f
            vignetteEnabled = 1f
            usePostProcess = true
        }
        return Triple(frame, cells, pheromones)
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
        assertTrue(scene.usePostProcess)
        assertEquals(0.4f, scene.pheromoneK)
        assertEquals(0.01f, scene.pheromoneP)
        assertEquals(listOf("cell_textures/eye.png", "cell_textures/not_cell.png"), scene.texturePaths)

        assertEquals(7, scene.cellCount)
        assertEquals(4, scene.pheromoneCount)

        assertBuffersEqual(frame.cells!!, scene.cells, "буфер клеток")
        assertBuffersEqual(frame.pheromones!!, scene.pheromones, "буфер феромонов")
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
