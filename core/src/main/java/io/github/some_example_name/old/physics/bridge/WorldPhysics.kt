package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.journal.Fnv
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.physics.PhysicsWorkers
import it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * ФИЗИКА МИРА ИГРЫ НА БИБЛИОТЕКЕ — фаза тика вместо прежнего движения (ARRANGE). План
 * docs/physics/library-integration-plan-2026-10-25.md, этап B1 и решение 2: позиции и
 * скорости частиц, которые ведёт мир физики, игра читает прямо из его массивов
 * (ParticleEntity.x/y/vx/vy по ParticleEntity.physicsIndex) — копии нет.
 *
 * Фаза однопоточная вокруг шага мира (сам шаг — на воркерах игры) и идёт так:
 *  1. СВЕРКА СОСТАВА. Мир физики догоняет игру: организм с ареной и без тела — тело всей
 *     раскладкой из его живой клетки (зиготы); клетка игры жива, а в мире не рождена —
 *     рождение (до него клетка стоит там, куда её поставила игра, — ParticleEntity.local*);
 *     клетка игры умерла — kill; организм умер (или его номер занят новым — другое поколение)
 *     — тело убирается. Так же субстанции: новая — свободная частица, исчезла — убрана,
 *     сменила радиус (слияние мелких) — заводится заново. Отдельных крючков в обработчиках
 *     команд нет: проход по слотам ловит любой путь создания и удаления, в том числе загрузку.
 *  2. ВХОДЫ. Мышца клетки — degreeOfShortening (0.5–1.5, среднее гармоническое на связи, как
 *     было в LinkPhysicsSystem). Приращения скорости из параллельных стадий (жгутик, плавник,
 *     липучка, присоска — ParticleEntity.pushVx/pushVy) уходят в мир с пробуждением организма.
 *     Однопоточные записи (мышь, отладочный бросок) идут в мир сразу — [setVelocity], [place].
 *  3. ШАГ МИРА.
 *  4. СЕТКА: смена клетки сетки — gridId и след феромона (как делал MovementManager); ссылки
 *     ParticleEntity.physics* — на массивы мира (он заводит их заново при росте и уплотнении);
 *     после уплотнения мира физики номера частиц пересчитываются по номерам тел.
 *
 * Порядок обхода — организмы в порядке подсадки, слоты по порядку, субстанции в порядке
 * появления: от числа воркеров не зависит ничего.
 *
 * Цена: проход по клеткам и субстанциям до шага (сверка со входами) и после (сетка), потоком
 * по аренам. События библиотеки (разрыв, гибель клетки физикой, касания) игре не сообщаются
 * (B2): клетка, убитая физикой, стоит на месте гибели.
 */
class WorldPhysics(
    private val organEntity: OrganEntity,
    private val cellEntity: CellEntity,
    private val particleEntity: ParticleEntity,
    private val substancesEntity: SubstancesEntity,
    private val gridManager: GridManager,
    private val pheromonesManager: PheromonesManager,
    private val workers: PhysicsWorkers?
) {
    var bridge = PhysicsBridge(workers)
        private set

    private var boundsWidth = -1
    private var boundsHeight = -1
    private var compactions = 0

    /** Организмы с телом в порядке подсадки и поколение организма при подсадке. */
    private val organsWithBody = IntArrayList()
    private var bodyGeneration = IntArray(64)

    /** Субстанции со свободной частицей в порядке появления; номер частицы, поколение, радиус. */
    private val trackedSubstances = IntArrayList()
    private var freeOfSubstance = IntArray(256) { -1 }
    private var substanceGeneration = IntArray(256)
    private var substanceRadius = FloatArray(256)

    init {
        particleEntity.physics = this
        publish()
    }

    /** Новый мир физики: прежний мир игры стёрт (рестарт, новая карта, загрузка). */
    fun reset() {
        bridge = PhysicsBridge(workers)
        boundsWidth = -1; boundsHeight = -1
        compactions = 0
        organsWithBody.clear()
        trackedSubstances.clear()
        freeOfSubstance.fill(-1)
        particleEntity.physicsIndex.fill(-1)
        publish()
    }

    fun tick() {
        ensureCapacity()
        if (boundsWidth != gridManager.gridWidth || boundsHeight != gridManager.gridHeight) {
            boundsWidth = gridManager.gridWidth; boundsHeight = gridManager.gridHeight
            bridge.setBounds(boundsWidth.toFloat(), boundsHeight.toFloat())
        }
        removeGone()
        spawnNewOrganisms()
        for (k in 0 until organsWithBody.size) syncOrganism(organsWithBody.getInt(k))
        syncSubstances()
        bridge.step()
        publish()
        updateGrid()
    }

    /**
     * Часть хэша состояния мира (StateHash): позиции и скорости всех частиц мира физики,
     * побитово. Остальное его состояние (prev, контакты, сон) расходится вслед за ними за тик.
     */
    fun hash(h: Long): Long {
        val world = bridge.world
        val n = world.particleCount
        var x = Fnv.word(h, n)
        if (n == 0) return x            // пустой мир своих массивов ещё не завёл
        val px = world.x; val py = world.y; val vx = world.vx; val vy = world.vy
        for (i in 0 until n) {
            x = Fnv.long(x, px[i].toRawBits()); x = Fnv.long(x, py[i].toRawBits())
            x = Fnv.long(x, vx[i].toRawBits()); x = Fnv.long(x, vy[i].toRawBits())
        }
        return x
    }

    /** Однопоточная запись скорости в единицах игры (ParticleEntity.setVelocity). */
    fun setVelocity(i: Int, vx: Float, vy: Float) = bridge.setVelocity(i, vx, vy)

    /** Однопоточная перестановка частицы в точку игры (ParticleEntity.place). */
    fun place(i: Int, x: Float, y: Float) {
        bridge.world.place(i, x * PHYSICS_PER_GAME, y * PHYSICS_PER_GAME)
        bridge.world.wake(i)
    }

    // --- 1. сверка состава и 2. входы ---------------------------------------------------

    private fun removeGone() {
        var k = 0
        while (k < organsWithBody.size) {
            val organ = organsWithBody.getInt(k)
            if (!organEntity.isAlive[organ] || organEntity.getGeneration(organ) != bodyGeneration[organ]) {
                bridge.removeOrganism(organ)
                organsWithBody.removeInt(k)     // со сдвигом: порядок подсадки сохраняется
            } else k++
        }
        k = 0
        while (k < trackedSubstances.size) {
            val sub = trackedSubstances.getInt(k)
            if (!substancesEntity.isAlive[sub] || substancesEntity.getGeneration(sub) != substanceGeneration[sub]) {
                bridge.removeSubstance(freeOfSubstance[sub])
                freeOfSubstance[sub] = -1
                trackedSubstances.removeInt(k)
            } else k++
        }
        remapIfCompacted()
    }

    private fun spawnNewOrganisms() {
        val alive = organEntity.aliveList
        for (k in 0 until alive.size) {
            val organ = alive.getInt(k)
            if (organEntity.cellArenaBase[organ] == -1 || bridge.hasBody(organ)) continue
            val cellBase = organEntity.cellArenaBase[organ]
            var zygote = -1
            for (slot in 0 until organEntity.cellArenaCapacity[organ]) {
                if (cellEntity.isAlive[cellBase + slot]) { zygote = slot; break }
            }
            if (zygote == -1) continue      // опустел, deleteEmptiedOrgans уберёт его в конце тика
            val p = organEntity.particleArenaBase[organ] + zygote
            val pe = particleEntity
            bridge.spawnOrganism(organ, organEntity.arenaLayout[organ]!!, zygote, pe.localX[p], pe.localY[p])
            bodyGeneration[organ] = organEntity.getGeneration(organ)
            organsWithBody.add(organ)
            onBorn(p, bridge.particleOf(organ, zygote))
        }
    }

    private fun syncOrganism(organ: Int) {
        val world = bridge.world
        val dead = world.state.cellDead
        val first = bridge.particleOf(organ, 0)
        val cellBase = organEntity.cellArenaBase[organ]
        val particleBase = organEntity.particleArenaBase[organ]
        val alive = cellEntity.isAlive
        val shortening = cellEntity.degreeOfShortening
        for (slot in 0 until organEntity.cellArenaCapacity[organ]) {
            val i = first + slot
            val cell = cellBase + slot
            if (world.isUnborn(i)) {
                if (!alive[cell]) continue
                world.activate(i)
                onBorn(particleBase + slot, i)
                continue
            }
            // Умершая в мире физики клетка заново не рождается (TODO к B2): у живой клетки игры
            // в таком слоте номера в мире физики нет, она стоит там, куда её поставила игра.
            if (dead[i]) continue
            if (!alive[cell]) { world.kill(i); continue }
            world.setCellScale(i, shortening[cell])
            push(particleBase + slot, i)
        }
    }

    private fun syncSubstances() {
        val alive = substancesEntity.aliveList
        for (k in 0 until alive.size) {
            val sub = alive.getInt(k)
            val p = substancesEntity.particleIndex[sub]
            val id = freeOfSubstance[sub]
            if (id == -1) {
                addSubstance(sub, p, particleEntity.localX[p], particleEntity.localY[p], particleEntity.localVx[p], particleEntity.localVy[p])
                trackedSubstances.add(sub)
            } else if (particleEntity.radius[p] != substanceRadius[sub]) {
                // Слияние мелких субстанций (CollisionManager) меняет радиус: у свободной
                // частицы мира он постоянный, поэтому она заводится заново на том же месте.
                // Из моста, а не из ParticleEntity: подсадка и рост пула в этой же сверке могли
                // завести массивы мира заново, ссылки на них обновятся после шага.
                val i = bridge.substanceParticle(id)
                val x = bridge.x(i); val y = bridge.y(i); val vx = bridge.vx(i); val vy = bridge.vy(i)
                bridge.removeSubstance(id)
                addSubstance(sub, p, x, y, vx, vy)
                remapIfCompacted()
            } else {
                push(p, bridge.substanceParticle(id))
            }
        }
    }

    private fun addSubstance(sub: Int, p: Int, x: Float, y: Float, vx: Float, vy: Float) {
        val pe = particleEntity
        val id = bridge.addSubstance(x, y, pe.radius[p], vx, vy, drag = PhysicsBridge.dragScaleOf(pe.dragCoefficient[p]))
        freeOfSubstance[sub] = id
        substanceGeneration[sub] = substancesEntity.getGeneration(sub)
        substanceRadius[sub] = pe.radius[p]
        pe.physicsIndex[p] = bridge.substanceParticle(id)
        pe.pushVx[p] = 0f; pe.pushVy[p] = 0f
    }

    /**
     * Клетка родилась в мире физики. Сопротивление — вязкость мира игры в долях значения по
     * умолчанию: при нём — материал стенда (среда библиотеки). Прежняя вязкость клетки
     * (0.03 за тик, ослабленная числом связей) поверх среды библиотеки сделала бы тело вдесятеро
     * вязче, чем его подбирали. Скорость рождения — от библиотеки.
     */
    private fun onBorn(p: Int, i: Int) {
        val pe = particleEntity
        val drag = pe.dragCoefficient[p].toDouble() / DEFAULT_VISCOSITY.toDouble()
        if (drag != 1.0) bridge.setDrag(i, drag)
        pe.physicsIndex[p] = i
        pe.pushVx[p] = 0f; pe.pushVy[p] = 0f
    }

    /** Приращение скорости из параллельных стадий — в мир, с пробуждением организма. */
    private fun push(p: Int, i: Int) {
        val pe = particleEntity
        val dvx = pe.pushVx[p]; val dvy = pe.pushVy[p]
        if (dvx == 0f && dvy == 0f) return
        pe.pushVx[p] = 0f; pe.pushVy[p] = 0f
        bridge.setVelocity(i, bridge.vx(i) + dvx, bridge.vy(i) + dvy)
    }

    // --- 4. сетка и номера частиц -------------------------------------------------------

    /** Ссылки ParticleEntity на массивы мира физики: он заводит их заново при росте и уплотнении. */
    private fun publish() {
        val world = bridge.world
        val pe = particleEntity
        if (world.particleCount == 0) {
            // Пустой мир (до первой подсадки) своих массивов ещё не завёл.
            pe.physicsX = EMPTY; pe.physicsY = EMPTY; pe.physicsVx = EMPTY; pe.physicsVy = EMPTY
            return
        }
        pe.physicsX = world.x; pe.physicsY = world.y
        pe.physicsVx = world.vx; pe.physicsVy = world.vy
    }

    /** Мир физики уплотнился — номера частиц сдвинулись: заново по номерам тел. */
    private fun remapIfCompacted() {
        val world = bridge.world
        if (world.compactions == compactions) return
        compactions = world.compactions
        val pe = particleEntity
        for (k in 0 until organsWithBody.size) {
            val organ = organsWithBody.getInt(k)
            val first = bridge.particleOf(organ, 0)
            val particleBase = organEntity.particleArenaBase[organ]
            for (slot in 0 until organEntity.cellArenaCapacity[organ]) {
                val p = particleBase + slot
                if (pe.physicsIndex[p] >= 0) pe.physicsIndex[p] = first + slot
            }
        }
        for (k in 0 until trackedSubstances.size) {
            val sub = trackedSubstances.getInt(k)
            pe.physicsIndex[substancesEntity.particleIndex[sub]] = bridge.substanceParticle(freeOfSubstance[sub])
        }
        publish()
    }

    private fun updateGrid() {
        val alive = particleEntity.isAlive
        val index = particleEntity.physicsIndex
        for (k in 0 until organsWithBody.size) {
            val organ = organsWithBody.getInt(k)
            val particleBase = organEntity.particleArenaBase[organ]
            for (slot in 0 until organEntity.cellArenaCapacity[organ]) {
                val p = particleBase + slot
                if (alive[p] && index[p] >= 0) updateCell(p)
            }
        }
        for (k in 0 until trackedSubstances.size) updateCell(substancesEntity.particleIndex[trackedSubstances.getInt(k)])
    }

    private fun updateCell(p: Int) {
        val pe = particleEntity
        val x = pe.x(p); val y = pe.y(p)
        if (DEBUG_CHECKS && (x.isNaN() || y.isNaN())) nanPosition(p)
        val cell = gridManager.cellIndexOfClamped(x.toInt(), y.toInt())
        if (cell != pe.gridId[p]) {
            // Вне стадий: команды феромонов встают после последней стадии тика (WorkOrder).
            if (pe.isPheromoneEmitter[p]) pheromonesManager.newGridCell(x, y, p, 0)
            pe.gridId[p] = cell
        }
    }

    private fun nanPosition(p: Int): Nothing =
        throw IllegalStateException("частица $p (мир физики ${particleEntity.physicsIndex[p]}) получила NaN-позицию")

    private fun ensureCapacity() {
        val organs = organEntity.cellArenaBase.size
        if (bodyGeneration.size < organs) bodyGeneration = bodyGeneration.copyOf(organs)
        val subs = substancesEntity.particleIndex.size
        if (freeOfSubstance.size < subs) {
            val old = freeOfSubstance.size
            freeOfSubstance = freeOfSubstance.copyOf(subs).also { it.fill(-1, old, subs) }
            substanceGeneration = substanceGeneration.copyOf(subs)
            substanceRadius = substanceRadius.copyOf(subs)
        }
    }

    private companion object {
        /** Вязкость среды мира по умолчанию — GlobalSettings.viscosityOfTheEnvironment. */
        const val DEFAULT_VISCOSITY = 0.03f

        val EMPTY = DoubleArray(0)
    }
}
