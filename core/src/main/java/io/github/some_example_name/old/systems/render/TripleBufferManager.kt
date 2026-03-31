package io.github.some_example_name.old.systems.render

import io.github.some_example_name.old.entities.ParticleEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class TripleBufferManager(
    val particleEntity: ParticleEntity
) {

    companion object {
        const val INITIAL_CAPACITY = 50_000
    }

    private val buffers = arrayOf(
        allocateBuffer(INITIAL_CAPACITY).apply { clear(); flip() },  // ← remaining = 0
        allocateBuffer(INITIAL_CAPACITY).apply { clear(); flip() },
        allocateBuffer(INITIAL_CAPACITY).apply { clear(); flip() }
    )

    private val latestIndex = AtomicInteger(0)
    private var producerIndex = 1
    private var lastReturnedIndex = -1

    private fun allocateBuffer(numParticles: Int): ByteBuffer =
        ByteBuffer.allocateDirect(numParticles * 16).order(ByteOrder.nativeOrder())

    private fun ensureCapacityForWrite(neededParticles: Int) {
        val buf = buffers[producerIndex]
        val currentCapacity = buf.capacity() / 16
        if (neededParticles <= currentCapacity) return

        var newCapacity = currentCapacity.toDouble()
        do {
            newCapacity *= 1.5
        } while (newCapacity < neededParticles)

        val finalCapacity = newCapacity.toInt().coerceAtLeast(neededParticles)
        buffers[producerIndex] = allocateBuffer(finalCapacity)
    }

    private fun putBufferData(clear: Boolean) {
        val needed = particleEntity.lastId + 1
        ensureCapacityForWrite(needed)

        val target = buffers[producerIndex]
        target.clear()

        with(particleEntity) {
            if (!clear) {
                for (i in 0..<aliveList.size) {
                    val idx = aliveList.getInt(i)
                    target.putFloat(x[idx])
                    target.putFloat(y[idx])
                    target.putFloat(radius[idx])
                    target.putInt(color[idx])
                }
            } else {
                val currentCapacity = buffers[producerIndex].capacity() / 16
                for (i in 0..<currentCapacity) {
                    target.putFloat(0f)
                    target.putFloat(0f)
                    target.putFloat(0.5f)
                    target.putInt(0)
                }
            }
        }
        target.flip()
    }

    fun updateAndCommitProducer(clear: Boolean) {
        putBufferData(clear)

        val oldLatestIndex = latestIndex.getAndSet(producerIndex)
        // Теперь producerIndex = третий буфер (ни latest, ни тот, который может читать render)
        producerIndex = 3 - producerIndex - oldLatestIndex
    }

    fun getAndSwapConsumer(): Pair<ByteBuffer, Boolean> {
        val currentLatestIndex = latestIndex.get()
        val currentLatest = buffers[currentLatestIndex]

        val isNewFrame = currentLatestIndex != lastReturnedIndex
        if (isNewFrame) {
            lastReturnedIndex = currentLatestIndex
        }

        currentLatest.rewind()
        return currentLatest to isNewFrame
    }
}
