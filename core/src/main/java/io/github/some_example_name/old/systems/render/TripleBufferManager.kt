package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.entities.ParticleEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

class TripleBufferManager(
    val particleEntity: ParticleEntity
) {

    companion object {
        const val INITIAL_CAPACITY = 50_000          // стартовый размер (в частицах)
        // Максимум убираем — буфер теперь полностью динамический.
        // При необходимости можно добавить позже.
    }

    // Два буфера, но теперь они могут «перерождаться» в больший размер
    private var bufferA: ByteBuffer = allocateBuffer(INITIAL_CAPACITY)
    private var bufferB: ByteBuffer = allocateBuffer(INITIAL_CAPACITY)

    private val latestBuffer = AtomicReference(bufferA)   // буфер, который видит рендер
    private var writeBuffer: ByteBuffer = bufferB         // куда пишет симуляция

    private var lastReturnedBuffer: ByteBuffer? = null    // для правильного isNewFrame (только в render thread)

    /**
     * Создаёт прямой ByteBuffer нужного размера (16 байт на частицу).
     */
    private fun allocateBuffer(numParticles: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(numParticles * 16).order(ByteOrder.nativeOrder())
    }

    /**
     * Увеличивает writeBuffer, если текущей ёмкости не хватает.
     * Рост — ×1.2 каждый раз, пока не хватит (экспоненциальный рост при резком увеличении частиц).
     */
    private fun ensureCapacityForWrite(neededParticles: Int) {
        val currentCapacity = writeBuffer.capacity() / 16
        if (neededParticles <= currentCapacity) return

        // Вычисляем новый размер с ростом 1.2×
        var newCapacity = currentCapacity.toDouble()
        do {
            newCapacity *= 1.5
        } while (newCapacity < neededParticles)

        val finalCapacity = newCapacity.toInt().coerceAtLeast(neededParticles)

        // Заменяем writeBuffer на новый больший буфер
        writeBuffer = allocateBuffer(finalCapacity)
    }

    private fun putBufferData() {
        val needed = particleEntity.lastId + 1

        // ← Здесь происходит вся динамика размера
        ensureCapacityForWrite(needed)

        writeBuffer.clear()
        with(particleEntity) {
            for (i in 0..<aliveList.size) {
                val aliveParticleIndex = aliveList.getInt(i)
                writeBuffer.putFloat(x[aliveParticleIndex])
                writeBuffer.putFloat(y[aliveParticleIndex])
                writeBuffer.putFloat(radius[aliveParticleIndex])
                writeBuffer.putInt(color[aliveParticleIndex])
            }
        }
        writeBuffer.flip()
    }

    fun updateAndCommitProducer() {
        putBufferData()

        // Публикуем новый буфер и забираем старый для следующей записи
        val old = latestBuffer.getAndSet(writeBuffer)
        writeBuffer = old
    }

    // Вызывается каждый кадр рендера
    fun getAndSwapConsumer(): Pair<ByteBuffer, Boolean> {
        val currentLatest = latestBuffer.get()

        val isNewFrame = currentLatest !== lastReturnedBuffer
        if (isNewFrame) {
            lastReturnedBuffer = currentLatest
        }

        currentLatest.rewind()
        return currentLatest to isNewFrame
    }
}
