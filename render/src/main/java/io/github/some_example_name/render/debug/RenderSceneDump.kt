package io.github.some_example_name.render.debug

import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.PheromoneInstanceBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Снимок кадра на диск: ровно то, что ушло бы в WorldRenderer, но в файле.
 *
 * ЗАЧЕМ
 * -----
 * Чтобы крутить шейдеры, не запуская игру. Симуляция один раз выгружает интересный кадр
 * (клавиша в SimulationScreen), а дальше стенд RenderLab открывает этот файл и рисует его
 * сколько угодно раз — без DI-контейнеров, без потока симуляции, без загрузки геномов.
 * Пересобирать при этом нужно только :render.
 *
 * Обычный скриншот тут не годится: он показывает результат, а не вход. По PNG нельзя
 * понять, клетка ли приехала не того радиуса или шейдер не так распаковал байт.
 *
 * ПОЧЕМУ БИНАРНО И ПОЧЕМУ СВОЙ ФОРМАТ
 * -----------------------------------
 * Буферы инстансов и так лежат упакованными байтами — они пишутся как есть, без единого
 * преобразования. Любой текстовый формат означал бы кодирование туда и обратно, то есть
 * ещё одно место, где раскладка может разъехаться. Здесь же гарантия сильная: что
 * прочитал WorldRenderer из файла, то и было бы в живом кадре, байт в байт.
 *
 * ФОРМАТ (порядок байт — BIG_ENDIAN, чтобы файл не зависел от машины)
 * ------------------------------------------------------------------
 *   magic            8 байт  "GENOMSCN"
 *   version          int
 *   viewportWidth    int
 *   viewportHeight   int
 *   cameraX          float
 *   cameraY          float
 *   zoom             float
 *   blurAmount       float
 *   vignetteEnabled  float
 *   usePostProcess   byte    (0/1)
 *   pheromoneK       float
 *   pheromoneP       float
 *   texturePaths     int count, далее для каждого: int длина + UTF-8 байты
 *   cellCount        int, далее cellCount * 32 байта
 *   pheromoneCount   int, далее pheromoneCount * 16 байт
 *
 * Пути к текстурам лежат в файле намеренно: тип клетки — это индекс слоя в TextureArray,
 * поэтому без исходного ПОРЯДКА путей дамп рисовался бы чужими текстурами. Собрать тот же
 * порядок стенд сам не может: он задаётся списком клеток из :core, о котором :render
 * ничего не знает и знать не должен.
 */
object RenderSceneDump {

    private const val MAGIC = "GENOMSCN"
    const val VERSION = 1

    /** Расширение по умолчанию, чтобы дампы было видно глазами в каталоге. */
    const val EXTENSION = "scene"

    /**
     * Кадр, восстановленный из файла.
     *
     * Камеру отдаём разобранной на позицию и зум, а не готовой матрицей: со склеенной
     * матрицей стенд стал бы просмотрщиком одной точки зрения, а по сцене надо ездить —
     * половина вопросов к шейдеру («что там на краю клетки?») это вопросы про зум.
     */
    class Scene(
        val viewportWidth: Int,
        val viewportHeight: Int,
        val cameraX: Float,
        val cameraY: Float,
        val zoom: Float,
        val blurAmount: Float,
        val vignetteEnabled: Float,
        val usePostProcess: Boolean,
        val pheromoneK: Float,
        val pheromoneP: Float,
        val texturePaths: List<String>,
        val cellCount: Int,
        val cells: ByteBuffer,
        val pheromoneCount: Int,
        val pheromones: ByteBuffer
    )

    /**
     * Записать кадр.
     *
     * [frame] берётся ровно тот, что уходит в WorldRenderer.render — его буферы уже
     * перевёрнуты под чтение. Позицию и предел мы не трогаем: читаем абсолютными
     * индексами, потому что этот же буфер сейчас поедет на GPU.
     */
    fun write(
        file: FileHandle,
        frame: RenderFrame,
        viewportWidth: Int,
        viewportHeight: Int,
        cameraX: Float,
        cameraY: Float,
        zoom: Float,
        texturePaths: List<String>
    ) {
        val cells = frame.cells
        val pheromones = frame.pheromones

        val cellBytes = cells?.remaining() ?: 0
        val pheromoneBytes = pheromones?.remaining() ?: 0

        val pathBytes = texturePaths.map { it.toByteArray(Charsets.UTF_8) }
        val pathsSize = 4 + pathBytes.sumOf { 4 + it.size }

        val total = MAGIC.length + 4 + 4 + 4 + (3 * 4) + (2 * 4) + 1 + (2 * 4) +
            pathsSize + 4 + cellBytes + 4 + pheromoneBytes

        val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)

        out.put(MAGIC.toByteArray(Charsets.US_ASCII))
        out.putInt(VERSION)
        out.putInt(viewportWidth)
        out.putInt(viewportHeight)
        out.putFloat(cameraX)
        out.putFloat(cameraY)
        out.putFloat(zoom)
        out.putFloat(frame.blurAmount)
        out.putFloat(frame.vignetteEnabled)
        out.put(if (frame.usePostProcess) 1 else 0)
        out.putFloat(frame.pheromoneK)
        out.putFloat(frame.pheromoneP)

        out.putInt(pathBytes.size)
        pathBytes.forEach {
            out.putInt(it.size)
            out.put(it)
        }

        out.putInt(cellBytes / CellInstanceBuffer.STRUCT_SIZE)
        if (cells != null) copyAbsolute(cells, out)

        out.putInt(pheromoneBytes / PheromoneInstanceBuffer.STRUCT_SIZE)
        if (pheromones != null) copyAbsolute(pheromones, out)

        file.writeBytes(out.array(), false)
    }

    fun read(file: FileHandle): Scene {
        val input = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.BIG_ENDIAN)

        val magic = ByteArray(MAGIC.length)
        input.get(magic)
        val magicText = String(magic, Charsets.US_ASCII)
        require(magicText == MAGIC) {
            "не похоже на дамп сцены: в начале файла '$magicText', ожидалось '$MAGIC'"
        }

        val version = input.int
        require(version == VERSION) {
            "дамп версии $version, а читать умеем только $VERSION — перезапишите файл"
        }

        val viewportWidth = input.int
        val viewportHeight = input.int
        val cameraX = input.float
        val cameraY = input.float
        val zoom = input.float
        val blurAmount = input.float
        val vignetteEnabled = input.float
        val usePostProcess = input.get().toInt() != 0
        val pheromoneK = input.float
        val pheromoneP = input.float

        val pathCount = input.int
        val texturePaths = ArrayList<String>(pathCount)
        repeat(pathCount) {
            val length = input.int
            val bytes = ByteArray(length)
            input.get(bytes)
            texturePaths.add(String(bytes, Charsets.UTF_8))
        }

        val cellCount = input.int
        val cells = readDirect(input, cellCount * CellInstanceBuffer.STRUCT_SIZE)

        val pheromoneCount = input.int
        val pheromones = readDirect(input, pheromoneCount * PheromoneInstanceBuffer.STRUCT_SIZE)

        return Scene(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            cameraX = cameraX,
            cameraY = cameraY,
            zoom = zoom,
            blurAmount = blurAmount,
            vignetteEnabled = vignetteEnabled,
            usePostProcess = usePostProcess,
            pheromoneK = pheromoneK,
            pheromoneP = pheromoneP,
            texturePaths = texturePaths,
            cellCount = cellCount,
            cells = cells,
            pheromoneCount = pheromoneCount,
            pheromones = pheromones
        )
    }

    /** Читает без сдвига позиции источника: буфер сейчас поедет на GPU как есть. */
    private fun copyAbsolute(source: ByteBuffer, target: ByteBuffer) {
        val from = source.position()
        val to = source.limit()
        for (i in from until to) {
            target.put(source.get(i))
        }
    }

    /**
     * Инстансы кладём в DIRECT-буфер нативного порядка: именно такой ждёт glTexSubImage2D,
     * а сами байты внутри инстанса писались нативным порядком ещё при упаковке.
     *
     * Здесь важно не запутаться: BIG_ENDIAN — это порядок ЗАГОЛОВКА файла, чтобы дамп
     * с одной машины читался на другой. Полезная нагрузка инстансов — непрозрачные байты,
     * их мы не интерпретируем, а переносим один в один.
     */
    private fun readDirect(source: ByteBuffer, byteCount: Int): ByteBuffer {
        val target = ByteBuffer.allocateDirect(byteCount.coerceAtLeast(1))
            .order(ByteOrder.nativeOrder())
        repeat(byteCount) { target.put(source.get()) }
        (target as java.nio.Buffer).flip()
        (target as java.nio.Buffer).limit(byteCount)
        return target
    }
}
