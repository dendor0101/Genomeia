package io.github.some_example_name.old.logic

import io.github.some_example_name.old.good_one.time
import io.github.some_example_name.old.logic.GridManager.Companion.GRID_SIZE
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_CELL_HEIGHT
import io.github.some_example_name.old.logic.GridManager.Companion.WORLD_SIZE_TYPE
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

@Volatile
var isPlay = true
// Флаг для контроля работы потока

class ThreadManager(private val gridManager: GridManager, private val cellManager: CellManager) {

    val executor = Executors.newFixedThreadPool(THREAD_COUNT)
    val futures = mutableListOf<java.util.concurrent.Future<*>>()
    private val frameTimeTarget = 16.66666667//6.94 // миллисекунды на кадр
    private val deltaTime = 0.016666666f
    val updateDone = Semaphore(0)

    private val updateThread = Thread { runUpdateLoop() }  // Поток для цикла обновлений
    var isRunning = false

    init {
//        println(CHUNK_SIZE_Y)
    }

    // Запуск потока
    fun startUpdateThread() {
        if (!isRunning) {
            isRunning = true
            updateThread.start()
        }
    }

    // Остановка потока
    fun stopUpdateThread() {
        isRunning = false
        updateThread.interrupt()  // Прерываем sleep, если есть
    }

    // Основной цикл в отдельном потоке
    private fun runUpdateLoop() {
        while (isRunning) {
            if (isPlay) {
                val startTime = System.nanoTime()

                cellManager.updateBeforeCycle()
                val start1 = System.nanoTime()
                update()//2.4123 ms
                val end1 = System.nanoTime()
                cellManager.updateAfterCycle()
                val end2 = System.nanoTime()
                cellManager.updateDraw()
                val end3 = System.nanoTime()
                updateDone.release()
//                println("${(end2 - end1) / 1_000_000.0}")

                val elapsedTime = (System.nanoTime() - startTime) / 1_000_000.0 // в миллисекундах
                val sleepTime = frameTimeTarget - elapsedTime

//                println("Warning: Chunk processing took ${elapsedTime}ms (target: $frameTimeTarget ms); debug update:${(end1 - start1) / 1_000_000.0}; updateAfterCycle:${(end2 - end1) / 1_000_000.0}; updateDraw:${(end3 - end2) / 1_000_000.0};")
                if (sleepTime > 0) {
                    val millis = sleepTime.toLong()
                    val nanos = ((sleepTime - millis) * 1_000_000.0).toInt()
                    Thread.sleep(millis, nanos)
                } else {
                    // Логирование предупреждения, если обработка заняла больше времени
//                    println("Warning: Chunk processing took ${elapsedTime}ms (target: $frameTimeTarget ms); debug update:${(end1 - start1) / 1_000_000.0}; updateAfterCycle:${(end2 - end1) / 1_000_000.0}; updateDraw:${(end3 - end2) / 1_000_000.0};")
                }
                time += deltaTime
            } else {
                Thread.sleep(16)
            }
        }
    }

    private fun update() {
        // Фаза 1: нечётные чанки
        var threadCounter = 0
        for (i in 1 until TOTAL_CHUNKS step 2) {
            val start = i * CHUNK_SIZE
            val end = if (i == TOTAL_CHUNKS - 1) GRID_SIZE else (i + 1) * CHUNK_SIZE
            val threadId = threadCounter++
            futures.add(executor.submit {
                processGridChunkWithTiming(start, end, threadId)
            })
        }
        futures.forEach { it.get() }
        futures.clear()
        threadCounter = 0
        // Фаза 2: чётные чанки
        for (i in 0 until TOTAL_CHUNKS step 2) {
            val start = i * CHUNK_SIZE
            val end = if (i == TOTAL_CHUNKS - 1) GRID_SIZE else (i + 1) * CHUNK_SIZE
            val threadId = threadCounter++
            futures.add(executor.submit {
                processGridChunkWithTiming(start, end, threadId)
            })
        }
        futures.forEach { it.get() }
        futures.clear()
    }

    private fun processGridChunkWithTiming(start: Int, end: Int, threadId: Int) {
        for (i in start until end) {
            if (gridManager.cellCounts[i] > 0) {
                val x = i % GridManager.WORLD_CELL_WIDTH
                val y = i / GridManager.WORLD_CELL_WIDTH
                val cells = gridManager.getCells(x, y)

                cellManager.processCellClosest(cells, threadId)
                for (cellId in cells) {
                    cellManager.processCell(cellId, x, y, threadId)
                }
            }
        }
    }

    companion object {
        val THREAD_COUNT = WORLD_SIZE_TYPE.threadCount//maxOf(1, Runtime.getRuntime().availableProcessors() - 2)
        val TOTAL_CHUNKS = THREAD_COUNT * 2
        val CHUNK_SIZE = GRID_SIZE / TOTAL_CHUNKS
        val CHUNK_SIZE_Y = WORLD_CELL_HEIGHT / TOTAL_CHUNKS
    }

}
