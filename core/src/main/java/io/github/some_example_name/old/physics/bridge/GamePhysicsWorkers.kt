package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.old.systems.simulation.ParallelExecutor
import io.github.some_example_name.old.systems.simulation.Phase
import io.github.some_example_name.old.systems.simulation.ThreadManager
import io.github.some_example_name.physics.PhysicsJob
import io.github.some_example_name.physics.PhysicsWorkers

/**
 * Воркеры физики-библиотеки — пул игры (план, раздел 4): два пула на одних ядрах дрались бы
 * в спин-петлях. Исполнитель берётся из [threads] на каждый вызов: ThreadManager пересоздаёт
 * его при входе в симуляцию и смене размера мира.
 *
 * Раздача у игры динамическая (курсор работ), у своего пула библиотеки — статическая ради
 * блоков в L2 ядра; на результат это не влияет (стадии пишут только в свой кусок), теряется
 * только привязка к L2 — замерить на этапе D.
 *
 * Задание держится в поле, а не в захватывающей лямбде: подшаг — десятки стадий за тик, и
 * лямбда на каждую стадию была бы выделением в самом горячем месте.
 */
class GamePhysicsWorkers(private val threads: ThreadManager) : PhysicsWorkers {
    private var job: PhysicsJob? = null
    private val chunk = ParallelExecutor.ChunkJob { c, w -> job!!.run(c, w) }

    override val count: Int get() = threads.executor.workerCount

    override fun run(chunks: Int, job: PhysicsJob) {
        this.job = job
        threads.executor.runChunks(chunks, Phase.ARRANGE, chunk)
        this.job = null
    }

    /** Пул принадлежит игре: останавливает его ThreadManager. */
    override fun shutdown() {}
}
