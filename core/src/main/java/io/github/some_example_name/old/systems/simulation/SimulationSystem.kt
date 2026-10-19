package io.github.some_example_name.old.systems.simulation

import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.commands.UserCommandManager
import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.core.PlatformTuning
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import io.github.some_example_name.old.core.DISimulationContainer
import io.github.some_example_name.old.core.DISimulationContainer.threadCount
import io.github.some_example_name.old.core.SubstrateSettings
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.Entity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.NeuralLinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.PheromoneEntity
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.genomics.CellSystem
import io.github.some_example_name.old.systems.genomics.OrganManager
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.systems.physics.LinkPhysicsSystem
import io.github.some_example_name.old.systems.physics.ParticlePhysicsSystem
import io.github.some_example_name.old.systems.render.RenderBufferManager
import io.github.some_example_name.old.features.worldeditor.WorldTerrainManager
import io.github.some_example_name.old.systems.genomics.NeuralLinkManager
import io.github.some_example_name.old.systems.physics.MovementManager
import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS
import io.github.some_example_name.old.systems.genomics.OrganismSpawner
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.ControllerKeys
import io.github.some_example_name.old.journal.Fnv
import io.github.some_example_name.old.journal.SessionJournal
import io.github.some_example_name.old.journal.SessionStarted
import io.github.some_example_name.old.journal.SimRestarted
import io.github.some_example_name.old.journal.StateHash
import io.github.some_example_name.old.journal.StateHashed
import io.github.some_example_name.old.journal.WorldSaveCommitted
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SimulationSystem(
    val gridManager: GridManager,
    val worldCommandsManager: WorldCommandsManager,
    val organManager: OrganManager,
    val organEntity: OrganEntity,
    val cellEntity: CellEntity,
    val linkEntity: LinkEntity,
    val neuralLinkEntity: NeuralLinkEntity,
    val neuralLinkManager: NeuralLinkManager,
    val particleEntity: ParticleEntity,
    val pheromoneEntity: PheromoneEntity,
    val substrateSettings: SubstrateSettings,
    val threadManager: ThreadManager,
    val genomeManager: GenomeManager,
    val particlePhysicsSystem: ParticlePhysicsSystem,
    val linkPhysicsSystem: LinkPhysicsSystem,
    val simulationData: SimulationData,
    val cellSystem: CellSystem,
    val userCommandManager: UserCommandManager,
    val entityList: List<Entity>,
    val renderBufferManager: RenderBufferManager,
    val pheromonesManager: PheromonesManager,
    val movementManager: MovementManager,
    val worldTerrainManager: WorldTerrainManager
) {

    private var simulationThread: Thread? = null

    fun startThread() {
        if (!threadManager.isRunning) {
            threadManager.ensureStarted()
            threadManager.isRunning = true

            // Поток симуляции — это воркер 0, он разбирает чанки наравне с остальными,
            // поэтому получает ровно тот же приоритет. Разъехавшиеся приоритеты на
            // спин-барьере дороже, чем низкий приоритет у всех сразу.
            simulationThread = Thread {
                PlatformTuning.onWorkerThreadStart(0)
                // Мир к этому моменту уже построен (см. SimulationScreen.show), а сессия
                // начинается в потоке симуляции, как и все последующие (рестарт, replay).
                startSession()
                threadManager.runUpdateLoop { updateTick() }
            }.apply {
                isDaemon = true
                name = "Simulation-Main-Thread"
            }
            simulationThread?.start()
        }
    }

    // --- Performance Profiler ---
    //
    // Замер идёт всегда (два nanoTime на фазу — ~0.6 мкс на тик), вывод в CSV включается
    // флагом PROFILE_LOG. Накопители — обычные LongArray по номеру фазы: ни строк,
    // ни хэш-мапы, ни боксинга Double в цикле симуляции.
    private val profiler = PhaseProfiler()
    private val worldStats = WorldStats()

    /**
     * Только для отладочного стресс-теста, см. applyDebugStress. Сид — от сида сессии
     * (resetSessionInput): иначе повтор бага не смог бы повторить, какие клетки стресс
     * убил или разогнал.
     */
    private val debugRandom = java.util.Random()

    /**
     * Тик целиком выполняется под этим локом, см. [betweenTicks].
     *
     * Честный: на максимальной скорости поток симуляции отпускает лок и тут же берёт снова,
     * и нечестный лок мог бы раз за разом доставаться ему, а не ждущему сохранению.
     */
    private val tickLock = ReentrantLock(true)

    /**
     * Выполнить [block], пока мир стоит между тиками.
     *
     * Для сохранения и загрузки мира из потока рендера. Пауза через isPlay здесь не годится:
     * цикл обновления крутит несколько тиков подряд, не проверяя её, и снимок мог попасть
     * в середину следующего тика — координаты из одного, списки живых из другого.
     */
    fun <T> betweenTicks(block: () -> T): T = tickLock.withLock(block)

    fun updateTick() {
        // Повтор бага держит мир на тике: сохранение мира ещё не случилось, или запись
        // кончилась. Ждём вне лока — иначе само сохранение не смогло бы его взять.
        if (BugReplay.isActive && BugReplay.mustHold(SessionJournal.epoch, simulationData.tickCounter)) {
            try {
                Thread.sleep(HOLD_SLEEP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return
        }
        tickLock.withLock { tick() }
    }

    private fun tick() {
        val tickStart = System.nanoTime()

        if (simulationData.isFinish) {
            dispose()
            return
        }
        if (BugReplay.isActive) {
            // При повторе рестарт берётся из записи — в том тике, где он случился у
            // игрока, а не когда до флага дошёл бы этот поток.
            simulationData.isRestart = false
            if (BugReplay.restartAt(SessionJournal.epoch, simulationData.tickCounter)) {
                restartSim()
                return
            }
        } else if (simulationData.isRestart) {
            SessionJournal.recordSim(CommandSource.SYSTEM, SimRestarted, SessionJournal.epoch, simulationData.tickCounter)
            restartSim()
            return
        }

        simulationData.tickCounter++
        simulationData.timeSimulation += DELTA_SIM_TICK_TIME

        latchControllerKeys()

        if (DEBUG_CHECKS) applyDebugStress()
        applyPeriodicStress()

        // --- Измерения ---
        profiler.measure(Phase.LINKS) { linkPhysicsSystem.iterateLinksInParallel() }
        profiler.measure(Phase.NEURAL) { neuralLinkManager.iterateInParallel() }
        profiler.measure(Phase.COLLIDE) { processParticleCollision() }
        profiler.measure(Phase.CELLS) { cellSystem.iterateCellInParallel() }
        profiler.measure(Phase.PHEROMONE) { pheromonesManager.iterate() }
        profiler.measure(Phase.ARRANGE) { arrangementOfPositionsInTheGrid() }
        profiler.measure(Phase.WORLD_CMD) { worldCommandsManager.executingCommandsFromTheWorld() }
        profiler.measure(Phase.ORGANS) { organManager.performOrgansNextStage() }
        profiler.measure(Phase.USER_CMD) { userCommandManager.processingCommandsFromUser() }
        profiler.measure(Phase.LAST_WORLD_CMD) { worldCommandsManager.executingLastCommandsFromTheWorld() }
        // Организмы, потерявшие в этом тике последнюю клетку, — после всех команд тика: они
        // ещё читали поля организма. Арены уходят в свободные куски до перестройки сетки,
        // но их частицы уже мертвы, и сетка их пропустит.
        organEntity.deleteEmptiedOrgans()
        // Единственное место, где меняется структура пространственной сетки. Стоит здесь,
        // потому что к этому моменту уже применены все перемещения (фаза 6) и все
        // создания/удаления частиц (фазы 7-10), то есть сетка собирается один раз по
        // финальному состоянию тика. Фазы 1-6 следующего тика читают её только на чтение,
        // поэтому многопоточным фазам не нужно ни блокировок, ни чётно-нечётных ограничений
        // из-за мутации сетки.
        profiler.measure(Phase.REBUILD) { gridManager.rebuild(particleEntity.isAlive, particleEntity.gridId, particleEntity.isInGrid) }

        // Ловит расхождение «частица есть в aliveList, но её нет в сетке или она лежит не
        // в своей клетке» — то самое состояние, в котором частица рисуется, но не
        // сталкивается и не берётся курсором. Стоит здесь, потому что сразу после
        // перестройки инвариант обязан выполняться точно.
        // Клетки организма обязаны лежать в его арене — на этом будет держаться обход
        // организма по диапазону в параллельных фазах. Стоит здесь, вместе с проверкой
        // сетки: обе ловят «сущность есть, но её никто не обсчитывает».
        if (DEBUG_CHECKS) cellEntity.verifyArenaIntegrity()

        if (DEBUG_CHECKS) {
            gridManager.verifyIntegrity(
                isAlive = particleEntity.isAlive,
                particleCell = particleEntity.gridId,
                inGrid = particleEntity.isInGrid,
                px = particleEntity.x,
                py = particleEntity.y
            )
        }

        // Тик закончен целиком и сетка собрана — самое место для снимка состояния:
        // однопоточно, и ни одна фаза уже ничего не допишет.
        if (simulationData.tickCounter % STATE_HASH_PERIOD == 0) hashState()

        profiler.measure(Phase.RENDER) { renderBufferManager.updateBuffer(profiler.text) }

        // Полный тик меряется отдельно, а не суммой фаз: разница между ним и суммой —
        // это неучтённое время (паузы GC, вытеснение потока планировщиком), и она сама
        // по себе диагностична.
        profiler.record(Phase.TICK, System.nanoTime() - tickStart)

        if (profiler.endTick()) {
            fillWorldStats()
            profiler.flush(worldStats)
            // Раз в окно профиля, а не каждый тик: отчёт стоит проход по всем связям.
            if (DEBUG_CHECKS) println(linkPhysicsSystem.describeLinkLocality())
        }
    }

    /**
     * Отладочный стресс-тест жизненного цикла клеток.
     *
     * Пока зажата A — несколько случайных клеток за тик умирают; пока зажата D — несколько
     * получают предельную скорость в случайном направлении и улетают, разрывая свои связи.
     *
     * Смысл в том, чтобы гонки и битые ссылки в связях воспроизводились сами: они всплывают
     * именно при активной смерти клеток и разрыве связей, а руками такой сценарий держать
     * долго и ненадёжно.
     *
     * Стоит в самом начале тика, до всех фаз: команда DELETE_CELL попадёт в буфер и будет
     * применена этим же тиком в фазе 7, а запись скорости идёт в однопоточном участке,
     * пока ни один воркер не запущен.
     */
    private fun applyDebugStress() {
        if (simulationData.debugKillCells) {
            repeat(DEBUG_STRESS_CELLS_PER_TICK) { killRandomCell() }
        }
        if (simulationData.debugFlingCells) {
            repeat(DEBUG_STRESS_CELLS_PER_TICK) { flingRandomCell() }
        }
    }

    /**
     * Стресс жизненного цикла по команде PlayerCommand.SetStress — для проверок
     * (bugReplayCheck -PkillEvery/-PflingEvery/-PspawnEvery). В отличие от [applyDebugStress]
     * работает и без DEBUG_CHECKS: проверка должна гонять смерти и рождения на той же
     * сборке, что у игрока. Выключенный стоит три сравнения с нулём за тик.
     *
     * Там же, в начале тика, в однопоточной части: смерть — обычной командой DELETE_CELL,
     * посадка — тем же OrganismSpawner, что у игрока и продюсера. Случайность — из
     * debugRandom с сидом сессии, поэтому повтор делает то же самое.
     */
    private fun applyPeriodicStress() {
        val tick = simulationData.tickCounter
        val kill = simulationData.stressKillEvery
        val fling = simulationData.stressFlingEvery
        val spawn = simulationData.stressSpawnEvery
        if (kill > 0 && tick % kill == 0) killRandomCell()
        if (fling > 0 && tick % fling == 0) flingRandomCell()
        if (spawn > 0 && tick % spawn == 0) spawnRandomOrganism()
    }

    private val stressZygote by lazy { cellEntity.cellList.filterIsInstance<Zygote>().single() }
    private val stressSpawner by lazy { OrganismSpawner(organEntity, cellEntity, genomeManager, stressZygote) }

    private fun spawnRandomOrganism() {
        val genomes = genomeManager.genomes
        var baked = 0
        for (genome in genomes) if (genome.cellsAmount > 0) baked++
        if (baked == 0) return
        // k-й по счёту запечённый — без списка, это и так раз в много тиков.
        var pick = debugRandom.nextInt(baked)
        var genomeIndex = 0
        while (genomes[genomeIndex].cellsAmount <= 0 || pick-- > 0) genomeIndex++
        val margin = 10f
        stressSpawner.spawn(
            genomeIndex = genomeIndex,
            x = margin + debugRandom.nextFloat() * (gridManager.gridWidth - 2 * margin),
            y = margin + debugRandom.nextFloat() * (gridManager.gridHeight - 2 * margin),
            color = stressZygote.defaultColor.toIntBits(),
            radius = PARTICLE_MAX_RADIUS,
            angleCos = 1f,
            angleSin = 0f
        )
    }

    private fun killRandomCell() {
        val alive = cellEntity.aliveList
        if (alive.isEmpty()) return

        val cellIndex = alive.getInt(debugRandom.nextInt(alive.size))
        // Через обычную команду, а не напрямую: удаление клетки тянет за собой снятие
        // связей, освобождение частицы и органа, и всё это должно пройти штатным путём.
        worldCommandsManager.worldCommandBuffer[0].push(
            WorldCommandType.DELETE_CELL,
            cellIndex,
            cellEntity.getGeneration(cellIndex)
        )
    }

    private fun flingRandomCell() {
        val alive = cellEntity.aliveList
        if (alive.isEmpty()) return

        val cellIndex = alive.getInt(debugRandom.nextInt(alive.size))
        val particleIndex = cellEntity.getParticleIndex(cellIndex)
        if (particleIndex == -1) return

        // Ровно предел скорости: больше ставить бессмысленно, seedBump всё равно зажмёт.
        // При MAX_SPEED смещение за тик равно HALF_CHUNK_HEIGHT, то есть связи длиной до
        // sqrt(linkMaxLength2) рвутся за пару тиков.
        val angle = debugRandom.nextFloat() * 2f * PI.toFloat()
        particleEntity.vx[particleIndex] = cos(angle) * MovementManager.MAX_SPEED
        particleEntity.vy[particleIndex] = sin(angle) * MovementManager.MAX_SPEED
    }

    /**
     * Снимок объёма мира на момент закрытия окна усреднения.
     *
     * Считается раз в PROFILE_WINDOW_TICKS тиков, поэтому дороговизна не важна — но всё
     * равно это только вычитания и сумма по 16 спискам.
     *
     * Количества живых сущностей берутся так же, как их считает оверлей: lastId минус
     * размер стека переиспользуемых индексов.
     */
    private fun fillWorldStats() {
        val stats = worldStats

        stats.tick = simulationData.tickCounter.toLong()
        stats.ups = simulationData.ups

        // По aliveList, а не по lastId.
        //
        // Раньше считалось как lastId - deadStack.size + 1, и это работало, пока индексы
        // выдавались подряд. С аренами lastId сдвигается за конец КАЖДОЙ брони сразу при
        // рождении организма (Entity.reserveRange), задолго до того, как тело её заполнит,
        // поэтому формула начала возвращать зарезервированную ёмкость вместо числа живых.
        // На замере это выглядело как 11655 клеток и 31194 связи при реальных 8523 и 23553,
        // то есть все производные метрики "на клетку" врали в полтора раза.
        stats.cells = cellEntity.aliveList.size
        stats.particles = particleEntity.aliveList.size
        stats.links = linkEntity.aliveList.size

        stats.gridSize = gridManager.gridSize
        stats.occupiedCells = gridManager.occupiedCells
        stats.maxParticlesInCell = gridManager.maxParticlesInCell

        stats.workers = threadManager.executor.workerCount

        stats.pairCandidatesTotal = SimCounters.take(SimCounters.PAIR_CANDIDATES)
        stats.collisionsTotal = SimCounters.take(SimCounters.COLLISIONS)
        stats.linkedSkipsTotal = SimCounters.take(SimCounters.LINKED_SKIPS)
        stats.contactsTotal = SimCounters.take(SimCounters.CONTACTS)
        stats.linkBreaksTotal = SimCounters.take(SimCounters.LINK_BREAKS)
        stats.linkAnglesTotal = SimCounters.take(SimCounters.LINK_ANGLES)

        // Занятость снимается по всем фазам разом: takeStage* обнуляет накопители,
        // поэтому пропущенная фаза копила бы данные до следующего окна и врала.
        val executor = threadManager.executor
        for (phase in 0 until Phase.COUNT) {
            stats.stageBusyNanos[phase] = executor.takeStageBusyNanos(phase)
            stats.stageWallNanos[phase] = executor.takeStageWallNanos(phase)
        }

        // Все живые связи обходятся по аренам организмов, поэтому "обработано" и "живо" —
        // теперь одно и то же число. Поле оставлено, чтобы не ломать формат CSV.
        stats.linksProcessed = linkEntity.aliveList.size
    }

    fun processParticleCollision() {
        threadManager.runChunkStage(isOdd = true, stageId = Phase.COLLIDE) { start, end, threadId ->
            particlePhysicsSystem.processGridRangePhysics(start, end, threadId)
        }
        threadManager.runChunkStage(isOdd = false, stageId = Phase.COLLIDE) { start, end, threadId ->
            particlePhysicsSystem.processGridRangePhysics(start, end, threadId)
        }
    }

    /**
     * Интегрирование позиций. Работы здесь мало (позиция += скорость, ограничение
     * по границам мира, пересчёт gridId), поэтому фаза почти целиком состояла из
     * накладных расходов: два барьера на ExecutorService по ~8 submit + 8 Future.get
     * каждый — это десятки микросекунд на десяток микросекунд полезной работы.
     * Теперь барьер спиновый, а слоты раздаются динамически: в стеке одного чанка
     * могут быть тысячи частиц, в соседнем — единицы.
     *
     * Две стадии (odd/even) пока сохранены, потому что буферы отложенных команд
     * индексируются номером чанка, и слот odd-чанка совпал бы со слотом even-чанка.
     * Само движение сетку больше не мутирует, так что после разведения буферов по
     * чанкам обе стадии можно будет слить в одну.
     */
    fun arrangementOfPositionsInTheGrid() {
        val alive = particleEntity.aliveList
        val size = alive.size
        if (size == 0) return

        // Ровные блоки aliveList, а не стеки, набитые обходом сетки.
        //
        // Раньше список частиц для движения набирался ПОБОЧНЫМ ЭФФЕКТОМ фазы коллизий:
        // проходя сетку, она складывала встреченные индексы в стеки чанков, и двигалось
        // ровно то, что попало в сетку. Пока в сетке были все частицы, это работало и
        // экономило отдельный обход. Как только из сетки убрали внутренние клетки
        // (ParticleEntity.isInGrid), они перестали и двигаться — стояли на месте, потому
        // что их никто не клал в стек.
        //
        // Связывать движение с сеткой не было причины и раньше: moveParticle трогает
        // только свою же частицу, никакой пространственной изоляции ему не нужно.
        // Чётность существовала лишь потому, что буферы команд индексировались номером
        // чанка; теперь номер воркера приходит отдельно, и обе стадии слились в одну —
        // на барьер меньше.
        val blockCount = threadCount
        val blockSize = (size + blockCount - 1) / blockCount

        threadManager.runWorkStage(blockCount, Phase.ARRANGE) { block, workerId ->
            val start = block * blockSize
            val end = minOf(start + blockSize, size)
            for (i in start until end) {
                movementManager.moveParticle(alive.getInt(i), workerId)
            }
        }
    }

    fun stopUpdateThread() {
        threadManager.stopSimulationLoop()

        simulationThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    fun dispose() {
        gridManager.clearAll()
        entityList.forEach { it.clear() }
        simulationData.clear()
        worldCommandsManager.dispose()
    }

    private fun restartSim() {
        dispose()
        simulationData.isRestart = false
        worldTerrainManager.initWorld(
            gridWidth = DISimulationContainer.gridWidth,
            gridHeight = DISimulationContainer.gridHeight
        )
        startSession()
    }

    /**
     * Начало сессии в только что построенном мире: новая эпоха журнала и новый seed для
     * случайностей команд игрока. При повторе бага seed берётся из записи.
     */
    private fun startSession() {
        val epoch = SessionJournal.beginSimEpoch()
        val seed = (if (BugReplay.isActive) BugReplay.sessionSeed(epoch) else null)
            // RandomXS128 сам перемешивает seed, так что близкие значения nanoTime не страшны.
            ?: System.nanoTime()
        resetSessionInput(seed)
        SessionJournal.recordSim(CommandSource.SYSTEM, SessionStarted(seed), epoch, simulationData.tickCounter)
    }

    private fun resetSessionInput(seed: Long) {
        userCommandManager.startSession(seed)
        debugRandom.setSeed(seed xor DEBUG_RANDOM_SALT)
        simulationData.controllerKeys.fill(false)
        simulationData.stressKillEvery = 0
        simulationData.stressFlingEvery = 0
        simulationData.stressSpawnEvery = 0
        lastControllerKeysMask = 0
    }

    /**
     * Хэш состояния мира — в журнал, а при повторе сверка с записанным. Первое
     * несовпадение — первый тик, где повтор разошёлся с игрой.
     */
    private fun hashState() {
        val tick = simulationData.tickCounter
        val state = StateHash.compute(
            entities = entityList,
            simulation = { h ->
                var x = Fnv.word(h, simulationData.tickCounter)
                x = Fnv.word(x, java.lang.Float.floatToRawIntBits(simulationData.timeSimulation))
                x = Fnv.word(x, simulationData.currentGenomeIndex)
                x = Fnv.word(x, simulationData.selectedCellIndex)
                x = Fnv.word(x, simulationData.stressKillEvery)
                x = Fnv.word(x, simulationData.stressFlingEvery)
                Fnv.word(x, simulationData.stressSpawnEvery)
            },
            input = userCommandManager::hashInput,
            grid = gridManager::hashOrder
        )
        if (BugReplay.isActive) {
            BugReplay.checkStateHash(SessionJournal.epoch, tick, state, stateHashNames)
        } else {
            SessionJournal.recordSim(
                CommandSource.SYSTEM,
                StateHashed(state.hash, StateHash.VERSION, state.parts.toList()),
                SessionJournal.epoch,
                tick
            )
        }
    }

    /** Имена частей хэша мира — для сообщения, что именно разошлось. */
    private val stateHashNames by lazy { StateHash.partNames(entityList) }

    /**
     * Мир записан в слот [slot]. Зовётся из сохранения между тиками (под тем же локом),
     * поэтому номер тика здесь точный.
     */
    fun onWorldSaved(slot: String) {
        if (BugReplay.isActive) {
            BugReplay.onWorldSaveFinished()
        } else {
            SessionJournal.recordSim(
                CommandSource.SYSTEM, WorldSaveCommitted(slot), SessionJournal.epoch, simulationData.tickCounter
            )
        }
    }

    private var lastControllerKeysMask = 0

    /**
     * Клавиши контроллера на этот тик. Поток рендера пишет controllerKeyTouched когда
     * угодно, поэтому симуляция читает не его, а снимок, сделанный здесь, в однопоточном
     * начале тика. Снимок пишется в журнал (только при изменении), а при повторе бага
     * подменяется записанным.
     */
    private fun latchControllerKeys() {
        val epoch = SessionJournal.epoch
        val tick = simulationData.tickCounter
        val mask = if (BugReplay.isActive) {
            BugReplay.keysAt(epoch, tick) ?: lastControllerKeysMask
        } else {
            val input = simulationData.controllerKeyTouched
            var live = 0
            for (i in input.indices) if (input[i]) live = live or (1 shl i)
            live
        }
        if (mask != lastControllerKeysMask) {
            lastControllerKeysMask = mask
            SessionJournal.recordSim(CommandSource.SYSTEM, ControllerKeys(mask), epoch, tick)
        }
        val keys = simulationData.controllerKeys
        for (i in keys.indices) keys[i] = mask and (1 shl i) != 0

        // Отладочный стресс-тест — от тех же клавиш A и D, что и управление организмом.
        // Из снимка, а не прямо с клавиатуры: снимок пишется в журнал, и повтор бага
        // запускает стресс в тех же тиках.
        if (DEBUG_CHECKS) {
            simulationData.debugKillCells = keys[KEY_A]
            simulationData.debugFlingCells = keys[KEY_D]
        }
    }

    fun initMap() {
        worldTerrainManager.initWorld(
            gridWidth = DISimulationContainer.gridWidth,
            gridHeight = DISimulationContainer.gridHeight
        )
    }

    companion object {
        const val DELTA_SIM_TICK_TIME = 0.016666666f

        /** Индексы клавиш A и D в controllerKeys (0–9 цифры, 10–13 WASD, 14 пробел, 15–18 стрелки). */
        private const val KEY_A = 11
        private const val KEY_D = 13

        /** Чтобы сид стресс-теста не совпадал с сидом команд игрока. */
        private const val DEBUG_RANDOM_SALT = 0x5DEECE66DL

        /**
         * Хэш состояния мира — раз в столько тиков (раз в секунду при 60 тиках/с).
         * -Dgenomeia.stateHashPeriod=1 — каждый тик: так находится первый тик, где повтор
         * разошёлся. Запись и повтор должны идти с одним и тем же значением.
         */
        val STATE_HASH_PERIOD = System.getProperty("genomeia.stateHashPeriod")?.toIntOrNull()?.coerceAtLeast(1) ?: 60

        /** Сколько спать, пока повтор держит мир на тике. */
        private const val HOLD_SLEEP_MS = 2L

        /** Сколько клеток за тик убивает / разгоняет отладочный стресс-тест. */
        private const val DEBUG_STRESS_CELLS_PER_TICK = 55
    }
}
