package com.example.concurrent

import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.WorldResizable
import io.github.some_example_name.old.systems.simulation.SimulationData
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal class ThreadManager(
    val simulationData: SimulationData
): WorldResizable {

    var executor: ExecutorService = createDaemonFixedThreadPool()
    val futures = mutableListOf<Future<*>>()

    var isRunning = false

    private fun createDaemonFixedThreadPool(): ExecutorService {
        return Executors.newFixedThreadPool(DISimulationContainer.threadCount) { runnable ->
            val thread = Thread(runnable)
            thread.isDaemon = true
            thread.name = "Sim-Worker-${DISimulationContainer.threadCount}"
            thread
        }
    }

    private fun shutdownExecutor(exec: ExecutorService) {
        exec.shutdown()
        try {
            if (!exec.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
                exec.shutdownNow()
            }
        } catch (e: InterruptedException) {
            exec.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    fun dispose() {
        isRunning = false
        shutdownExecutor(executor)
        futures.clear()
    }

    fun stopSimulationLoop() {
        isRunning = false
    }

    inline fun runUpdateLoop(onUpdateTick: () -> Unit) {
        var lastTime = System.nanoTime()
        var accumulator = 0.0

        // для UPS
        var updatesThisSecond = 0
        var lastUpsTime = System.nanoTime()

        // кэшируем последнее значение, чтобы реагировать на изменение на лету
        var lastTargetUPS = simulationData.targetUPS
        var deltaTimePerTick = 1.0 / lastTargetUPS.toDouble()

        try {
            while (isRunning) {
                if (!simulationData.isPlay) {
                    try {
                        Thread.sleep(16)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                val currentTime = System.nanoTime()
                val frameTime = minOf((currentTime - lastTime) / 1_000_000_000.0, 0.25)
                lastTime = currentTime

                // === Реакция на изменение targetUPS на лету ===
                if (!simulationData.maxSpeed && lastTargetUPS != simulationData.targetUPS) {
                    deltaTimePerTick = 1.0 / simulationData.targetUPS.toDouble()
                    accumulator = 0.0          // сбрасываем, чтобы не было резкого "взрыва" обновлений
                    lastTargetUPS = simulationData.targetUPS
                }

                accumulator += frameTime

                if (simulationData.maxSpeed) {
                    onUpdateTick.invoke()
                    updatesThisSecond++
                } else {
                    // обычный фиксированный timestep
                    while (accumulator >= deltaTimePerTick) {
                        onUpdateTick.invoke()
                        accumulator -= deltaTimePerTick
                        updatesThisSecond++
                    }

                    // === спим остаток тика (даже если обновлений в этом кадре не было) ===
                    val elapsed = (System.nanoTime() - currentTime) / 1_000_000_000.0
                    val sleepTime = deltaTimePerTick - elapsed
                    if (sleepTime > 0.001) {
                        try {
                            Thread.sleep(
                                (sleepTime * 1000).toLong(),
                                ((sleepTime * 1_000_000) % 1_000_000).toInt()
                            )
                        } catch (_: InterruptedException) {
                            break
                        }
                    } else if (sleepTime > 0) {
                        Thread.yield()   // совсем маленький остаток — просто уступаем CPU
                    }
                }

                // === UPS каждую секунду (реальное количество вызовов onUpdateTick) ===
                val now = System.nanoTime()
                if ((now - lastUpsTime) >= 1_000_000_000L) {
                    simulationData.ups = updatesThisSecond
                    updatesThisSecond = 0
                    lastUpsTime = now
                }
            }
        } catch (_: InterruptedException) {
            // выход // exit
        }
    }

    inline fun runChunkStage(
        isOdd: Boolean,
        crossinline job: (start: Int, end: Int, threadId: Int) -> Unit
    ) {
        var threadCounter = 0
        val first = if (isOdd) 1 else 0
        for (i in first until DISimulationContainer.totalChunks step 2) {
            val start = i * DISimulationContainer.chunkSize
            val end = if (i == DISimulationContainer.totalChunks - 1) DISimulationContainer.gridSize else (i + 1) * DISimulationContainer.chunkSize
            val threadId = threadCounter++
            futures.add(executor.submit { job(start, end, threadId) })
        }
        futures.forEach { it.get() }   // <- barrier for this stage
        futures.clear()
    }

    override fun resize() {
        val oldExecutor = executor
        executor = createDaemonFixedThreadPool()

        shutdownExecutor(oldExecutor)
        futures.clear()
    }
}
