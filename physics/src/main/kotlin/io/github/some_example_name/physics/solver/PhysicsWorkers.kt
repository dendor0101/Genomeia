package io.github.some_example_name.physics

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.LockSupport

/** Меньше частиц — все стадии в одном потоке (BodyState.activeWorkers). */
const val PARALLEL_MIN_PARTICLES = 4000

/** Работа стадии: [chunk] — номер куска, [worker] — номер воркера (0 — вызывающий поток). */
fun interface PhysicsJob {
    fun run(chunk: Int, worker: Int)
}

/**
 * ИСПОЛНИТЕЛЬ ПАРАЛЛЕЛЬНЫХ СТАДИЙ ФИЗИКИ. Библиотека не зависит от игры, поэтому пул
 * свой; игра может подставить обёртку над своим ParallelExecutor (BodyState.workers).
 *
 * Требование к любой реализации: [run] возвращается, когда выполнены все куски, и
 * все записи воркеров видны вызывающему потоку. Какой воркер взял какой кусок, на
 * результат не влияет: стадии пишут только в данные своего куска (см. ParallelBlocks).
 * Номер воркера нужен только для его личных счётчиков и черновиков.
 */
interface PhysicsWorkers {
    val count: Int
    fun run(chunks: Int, job: PhysicsJob)
    fun shutdown()

    companion object {
        private val pools = HashMap<Int, PhysicsWorkers>()

        /** Общий пул процесса на [count] воркеров: стенды создают много BodyState подряд. */
        @Synchronized
        fun shared(count: Int): PhysicsWorkers = pools.getOrPut(count) { SpinPhysicsWorkers(count) }
    }
}

/**
 * Постоянные потоки и барьер на спине — тот же приём, что ParallelExecutor игры (там
 * подробный разбор цены ExecutorService). Подшаг — это несколько стадий по десяткам
 * микросекунд, 32 раза за тик: на парковке и пробуждении потоков такое не живёт.
 *
 * РАЗДАЧА КУСКОВ — ОБЩИЙ КУРСОР, как у ParallelExecutor игры: блоки в толпе разного размера
 * (тело 300 клеток — один блок, тело 3000 — три по 1000), и равный по штукам дележ оставлял
 * воркеров ждать самого загруженного. Статическая раздача ради L2 ядра выигрыша не дала: опыт
 * 25.10 — 100k 3.62x против 3.62x; опыт 06.11 с постоянными наборами блоков — в пределах шума.
 *
 * Память (JMM): задание публикуется volatile-записью [epoch] после обычных записей
 * задания; воркер читает epoch, потом задание. Завершение — getAndDecrement счётчика,
 * главный поток крутится до нуля: все записи воркеров ему видны.
 */
class SpinPhysicsWorkers(override val count: Int,
                         private val spinLimit: Int = 200_000) : PhysicsWorkers {
    @Volatile private var epoch = 0L
    @Volatile private var running = true
    @Volatile private var failure: Throwable? = null
    private var job: PhysicsJob? = null
    private var chunks = 0

    /** Счётчик незавершённых и флаги парковки — каждый в своей кэш-линии. */
    private val control = AtomicIntegerArray((count + 5) * PADDING)
    /** Курсор — единственная общая запись стадии: 128 байт от соседей, префетчер тянет линии парой. */
    private val cursor = (count + 3) * PADDING

    private val threads = Array(count - 1) { k ->
        Thread({ loop(k + 1) }, "Physics-Worker-${k + 1}").apply { isDaemon = true; start() }
    }

    init {
        while (control.get(READY) < count - 1) Thread.onSpinWait()
    }

    /**
     * Пул занят стадией. Общий пул (shared) достаётся нескольким BodyState, и их могут
     * считать разные потоки: окно стенда прогревает отдельную копию тела в фоне. Два
     * одновременных run на одном пуле портят задание и вешают барьер — так и висло окно
     * 25.10 (Windows закрыла его как зависшее). Второй вызывающий считает куски сам: у
     * каждого BodyState свои черновики, а результат от раздачи не зависит.
     */
    private val busy = AtomicBoolean(false)

    override fun run(chunks: Int, job: PhysicsJob) {
        if (chunks <= 0) return
        if (count == 1 || chunks == 1 || !busy.compareAndSet(false, true)) {
            for (k in 0 until chunks) job.run(k, 0)
            return
        }
        try {
            runShared(chunks, job)
        } finally {
            busy.set(false)
        }
    }

    private fun runShared(chunks: Int, job: PhysicsJob) {
        this.job = job; this.chunks = chunks
        control.set(cursor, 0)
        control.set(PENDING, count)
        epoch++
        for (k in threads.indices) if (control.get(parkSlot(k + 1)) != 0) LockSupport.unpark(threads[k])
        try {
            drain(0, job, chunks)
        } finally {
            control.getAndDecrement(PENDING)
        }
        var spins = 0
        while (control.get(PENDING) != 0) {
            Thread.onSpinWait()
            if (++spins >= spinLimit) { Thread.yield(); spins = 0 }
        }
        val f = failure
        if (f != null) { failure = null; throw IllegalStateException("воркер физики упал", f) }
    }

    private fun drain(worker: Int, job: PhysicsJob, chunks: Int) {
        while (true) {
            val k = control.getAndIncrement(cursor)
            if (k >= chunks) return
            job.run(k, worker)
        }
    }

    private fun loop(worker: Int) {
        var seen = 0L
        control.getAndIncrement(READY)
        while (running) {
            var spins = 0
            var park = 50_000L
            while (epoch == seen) {
                if (!running) return
                if (spins < spinLimit) { Thread.onSpinWait(); spins++; continue }
                // Флаг до повторной проверки: иначе главный мог опубликовать задание
                // между проверкой и парковкой и не разбудить нас.
                control.set(parkSlot(worker), 1)
                if (epoch == seen && running) LockSupport.parkNanos(park)
                control.set(parkSlot(worker), 0)
                park = minOf(park * 2, 2_000_000L)
            }
            seen = epoch
            try {
                drain(worker, job!!, chunks)
            } catch (t: Throwable) {
                if (failure == null) failure = t
            } finally {
                control.getAndDecrement(PENDING)
            }
        }
    }

    override fun shutdown() {
        running = false
        epoch++
        for (t in threads) LockSupport.unpark(t)
    }

    private fun parkSlot(worker: Int) = (worker + 2) * PADDING

    private companion object {
        /** 16 int = 64 байта: счётчик и флаги не делят кэш-линию. */
        const val PADDING = 16
        const val PENDING = 0
        const val READY = PADDING
    }
}
