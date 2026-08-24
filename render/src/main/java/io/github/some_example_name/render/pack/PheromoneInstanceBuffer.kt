package io.github.some_example_name.render.pack

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Буфер инстансов феромона: один тексель RGBA32UI на штуку.
 *
 * РАСКЛАДКА (16 байт, порядок байт нативный)
 * ------------------------------------------
 *   [0..3]   float x
 *   [4..7]   float y
 *   [8..11]  float a      — накопленная концентрация
 *   [12..15] int   color  — RGBA8
 *
 * Все четыре поля читаются в шейдере как uint и возвращаются в float через
 * uintBitsToFloat (shaders/pheromone/pheromone_pc.vert). Целочисленная текстура здесь
 * не прихоть: во float-текстуре драйвер вправе канонизировать NaN, а это испортило бы
 * биты цвета, который лежит рядом с координатами.
 */
class PheromoneInstanceBuffer(initialCapacity: Int = INITIAL_CAPACITY) {

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

    fun put(x: Float, y: Float, a: Float, color: Int) {
        if (buffer.remaining() < STRUCT_SIZE) ensureCapacity(written + 1)
        buffer.putFloat(x)
        buffer.putFloat(y)
        buffer.putFloat(a)
        buffer.putInt(color)
        written++
    }

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
        /** 16 байт = 1 тексель RGBA32UI. Менять только вместе с pheromone_pc.vert. */
        const val STRUCT_SIZE = 16

        const val INITIAL_CAPACITY = 1_000

        private const val GROWTH_FACTOR = 1.5
    }
}
