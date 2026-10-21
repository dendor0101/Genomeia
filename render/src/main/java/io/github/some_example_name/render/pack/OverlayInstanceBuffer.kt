package io.github.some_example_name.render.pack

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Буфер фигур оверлея отладочного режима: нейросвязи, стрелки направлений, лучи глаз,
 * кольца феромонов, жгутики хвостов. Читает его DebugRenderer — инстансами, по кваду на фигуру.
 *
 * Модуль рендера не знает, что такое глаз или нейрон: здесь только фигуры. Что из
 * клеток превращается в какую фигуру и каким цветом, решает :core (RenderSystem).
 *
 * РАСКЛАДКА (40 байт, порядок байт нативный)
 * -------------------------------------------
 *   [0..7]   float x0, y0      → a_from   (2 × GL_FLOAT) — начало отрезка, центр кольца
 *   [8..15]  float x1, y1      → a_to     (2 × GL_FLOAT) — конец отрезка; у кольца x1 — радиус
 *   [16..19] int   color       → a_color  (4 × GL_UNSIGNED_BYTE, normalized) — RGBA8
 *   [20..23] int   params      → a_params (4 × GL_UNSIGNED_BYTE, normalized)
 *   [24..31] float prevX0, prevY0 → a_prevFrom (2 × GL_FLOAT) — начало в прошлом тике
 *   [32..39] float prevX1, prevY1 → a_prevTo   (2 × GL_FLOAT) — конец в прошлом тике
 *
 *   params = kind | signalByte<<8 | seed<<16 | 0<<24
 *
 *   kind       — [KIND_RING], [KIND_RAY], [KIND_ARROW], [KIND_NEURAL], [KIND_FLAGELLUM];
 *   signalByte — сигнал из -1..1 со смещением 127, см. [signalByte];
 *   seed       — сдвиг фазы импульсов или волны, чтобы фигуры не двигались в такт.
 *
 * Порядок записи — порядок отрисовки: что записано позже, ложится сверху.
 *
 * Прошлые концы — поза фигуры в прошлом тике. Оверлей интерполируется тем же u_lag, что и
 * клетки (CellInstanceBuffer): иначе стрелки и нейросвязи отставали бы от плавно едущих
 * под ними клеток и дёргались бы относительно них раз в тик. У кольца «конец» — это
 * радиус, и прошлый у него тот же.
 *
 * Размеры фигур (толщины, наконечники, шаг импульсов) задаёт шейдер —
 * shaders/debug_view/overlay_common.glsl, сюда едут только концы, цвет и сигнал.
 */
class OverlayInstanceBuffer(initialCapacity: Int = INITIAL_CAPACITY) {

    private var buffer: ByteBuffer = allocate(initialCapacity)

    private var written = 0

    /** См. пояснение про идемпотентность в CellInstanceBuffer. */
    private var finished = false

    val capacity: Int get() = buffer.capacity() / STRUCT_SIZE

    val count: Int get() = written

    fun begin(expectedInstances: Int = 0) {
        ensureCapacity(expectedInstances)
        (buffer as Buffer).clear()
        written = 0
        finished = false
    }

    /**
     * Готовые поля, без арифметики. [prevX0]..[prevY1] — концы в прошлом тике; по умолчанию
     * нынешние — фигура стоит.
     */
    fun put(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: Int,
        params: Int,
        prevX0: Float = x0,
        prevY0: Float = y0,
        prevX1: Float = x1,
        prevY1: Float = y1
    ) {
        if (buffer.remaining() < STRUCT_SIZE) ensureCapacity(written + 1)
        buffer.putFloat(x0)
        buffer.putFloat(y0)
        buffer.putFloat(x1)
        buffer.putFloat(y1)
        buffer.putInt(color)
        buffer.putInt(params)
        buffer.putFloat(prevX0)
        buffer.putFloat(prevY0)
        buffer.putFloat(prevX1)
        buffer.putFloat(prevY1)
        written++
    }

    /**
     * Нейросвязь ОТ источника К приёмнику: шеврон смотрит на приёмник, импульсы бегут
     * туда же. [signal] — то, что источник отдаёт в связь, зажимается в -1..1.
     * [seed] — любой устойчивый ключ связи (её индекс): от него только сдвиг фазы.
     */
    fun putNeuralLink(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        color: Int,
        signal: Float,
        seed: Int
    ) = put(fromX, fromY, toX, toY, color, params(KIND_NEURAL, signal, seed))

    /** Стрелка направления клетки: из ([x], [y]) в (endX, endY). [signal] подсвечивает её. */
    fun putArrow(x: Float, y: Float, endX: Float, endY: Float, color: Int, signal: Float = 0f) =
        put(x, y, endX, endY, color, params(KIND_ARROW, signal, 0))

    /** Луч глаза от ([x0], [y0]) до предела видимости. [signal] — выход глаза, луч ярче. */
    fun putRay(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, signal: Float = 0f) =
        put(x0, y0, x1, y1, color, params(KIND_RAY, signal, 0))

    /**
     * Жгутик: от поверхности клетки ([x0], [y0]) до кончика. По нему бежит волна; [signal]
     * — тяга хвоста, от неё размах волны.
     */
    fun putFlagellum(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, signal: Float, seed: Int) =
        put(x0, y0, x1, y1, color, params(KIND_FLAGELLUM, signal, seed))

    /** Окружность радиуса [radius] вокруг ([x], [y]). */
    fun putRing(x: Float, y: Float, radius: Float, color: Int) =
        put(x, y, radius, 0f, color, params(KIND_RING, 0f, 0))

    /** Завершить кадр и отдать буфер на загрузку. Идемпотентно. */
    fun end(): ByteBuffer {
        if (!finished) {
            (buffer as Buffer).flip()
            finished = true
        }
        return buffer
    }

    fun ensureCapacity(instances: Int) {
        val current = capacity
        if (instances <= current) return

        var grown = current.toDouble()
        do {
            grown *= GROWTH_FACTOR
        } while (grown < instances)

        val bigger = allocate(grown.toInt().coerceAtLeast(instances))
        if (written > 0) {
            val old = buffer
            (old as Buffer).position(0)
            (old as Buffer).limit(written * STRUCT_SIZE)
            bigger.put(old)
        }
        buffer = bigger
    }

    private fun allocate(instances: Int): ByteBuffer =
        ByteBuffer
            .allocateDirect(instances.coerceAtLeast(1) * STRUCT_SIZE)
            .order(ByteOrder.nativeOrder())

    companion object {
        /** Менять только вместе с OverlayGeometry и shaders/debug_view/overlay.vert. */
        const val STRUCT_SIZE = 40

        const val OFFSET_FROM = 0
        const val OFFSET_TO = 8
        const val OFFSET_COLOR = 16
        const val OFFSET_PARAMS = 20

        const val OFFSET_PREV_FROM = 24
        const val OFFSET_PREV_TO = 32

        const val INITIAL_CAPACITY = 4_096

        private const val GROWTH_FACTOR = 1.5

        /** Виды фигур — дублируются константами KIND_* в overlay_common.glsl. */
        const val KIND_RING = 0
        const val KIND_RAY = 1
        const val KIND_ARROW = 2
        const val KIND_NEURAL = 3
        const val KIND_FLAGELLUM = 4

        /**
         * Байт сигнала: -1..1 → 0..254, ноль — ровно 127.
         *
         * Смещение 127, а не 127.5, чтобы ноль кодировался точно: «сигнала нет» должно
         * давать ровно ноль и в шейдере, иначе по каждой молчащей связи бежали бы
         * еле видные импульсы. Шаг — 1/127, всё, что меньше половины шага, — тишина.
         * Обратно в шейдере: `(byte - 127.0) / 127.0`.
         */
        fun signalByte(signal: Float): Int {
            if (signal.isNaN()) return SIGNAL_ZERO
            val clamped = signal.coerceIn(-1f, 1f)
            return (clamped * 127f).roundToInt() + SIGNAL_ZERO
        }

        const val SIGNAL_ZERO = 127

        fun params(kind: Int, signal: Float, seed: Int): Int =
            (kind and 0xFF) or
                (signalByte(signal) shl 8) or
                ((seed and 0xFF) shl 16)
    }
}
