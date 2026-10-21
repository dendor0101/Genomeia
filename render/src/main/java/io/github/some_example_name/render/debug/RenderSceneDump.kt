package io.github.some_example_name.render.debug

import com.badlogic.gdx.files.FileHandle
import io.github.some_example_name.render.RenderFrame
import io.github.some_example_name.render.RenderMode
import io.github.some_example_name.render.pack.CellInstanceBuffer
import io.github.some_example_name.render.pack.OverlayInstanceBuffer
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
 *   mode             byte    RenderMode.dumpCode: 0 отладка, 1 реалистичный, 2 ностальгический
 *   pheromoneK       float
 *   pheromoneP       float
 *   time             float   с версии 3: часы анимации оверлея (RenderFrame.time)
 *   interpolationLag float   с версии 5: отставание кадра от снимка (RenderFrame.interpolationLag)
 *   texturePaths     int count, далее для каждого: int длина + UTF-8 байты
 *   cellCount        int, далее cellCount * 40 байт (в версии 1 — по 32 старой раскладки,
 *                    во 2–3 — по 20, в 4 — по 24)
 *   pheromoneCount   int, далее pheromoneCount * 16 байт
 *   overlayCount     int, далее overlayCount * 40 байт — с версии 3 (в 3–4 — по 24), оверлей отладки
 *
 * Пути к текстурам лежат в файле намеренно: тип клетки — это индекс слоя в TextureArray,
 * поэтому без исходного ПОРЯДКА путей дамп рисовался бы чужими текстурами. Собрать тот же
 * порядок стенд сам не может: он задаётся списком клеток из :core, о котором :render
 * ничего не знает и знать не должен.
 */
object RenderSceneDump {

    private const val MAGIC = "GENOMSCN"

    /**
     * Версия 2: инстанс клетки ужался с 32 байт до 20, см. CellInstanceBuffer.
     * Версия 3: оверлей отладочного режима и время его анимации.
     * Версия 4: инстанс клетки 24 байта — добавился вид в отладке (CellInstanceBuffer.look).
     * Версия 5: инстанс клетки 40 байт — направление по 16 бит и поза в прошлом тике, у
     *           оверлея тоже прошлые концы; в заголовке — отставание кадра от снимка.
     */
    const val VERSION = 5

    /** Раскладка версии 1: x, y, color, packed1, packed2 + 12 байт добивки. */
    private const val V1_CELL_STRUCT_SIZE = 32

    /** Раскладка версий 2–3: x, y, color, shape, type — без look. */
    private const val V2_CELL_STRUCT_SIZE = 20

    /** Раскладка версии 4: x, y, color, shape (cos, sin, радиус, энергия по байту), type, look. */
    private const val V4_CELL_STRUCT_SIZE = 24

    /** Оверлей версий 3–4: без смещений концов. */
    private const val V4_OVERLAY_STRUCT_SIZE = 24

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
        val mode: RenderMode,
        val pheromoneK: Float,
        val pheromoneP: Float,
        val texturePaths: List<String>,
        val cellCount: Int,
        val cells: ByteBuffer,
        val pheromoneCount: Int,
        val pheromones: ByteBuffer,
        /** Часы анимации оверлея; в дампах до версии 3 — ноль. */
        val time: Float = 0f,
        /** Фигуры оверлея; в дампах до версии 3 их нет — пустой буфер. */
        val overlayCount: Int = 0,
        val overlay: ByteBuffer = emptyDirect(),
        /** Отставание кадра от снимка; в дампах до версии 5 — ноль, они без смещений. */
        val interpolationLag: Float = 0f
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
        val overlay = frame.overlay

        val cellBytes = cells?.remaining() ?: 0
        val pheromoneBytes = pheromones?.remaining() ?: 0
        val overlayBytes = overlay?.remaining() ?: 0

        val pathBytes = texturePaths.map { it.toByteArray(Charsets.UTF_8) }
        val pathsSize = 4 + pathBytes.sumOf { 4 + it.size }

        val total = MAGIC.length + 4 + 4 + 4 + (3 * 4) + (2 * 4) + 1 + (2 * 4) + 4 + 4 +
            pathsSize + 4 + cellBytes + 4 + pheromoneBytes + 4 + overlayBytes

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
        out.put(frame.mode.dumpCode.toByte())
        out.putFloat(frame.pheromoneK)
        out.putFloat(frame.pheromoneP)
        out.putFloat(frame.time)
        out.putFloat(frame.interpolationLag)

        out.putInt(pathBytes.size)
        pathBytes.forEach {
            out.putInt(it.size)
            out.put(it)
        }

        out.putInt(cellBytes / CellInstanceBuffer.STRUCT_SIZE)
        if (cells != null) copyAbsolute(cells, out)

        out.putInt(pheromoneBytes / PheromoneInstanceBuffer.STRUCT_SIZE)
        if (pheromones != null) copyAbsolute(pheromones, out)

        out.putInt(overlayBytes / OverlayInstanceBuffer.STRUCT_SIZE)
        if (overlay != null) copyAbsolute(overlay, out)

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
        require(version in 1..VERSION) {
            "дамп версии $version, а читать умеем с 1 по $VERSION — перезапишите файл"
        }

        val viewportWidth = input.int
        val viewportHeight = input.int
        val cameraX = input.float
        val cameraY = input.float
        val zoom = input.float
        val blurAmount = input.float
        val vignetteEnabled = input.float
        // Раньше здесь был байт usePostProcess (0/1). Коды первых двух режимов
        // совпадают с ним намеренно — старые дампы читаются без миграции.
        val mode = RenderMode.fromDumpCode(input.get().toInt())
        val pheromoneK = input.float
        val pheromoneP = input.float
        val time = if (version >= 3) input.float else 0f
        val interpolationLag = if (version >= 5) input.float else 0f

        val pathCount = input.int
        val texturePaths = ArrayList<String>(pathCount)
        repeat(pathCount) {
            val length = input.int
            val bytes = ByteArray(length)
            input.get(bytes)
            texturePaths.add(String(bytes, Charsets.UTF_8))
        }

        val cellCount = input.int
        val cells = when {
            version == 1 -> readCellsV1(input, cellCount)
            version <= 3 -> readLegacyCells(input, cellCount, V2_CELL_STRUCT_SIZE)
            version == 4 -> readLegacyCells(input, cellCount, V4_CELL_STRUCT_SIZE)
            else -> readDirect(input, cellCount * CellInstanceBuffer.STRUCT_SIZE)
        }

        val pheromoneCount = input.int
        val pheromones = readDirect(input, pheromoneCount * PheromoneInstanceBuffer.STRUCT_SIZE)

        val overlayCount = if (version >= 3) input.int else 0
        val overlay = if (version >= 5) {
            readDirect(input, overlayCount * OverlayInstanceBuffer.STRUCT_SIZE)
        } else {
            readLegacyOverlay(input, overlayCount)
        }

        return Scene(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            cameraX = cameraX,
            cameraY = cameraY,
            zoom = zoom,
            blurAmount = blurAmount,
            vignetteEnabled = vignetteEnabled,
            mode = mode,
            pheromoneK = pheromoneK,
            pheromoneP = pheromoneP,
            texturePaths = texturePaths,
            cellCount = cellCount,
            cells = cells,
            pheromoneCount = pheromoneCount,
            pheromones = pheromones,
            time = time,
            overlayCount = overlayCount,
            overlay = overlay,
            interpolationLag = interpolationLag
        )
    }

    /**
     * Клетки из дампа версии 1 — переупаковка старой раскладки в текущую.
     *
     * Смысл поддержки: дампы это тот материал, на котором проверяют правки шейдера, и
     * терять накопленные снимки при каждой смене раскладки нельзя — иначе сравнивать
     * «до и после» будет попросту не на чем.
     *
     * Было (32 байта):  x, y, color, packed1, packed2, три нулевых int
     *   packed1 = cos | sin<<8 | 0<<16 | radius<<24
     *   packed2 = energy | cellType<<8 | seed<<16
     * Стало: текущая раскладка, см. [putLegacyCell].
     */
    private fun readCellsV1(source: ByteBuffer, cellCount: Int): ByteBuffer {
        val target = ByteBuffer
            .allocateDirect((cellCount * CellInstanceBuffer.STRUCT_SIZE).coerceAtLeast(1))
            .order(ByteOrder.nativeOrder())

        // Внутри инстанса байты лежали нативным порядком, а заголовок файла — big-endian,
        // поэтому для полезной нагрузки нужен отдельный вид с нативным порядком.
        val native = source.slice().order(ByteOrder.nativeOrder())

        for (i in 0 until cellCount) {
            val base = i * V1_CELL_STRUCT_SIZE
            val x = native.getFloat(base)
            val y = native.getFloat(base + 4)
            val color = native.getInt(base + 8)
            val packed1 = native.getInt(base + 12)
            val packed2 = native.getInt(base + 16)

            val cosByte = packed1 and 0xFF
            val sinByte = (packed1 ushr 8) and 0xFF
            val radiusByte = (packed1 ushr 24) and 0xFF
            val energyByte = packed2 and 0xFF
            val cellType = (packed2 ushr 8) and 0xFF
            val seed = (packed2 ushr 16) and 0xFFFF

            putLegacyCell(
                target, x, y, color, cosByte, sinByte, radiusByte, energyByte,
                type = CellInstanceBuffer.type(cellType, seed),
                look = 0
            )
        }

        (source as java.nio.Buffer).position(source.position() + cellCount * V1_CELL_STRUCT_SIZE)
        (target as java.nio.Buffer).flip()
        return target
    }

    /**
     * Клетки из дампов версий 2–4: x, y, color, shape, type и (с версии 4) look, где
     * shape = cos | sin<<8 | radius<<16 | energy<<24 — направление по байту. В версиях 2–3
     * look нет: ноль — непрозрачная клетка без рисунка, то есть ровно то, как такие дампы
     * и рисовались.
     */
    private fun readLegacyCells(source: ByteBuffer, cellCount: Int, structSize: Int): ByteBuffer {
        val target = ByteBuffer
            .allocateDirect((cellCount * CellInstanceBuffer.STRUCT_SIZE).coerceAtLeast(1))
            .order(ByteOrder.nativeOrder())
        val native = source.slice().order(ByteOrder.nativeOrder())

        for (i in 0 until cellCount) {
            val base = i * structSize
            val shape = native.getInt(base + 12)
            putLegacyCell(
                target,
                x = native.getFloat(base),
                y = native.getFloat(base + 4),
                color = native.getInt(base + 8),
                cosByte = shape and 0xFF,
                sinByte = (shape ushr 8) and 0xFF,
                radiusByte = (shape ushr 16) and 0xFF,
                energyByte = (shape ushr 24) and 0xFF,
                type = native.getInt(base + 16),
                look = if (structSize > V2_CELL_STRUCT_SIZE) native.getInt(base + 20) else 0
            )
        }

        (source as java.nio.Buffer).position(source.position() + cellCount * structSize)
        (target as java.nio.Buffer).flip()
        return target
    }

    /**
     * Клетка старой раскладки (направление по байту на cos и sin) — в текущую: байт
     * распаковывается так, как его распаковывал прежний шейдер, b/255 * 2 - 1. Прошлая поза
     * равна нынешней — старые дампы снимались без интерполяции.
     */
    private fun putLegacyCell(
        target: ByteBuffer,
        x: Float,
        y: Float,
        color: Int,
        cosByte: Int,
        sinByte: Int,
        radiusByte: Int,
        energyByte: Int,
        type: Int,
        look: Int
    ) {
        val dir = CellInstanceBuffer.dir(cosByte / 255f * 2f - 1f, sinByte / 255f * 2f - 1f)
        target.putFloat(x)
        target.putFloat(y)
        target.putInt(color)
        target.putInt(CellInstanceBuffer.shape(radiusByte, energyByte))
        target.putInt(type)
        target.putInt(look)
        target.putInt(dir)
        target.putFloat(x)
        target.putFloat(y)
        target.putInt(dir)
    }

    /** Оверлей версий 3–4: те же поля без прошлых концов — фигуры стоят. */
    private fun readLegacyOverlay(source: ByteBuffer, overlayCount: Int): ByteBuffer {
        val target = ByteBuffer
            .allocateDirect((overlayCount * OverlayInstanceBuffer.STRUCT_SIZE).coerceAtLeast(1))
            .order(ByteOrder.nativeOrder())
        repeat(overlayCount) {
            val from = target.position()
            repeat(V4_OVERLAY_STRUCT_SIZE) { target.put(source.get()) }
            // Прошлые концы — те же, что нынешние: x0, y0, x1, y1 в начале фигуры.
            repeat(4 * 4) { k -> target.put(target.get(from + k)) }
        }
        (target as java.nio.Buffer).flip()
        return target
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
    private fun emptyDirect(): ByteBuffer = readDirect(ByteBuffer.allocate(0), 0)

    private fun readDirect(source: ByteBuffer, byteCount: Int): ByteBuffer {
        val target = ByteBuffer.allocateDirect(byteCount.coerceAtLeast(1))
            .order(ByteOrder.nativeOrder())
        repeat(byteCount) { target.put(source.get()) }
        (target as java.nio.Buffer).flip()
        (target as java.nio.Buffer).limit(byteCount)
        return target
    }
}
