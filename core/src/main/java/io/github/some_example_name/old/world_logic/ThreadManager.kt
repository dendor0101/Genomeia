package io.github.some_example_name.old.world_logic

import io.github.some_example_name.old.good_one.time
import io.github.some_example_name.old.world_logic.GridManager.Companion.GRID_SIZE
import io.github.some_example_name.old.world_logic.GridManager.Companion.WORLD_CELL_HEIGHT
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@Volatile
var isPlay = true

@Volatile
var maxSpeed = false
// Флаг для контроля работы потока // Flag for controlling the flow operation
@Volatile
var ups = 60

interface ThreadManagerPlug {
    val executor: ExecutorService
    val futures: MutableList<Future<*>>
    fun startUpdateThread()
    fun stopUpdateThread()
    fun dispose()
}

class ThreadManagerPlugImpl(
) : ThreadManagerPlug {
    override val executor: ExecutorService = Executors.newFixedThreadPool(1)
    override val futures = mutableListOf<Future<*>>()
    override fun startUpdateThread() {}
    override fun stopUpdateThread() {}
    override fun dispose() {}
}

class ThreadManager(private val gridManager: GridManager, private val cellManager: CellManager): ThreadManagerPlug {

    override val executor = Executors.newFixedThreadPool(THREAD_COUNT)
    override val futures = mutableListOf<Future<*>>()
    private val frameTimeTarget = 16.66666667 // миллисекунды на кадр // milliseconds per frame
    private val deltaTime = 0.016666666f
    private val updateThread = Thread { runUpdateLoop() }  // Поток для цикла обновлений // Stream for the update cycle
    var isRunning = false
    var tickCounter = 0
    var isUpdatePheromone = false

    // Запуск потока // Starting a thread
    override fun startUpdateThread() {
        if (!isRunning) {
            isRunning = true
            updateThread.start()
        }
    }

    // Остановка потока // Stopping a thread
    override fun stopUpdateThread() {
        isRunning = false
        updateThread.interrupt()  // Прерываем sleep, если есть // Interrupt sleep if there is one
    }

    override fun dispose() {
        // 1. Остановить главный поток // Stop the main thread
        isRunning = false
        updateThread.interrupt()
        try {
            updateThread.join(1000) // ждём до 1 секунды // wait up to 1 second
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        // 2. Остановить executor // Stop the executor
        executor.shutdown()
        try {
            if (!executor.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }

        futures.clear()
    }

    // Основной цикл в отдельном потоке // Main loop in a separate thread
    private fun runUpdateLoop() {
        var lastTime = System.nanoTime()
        var accumulator = 0.0
        var wasMaxSpeed = false

        // для UPS // for UPS
        var updatesThisSecond = 0
        var lastUpsTime = System.nanoTime()

        try {
            while (isRunning) {
                if (isPlay) {
                    val currentTime = System.nanoTime()
                    var frameTime = (currentTime - lastTime) / 1_000_000_000.0
                    lastTime = currentTime

                    // Сброс при выходе из maxSpeed // Reset when exiting maxSpeed
                    if (wasMaxSpeed && !maxSpeed) {
                        accumulator = 0.0
                        frameTime = 0.0
                        wasMaxSpeed = false
                    }

                    val clampedFrameTime = minOf(frameTime, 0.25)
                    accumulator += clampedFrameTime

                    if (maxSpeed) {
                        wasMaxSpeed = true
                        update()
                        //TODO Triple buffering
                        synchronized(cellManager) {
                            cellManager.updateAfterCycle()
                        }
                        time += deltaTime
                        updatesThisSecond++
                    } else {
                        // обычный фиксированный timestep // normal fixed timestep
                        var didUpdate = false
                        while (accumulator >= deltaTime) {
                            update()
                            synchronized(cellManager) {
                                cellManager.updateAfterCycle()
                            }
                            accumulator -= deltaTime
                            time += deltaTime
                            updatesThisSecond++
                            didUpdate = true
                        }

                        // спим остаток кадра // we sleep for the rest of the frame
                        if (didUpdate) {
                            val targetFrameTime = frameTimeTarget / 1000.0
                            val elapsed = (System.nanoTime() - currentTime) / 1_000_000_000.0
                            val sleepTime = targetFrameTime - elapsed
                            if (sleepTime > 0) {
                                try {
                                    Thread.sleep(
                                        (sleepTime * 1000).toLong(),
                                        ((sleepTime * 1_000_000) % 1000_000).toInt()
                                    )
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }
                    }

                    // === UPS вывод каждые 1 сек === UPS output every 1 sec ===
                    val now = System.nanoTime()
                    if ((now - lastUpsTime) >= 1_000_000_000L) {
                        ups = updatesThisSecond
                        updatesThisSecond = 0
                        lastUpsTime = now
                    }

                } else {
                    try {
                        Thread.sleep(16)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
            // выход // exit
        }
    }

    private fun update() {
        tickCounter++
        isUpdatePheromone = tickCounter % 8 == 0
        // Stage 1: Physics for all chunks (odd then even to avoid neighbor races)
        runChunkStage(isOdd = true) { start, end, threadId ->
            processGridChunkPhysics(start, end, threadId)
        }
        runChunkStage(isOdd = false) { start, end, threadId ->
            processGridChunkPhysics(start, end, threadId)
        }

        // Stage 2: Cell behaviors for all chunks (only AFTER all physics is done)
        runChunkStage(isOdd = true) { start, end, threadId ->
            processGridChunkCells(start, end, threadId, isOdd = true, isUpdatePheromone)
        }
        runChunkStage(isOdd = false) { start, end, threadId ->
            processGridChunkCells(start, end, threadId, isOdd = false, isUpdatePheromone)
        }
    }

    private inline fun runChunkStage(
        isOdd: Boolean,
        crossinline job: (start: Int, end: Int, threadId: Int) -> Unit
    ) {
        var threadCounter = 0
        val first = if (isOdd) 1 else 0
        for (i in first until TOTAL_CHUNKS step 2) {
            val start = i * CHUNK_SIZE
            val end = if (i == TOTAL_CHUNKS - 1) GRID_SIZE else (i + 1) * CHUNK_SIZE
            val threadId = threadCounter++
            futures.add(executor.submit { job(start, end, threadId) })
        }
        futures.forEach { it.get() }   // <- barrier for this stage
        futures.clear()
    }

    private fun processGridChunkPhysics(start: Int, end: Int, threadId: Int) {
        for (i in start until end) {
            val x = i % GridManager.WORLD_CELL_WIDTH
            val y = i / GridManager.WORLD_CELL_WIDTH

            if (gridManager.cellCounts[i] > 0) {
                val cells = gridManager.getCells(i)

                cellManager.processCellClosest(cells, threadId)

                // Physics & links
                for (cellId in cells) {
                    cellManager.processPhysics(cellId, x, y, threadId)
                }
            }
        }
    }

    private fun processGridChunkCells(start: Int, end: Int, threadId: Int, isOdd: Boolean, isUpdatePheromone: Boolean) {
        for (i in start until end) {
            val x = i % GridManager.WORLD_CELL_WIDTH
            val y = i / GridManager.WORLD_CELL_WIDTH

            // If this depends on physics results from neighbors, it's now safe:
            // all physics for all chunks has completed before we enter Stage 2.
            if (isUpdatePheromone) gridManager.pheromoneUpdate(cellManager, x, y)

            if (gridManager.cellCounts[i] > 0) {
                val cells = gridManager.getCells(i)
                for (cellId in cells) {
                    cellManager.processCell(cellId, x, y, threadId, isOdd)
                }
            }
        }
    }

    companion object {
        val THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 2
        val TOTAL_CHUNKS = THREAD_COUNT * 2
        val CHUNK_SIZE = GRID_SIZE / TOTAL_CHUNKS
        val CHUNK_SIZE_Y = WORLD_CELL_HEIGHT / TOTAL_CHUNKS
    }
}
