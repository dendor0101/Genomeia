package io.github.some_example_name.old.good_one

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector2
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TripleBufferExample : ApplicationAdapter() {
    // Размер буфера (пример: 100 частиц, каждая с x, y позицией — 200 floats)
    private val BUFFER_SIZE = 200

    // Три буфера: предвыделенные массивы float[]
    private val bufferA = FloatArray(BUFFER_SIZE)
    private val bufferB = FloatArray(BUFFER_SIZE)
    private val bufferC = FloatArray(BUFFER_SIZE)

    // Атомарные ссылки для обмена буферами
    private val writeBuffer = AtomicReference(bufferA)  // Текущий для записи (producer)
    private val readyBuffer = AtomicReference(bufferB)  // Готовый для чтения (может быть null, если ничего не готово)
    private val readBuffer = AtomicReference(bufferC)   // Текущий для чтения (consumer)

    private lateinit var batch: SpriteBatch
    private val particles = Array(100) { Vector2() }  // Для симуляции физики

    // Поток физики
    private lateinit var physicsThread: Thread

    override fun create() {
        batch = SpriteBatch()

        // Инициализация частиц (пример)
        for (i in particles.indices) {
            particles[i].set((Math.random() * Gdx.graphics.width).toFloat(), (Math.random() * Gdx.graphics.height).toFloat())
        }

        // Запуск потока физики
        physicsThread = thread(start = true, isDaemon = true) {
            while (true) {
                updatePhysics()  // Симуляция физики
                swapProducerBuffers()  // Атомарный своп для producer
                Thread.sleep(16)  // ~60 FPS, адаптируйте под вашу физику
            }
        }
    }

    // Обновление физики: запись данных в текущий writeBuffer
    private fun updatePhysics() {
        val currentWrite = writeBuffer.get()
        for (i in particles.indices) {
            // Симуляция: случайное движение
            particles[i].add(((Math.random() - 0.5) * 2).toFloat(), ((Math.random() - 0.5) * 2).toFloat())
            val index = i * 2
            currentWrite[index] = particles[i].x
            currentWrite[index + 1] = particles[i].y
        }
    }

    // Producer: после записи, атомарно меняем writeBuffer на readyBuffer
    private fun swapProducerBuffers() {
        // Получаем текущий write и старый ready
        val newReady = writeBuffer.get()
        val oldReady = readyBuffer.getAndSet(newReady)

        // Меняем writeBuffer на старый ready (или на свободный, если oldReady был null)
        if (oldReady != null) {
            writeBuffer.set(oldReady)
        } else {
            // Если нет старого ready, используем третий буфер (но в тройном это редкость)
            // В нашем setup третий всегда доступен через ротацию
        }
    }

    override fun render() {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        swapConsumerBuffers()  // Атомарный своп для consumer перед рендером

        val currentRead = readBuffer.get()
        batch.begin()
        for (i in 0 until BUFFER_SIZE step 2) {
            // Пример рендера: draw точку или спрайт на позиции из буфера
            // В реальности: используйте Mesh или VertexBuffer для GPU
            // Здесь упрощённо: batch.draw(texture, currentRead[i], currentRead[i + 1])
            // Для демонстрации просто представим, что данные передаются на GPU
        }
        batch.end()

        // Здесь данные из currentRead уже на GPU (в вашем коде — bind VBO и glBufferData)
    }

    // Consumer: перед рендером, если есть readyBuffer, атомарно меняем на readBuffer
    private fun swapConsumerBuffers() {
        val newRead = readyBuffer.getAndSet(null)  // Забираем ready и сбрасываем его
        if (newRead != null) {
            val oldRead = readBuffer.getAndSet(newRead)
            // Возвращаем oldRead в пул (для producer в будущем)
            // В тройном буфере это ротация: oldRead станет доступным для write
            writeBuffer.compareAndSet(newRead, oldRead)  // Атомарно, если не изменилось
        }
    }

    override fun dispose() {
        batch.dispose()
        physicsThread.interrupt()  // Остановка потока
    }
}
