package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.physics.PhysicsWorkers
import it.unimi.dsi.fastutil.ints.IntArrayList

/**
 * ФИЗИКА МИРА ИГРЫ НА БИБЛИОТЕКЕ — фаза тика вместо прежнего движения (ARRANGE). Этап B1 плана
 * docs/physics/library-integration-plan-2026-10-25.md, вариант «сначала копия» (решение
 * пользователя 26.10): позиции и скорости после шага переписываются в ParticleEntity, и все
 * читатели игры работают как раньше; на прямое чтение мира они переходят по одному, копия
 * уходит к B5.
 *
 * Фаза однопоточная вокруг шага мира (сам шаг — на воркерах игры) и идёт так:
 *  1. СВЕРКА СОСТАВА. Мир физики догоняет игру: организм с ареной и без тела — тело всей
 *     раскладкой из его живой клетки (зиготы); клетка игры жива, а в мире не рождена —
 *     рождение; клетка игры умерла — kill; организм умер (или его номер занят новым — другое
 *     поколение) — тело убирается. Так же субстанции: новая — свободная частица, исчезла —
 *     убрана, сменила радиус (слияние мелких) — заводится заново. Отдельных крючков в
 *     обработчиках команд нет: копия всё равно проходит по всем слотам, и сверка в том же
 *     проходе ловит любой путь создания и удаления, в том числе загрузку мира.
 *  2. ВХОДЫ. Мышца клетки — degreeOfShortening (0.5–1.5, среднее гармоническое на связи, как
 *     было в LinkPhysicsSystem). Скорость: всё, что игра записала в vx/vy после прошлой копии
 *     (жгутик, плавник, захват мышью, липучка, отладочный бросок), уходит в мир — сравнением
 *     с тем, что записала копия. Нетронутые частицы мир не трогает: перевод float → double
 *     туда-обратно иначе каждый тик сбивал бы скорости на округление.
 *  3. ШАГ МИРА.
 *  4. КОПИЯ: позиции и скорости в единицах игры в ParticleEntity; смена клетки сетки — gridId
 *     и след феромона (как делал MovementManager).
 *
 * Порядок обхода — организмы в порядке подсадки, слоты по порядку, субстанции в порядке
 * появления: от числа воркеров не зависит ничего.
 *
 * Цена: два прохода по клеткам и субстанциям за тик (сверка со входами и копия), потоком по
 * аренам. Состояние мира физики в сохранение и хэш пока не входит (B3); события библиотеки
 * (разрыв, гибель клетки физикой, касания) игре не сообщаются (B2).
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

    /** Организмы с телом в порядке подсадки и поколение организма при подсадке. */
    private val organsWithBody = IntArrayList()
    private var bodyGeneration = IntArray(64)

    /** Субстанции со свободной частицей в порядке появления; номер частицы, поколение, радиус. */
    private val trackedSubstances = IntArrayList()
    private var freeOfSubstance = IntArray(256) { -1 }
    private var substanceGeneration = IntArray(256)
    private var substanceRadius = FloatArray(256)

    /** Что копия записала в vx/vy частицы игры: отличие — скорость от игры (ВХОДЫ). */
    private var copiedVx = FloatArray(0)
    private var copiedVy = FloatArray(0)

    /** Новый мир физики: прежний мир игры стёрт (рестарт, новая карта, загрузка). */
    fun reset() {
        bridge = PhysicsBridge(workers)
        boundsWidth = -1; boundsHeight = -1
        organsWithBody.clear()
        trackedSubstances.clear()
        freeOfSubstance.fill(-1)
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
        copyOut()
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
            bridge.spawnOrganism(organ, organEntity.arenaLayout[organ]!!, zygote, particleEntity.x[p], particleEntity.y[p])
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
            // Умершая в мире физики клетка заново не рождается (TODO к B2): живая клетка игры в
            // таком слоте стоит на месте гибели.
            if (dead[i]) continue
            if (!alive[cell]) { world.kill(i); continue }
            world.setCellScale(i, shortening[cell])
            pushVelocity(particleBase + slot, i)
        }
    }

    private fun syncSubstances() {
        val alive = substancesEntity.aliveList
        for (k in 0 until alive.size) {
            val sub = alive.getInt(k)
            val p = substancesEntity.particleIndex[sub]
            val id = freeOfSubstance[sub]
            if (id == -1) {
                addSubstance(sub, p)
                trackedSubstances.add(sub)
            } else if (particleEntity.radius[p] != substanceRadius[sub]) {
                // Слияние мелких субстанций (CollisionManager) меняет радиус: у свободной
                // частицы мира он постоянный, поэтому она заводится заново на том же месте.
                bridge.removeSubstance(id)
                addSubstance(sub, p)
            } else {
                pushVelocity(p, bridge.substanceParticle(id))
            }
        }
    }

    private fun addSubstance(sub: Int, p: Int) {
        val pe = particleEntity
        freeOfSubstance[sub] = bridge.addSubstance(
            pe.x[p], pe.y[p], pe.radius[p], pe.vx[p], pe.vy[p],
            drag = PhysicsBridge.dragScaleOf(pe.dragCoefficient[p])
        )
        substanceGeneration[sub] = substancesEntity.getGeneration(sub)
        substanceRadius[sub] = pe.radius[p]
        copiedVx[p] = pe.vx[p]; copiedVy[p] = pe.vy[p]
    }

    /**
     * Клетка родилась в мире физики. Сопротивление — вязкость мира игры в долях значения по
     * умолчанию: при нём — материал стенда (среда библиотеки). Прежняя вязкость клетки
     * (0.03 за тик, ослабленная числом связей) поверх среды библиотеки сделала бы тело вдесятеро
     * вязче, чем его подбирали. Скорость рождения — от библиотеки: то, что игра записала в
     * vx/vy новой клетки, считается уже увиденным.
     */
    private fun onBorn(p: Int, i: Int) {
        val drag = particleEntity.dragCoefficient[p].toDouble() / DEFAULT_VISCOSITY.toDouble()
        if (drag != 1.0) bridge.setDrag(i, drag)
        copiedVx[p] = particleEntity.vx[p]; copiedVy[p] = particleEntity.vy[p]
    }

    private fun pushVelocity(p: Int, i: Int) {
        val vx = particleEntity.vx[p]; val vy = particleEntity.vy[p]
        if (vx != copiedVx[p] || vy != copiedVy[p]) bridge.setVelocity(i, vx, vy)
    }

    // --- 4. копия -----------------------------------------------------------------------

    private fun copyOut() {
        val alive = particleEntity.isAlive
        for (k in 0 until organsWithBody.size) {
            val organ = organsWithBody.getInt(k)
            val first = bridge.particleOf(organ, 0)
            val particleBase = organEntity.particleArenaBase[organ]
            for (slot in 0 until organEntity.cellArenaCapacity[organ]) {
                val p = particleBase + slot
                if (alive[p]) copy(p, first + slot)
            }
        }
        for (k in 0 until trackedSubstances.size) {
            val sub = trackedSubstances.getInt(k)
            copy(substancesEntity.particleIndex[sub], bridge.substanceParticle(freeOfSubstance[sub]))
        }
    }

    private fun copy(p: Int, i: Int) {
        val pe = particleEntity
        val oldX = pe.x[p].toInt()
        val oldY = pe.y[p].toInt()
        val x = bridge.x(i); val y = bridge.y(i)
        if (DEBUG_CHECKS && (x.isNaN() || y.isNaN())) nanPosition(p, i)
        pe.x[p] = x; pe.y[p] = y
        val vx = bridge.vx(i); val vy = bridge.vy(i)
        pe.vx[p] = vx; pe.vy[p] = vy
        copiedVx[p] = vx; copiedVy[p] = vy
        val gx = x.toInt(); val gy = y.toInt()
        if (gx != oldX || gy != oldY) {
            // Вне стадий: команды феромонов встают после последней стадии тика (WorkOrder).
            if (pe.isPheromoneEmitter[p]) pheromonesManager.newGridCell(x, y, p, 0)
            pe.gridId[p] = gridManager.cellIndexOfClamped(gx, gy)
        }
    }

    private fun nanPosition(p: Int, i: Int): Nothing =
        throw IllegalStateException("частица $p (мир физики $i) получила NaN-позицию")

    private fun ensureCapacity() {
        val particles = particleEntity.x.size
        if (copiedVx.size < particles) { copiedVx = copiedVx.copyOf(particles); copiedVy = copiedVy.copyOf(particles) }
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
    }
}
