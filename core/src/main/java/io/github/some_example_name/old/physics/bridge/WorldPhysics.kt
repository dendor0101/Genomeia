package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.old.commands.WorldCommandType
import io.github.some_example_name.old.commands.WorldCommandsManager
import io.github.some_example_name.old.core.DEBUG_CHECKS
import io.github.some_example_name.old.entities.CellEntity
import io.github.some_example_name.old.entities.LinkEntity
import io.github.some_example_name.old.entities.OrganEntity
import io.github.some_example_name.old.entities.ParticleEntity
import io.github.some_example_name.old.entities.SubstancesEntity
import io.github.some_example_name.old.features.worldeditor.WorldTerrainManager
import io.github.some_example_name.old.journal.Fnv
import io.github.some_example_name.old.systems.pheromone.PheromonesManager
import io.github.some_example_name.old.systems.physics.GridManager
import io.github.some_example_name.physics.PhysicsWorkers
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
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
 *     Стенка вдоль рамки — неподвижные частицы на месте террейна, раз на размер мира ([buildWall]).
 *  2. ВХОДЫ. Кость — клетка игры стала типа «кость» (мутация) → setBone, форма на этот момент.
 *     Мягкость — клетка типа «жир» → setSoftness([FAT_SOFTNESS]), с мутацией туда и обратно.
 *     Присоска-чашка — хватка (трение о дно) каждый тик — setGrip; клетка без столкновений
 *     (isCollidable = false, присоска) — setContactless.
 *     Мышца клетки — degreeOfShortening (0.5–1.5, среднее гармоническое на связи, как
 *     было в LinkPhysicsSystem). Приращения скорости из параллельных стадий (жгутик, плавник,
 *     липучка, присоска — ParticleEntity.pushVx/pushVy) уходят в мир с пробуждением организма.
 *     Однопоточные записи (мышь, отладочный бросок) идут в мир сразу — [setVelocity], [place].
 *  3. ШАГ МИРА.
 *  4. СОБЫТИЯ (этап B2): что погибло в шаге, игра узнаёт командами мира этого же тика —
 *     клетка организма — DELETE_CELL, свободная частица — DELETE_SUBSTANCE, порванная связь
 *     между живыми клетками — DELETE_LINK ([reportDeaths]). Край тела клеток игры (сетка
 *     касаний) — по флагу физики «клетка сталкивается» ([syncSurface]).
 *  5. СЕТКА: смена клетки сетки — gridId и след феромона (как делал MovementManager); ссылки
 *     ParticleEntity.physics* — на массивы мира (он заводит их заново при росте и уплотнении);
 *     после уплотнения мира физики номера частиц пересчитываются по номерам тел.
 *
 * Порядок обхода — организмы в порядке подсадки, слоты по порядку, субстанции в порядке
 * появления: от числа воркеров не зависит ничего.
 *
 * Цена: проход по клеткам и субстанциям до шага (сверка со входами) и после (сетка), потоком
 * по аренам. Касания для сенсоров и поедания пока находит прежний перебор пар по сетке игры
 * (CollisionManager.onContact) по позициям мира физики.
 */
class WorldPhysics(
    private val organEntity: OrganEntity,
    private val cellEntity: CellEntity,
    private val linkEntity: LinkEntity,
    private val particleEntity: ParticleEntity,
    private val substancesEntity: SubstancesEntity,
    private val gridManager: GridManager,
    private val pheromonesManager: PheromonesManager,
    private val worldCommandsManager: WorldCommandsManager,
    private val workers: PhysicsWorkers?
) {
    var bridge = PhysicsBridge(workers)
        private set

    /** Тип клетки «кость» (Bone) — по ключу: номер это место в реестре типов. */
    private val boneType = cellEntity.cellList.requireIndex("bone")

    /** Тип клетки «жир» (Fat) — мягкая ткань, [FAT_SOFTNESS]. */
    private val fatType = cellEntity.cellList.requireIndex("fat")

    /** Тип клетки «присоска-чашка» (SuctionCup): хватка каждый тик (PhysicsWorld.setGrip). */
    private val suctionCupType = cellEntity.cellList.requireIndex("suction_cup")

    /** Липучки и их временные связи в мире физики ([requestStick], StickyJoints). */
    private val sticks = StickyJoints(cellEntity, particleEntity, substancesEntity, cellEntity.cellList.requireIndex("sticky"))

    /** Мягкий террейн из плиток со швами (TerrainBodies): одно тело мира физики на мир. */
    private val terrain = TerrainBodies(particleEntity)

    private var boundsWidth = -1
    private var boundsHeight = -1
    private var compactions = 0
    /** Уплотнение, при котором флаги края клеток сверены с физикой целиком; -1 — ещё ни разу. */
    private var surfaceCompactions = -1

    /** Организмы с телом в порядке подсадки и поколение организма при подсадке. */
    private val organsWithBody = IntArrayList()
    private var bodyGeneration = IntArray(64)

    /** Субстанции со свободной частицей в порядке появления; номер частицы, поколение, радиус. */
    private val trackedSubstances = IntArrayList()
    private var freeOfSubstance = IntArray(256) { -1 }
    private var substanceGeneration = IntArray(256)
    private var substanceRadius = FloatArray(256)

    /** Стенка вдоль рамки ([buildWall]): номера её неподвижных частиц и размер мира, под который она стоит. */
    private val wallIds = IntArrayList()
    private var wallWidth = -1
    private var wallHeight = -1

    init {
        particleEntity.physics = this
        publish()
    }

    /** Новый мир физики: прежний мир игры стёрт (рестарт, новая карта, загрузка). */
    fun reset() {
        bridge = PhysicsBridge(workers)
        boundsWidth = -1; boundsHeight = -1
        compactions = 0
        surfaceCompactions = -1
        organsWithBody.clear()
        trackedSubstances.clear()
        freeOfSubstance.fill(-1)
        wallIds.clear(); wallWidth = -1; wallHeight = -1
        sticks.clear()
        terrain.clear()
        particleEntity.physicsIndex.fill(-1)
        publish()
    }

    fun tick() {
        ensureCapacity()
        if (boundsWidth != gridManager.gridWidth || boundsHeight != gridManager.gridHeight) {
            boundsWidth = gridManager.gridWidth; boundsHeight = gridManager.gridHeight
            bridge.setBounds(boundsWidth.toFloat(), boundsHeight.toFloat())
        }
        if (wallWidth != gridManager.gridWidth || wallHeight != gridManager.gridHeight) buildWall()
        if (!terrain.built) terrain.build(bridge.world)
        removeGone()
        spawnNewOrganisms()
        for (k in 0 until organsWithBody.size) syncOrganism(organsWithBody.getInt(k))
        syncSubstances()
        sticks.sync(bridge.world)
        bridge.step()
        publish()
        reportDeaths()
        syncSurface()
        updateGrid()
    }

    /**
     * СТЕНКА: на месте частиц террейна вдоль рамки — неподвижные бессмертные частицы мира физики
     * (PhysicsWorld.addFixed) той же раскладки и радиуса (WorldTerrainManager.forEachWallParticle).
     * Тела упираются в видимую стенку, а не в рамку за ней; рамка (clampToBounds) остаётся
     * страховкой. Строится раз на размер мира, в снимке мира физики лежит вместе с ним, номера —
     * в [encodeState]. Частицы террейна игры остаются как были (отрисовка): стенка не движется,
     * и две копии мест не расходятся.
     */
    private fun buildWall() {
        for (k in 0 until wallIds.size) bridge.removeSubstance(wallIds.getInt(k))
        wallIds.clear()
        wallWidth = gridManager.gridWidth; wallHeight = gridManager.gridHeight
        WorldTerrainManager.forEachWallParticle(wallWidth, wallHeight) { x, y ->
            val id = bridge.addWall(x, y, WorldTerrainManager.WALL_RADIUS)
            wallIds.add(id)
            // Стенка и террейн — одна группа касаний: террейн у рамки перекрывает стенку и не расталкивается.
            bridge.world.setContactGroup(bridge.substanceParticle(id), TerrainBodies.TERRAIN_GROUP)
        }
        mapWallParticles()
    }

    // --- сохранение мира (MapSave, этап B3) --------------------------------------------

    /**
     * Снимок для сохранения мира: мир физики (PhysicsWorld.write — полный, с побитовым
     * продолжением), тела организмов с поколениями, субстанции со свободными частицами и то,
     * у каких клеток игры есть частица в мире физики (у клетки, выросшей заново в слоте
     * умершей, её нет — по одному миру это не вывести). Запись уплотняет мир физики: номера
     * частиц пересчитываются сразу, пишущий мир дальше идёт по тому же, что и загруженный.
     * Однопоточно, между тиками.
     */
    fun encodeState(out: java.io.DataOutputStream) {
        val hasWorld = bridge.world.particleCount > 0
        out.writeBoolean(hasWorld)
        if (!hasWorld) return
        bridge.write(out)
        remapIfCompacted()
        val pe = particleEntity
        out.writeInt(organsWithBody.size)
        for (k in 0 until organsWithBody.size) {
            val organ = organsWithBody.getInt(k)
            out.writeInt(organ); out.writeInt(bodyGeneration[organ])
            val particleBase = organEntity.particleArenaBase[organ]
            val capacity = organEntity.cellArenaCapacity[organ]
            out.writeInt(capacity)
            for (slot in 0 until capacity) out.writeBoolean(pe.physicsIndex[particleBase + slot] >= 0)
        }
        out.writeInt(trackedSubstances.size)
        for (k in 0 until trackedSubstances.size) {
            val sub = trackedSubstances.getInt(k)
            out.writeInt(sub); out.writeInt(freeOfSubstance[sub])
            out.writeInt(substanceGeneration[sub]); out.writeFloat(substanceRadius[sub])
        }
        out.writeInt(wallWidth); out.writeInt(wallHeight)
        out.writeInt(wallIds.size)
        for (k in 0 until wallIds.size) out.writeInt(wallIds.getInt(k))
        sticks.write(out)
        terrain.write(out)
    }

    /** Прочитать снимок [encodeState]. Сущности игры к этому моменту уже загружены. */
    fun decodeState(input: java.io.DataInputStream) {
        reset()
        if (!input.readBoolean()) return
        bridge.read(input)
        ensureCapacity()
        val pe = particleEntity
        repeat(input.readInt()) {
            val organ = input.readInt()
            bodyGeneration[organ] = input.readInt()
            organsWithBody.add(organ)
            val first = bridge.particleOf(organ, 0)
            val particleBase = organEntity.particleArenaBase[organ]
            val capacity = input.readInt()
            check(capacity == organEntity.cellArenaCapacity[organ]) { "у организма $organ другая арена, чем в мире физики" }
            for (slot in 0 until capacity) if (input.readBoolean()) pe.physicsIndex[particleBase + slot] = first + slot
        }
        repeat(input.readInt()) {
            val sub = input.readInt()
            val id = input.readInt()
            freeOfSubstance[sub] = id
            substanceGeneration[sub] = input.readInt()
            substanceRadius[sub] = input.readFloat()
            trackedSubstances.add(sub)
            pe.physicsIndex[substancesEntity.particleIndex[sub]] = bridge.substanceParticle(id)
        }
        wallWidth = input.readInt(); wallHeight = input.readInt()
        repeat(input.readInt()) { wallIds.add(input.readInt()) }
        sticks.read(input)
        terrain.read(input)
        terrain.remap(bridge.world)
        mapWallParticles()
        compactions = bridge.world.compactions
        publish()
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
        return sticks.hash(x)
    }

    /**
     * Липучка [c] поколения [gen] коснулась частицы игры [p] (команда STICK, однопоточно в порядке
     * команд). Частица мира физики партнёра — её номер (у клетки, еды, террейна и стенки у рамки он есть).
     */
    fun requestStick(c: Int, gen: Int, p: Int) = sticks.request(bridge.world, c, gen, p, particleEntity.physicsIndex[p])

    /**
     * Частицы стенки игры (ParticleEntity.terrainCell — метка TerrainBodies.wallMark) — к частицам стенки мира
     * физики по номеру в раскладке ([wallIds] — в её же порядке): рисуются и спят по миру физики, липучка
     * липнет к ним по номеру. После постройки стенки, уплотнения мира и загрузки. Проход по частицам не-клеткам.
     */
    private fun mapWallParticles() {
        val pe = particleEntity
        val list = pe.nonCellList
        for (k in 0 until list.size) {
            val p = list.getInt(k)
            val mark = pe.terrainCell[p]
            if (mark > -2) continue
            val w = TerrainBodies.wallIndexOf(mark)
            if (w < wallIds.size) pe.physicsIndex[p] = bridge.substanceParticle(wallIds.getInt(w))
        }
    }

    /** Спит ли организм частицы игры [p] в мире физики (фильтр отладки «сон»). Между тиками. */
    fun isAsleep(p: Int): Boolean {
        val i = particleEntity.physicsIndex[p]
        return i >= 0 && bridge.world.isAsleep(i)
    }

    /** Однопоточная запись скорости в единицах игры (ParticleEntity.setVelocity). */
    fun setVelocity(i: Int, vx: Float, vy: Float) = bridge.setVelocity(i, vx, vy)

    /** Убивает ли глубокое проникновение частиц (галочка отладки, SimulationData.debugNoDeepKill). */
    var deepKillOn: Boolean
        get() = bridge.world.deepKillOn
        set(value) { bridge.world.deepKillOn = value }

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

    /** Прямое размещение взрослого макета в отдельном мире до первого тика. */
    fun placeAdultAtRest(organ: Int) {
        val layout = checkNotNull(organEntity.arenaLayout[organ])
        val firstCell = organEntity.cellArenaBase[organ]
        val firstParticle = organEntity.particleArenaBase[organ]
        require(organEntity.alreadyGrownUp[organ] && (0 until layout.cellsInLayout).all { cellEntity.isAlive[firstCell + it] })
        ensureCapacity()
        bridge.spawnPlacedBody(organ, layout, particleEntity.localX[firstParticle], particleEntity.localY[firstParticle])
        bodyGeneration[organ] = organEntity.getGeneration(organ)
        organsWithBody.add(organ)
        for (slot in 0 until layout.cellsInLayout) onBorn(firstParticle + slot, bridge.particleOf(organ, slot))
        publish()
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
                // Мягкость — до рождения: связи новорождённой клетки возьмут её сразу.
                world.setSoftness(i, softnessOf(cell))
                world.setContactless(i, !particleEntity.isCollidable[particleBase + slot])
                // Деление: родитель игры жив, в этом же теле и рождён в мире физики — клетка
                // рождается почти в нём и выталкивается связями (activateFromParent). Иначе
                // (зигота, родитель умер или в другом теле) — по позе, как раньше.
                val parent = cellEntity.parentIndex[cell]
                val ps = parent - cellBase
                if (parent >= 0 && alive[parent] && ps in 0 until organEntity.cellArenaCapacity[organ] &&
                    !world.isUnborn(first + ps) && !dead[first + ps]) world.activateFromParent(i, first + ps)
                else world.activate(i)
                onBorn(particleBase + slot, i)
                continue
            }
            // Умершая в мире физики клетка заново не рождается (TODO к B2): у живой клетки игры
            // в таком слоте номера в мире физики нет, она стоит там, куда её поставила игра.
            if (dead[i]) continue
            if (!alive[cell]) { world.kill(i); refreshDragAround(i); continue }
            // Клетка стала костью (мутация, деление с типом «кость»): кость включается с формой
            // на этот момент. У клетки не из кости раскладки и у уже включённой — ничего.
            if (cellEntity.cellType[cell].toInt() == boneType && !world.isBone(i)) world.setBone(i)
            world.setSoftness(i, softnessOf(cell))
            world.setCellScale(i, shortening[cell])
            // Присоска (isCollidable = false) ни с чем не сталкивается, как в прежней физике игры.
            world.setContactless(i, !particleEntity.isCollidable[particleBase + slot])
            // Присоска-чашка: хватка — её dragCoefficient (импульс 0..1, SuctionCup.doOnTick), каждый тик.
            world.setGrip(i, if (cellEntity.cellType[cell].toInt() == suctionCupType) particleEntity.dragCoefficient[particleBase + slot] else 0f)
            push(particleBase + slot, i)
        }
    }

    private fun softnessOf(cell: Int): Float = if (cellEntity.cellType[cell].toInt() == fatType) FAT_SOFTNESS else 1f

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

    /** Клетка родилась в мире физики; скорость рождения — от библиотеки. Сопротивление — [refreshDrag]. */
    private fun onBorn(p: Int, i: Int) {
        val pe = particleEntity
        pe.physicsIndex[p] = i
        pe.pushVx[p] = 0f; pe.pushVy[p] = 0f
        refreshDrag(p, i)
        refreshDragAround(i)
    }

    /**
     * Сопротивление среды клетки [i] мира физики (частица игры [p]).
     *  - Со связями — вязкость мира игры в долях значения по умолчанию: при нём — материал стенда
     *    (среда библиотеки). Основное сопротивление такой клетке дают рёбра контура (NORMAL_DRAG);
     *    прежняя вязкость клетки (0.03 за тик) поверх среды библиотеки сделала бы тело вдесятеро
     *    вязче, чем его подбирали.
     *  - Без живых связей (зигота до первого соседа, оторвавшаяся клетка) — как у свободной частицы
     *    той же вязкости (еда, PhysicsBridge.dragScaleOf): рёбер контура у неё нет, и одной среды
     *    библиотеки (0.092/с, вдвое за ~7.5 с) хватало, чтобы оторванная клетка катилась через весь
     *    мир (отчёт 27.10 19:40: клетка 237 Crayfish пролетела 65 клеток до стенки).
     * Зовётся по событиям — рождение, гибель ребра, гибель клетки, — а не проходом по миру.
     */
    private fun refreshDrag(p: Int, i: Int) {
        val pe = particleEntity
        // У чашки dragCoefficient — хватка (setGrip), а не вязкость: среда у неё как у обычной клетки.
        val drag = if (pe.isCell[p] && cellEntity.cellType[pe.holderEntityIndex[p]].toInt() == suctionCupType) DEFAULT_VISCOSITY
        else pe.dragCoefficient[p]
        val scale = if (bridge.world.liveDegree(i) == 0) PhysicsBridge.dragScaleOf(drag)
        else drag.toDouble() / DEFAULT_VISCOSITY.toDouble()
        bridge.setDrag(i, scale)
    }

    /** [refreshDrag] у живых соседей клетки [i] мира физики: у них стало на связь больше или меньше. */
    private fun refreshDragAround(i: Int) {
        val world = bridge.world
        for (k in 0 until world.neighborCount(i)) refreshDragAlive(world.neighbor(i, k))
    }

    private fun refreshDragAlive(i: Int) {
        val world = bridge.world
        if (world.isUnborn(i) || world.state.cellDead[i]) return
        val p = gameParticleOf(i)
        if (p >= 0) refreshDrag(p, i)
    }

    /** Приращение скорости из параллельных стадий — в мир, с пробуждением организма. */
    private fun push(p: Int, i: Int) {
        val pe = particleEntity
        val dvx = pe.pushVx[p]; val dvy = pe.pushVy[p]
        if (dvx == 0f && dvy == 0f) return
        pe.pushVx[p] = 0f; pe.pushVy[p] = 0f
        bridge.setVelocity(i, bridge.vx(i) + dvx, bridge.vy(i) + dvy)
    }

    // --- 4. события шага ---------------------------------------------------------------

    /** Частица мира физики → субстанция; собирается, только когда погибла не клетка организма. */
    private val substanceOfParticle = Int2IntOpenHashMap().apply { defaultReturnValue(-1) }

    /**
     * Что погибло в шаге — командами мира этого же тика (WORLD_CMD идёт после фазы физики), в
     * порядке гибели и вне стадий: команды встают после последней стадии тика (WorkOrder).
     * Порванное ребро погибшей клетки пропускается — её связи снимет DELETE_CELL.
     */
    private fun reportDeaths() {
        val world = bridge.world
        val cells = world.cellDeathCount
        val edges = world.edgeDeathCount
        if (cells == 0 && edges == 0) return
        val buffer = worldCommandsManager.worldCommandBuffer[0]
        val alive = cellEntity.isAlive
        substanceOfParticle.clear()
        for (k in 0 until cells) {
            val i = world.cellDeath(k)
            val organ = organOf(i)
            if (organ >= 0) {
                val cell = organEntity.cellArenaBase[organ] + (i - bridge.particleOf(organ, 0))
                if (alive[cell]) buffer.push(WorldCommandType.DELETE_CELL, cell, cellEntity.getGeneration(cell))
                continue
            }
            val sub = substanceOf(i)
            if (sub >= 0) buffer.push(WorldCommandType.DELETE_SUBSTANCE, sub, substancesEntity.getGeneration(sub))
        }
        val dead = world.state.cellDead
        for (k in 0 until edges) {
            val a = world.edgeDeathA(k); val b = world.edgeDeathB(k)
            // Связей у концов стало меньше — одиночная клетка получает сопротивление свободной.
            refreshDragAlive(a); refreshDragAlive(b)
            if (dead[a] || dead[b]) continue
            val organ = organOf(a)
            if (organ < 0) continue
            val first = bridge.particleOf(organ, 0)
            val cellBase = organEntity.cellArenaBase[organ]
            val cellA = cellBase + (a - first); val cellB = cellBase + (b - first)
            if (!alive[cellA] || !alive[cellB]) continue
            val link = organEntity.takeLinkSlot(organ, cellEntity.cellGenomeId[cellA], cellEntity.cellGenomeId[cellB])
            if (link == -1 || !linkEntity.isAlive[link]) continue
            val l1 = linkEntity.links1[link]; val l2 = linkEntity.links2[link]
            if (!((l1 == cellA && l2 == cellB) || (l1 == cellB && l2 == cellA))) continue
            buffer.push(WorldCommandType.DELETE_LINK, link, linkEntity.getGeneration(link))
        }
    }

    /**
     * КРАЙ ТЕЛА ИЗ ФИЗИКИ (решение 03.11): клетка игры на краю — в сетке касаний сенсоров,
     * поедания и курсора, — ровно когда она сталкивается в мире физики (PhysicsWorld.isSurfaceCell).
     * Свой признак игры (число связей против взрослого тела) с контуром физики расходился.
     * По журналу смен флага, который пишут и шаг, и рождение с гибелью в сверке до шага; после
     * уплотнения, загрузки и в новом мире журнал начат заново — тогда сверка всех клеток. Цена —
     * O(сменивших флаг клеток) на событиях топологии, двоичный поиск организма на каждую.
     */
    private fun syncSurface() {
        val world = bridge.world
        if (world.particleCount == 0) return
        if (surfaceCompactions != world.compactions) {
            surfaceCompactions = world.compactions
            for (k in 0 until organsWithBody.size) syncAllSurface(organsWithBody.getInt(k))
        } else {
            for (k in 0 until world.surfaceChangeCount) syncSurfaceOf(world.surfaceChange(k))
        }
        world.clearSurfaceChanges()
    }

    private fun syncAllSurface(organ: Int) {
        val world = bridge.world
        val index = particleEntity.physicsIndex
        val cellBase = organEntity.cellArenaBase[organ]
        val particleBase = organEntity.particleArenaBase[organ]
        for (slot in 0 until organEntity.cellArenaCapacity[organ]) {
            val i = index[particleBase + slot]
            if (i >= 0 && cellEntity.isAlive[cellBase + slot]) cellEntity.setOnEdge(cellBase + slot, world.isSurfaceCell(i))
        }
    }

    private fun syncSurfaceOf(i: Int) {
        val organ = organOf(i)
        if (organ < 0) return
        val slot = i - bridge.particleOf(organ, 0)
        val cell = organEntity.cellArenaBase[organ] + slot
        // Не рождённая в игре или выросшая заново в слоте умершей — частицы мира у неё нет.
        if (!cellEntity.isAlive[cell] || particleEntity.physicsIndex[organEntity.particleArenaBase[organ] + slot] != i) return
        cellEntity.setOnEdge(cell, bridge.world.isSurfaceCell(i))
    }

    /**
     * Организм частицы мира физики, -1 — не клетка организма (свободная частица, слот пула).
     * Организмы с телом идут в порядке подсадки, а тела в мире — в том же порядке (дописывание
     * в конец, уплотнение порядок сохраняет): двоичный поиск по началу тела.
     */
    private fun organOf(i: Int): Int {
        var lo = 0; var hi = organsWithBody.size - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (bridge.particleOf(organsWithBody.getInt(mid), 0) <= i) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return -1
        val organ = organsWithBody.getInt(found)
        return if (i < bridge.particleOf(organ, 0) + organEntity.cellArenaCapacity[organ]) organ else -1
    }

    /**
     * Живые связи тел мира физики частицами игры — для отладочной отрисовки (галочка «Связи»):
     * концы пар подряд в [out]. Поток симуляции, после тика. Проход по аренам организмов —
     * только при включённой галочке.
     */
    fun linkParticles(out: IntArrayList) {
        out.clear()
        // Пустой мир (до первой подсадки): топологии ещё нет — baked не заведён.
        if (organsWithBody.size == 0) return
        val world = bridge.world
        val st = world.state
        val b = st.baked
        for (k in 0 until organsWithBody.size) {
            val organ = organsWithBody.getInt(k)
            val first = bridge.particleOf(organ, 0)
            val base = organEntity.particleArenaBase[organ]
            for (s in 0 until organEntity.cellArenaCapacity[organ]) {
                val i = first + s
                if (world.isUnborn(i) || st.cellDead[i]) continue
                for (q in b.vertStart[i] until b.vertStart[i + 1]) {
                    val o = b.vertOther[q]
                    if (o <= i || b.edgeDead[b.vertEdge[q]]) continue
                    out.add(base + s); out.add(base + (o - first))
                }
            }
        }
    }

    /**
     * Рёбра контура мира физики частицами игры — для отладочной отрисовки границы (галочка
     * «Граница», RenderBufferManager): концы пар подряд в [out]. Поток симуляции, после тика.
     * Только по рёбрам контура, без прохода по миру.
     */
    fun boundaryParticles(out: IntArrayList) {
        out.clear()
        val world = bridge.world
        for (k in 0 until world.boundaryEdgeCount) {
            val a = gameParticleOf(world.boundaryEdgeA(k))
            val b = gameParticleOf(world.boundaryEdgeB(k))
            if (a < 0 || b < 0) continue
            out.add(a); out.add(b)
        }
    }

    /** Частица игры для частицы мира физики, -1 — не клетка организма. */
    private fun gameParticleOf(i: Int): Int {
        val organ = organOf(i)
        return if (organ < 0) -1 else organEntity.particleArenaBase[organ] + (i - bridge.particleOf(organ, 0))
    }

    private fun substanceOf(i: Int): Int {
        if (substanceOfParticle.isEmpty()) {
            for (k in 0 until trackedSubstances.size) {
                val sub = trackedSubstances.getInt(k)
                substanceOfParticle.put(bridge.substanceParticle(freeOfSubstance[sub]), sub)
            }
        }
        return substanceOfParticle.get(i)
    }

    // --- 5. сетка и номера частиц -------------------------------------------------------

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
        terrain.remap(world)
        mapWallParticles()
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
        // Мягкий террейн тоже ходит: проминается и возвращается. Почти весь спит — клетка не меняется.
        for (k in 0 until terrain.count) { val p = terrain.particle(k); if (alive[p] && index[p] >= 0) updateCell(p) }
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

        /**
         * Мягкость жира: податливость его связей и треугольников в 5 раз больше обычной
         * (PhysicsWorld.setSoftness; связь жира с листом — среднее, 3). Решение 03.11: сначала 2.5
         * (в старой физике linkStiffness жира — вдвое меньше), после осмотра в игре — ещё вдвое мягче.
         */
        const val FAT_SOFTNESS = 5f

        val EMPTY = DoubleArray(0)
    }
}
