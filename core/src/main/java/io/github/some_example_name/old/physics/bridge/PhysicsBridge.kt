package io.github.some_example_name.old.physics.bridge

import io.github.some_example_name.old.systems.genomics.genome.BakedLayout
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.MEDIUM_DRAG
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld

/**
 * МОСТ ИГРЫ К ФИЗИКЕ-БИБЛИОТЕКЕ (`:physics`). Этап B1 плана
 * docs/physics/library-integration-plan-2026-10-25.md, раздел 3.
 *
 * Игра говорит организмами и слотами раскладки, библиотека — телами и частицами. Мост
 * держит только соответствие «организм игры → номер тела» и переводит единицы
 * (PhysicsUnits.kt); всё состояние физики — в мире библиотеки ([world]).
 *
 * АДРЕСАЦИЯ. Частица клетки — `world.bodyFirst(тело) + слот`, где слот — смещение клетки в
 * арене организма (`cellIndex - cellArenaBase`, он же слот BakedLayout). Начало тела в
 * мире сдвигается уплотнением (PhysicsWorld.compact), поэтому номер частицы берётся заново
 * ([particleOf]), а не хранится. Субстанция — номер свободной частицы библиотеки, он через
 * уплотнение стабилен.
 *
 * ПОТОКИ. Все вызовы — из потока симуляции между шагами ([step]); параллельные стадии игры
 * мост не трогают. Воркеры библиотеки — [workers] (обёртка над пулом игры или null — один
 * поток; результат побитово тот же).
 *
 * Чего здесь пока нет: события библиотеки (разрывы, гибель клеток физикой, касания) —
 * этап B2; снимок мира в сохранении и хэше — B3.
 */
class PhysicsBridge(
    workers: PhysicsWorkers? = null,
    config: PhysicsConfig = PhysicsConfig()
) {
    val world = PhysicsWorld(config, workers = workers, scale = GAME_WORLD_SCALE).apply {
        // Разбор тел в stdout — для стенда; в игре он печатался бы на каждую подсадку.
        state.quiet = true
    }

    /** Организм игры → номер тела мира, -1 — тела нет. */
    private var bodyOfOrgan = IntArray(64) { -1 }

    // --- организмы ---------------------------------------------------------------------

    fun hasBody(organ: Int): Boolean = organ in bodyOfOrgan.indices && bodyOfOrgan[organ] != -1

    /**
     * Организм [organ] появился зиготой в слоте [zygoteSlot] в точке ([x], [y]) игры: тело
     * заводится сразу всей раскладкой, рождена только зигота. Остальные клетки рождает
     * [bornCell] по мере деления. Кости выключены: клетка становится костью мутацией —
     * [setBone] (поза кости — форма на этот момент).
     */
    fun spawnOrganism(organ: Int, layout: BakedLayout, zygoteSlot: Int, x: Float, y: Float) {
        require(!hasBody(organ)) { "у организма $organ уже есть тело" }
        val definition = bodyDefinitionOf(layout, zygoteSlot, x, y)
        val born = BooleanArray(definition.count)
        born[zygoteSlot] = true
        registerBody(organ, world.addBody(definition, born, boneOn = BooleanArray(definition.count)))
    }

    /** Готовое тело макета: все частицы и кости активны сразу в позе покоя. */
    fun spawnPlacedBody(organ: Int, layout: BakedLayout, firstX: Float, firstY: Float) {
        require(!hasBody(organ)) { "у организма $organ уже есть тело" }
        registerBody(organ, world.addBody(bodyDefinitionOf(layout, 0, firstX, firstY)))
    }

    private fun registerBody(organ: Int, body: Int) {
        if (organ >= bodyOfOrgan.size) {
            val old = bodyOfOrgan.size
            bodyOfOrgan = bodyOfOrgan.copyOf(maxOf(organ + 1, old * 2))
            bodyOfOrgan.fill(-1, old, bodyOfOrgan.size)
        }
        bodyOfOrgan[organ] = body
    }

    /** Частица клетки слота [slot] организма [organ] — заново на каждый запрос (см. АДРЕСАЦИЯ). */
    fun particleOf(organ: Int, slot: Int): Int = world.bodyFirst(bodyOfOrgan[organ]) + slot

    /**
     * Клетка слота [slot] родилась делением: встаёт по позе покоя рождённых соседей
     * (поворот и масштаб, PhysicsWorld.birthPlacement), её связи дорастают.
     *
     * Умершая клетка в библиотеке заново не рождается (activateCell требует нерождённую):
     * повторный рост в том же слоте — TODO к B2.
     */
    fun bornCell(organ: Int, slot: Int) = world.activate(particleOf(organ, slot))

    /** Клетка стала костью (мутация, деление с типом «кость»). */
    fun setBone(particle: Int) = world.setBone(particle)

    /** Клетку убила игра (голод, поедание, DELETE_CELL). */
    fun killCell(organ: Int, slot: Int) = world.kill(particleOf(organ, slot))

    /** Организм умер целиком: тело гаснет на месте, мир уплотнится сам (COMPACT_DEAD_SHARE). */
    fun removeOrganism(organ: Int) {
        world.removeBody(bodyOfOrgan[organ])
        bodyOfOrgan[organ] = -1
    }

    // --- субстанции --------------------------------------------------------------------

    /**
     * Субстанция в точке ([x], [y]) игры радиусом [radius] со скоростью игры ([vx], [vy]) и
     * множителем сопротивления среды [drag] (1 — среда мира, см. [dragScaleOf]). Возвращает
     * номер свободной частицы.
     */
    fun addSubstance(x: Float, y: Float, radius: Float, vx: Float = 0f, vy: Float = 0f, drag: Double = 1.0): Int =
        world.addFree(
            x * PHYSICS_PER_GAME, y * PHYSICS_PER_GAME, radius * PHYSICS_PER_GAME,
            vx * PHYSICS_VELOCITY_PER_GAME, vy * PHYSICS_VELOCITY_PER_GAME,
            drag = drag
        )

    fun removeSubstance(id: Int) = world.removeFree(id)

    /** Неподвижная бессмертная частица стенки в точке ([x], [y]) игры радиусом [radius]; номер — как у субстанции. */
    fun addWall(x: Float, y: Float, radius: Float): Int =
        world.addFixed(x * PHYSICS_PER_GAME, y * PHYSICS_PER_GAME, radius * PHYSICS_PER_GAME)

    fun substanceParticle(id: Int): Int = world.freeParticle(id)

    // --- входы тика --------------------------------------------------------------------

    /** Мышца клетки (миоцит, degreeOfShortening): доля длины покоя её связей, 0.5–1.5. */
    fun setMuscle(organ: Int, slot: Int, scale: Float) = world.setCellScale(particleOf(organ, slot), scale)

    /**
     * Скорость частицы [particle] в единицах игры (жгутик, плавник — скорость по углу клетки).
     * Организм будится: спящий обнулил бы скорость, назначенную мимо решателя.
     */
    fun setVelocity(particle: Int, vx: Float, vy: Float) {
        world.setVelocity(particle, vx * PHYSICS_VELOCITY_PER_GAME, vy * PHYSICS_VELOCITY_PER_GAME)
        world.wake(particle)
    }

    /** Множитель сопротивления среды частицы: 1 — среда мира. */
    fun setDrag(particle: Int, scale: Double) = world.setDrag(particle, scale)

    /** Рамка мира — сетка игры [width] × [height] клеток. */
    fun setBounds(width: Float, height: Float) =
        world.setBounds(0.0, 0.0, width * PHYSICS_PER_GAME, height * PHYSICS_PER_GAME)

    /** Захват мышью: частица тянется к точке игры, пока не отпущена. */
    fun grab(particle: Int, x: Float, y: Float) {
        world.dragTo(x * PHYSICS_PER_GAME, y * PHYSICS_PER_GAME)
        world.grab(particle)
    }

    fun dragTo(x: Float, y: Float) = world.dragTo(x * PHYSICS_PER_GAME, y * PHYSICS_PER_GAME)

    fun release() = world.release()

    /** Один тик игры — один шаг мира (DT, внутри подшаги). */
    fun step() = world.step()

    // --- снимок (сохранение мира, этап B3) ----------------------------------------------

    /**
     * Снимок мира и соответствия «организм → тело». Мир при записи уплотняется
     * (PhysicsWorld.write): номера частиц сдвигаются, номера тел и свободных частиц — нет.
     */
    fun write(out: java.io.DataOutput) {
        world.write(out)
        out.writeInt(bodyOfOrgan.size)
        for (body in bodyOfOrgan) out.writeInt(body)
    }

    /** Прочитать снимок [write] в этот мост — новый, с тем же материалом. */
    fun read(input: java.io.DataInput) {
        world.read(input)
        bodyOfOrgan = IntArray(input.readInt()) { input.readInt() }
    }

    // --- чтение в единицах игры --------------------------------------------------------

    fun x(particle: Int): Float = (world.x[particle] * GAME_PER_PHYSICS).toFloat()
    fun y(particle: Int): Float = (world.y[particle] * GAME_PER_PHYSICS).toFloat()
    fun vx(particle: Int): Float = (world.vx[particle] / PHYSICS_VELOCITY_PER_GAME).toFloat()
    fun vy(particle: Int): Float = (world.vy[particle] / PHYSICS_VELOCITY_PER_GAME).toFloat()

    companion object {
        /**
         * Сопротивление игры (доля скорости за тик, клетка — 0.003) → множитель изотропного
         * сопротивления библиотеки (1 — среда мира: MEDIUM_DRAG · DT = 0.00307 за шаг).
         */
        fun dragScaleOf(dragCoefficient: Float): Double = dragCoefficient / (MEDIUM_DRAG * DT)
    }
}
