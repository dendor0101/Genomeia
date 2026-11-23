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
import io.github.some_example_name.old.systems.render.CommandGhosts
import io.github.some_example_name.old.systems.rules.WorldRules
import io.github.some_example_name.old.bug.BugReplay
import io.github.some_example_name.old.journal.CommandSource
import io.github.some_example_name.old.journal.Fnv
import io.github.some_example_name.old.journal.SessionJournal
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun ScreenCell.aliveIn(cells: CellEntity): Boolean =
    epoch == SessionJournal.epoch && index in cells.isAlive.indices && cells.isAliveAndSameGen(index, generation)

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

    /**
     * Настройки мира — их меняют команды SetWorldRule и ResetWorldRules. Только у основной
     * симуляции (DISimulationContainer); у редактора генома null, и такие команды ничего не делают.
     */
    var worldRules: WorldRules? = null

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

        val p = grabbedParticleIndex
        if (p != -1 && isDragging) {
            val physics = particleEntity.physics
            // Событий мыши за тик бывает несколько. Кость тянем один раз к последней точке.
            if (physics != null && physics.isRigidBone(p)) physics.pullBone(p, tapX, tapY)
            else if (!isAlreadyDragged) dragParticle(p, tapX, tapY)
        }
    }

    private fun dragParticle(p: Int, targetX: Float, targetY: Float) = with(particleEntity) {
        setVelocity(p, vx(p) * 0.5f + (targetX - x(p)) * 0.02f, vy(p) * 0.5f + (targetY - y(p)) * 0.02f)
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
            cmd.copy(genomeIndex = simulationData.currentGenomeIndex)
        } else {
            cmd
        }

    private fun pickedParticle(x: Float, y: Float, target: ScreenCell?): Int {
        if (target != null && target.index >= 0) {
            return if (target.aliveIn(cellEntity)) cellEntity.particleIndexes[target.index] else -1
        }
        if (target != null && target.epoch != SessionJournal.epoch) return -1
        return gridManager.pickParticle(x, y, particleEntity, cellEntity, includeCells = target == null)
    }

    private fun applyCommand(cmd: PlayerCommand) {
        when (cmd) {
            is PlayerCommand.SetStress -> {
                simulationData.stressKillEvery = cmd.killEvery
                simulationData.stressFlingEvery = cmd.flingEvery
                simulationData.stressSpawnEvery = cmd.spawnEvery
            }
            is PlayerCommand.SetPhysicsDebug -> {
                simulationData.debugNoTailThrust = cmd.noTailThrust
                simulationData.debugNoDeepKill = cmd.noDeepKill
            }
            is PlayerCommand.SetWorldRule -> worldRules?.set(cmd.key, cmd.cellType, cmd.value)
            PlayerCommand.ResetWorldRules -> worldRules?.reset()
            PlayerCommand.StopDrag -> {
                grabbedParticleIndex = -1
                simulationData.selectedCellIndex = -1
                isDragging = false
            }

            is PlayerCommand.TouchDown -> {
                grabbedParticleIndex = pickedParticle(cmd.x, cmd.y, cmd.target)
                if (grabbedParticleIndex != -1) isDragging = false

                tapX = cmd.x
                tapY = cmd.y
                simulationData.selectedCellIndex = -1
            }

            is PlayerCommand.Drag -> {
                if (grabbedParticleIndex != -1) {
                    isAlreadyDragged = true
                    val p = grabbedParticleIndex
                    if (particleEntity.physics?.isRigidBone(p) != true) dragParticle(p, cmd.x, cmd.y)
                    isDragging = true

                    tapX = cmd.x
                    tapY = cmd.y
                    simulationData.selectedCellIndex = -1
                }
            }

            is PlayerCommand.Tap -> {
                val grabbedParticleIndexTap = pickedParticle(cmd.x, cmd.y, cmd.target)
                simulationData.selectedFrameCell = cmd.target

                simulationData.selectedCellIndex = if (grabbedParticleIndexTap != -1) {
                    if (particleEntity.isCell[grabbedParticleIndexTap]) {
                        isDragging = false
                        particleEntity.holderEntityIndex[grabbedParticleIndexTap]
                    } else -1
                } else -1

                if (simulationData.selectedCellIndex == -1) {
                    // Исчезновение нажатой клетки не превращает выбор в посадку или распыление.
                    if (cmd.target != null && (cmd.target.index >= 0 || cmd.target.epoch != SessionJournal.epoch)) return
                    if (cmd.isLeftButton) {
                        if (canSpawnAt(cmd.x, cmd.y, grabbedParticleIndexTap)) {
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
                        scatterSubstance(cmd.x, cmd.y, random) { x, y, radius ->
                            // Буфер фиксированный, а не случайный: число буферов
                            // равно числу потоков и на другой машине другое.
                            // Фаза команд игрока однопоточная, гонки тут нет.
                            worldCommandsManager.worldCommandBuffer[0].push(
                                type = WorldCommandType.ADD_SUBSTANCE,
                                floats = floatArrayOf(x, y, radius),
                                ints = intArrayOf(Color.RED.toIntBits(), 0)
                            )
                        }
                    }
                }

//                    println("Tap (${cmd.x}, ${cmd.y} ${cmd.isLeftButton} ${grabbedParticleIndex})")
            }

            is PlayerCommand.KillCell -> killCell(cmd.x, cmd.y, cmd.target)
        }
    }

    /**
     * Посадит ли касание организм. На террейне и стенке не сажаем: толщи террейна в мире
     * физики нет (StaticTerrain), и организм вырос бы внутри. Под курсором частица террейна —
     * значит, клик по нему.
     */
    private fun canSpawnAt(x: Float, y: Float, picked: Int): Boolean {
        val onTerrain = picked != -1 && particleEntity.terrainCell[picked] != -1
        return !onTerrain && x > 0 && x < gridManager.gridWidth && y > 0 && y < gridManager.gridHeight
    }

    /**
     * Распыление вещества касанием: десять точек в круге. Один код и для применения, и для
     * показа на паузе ([previewQueued]) — иначе показ потянул бы из генератора другие числа
     * и разошёлся с тем, что выпадет на деле. Радиус берётся из генератора, только если точка
     * свободна: этот порядок вызовов — часть детерминизма, не менять.
     */
    private inline fun scatterSubstance(cx: Float, cy: Float, rng: RandomXS128, put: (x: Float, y: Float, radius: Float) -> Unit) {
        val spread = 7.0f
        repeat(10) {
            val angle = rng.nextFloat() * MathUtils.PI2
            val r = spread * sqrt(rng.nextFloat())
            val x = cx + MathUtils.cos(angle) * r
            val y = cy + MathUtils.sin(angle) * r
            // Через pickParticle, а не только сетку: иначе субстанция
            // рождалась бы внутри тела, где клеток в сетке нет.
            if (x > 0 && x < gridManager.gridWidth && y > 0 && y < gridManager.gridHeight &&
                gridManager.pickParticle(x, y, particleEntity, cellEntity) == -1
            ) {
                put(x, y, rng.nextFloat() * (0.5f - 0.1f) + 0.1f)
            }
        }
    }

    /** Копия [random] для [previewQueued]: сам генератор показ не трогает. */
    private val previewRandom = RandomXS128()

    /**
     * Что сделает очередь, когда её применит следующий тик: призраки посадок, вещества и
     * убийств — в [out], номер выбранной клетки после очереди — в ответе (-1 — ничего не
     * выбрано). Для паузы: тиков нет, а игрок должен сразу видеть итог касаний.
     *
     * Только чтение мира, звать под замком между тиками. Решения те же, что в
     * [applyCommand]: тот же pickedParticle, то же [canSpawnAt], то же [scatterSubstance] с
     * копией генератора. Точность — до тика: следующий тик сначала двигает мир, потом
     * применяет очередь, так что точка вещества вплотную к клетке может и не выпасть.
     * Цена — проход по очереди на каждое касание на паузе; в ходе игры не зовётся.
     */
    fun previewQueued(out: CommandGhosts): Int {
        out.begin(simulationData.tickCounter, SessionJournal.epoch)
        previewRandom.setState(random.getState(0), random.getState(1))
        var selected = simulationData.selectedCellIndex
        var grabbed = grabbedParticleIndex
        for (cmd in commandQueue) {
            when (cmd) {
                PlayerCommand.StopDrag -> {
                    grabbed = -1
                    selected = -1
                }
                is PlayerCommand.TouchDown -> {
                    grabbed = pickedParticle(cmd.x, cmd.y, cmd.target)
                    selected = -1
                }
                is PlayerCommand.Drag -> if (grabbed != -1) selected = -1
                is PlayerCommand.Tap -> selected = previewTap(cmd, out)
                is PlayerCommand.KillCell -> {
                    val p = pickedParticle(cmd.x, cmd.y, cmd.target)
                    if (p != -1 && particleEntity.isCell[p]) {
                        out.kill(particleEntity.x(p), particleEntity.y(p), particleEntity.radius[p])
                        if (selected == particleEntity.holderEntityIndex[p]) selected = -1
                        if (grabbed == p) grabbed = -1
                    }
                }
                else -> {}
            }
        }
        return selected
    }

    private fun previewTap(cmd: PlayerCommand.Tap, out: CommandGhosts): Int {
        val picked = pickedParticle(cmd.x, cmd.y, cmd.target)
        if (picked != -1 && particleEntity.isCell[picked]) return particleEntity.holderEntityIndex[picked]
        if (cmd.target != null && (cmd.target.index >= 0 || cmd.target.epoch != SessionJournal.epoch)) return -1
        if (cmd.isLeftButton) {
            if (canSpawnAt(cmd.x, cmd.y, picked)) out.cell(cmd.x, cmd.y, PARTICLE_MAX_RADIUS, zygote.defaultColor.toIntBits())
        } else {
            scatterSubstance(cmd.x, cmd.y, previewRandom) { x, y, radius -> out.substance(x, y, radius, Color.RED.toIntBits()) }
        }
        return -1
    }

    /**
     * Убить клетку под касанием. Через обычную команду мира, как и стресс-тест
     * (SimulationSystem.killRandomCell): со смертью клетки снимаются связи, освобождаются
     * частица и орган, и всё это должно пройти штатным путём. Поколение в команде отсекает
     * случай, когда до исполнения на этом месте уже родилась другая клетка.
     */
    private fun killCell(x: Float, y: Float, target: ScreenCell?) {
        val particle = pickedParticle(x, y, target)
        if (particle == -1 || !particleEntity.isCell[particle]) return
        val cellIndex = particleEntity.holderEntityIndex[particle]
        worldCommandsManager.worldCommandBuffer[0].push(
            WorldCommandType.DELETE_CELL,
            cellIndex,
            cellEntity.getGeneration(cellIndex)
        )
        // Убитая клетка не должна остаться ни выбранной, ни схваченной: её номер скоро
        // достанется другой.
        if (simulationData.selectedCellIndex == cellIndex) simulationData.selectedCellIndex = -1
        if (grabbedParticleIndex == particle) {
            grabbedParticleIndex = -1
            isDragging = false
        }
    }

    private companion object {
        const val LOG_SOURCE = "Player"
    }

    override fun dispose() {
        commandQueue.clear()
    }
}
