package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.commands.WorkOrder

import io.github.some_example_name.old.core.DISimulationContainer.chunkSize
import io.github.some_example_name.old.core.DISimulationContainer.gridSize
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.core.DISimulationContainer.totalChunks
import io.github.some_example_name.old.core.PlatformTuning
import io.github.some_example_name.old.core.WORKER_COUNT_OVERRIDE
import io.github.some_example_name.old.core.WorldResizable

class ThreadManager(
    val simulationData: SimulationData
): WorldResizable {

    /**
     * Пул постоянных воркеров вместо ExecutorService. Раньше каждая стадия стоила N
     * аллокаций FutureTask, N вставок в очередь пула, N пробуждений потоков и N парковок
     * главного потока на Future.get — порядка 15-40 мкс на стадию, то есть при 6-8 стадиях
     * 15-30% времени тика уходило в накладные расходы планировщика. См. ParallelExecutor.
     */
    var executor = ParallelExecutor(workerCount())
        private set

    var isRunning = false

    /**
     * Ключи порядка отложенных команд мира (см. WorkOrder): стадия выставляет буферу ключ
     * своей работы перед тем, как её считать. null — порядок не отслеживается.
     */
    var workOrder: WorkOrder? = null

    fun dispose() {
        isRunning = false
        executor.shutdown()
    }

    /**
     * Поднимает пул, если он был остановлен через [dispose]. Вызывать при входе в экран
     * симуляции, до старта потока обновления.
     *
     * Нужно потому, что ThreadManager — синглтон контейнера, а SimulationScreen создаётся
     * и уничтожается при каждом заходе в симуляцию. На десктопе оставленный после выхода
     * пул умирал вместе с JVM (потоки daemon), на Android процесс переживает Activity, и
     * воркеры продолжали бы спать-просыпаться до самой смерти процесса.
     */
    fun ensureStarted() {
        if (executor.isShutdown) {
            executor = ParallelExecutor(workerCount())
        }
    }

    fun stopSimulationLoop() {
        isRunning = false
    }

    /**
     * Цикл тиков: на максимальной скорости — подряд, иначе — по расписанию [simulationData].targetUPS.
     *
     * ПОЧЕМУ ПО РАСПИСАНИЮ, А НЕ «СПАТЬ ОСТАТОК ТИКА»
     * ----------------------------------------------
     * Прежний цикл копил время в аккумуляторе и после каждого тика спал «длину тика минус
     * время его расчёта». Thread.sleep на Windows просыпается на 1–7 мс позже, это опоздание
     * оседало в аккумуляторе, и раз в несколько тиков цикл отдавал долг ДВУМЯ тиками
     * подряд — с разницей в доли миллисекунды. Замер 21.10 (PROFILE_INTERPOLATION, 144 Гц):
     * при 60 UPS рендер не видел 2–3 снимка в секунду, при 30 UPS промежуток между тиками
     * был ~40 мс вместо 33. Пропущенный снимок — это скачок тела на целый тик вперёд
     * поверх плавной интерполяции.
     *
     * Теперь у каждого тика своё время (nextTickAt += длина тика), и цикл спит до него.
     * После этого на том же замере: 30 UPS — ни одного пропуска, промежуток 32.5–36.5 мс.
     * Опоздание сна сдвигает только этот тик и не копится: следующий тик ждёт своего
     * времени, а не «полного тика после этого». Два тика подряд бывают только при
     * настоящем отставании симуляции; отставание больше [MAX_DEBT_NANOS] прощается, как
     * раньше прощалось всё сверх 0.25 с, — иначе после подвисания мир бы долго «догонял».
     *
     * Время на логику мира не влияет: меняется только, КОГДА идут тики, а не что в них
     * происходит.
     */
    inline fun runUpdateLoop(onUpdateTick: () -> Unit) {
        // для UPS
        var updatesThisSecond = 0
        var lastUpsTime = System.nanoTime()

        // кэшируем последнее значение, чтобы реагировать на изменение на лету
        var lastTargetUPS = simulationData.targetUPS
        var tickNanos = NANOS_PER_SECOND / lastTargetUPS
        var nextTickAt = System.nanoTime()

        try {
            while (isRunning) {
                if (!simulationData.isPlay) {
                    try {
                        Thread.sleep(16)
                    } catch (_: InterruptedException) {
                        break
                    }
                    // После паузы — с чистого листа: пауза не долг, догонять её нельзя.
                    nextTickAt = System.nanoTime()
                    continue
                }

                // === Реакция на изменение targetUPS на лету ===
                if (!simulationData.maxSpeed && lastTargetUPS != simulationData.targetUPS) {
                    lastTargetUPS = simulationData.targetUPS
                    tickNanos = NANOS_PER_SECOND / lastTargetUPS
                    nextTickAt = System.nanoTime() // без резкого "взрыва" обновлений
                }

                if (simulationData.maxSpeed) {
                    onUpdateTick.invoke()
                    updatesThisSecond++
                    nextTickAt = System.nanoTime()
                } else {
                    val now = System.nanoTime()
                    val wait = nextTickAt - now
                    if (wait <= 0L) {
                        onUpdateTick.invoke()
                        updatesThisSecond++
                        nextTickAt += tickNanos
                        if (now - nextTickAt > MAX_DEBT_NANOS) nextTickAt = now
                    } else if (wait > SLEEP_MIN_NANOS) {
                        try {
                            Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                        } catch (_: InterruptedException) {
                            break
                        }
                    } else {
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

    /**
     * Стадия по чанкам сетки одной чётности.
     *
     * Чанки раздаются динамически: освободившийся воркер берёт следующий сам. Раньше была
     * жёсткая привязка "чанк i потоку i", а работа между чанками распределена крайне
     * неравномерно — организмы кучкуются, и в одном чанке могут быть тысячи частиц, в
     * соседнем десятки. Время стадии определяется самым загруженным потоком, поэтому при
     * статической раздаче стадия упиралась в один перегруженный чанк, пока остальные ядра
     * стояли на барьере. Теперь ядро, разобравшее пустой чанк, идёт помогать с очередью.
     *
     * [slot] — это номер чанка внутри своей чётности (chunkIndex / 2 в старой нумерации).
     * По нему индексируются per-chunk структуры: буферы команд, стеки позиций, списки
     * связей. Два одновременно обрабатываемых чанка одной чётности не соседствуют в
     * пространстве, поэтому потоки не пишут в одни и те же частицы, а разные slot'ы
     * не пишут в одни и те же вспомогательные структуры — синхронизация не нужна.
     */
    inline fun runChunkStage(
        isOdd: Boolean,
        stageId: Int = -1,
        crossinline job: (start: Int, end: Int, slot: Int) -> Unit
    ) {
        val first = if (isOdd) 1 else 0
        val slots = (totalChunks - first + 1) / 2

        val order = workOrder
        val stage = order?.beginStage() ?: 0L
        executor.runChunks(slots, stageId) { slot, _ ->
            val chunk = first + slot * 2
            val start = chunk * chunkSize
            val end = if (chunk == totalChunks - 1) gridSize else (chunk + 1) * chunkSize
            // Буфер команд здесь — по слоту, а не по воркеру.
            order?.enter(slot, stage, slot)
            job(start, end, slot)
        }
        order?.endStage(stage)
    }

    /**
     * Стадия, работа которой уже разложена по слотам заранее (списки связей, стеки частиц).
     * Раздача тоже динамическая: слоты неравномерны по объёму работы ровно так же, как чанки.
     */
    inline fun runSlotStage(slotCount: Int, stageId: Int = -1, crossinline job: (slot: Int) -> Unit) {
        val order = workOrder
        val stage = order?.beginStage() ?: 0L
        executor.runChunks(slotCount, stageId) { slot, _ ->
            order?.enter(slot, stage, slot)
            job(slot)
        }
        order?.endStage(stage)
    }

    /**
     * Стадия, где работа и воркер — РАЗНЫЕ вещи.
     *
     * [runSlotStage] отдаёт один номер, который вызывающие используют сразу в двух ролях:
     * как «что считать» и как индекс в per-thread структурах (worldCommandBuffer, стеки,
     * счётчики). Пока работ было ровно threadCount, это совпадало. Обход по организмам
     * ломает совпадение: организмов может быть и девять, и сотня, а буферов команд
     * по-прежнему threadCount, и слот №9 писал бы за границу массива.
     *
     * Здесь [work] — номер работы (организм), [workerId] — кто её взял; в per-thread
     * структуры надо индексироваться вторым. Воркер за раз держит ровно одну работу,
     * поэтому два одновременных workerId различны, и гонки на буферах нет.
     */
    inline fun runWorkStage(
        workCount: Int,
        stageId: Int = -1,
        crossinline job: (work: Int, workerId: Int) -> Unit
    ) {
        val order = workOrder
        val stage = order?.beginStage() ?: 0L
        executor.runChunks(workCount, stageId) { work, workerId ->
            // Буфер команд — по воркеру, а ключ — по работе: кто бы её ни взял, её команды
            // применятся на её месте (WorkOrder).
            order?.enter(workerId, stage, work)
            job(work, workerId)
        }
        order?.endStage(stage)
    }

    override fun resize() {
        val old = executor
        executor = ParallelExecutor(workerCount())
        old.shutdown()
    }

    companion object {
        /**
         * Число потоков теперь не равно числу чанков.
         *
         * threadCount по смыслу это количество ПРОСТРАНСТВЕННЫХ СЛОТОВ в стадии
         * (gridHeight / chunkHeight / 2), и оно задаётся геометрией мира, а не железом.
         * Уменьшать chunkHeight, чтобы получить больше слотов, нельзя: поток, считающий
         * связи своего чанка, пишет в скорости частиц на расстояние до sqrt(linkMaxLength2)
         * клеток за его границы, поэтому между чанками одной чётности нужен зазор не меньше
         * двух таких длин — при chunkHeight = 8 и максимальной длине связи 3 это выполняется
         * ровно с запасом 2. Уменьшение чанка сразу даёт гонку на vx/vy.
         *
         * Зато воркеров можно сделать МЕНЬШЕ, чем слотов, и от этого только лучше:
         *  - на машине с 4 ядрами 8 спинящих воркеров дрались бы за ядра, а спин при
         *    переподписке — это чистая потеря (воркер жжёт такты, пока владелец его чанка
         *    не получит квант);
         *  - когда слотов больше, чем воркеров, динамическая раздача наконец начинает
         *    работать по-настоящему: перегруженный слот берёт кто-то один, а его пустые
         *    слоты разбирают остальные.
         *
         * Верхняя граница — число слотов: больше воркеров, чем слотов, всё равно нечем занять.
         *
         * availableProcessors() возвращает ЛОГИЧЕСКИЕ ядра, поэтому на машине с SMT он
         * вдвое завышает полезное число спинящих воркеров: два воркера на одном физическом
         * ядре делят L1/L2 и отнимают такты друг у друга в спин-петле. Определить число
         * физических ядер из JVM переносимо нельзя, поэтому оно задаётся константой
         * WORKER_COUNT_OVERRIDE (0 — прежнее поведение).
         *
         * На мобильных та же проблема выглядит иначе: там availableProcessors() возвращает
         * все ядра всех кластеров, включая little, которые вдвое-втрое медленнее. Значение
         * туда приходит от лаунчера через PlatformTuning.performanceCoreCount и имеет
         * приоритет над WORKER_COUNT_OVERRIDE: константа подобрана под конкретный десктоп
         * разработчика и на телефоне заведомо не имеет смысла.
         */
        fun workerCount(): Int {
            val hardware = when {
                PlatformTuning.performanceCoreCount > 0 -> PlatformTuning.performanceCoreCount
                WORKER_COUNT_OVERRIDE > 0 && !PlatformTuning.isMobile -> WORKER_COUNT_OVERRIDE
                else -> Runtime.getRuntime().availableProcessors()
            }
            return minOf(threadCount, hardware)
        }

        const val NANOS_PER_SECOND = 1_000_000_000L

        /**
         * Сколько отставания от расписания тиков цикл ещё догоняет; больше — прощает (см.
         * runUpdateLoop). Столько же, сколько прежний цикл: он обрезал кадр до 0.25 с.
         */
        const val MAX_DEBT_NANOS = 250_000_000L

        /** Короче этого не спим, а уступаем процессор: сон на Windows грубее миллисекунды. */
        const val SLEEP_MIN_NANOS = 1_000_000L
    }
}
