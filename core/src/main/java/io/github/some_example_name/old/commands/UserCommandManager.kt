package io.github.some_example_name.old.commands

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.RandomXS128
import com.badlogic.gdx.utils.Disposable
import io.github.some_example_name.old.cells.Cell
import io.github.some_example_name.old.cells.Zygote
import io.github.some_example_name.old.core.DISimulationContainer.worldCommandsManager
import io.github.some_example_name.old.core.log.ActionLog
import io.github.some_example_name.old.core.utils.pickParticle
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.systems.simulation.SimulationData
import io.github.some_example_name.old.systems.genomics.OrganismSpawner
import io.github.some_example_name.old.systems.genomics.genome.GenomeManager
import io.github.some_example_name.old.systems.physics.CollisionManager.Companion.PARTICLE_MAX_RADIUS
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.Fnv
import io.github.some_example_name.old.journal.SessionJournal
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class UserCommandManager(
    val organEntity: OrganEntity,
    val cellEntity: CellEntity,
    val genomeManager: GenomeManager,
    val cellList: List<Cell>,
    val simulationData: SimulationData,
    val gridManager: GridManager,
    val particleEntity: ParticleEntity,
    val zygote: Zygote,
    val isEditor: Boolean,
    /**
     * Команды пишутся в журнал сессии и при повторе бага берутся из него. Только у
     * основной симуляции: у редактора генома и мира за меню свои миры, игрок их не трогает.
     */
    val isJournaled: Boolean = false
): Disposable {

    private val commandQueue = ConcurrentLinkedQueue<PlayerCommand>()

    /** Посадка организма игроком — та же, что у потомков в мире (см. OrganismSpawner). */
    private val organismSpawner = OrganismSpawner(organEntity, cellEntity, genomeManager, zygote)

    @Volatile var grabbedParticleIndex = -1
    @Volatile var tapX = 0f
    @Volatile var tapY = 0f
    @Volatile var isDragging = false

    /**
     * Весь случайный выбор при обработке команд игрока — только отсюда. Seed задаётся
     * на каждую сессию ([startSession]) и пишется в запись, поэтому при воспроизведении
     * распыление вещества ложится в те же точки.
     */
    private val random = RandomXS128()

    private var isAlreadyDragged = false

    /**
     * Поток симуляции, начало сессии (старт мира, рестарт, воспроизведение). Сбрасывает
     * всё, что команды тащат из тика в тик: иначе захват частицы, начатый до рестарта,
     * указывал бы в новом мире на чужую частицу.
     */
    fun startSession(seed: Long) {
        random.setSeed(seed)
        commandQueue.clear()
        grabbedParticleIndex = -1
        isDragging = false
    }

    /**
     * Состояние ввода для хэша мира (StateHash): что схвачено, где касание, и генератор
     * случайностей команд — от него зависит, куда ляжет следующее вещество.
     */
    fun hashInput(hash: Long): Long {
        var h = Fnv.word(hash, grabbedParticleIndex)
        h = Fnv.word(h, java.lang.Float.floatToRawIntBits(tapX))
        h = Fnv.word(h, java.lang.Float.floatToRawIntBits(tapY))
        h = Fnv.word(h, if (isDragging) 1 else 0)
        h = Fnv.long(h, random.getState(0))
        return Fnv.long(h, random.getState(1))
    }

    fun push(cmd: PlayerCommand) {
        // Единственный вход для действий игрока в мире — значит и единственное место,
        // где их надо журналировать. Drag сыпется на каждое движение мыши, но подряд
        // идущие однотипные записи ActionLog схлопывает в одну со счётчиком.
        // При повторе бага касания мира берутся из записи, живые — мимо.
        if (isJournaled && BugReplay.isActive) return
        ActionLog.record(LOG_SOURCE, cmd)
        commandQueue.offer(cmd)
    }

    /**
     * Команда не от игрока, а от самой игры: редактор генома ставит зиготу в своём мире
     * при каждом пересчёте. В журнал действий она не идёт — игрок этого не делал, а при
     * повторе по журналу её поставит тот же самый код.
     */
    fun pushInternal(cmd: PlayerCommand) {
        commandQueue.offer(cmd)
    }

    fun processingCommandsFromUser() {
        isAlreadyDragged = false
        val tick = simulationData.tickCounter

        if (isJournaled && BugReplay.isActive) {
            // Во время повтора команды идут только из записи: живой ввод перемешался бы
            // с записанным и увёл сценарий в сторону.
            commandQueue.clear()
            BugReplay.forEachCommandAt(SessionJournal.epoch, tick) { applyCommand(it) }
        } else {
            while (true) {
                val cmd = resolve(commandQueue.poll() ?: break)
                // Пишем в момент применения и с этим тиком: ровно здесь же команда
                // будет применена и при повторе.
                if (isJournaled) SessionJournal.recordSim(CommandSource.PLAYER, cmd, SessionJournal.epoch, tick)
                applyCommand(cmd)
            }
        }

        if (grabbedParticleIndex != -1 && !isAlreadyDragged && isDragging) {
            val grabDrag = 0.5f // To reduce oscillations

            with(particleEntity) {
                vx[grabbedParticleIndex] = vx[grabbedParticleIndex] * grabDrag + (tapX - x[grabbedParticleIndex]) * 0.02f
                vy[grabbedParticleIndex] = vy[grabbedParticleIndex] * grabDrag + (tapY - y[grabbedParticleIndex]) * 0.02f
            }
        }
    }

    private fun resolveGenomeIndex(cmd: PlayerCommand): Int =
        (cmd as? PlayerCommand.Tap)?.genomeIndex ?: simulationData.currentGenomeIndex

    /**
     * Команда со всем, что решено в момент применения: у касания «поставить организм» —
     * какой геном выбран сейчас. В журнал уходит уже такая, чтобы при повторе геном не
     * зависел от того, когда именно был нажат выбор генома.
     */
    private fun resolve(cmd: PlayerCommand): PlayerCommand =
        if (cmd is PlayerCommand.Tap && cmd.genomeIndex == null) {
            PlayerCommand.Tap(cmd.x, cmd.y, cmd.isLeftButton, simulationData.currentGenomeIndex)
        } else {
            cmd
        }

    private fun applyCommand(cmd: PlayerCommand) {
        when (cmd) {
            is PlayerCommand.SetStress -> {
                simulationData.stressKillEvery = cmd.killEvery
                simulationData.stressFlingEvery = cmd.flingEvery
                simulationData.stressSpawnEvery = cmd.spawnEvery
            }
            PlayerCommand.StopDrag -> {
                grabbedParticleIndex = -1
                simulationData.selectedCellIndex = -1
                isDragging = false
            }

            is PlayerCommand.TouchDown -> {
                grabbedParticleIndex = gridManager.pickParticle(cmd.x, cmd.y, particleEntity, cellEntity)
                if (grabbedParticleIndex != -1) isDragging = false

                tapX = cmd.x
                tapY = cmd.y
                simulationData.selectedCellIndex = -1
            }

            is PlayerCommand.Drag -> {
                if (grabbedParticleIndex != -1) {
                    isAlreadyDragged = true
                    val grabDrag = 0.5f // To reduce oscillations

                    with(particleEntity) {
                        vx[grabbedParticleIndex] =
                            vx[grabbedParticleIndex] * grabDrag + (cmd.x - x[grabbedParticleIndex]) * 0.02f
                        vy[grabbedParticleIndex] =
                            vy[grabbedParticleIndex] * grabDrag + (cmd.y - y[grabbedParticleIndex]) * 0.02f
                    }
                    isDragging = true

                    tapX = cmd.x
                    tapY = cmd.y
                    simulationData.selectedCellIndex = -1
                }
            }

            is PlayerCommand.Tap -> {
                val grabbedParticleIndexTap =
                    gridManager.pickParticle(cmd.x, cmd.y, particleEntity, cellEntity)

                simulationData.selectedCellIndex = if (grabbedParticleIndexTap != -1) {
                    if (particleEntity.isCell[grabbedParticleIndexTap]) {
                        isDragging = false
                        particleEntity.holderEntityIndex[grabbedParticleIndexTap]
                    } else -1
                } else -1

                if (simulationData.selectedCellIndex == -1) {
                    if (cmd.isLeftButton) {
                        if (cmd.x > 0 && cmd.x < gridManager.gridWidth && cmd.y > 0 && cmd.y < gridManager.gridHeight) {
                            val genomeIndex = resolveGenomeIndex(cmd)
                            val randomAngle = if (isEditor) 0f else 0f//MathUtils.random(0f, MathUtils.PI2)
                            organismSpawner.spawn(
                                genomeIndex = genomeIndex,
                                x = cmd.x,
                                y = cmd.y,
                                color = zygote.defaultColor.toIntBits(),
                                radius = PARTICLE_MAX_RADIUS,
                                angleCos = cos(randomAngle),
                                angleSin = sin(randomAngle)
                            )
                        }
                    } else {
                        val radius = 7.0f
                        repeat(10) {
                            val angle = random.nextFloat() * MathUtils.PI2

                            val r = radius * sqrt(random.nextFloat())

                            val x = cmd.x + MathUtils.cos(angle) * r
                            val y = cmd.y + MathUtils.sin(angle) * r

                            if (x > 0 && x < gridManager.gridWidth && y > 0 && y < gridManager.gridHeight) {

                                // Через pickParticle, а не только сетку: иначе субстанция
                                // рождалась бы внутри тела, где клеток в сетке нет.
                                if (gridManager.pickParticle(x, y, particleEntity, cellEntity) == -1) {
                                    val color = Color.RED.toIntBits()
                                    val radius = random.nextFloat() * (0.5f - 0.1f) + 0.1f

                                    // Буфер фиксированный, а не случайный: число буферов
                                    // равно числу потоков и на другой машине другое.
                                    // Фаза команд игрока однопоточная, гонки тут нет.
                                    worldCommandsManager.worldCommandBuffer[0].push(
                                        type = WorldCommandType.ADD_SUBSTANCE,
                                        floats = floatArrayOf(x, y, radius),
                                        ints = intArrayOf(color, 0)
                                    )
                                }
                            }
                        }
                    }
                }

//                    println("Tap (${cmd.x}, ${cmd.y} ${cmd.isLeftButton} ${grabbedParticleIndex})")
            }
        }
    }

    private companion object {
        const val LOG_SOURCE = "Player"
    }

    override fun dispose() {
        commandQueue.clear()
    }
}
