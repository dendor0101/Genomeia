package io.github.some_example_name.render.pack

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Буфер инстансов клеток в том виде, в каком его читает GPU.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ КЛАСС
 * ---------------------
 * Раскладка этих 32 байт должна совпадать с тем, что распаковывает вершинный шейдер
 * (shaders/debug/circle_pc.vert). Раньше знание о ней жило в трёх местах сразу:
 * побитовые формулы — в RenderBufferManager.writeCell и в EditorRenderSystem.putBuffer,
 * порядок полей — в RenderSystem.drawCellShader и снова в putBuffer, а обратные формулы —
 * в самом шейдере. Три копии одних и тех же магических чисел (0.05, 0.7, 255), которые
 * обязаны сходиться, иначе картинка едет молча: клетки не того размера или с чужим
 * доворотом текстуры, но без единой ошибки в логе.
 *
 * Теперь копий на CPU одна, и она покрыта тестами (CellInstanceBufferTest), которые
 * распаковывают записанное ровно теми же формулами, что и шейдер.
 *
 * РАСКЛАДКА (32 байта = 2 текселя RGBA32UI, порядок байт нативный)
 * ---------------------------------------------------------------
 *   [0..3]   float x
 *   [4..7]   float y
 *   [8..11]  int   color   (RGBA8, распаковка unpackRGBA8 в шейдере)
 *   [12..15] int   packed1 (cosByte | sinByte<<8 | radiusByte<<24)
 *   [16..19] int   packed2 (energyByte | cellType<<8 | noiseSeed<<16)
 *   [20..31] три нулевых int — добивка до второго текселя
 *
 * ПРО ПОТОКИ
 * ----------
 * packed1/packed2 считает поток симуляции (RenderBufferManager) и кладёт готовыми
 * числами в снимок, а GL-поток потом только переписывает их в этот буфер. Так сделано
 * намеренно: побитовая арифметика на десятках тысяч клеток идёт параллельно кадру, а не
 * внутри него. Поэтому у класса два уровня API:
 *   - [put] — «положи уже готовые packed», для GL-потока;
 *   - [putCell] и функции из companion — упаковка, для того, кто считает.
 */
class CellInstanceBuffer(initialCapacity: Int = INITIAL_CAPACITY) {

    private var buffer: ByteBuffer = allocate(initialCapacity)

    /** Инстансов записано с последнего [begin]. Отдельным счётчиком, а не из position. */
    private var written = 0

    /**
     * Буфер уже перевёрнут под чтение.
     *
     * Нужно, чтобы [end] можно было звать сколько угодно раз за кадр. Редактор генома так
     * и делает: между тиками он не перезаписывает буфер (данные те же), но кадр рисует
     * каждый раз и буфер запрашивает заново. Голый flip() во второй раз выставил бы
     * limit = position = 0, то есть отдал бы ПУСТОЙ буфер — и клетки пропали бы с экрана.
     */
    private var finished = false

    /** Сколько инстансов помещается без пересоздания. */
    val capacity: Int get() = buffer.capacity() / STRUCT_SIZE

    /** Сколько инстансов записано с последнего [begin]. */
    val count: Int get() = written

    /**
     * Начать кадр.
     *
     * [expectedInstances] — ёмкость, которая точно понадобится. Передавать её стоит, когда
     * размер известен заранее: тогда рост случится один раз здесь, а не в середине записи.
     * Если размер неизвестен, [put] дорастит сам.
     */
    fun begin(expectedInstances: Int = 0) {
        ensureCapacity(expectedInstances)
        (buffer as Buffer).clear()
        written = 0
        finished = false
    }

    /** Готовые packed — путь GL-потока, без арифметики. */
    fun put(x: Float, y: Float, color: Int, packed1: Int, packed2: Int) {
        ensureRoomForOneMore()
        buffer.putFloat(x)
        buffer.putFloat(y)
        buffer.putInt(color)
        buffer.putInt(packed1)
        buffer.putInt(packed2)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(0)
        written++
    }

    /**
     * Упаковать и положить за один вызов.
     *
     * [noiseSeed] — устойчивый ключ шума: шейдер доворачивает текстуру на случайный угол,
     * взятый из него. Ключ обязан быть закреплён за клеткой на всю жизнь, иначе доворот
     * пересчитывается каждый кадр и текстуры визуально крутятся. Индекс частицы годится,
     * позиция в буфере — нет.
     */
    fun putCell(
        x: Float,
        y: Float,
        color: Int,
        angleCos: Float,
        angleSin: Float,
        radius: Float,
        energy: Float,
        cellType: Int,
        noiseSeed: Int
    ) {
        put(
            x = x,
            y = y,
            color = color,
            packed1 = packed1(angleByte(angleCos), angleByte(angleSin), radiusByte(radius)),
            packed2 = packed2(energyByte(energy), cellType, noiseSeed)
        )
    }

    /** Завершить кадр и отдать буфер на загрузку в текстуру данных. Идемпотентно. */
    fun end(): ByteBuffer {
        if (!finished) {
            (buffer as Buffer).flip()
            finished = true
        }
        return buffer
    }

    /**
     * Рост на ×1.5 с пересозданием.
     *
     * Direct-буфер нельзя расширить на месте, поэтому уже записанное копируется в новый.
     * На установившемся режиме этого не происходит вообще: ёмкость подбирается один раз
     * под размер мира и дальше держится.
     */
    fun ensureCapacity(instances: Int) {
        val current = capacity
        if (instances <= current) return

        var grown = current.toDouble()
        do {
            grown *= GROWTH_FACTOR
        } while (grown < instances)

        val bigger = allocate(grown.toInt().coerceAtLeast(instances))

        // Direct-буфер нельзя расширить на месте, поэтому уже записанное переезжает
        // копированием. Копируем ровно записанное, а не всю ёмкость.
        if (written > 0) {
            val old = buffer
            (old as Buffer).position(0)
            (old as Buffer).limit(written * STRUCT_SIZE)
            bigger.put(old)
        }
        buffer = bigger
    }

    private fun ensureRoomForOneMore() {
        if (buffer.remaining() < STRUCT_SIZE) ensureCapacity(written + 1)
    }

    private fun allocate(instances: Int): ByteBuffer =
        ByteBuffer
            .allocateDirect(instances.coerceAtLeast(1) * STRUCT_SIZE)
            .order(ByteOrder.nativeOrder())

    companion object {
        /** 32 байта = 2 текселя RGBA32UI. Менять только вместе с circle_pc.vert. */
        const val STRUCT_SIZE = 32

        const val INITIAL_CAPACITY = 30_000

        private const val GROWTH_FACTOR = 1.5

        /**
         * Диапазон радиуса, который влезает в один байт.
         * Обратное преобразование в шейдере: `ex_R = 0.05 + v1.w * 0.7`.
         */
        const val RADIUS_MIN = 0.05f
        const val RADIUS_SPAN = 0.7f

        /**
         * Делитель энергии. В шейдере распакованный байт идёт как `energy = v2.x * 0.5`,
         * а потом возводится в квадрат — это радиус чёрной точки внутри клетки, а не
         * физическая энергия. Число 10 здесь просто задаёт, при какой энергии точка
         * достигает максимума.
         */
        const val ENERGY_SPAN = 10f

        /** cos/sin из [-1..1] в байт. Обратно: `v1.x * 2.0 - 1.0`. */
        fun angleByte(value: Float): Int =
            ((value * 0.5f + 0.5f) * 255f + 0.5f).toInt().coerceIn(0, 255)

        fun radiusByte(radius: Float): Int =
            (((radius - RADIUS_MIN) / RADIUS_SPAN) * 255f + 0.5f).toInt().coerceIn(0, 255)

        fun energyByte(energy: Float): Int =
            ((energy / ENERGY_SPAN) * 255f + 0.5f).toInt().coerceIn(0, 255)

        fun packed1(cosByte: Int, sinByte: Int, radiusByte: Int): Int =
            cosByte or (sinByte shl 8) or (radiusByte shl 24)

        /**
         * Старшие 16 бит packed2 — ключ шума, средний байт — тип клетки (индекс слоя в
         * TextureArray), младший — энергия.
         */
        fun packed2(energyByte: Int, cellType: Int, noiseSeed: Int): Int =
            energyByte or (cellType.coerceIn(0, 255) shl 8) or ((noiseSeed and 0xFFFF) shl 16)
    }
}
