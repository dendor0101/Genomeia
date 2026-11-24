package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.killEdgeNow
import java.util.Random
import kotlin.math.sqrt

/**
 * Переключатели решателя на весь прогон: -Pconfig=RB_SLEEP=0,RB_AREAS=0 (свойство stands.config).
 * Так стенды проверяют сами себя: выключили стадию решателя — сцены обязаны покраснеть, а
 * если остались зелёными, значит, эту стадию ни одно правило не сторожит. Теми же ключами
 * пробуют правку физики, не трогая код. По умолчанию пусто: мир как в игре.
 */
val STAND_CONFIG: Map<String, String> = System.getProperty("stands.config").orEmpty().split(',')
    .mapNotNull { val kv = it.split('='); if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1].trim() else null }.toMap()

/**
 * Ключ опытов MX_FIBER (находки 25, 29): MX_FIBER=0 — стенды не задают волокон (мышцы сокращаются по площади),
 * MX_FIBER=k — показатель утолщения k. Сама физика ключа не читает: её настройка — FIBER_THICKEN
 * (config/FiberParameters.kt).
 */
val STAND_FIBERS: Boolean = STAND_CONFIG["MX_FIBER"]?.toDoubleOrNull() != 0.0

/**
 * Система отсчёта всего прогона: -Pframe=shift — сцены стоят в (1000, 700) клетках от начала координат, как в дальнем углу
 * большой карты (Frame.NAMED). Опыт 21.11 «float вместо double»: шаг float в координатах там в 2^9 раз крупнее, чем у
 * начала. По умолчанию — сцена как написана.
 */
val STAND_FRAME: Frame = System.getProperty("stands.frame")?.takeIf { it.isNotBlank() }?.let { id ->
    // shift:X,Y — свой сдвиг в клетках: в каком удалении от начала координат хватает точности float (находка 35).
    if (id.startsWith("shift:")) {
        val (sx, sy) = id.removePrefix("shift:").split(',').map { it.trim().toDouble() }
        Frame("shift", "сдвиг на ($sx, $sy) клеток", shiftX = sx, shiftY = sy)
    } else Frame.byId(id) ?: error("нет системы отсчёта $id: ${Frame.NAMED.joinToString { it.id }}")
} ?: Frame.SCENE

/**
 * Настройки мира стендов: закон волокна — тот же, что у игры (по умолчанию, находка 34); MX_FIBER=k — показатель
 * утолщения k для опыта; поверх — переключатели прогона (STAND_CONFIG).
 */
val STAND_WORLD_CONFIG: Map<String, String> =
    (STAND_CONFIG["MX_FIBER"]?.takeIf { STAND_FIBERS }?.let { mapOf("FIBER_THICKEN" to it) } ?: emptyMap()) + STAND_CONFIG

/**
 * Тело сцены: фигура, её описание в мире физики и номер тела. Частица клетки фигуры — [particle]:
 * обычно first + клетка, а при перенумерации (Frame.reverse) клетка k фигуры лежит в описании тела
 * под номером [slot][k]. [def] — описание, каким его получил мир (уже перенумерованное): его номера
 * связей и костей складываются с first напрямую.
 */
class StandBody(private val world: PhysicsWorld, val index: Int, val bodyId: Int, val shape: ShapeSpec, val def: BodyDefinition,
                private val slot: IntArray? = null) {
    val first: Int get() = world.bodyFirst(bodyId)
    val count: Int get() = shape.count
    fun particle(k: Int): Int = if (slot == null) first + k else first + slot[k]
    /** План роста или null у тела, поставленного взрослым. */
    var growth: Growth? = null
    /** Включены ли кости тела. Ложь — у тела, поставленного с выключенными костями, пока сценарий не превратил ткань в кость. */
    var bonesLive = true
    /** Тик, после которого кость, включённая сценарием, застыла целиком; −1 — такого не было. */
    var ossifiedAt = -1
    /** Последняя команда каждой мышечной группы: 1 — покой, 0.5 — сокращение, 1.5 — расслабление. */
    val command = FloatArray(shape.muscleGroups) { 1f }
}

/**
 * План роста тела: клетки рождаются в порядке [order], начиная с тика [start], раз в [period]
 * тиков по [batch] штук; batch = 0 — целым слоем обхода сразу (так делит клетки стадия генома).
 */
class Growth(val order: GrowthOrder, val start: Int, val period: Int, val batch: Int) {
    var next = 1
    /** Клетки, которым негде родиться: все рождённые соседи погибли. */
    var skipped = 0
    /**
     * Стадия генома, до которой дошёл рост тела генома (GrowthOrder.boneStage задан): раз в
     * [period] тиков наступает следующая, рождаются все её клетки и включаются её кости. У
     * обхода в ширину не используется.
     */
    var stage = 0
    private val lastStage: Int = if (order.boneStage != null) maxOf(order.depth.max(), order.boneStage.max()) else 0
    val done: Boolean get() = next >= order.order.size && stage >= lastStage
}

/** Рельеф сцены: клетки карты с шагом TERRAIN_STEP, как у игры. В мир физики идёт только кромка — сплошным ковром частиц (StandUnits.kt). */
class StandTerrain(val originX: Double, val originY: Double, val cols: Int, val rows: Int) {
    val solid = BooleanArray(cols * rows)
    fun isSolid(ix: Int, iy: Int): Boolean = ix in 0 until cols && iy in 0 until rows && solid[iy * cols + ix]
    /** Толща: клетка и все восемь соседей — рельеф. Снаружи до неё не добраться, не пройдя кромку. */
    fun isBulk(ix: Int, iy: Int): Boolean {
        for (dy in -1..1) for (dx in -1..1) if (!isSolid(ix + dx, iy + dy)) return false
        return true
    }
    fun cellOfX(x: Double): Int = Math.floor((x - originX) / TERRAIN_STEP + 0.5).toInt()
    fun cellOfY(y: Double): Int = Math.floor((y - originY) / TERRAIN_STEP + 0.5).toInt()
    fun centerX(ix: Int): Double = originX + ix * TERRAIN_STEP
    fun centerY(iy: Int): Double = originY + iy * TERRAIN_STEP
}

/**
 * Условие осмысленности сцены: то, что в ней обязано случиться, чтобы она хоть что-то проверила
 * (тела коснулись, тело дошло до стены, мышцы согнули тело), либо то, чего по постановке быть не
 * должно (тела «рядом, не касаясь» всё-таки коснулись). [tick] — когда условие сработало; −1 — ни разу.
 */
class Premise(val text: String, val forbidden: Boolean, val check: () -> Boolean) {
    var tick = -1
    val happened: Boolean get() = tick >= 0
}

/** Ожидание сценария: наименьшее или наибольшее значение пробы за тики [from]..[to] против [bound]. */
class Expectation(val text: String, val probe: String, val from: Int, val to: Int, val useMin: Boolean, val atMost: Boolean, val bound: Double)

/**
 * МИР СТЕНДА — МИР ФИЗИКИ, СОБРАННЫЙ ТАК ЖЕ, КАК ЕГО СОБИРАЕТ ИГРА.
 *
 * Тот же вход в библиотеку (PhysicsWorld), те же постоянные мира, параметры по умолчанию и
 * тот же порядок вызовов, что у моста игры (PhysicsBridge и WorldPhysics в :core): тело
 * заводится описанием с маской рождённых клеток, клетка рождается делением соседки
 * (activateFromParent), кость включается вызовом setBone каждый тик, пока не включится, мышца —
 * команда клетки 0.5..1.5, рельеф и стенка — неподвижные частицы одной группы касаний, мышь —
 * та же формула скорости, что в UserCommandManager. Стенд проверяет физику, какой её видит
 * игрок, а не какой её удобно собрать для проверки.
 *
 * Координаты и размеры — в единицах игры, скорости — в связях за тик (StandUnits.kt).
 */
class StandWorld(workers: PhysicsWorkers? = null, forceParallel: Boolean = false,
                 /** Система отсчёта сцены в мире физики — проверка симметрии. По умолчанию сцена стоит как написана. */
                 val frame: Frame = STAND_FRAME,
                 /**
                  * Число подшагов ЭТАЛОННОГО решателя; 0 — мир шагает быстрым, как в игре. С эталоном сцена
                  * собирается и ведётся теми же вызовами, меняется только шаг мира (PhysicsWorld.stepReference),
                  * а сон выключен: эталон его не знает. См. Reference.kt.
                  */
                 val reference: Int = 0) {
    val world = PhysicsWorld(
        PhysicsConfig(STAND_WORLD_CONFIG + if (forceParallel) mapOf("RB_PARALLEL_MIN" to "0") else emptyMap()),
        workers = workers, scale = STAND_WORLD_SCALE,
    ).apply { state.quiet = true }

    init {
        if (reference > 0) world.sleepOn = false
        // Перенумерация: пустой блок слотов свободных частиц заводится ДО сцены — номера всех её частиц
        // сдвигаются, а свободные частицы сцены лягут в начало мира, а не после тел.
        if (frame.pad) {
            val k = PHYSICS_PER_GAME
            world.removeFree(world.addFree((frame.x(0.0, 0.0) * k).toFloat(), (frame.y(0.0, 0.0) * k).toFloat(), (TERRAIN_RADIUS * k).toFloat()))
        }
    }

    /** Описание тела в порядке клеток этой системы отсчёта и перестановка клеток (null — подряд). */
    private fun ordered(def: BodyDefinition): Pair<BodyDefinition, IntArray?> {
        if (!frame.reverse) return def to null
        val slot = IntArray(def.count) { def.count - 1 - it }
        return def.renumbered(slot) to slot
    }

    val bodies = ArrayList<StandBody>()
    /** Подвижные свободные частицы — номерами библиотеки (PhysicsWorld.addFree). */
    val balls = ArrayList<Int>()
    /** Неподвижные частицы рельефа и стенки — номерами библиотеки. */
    val fixed = ArrayList<Int>()
    val terrains = ArrayList<StandTerrain>()

    var tick = 0
        private set

    /** Сколько кинетической энергии сценарий внёс толчками и новыми частицами — запас закрытой сцены. */
    var injected = 0.0
        private set

    /** Сценарий сам внёс кинетическую энергию [e] (толчок куску тела): она идёт в запас закрытой сцены. */
    fun addEnergy(e: Double) { if (e > 0.0) injected += e }

    /** Выключить сон на всю сцену: гидродинамика и замеры смотрят тело, которое не засыпает. */
    fun noSleep() { world.sleepOn = false }

    /**
     * Пустота: выключить сопротивление среды — и тягу по контуру, и изотропную часть (те же
     * выключатели, которыми решатель разбирают сами его авторы). Без среды на тело не действует
     * ничего внешнего, и импульс, момент импульса и энергия обязаны сохраняться: так сцены
     * «свободный полёт» проверяют сам решатель, а не то, как он тормозит о воду.
     */
    fun noMedium() { world.state.dbgDragOff = true; world.state.dbgIsoDragOff = true; mediumOff = true }
    var mediumOff = false
        private set

    private val script = ArrayList<(Int) -> Unit>()

    /** Последний тик, на котором сценарий ещё что-то делает: позже него мир идёт сам. */
    var scriptEnd = 0
        private set

    /** Сцене ещё есть что делать помимо сценария по тикам: тело растёт или его держит мышь. */
    val busy: Boolean get() = grabbedBody != null || bodies.any { it.growth?.done == false }
    val probes = LinkedHashMap<String, () -> Double>()
    val expectations = ArrayList<Expectation>()

    /** Рамка обзора в единицах игры: minX, minY, maxX, maxY. Пусто — по содержимому сцены. */
    var view: DoubleArray? = null

    /** Рождения последнего шага, измеренные в миг рождения — для правила «клетки рождаются на своём месте». */
    val births = BirthLog()

    // --- тела ---

    /**
     * Взрослое тело: фигура целиком в позе покоя, кости включены в задуманной форме. [bonesOn] =
     * false — кости описаны, но пока выключены: клетки стоят мягкой тканью, пока сцена не превратит
     * их в кость ([ossify]) — как клетка игры, ставшая костью мутацией.
     */
    fun body(shape: ShapeSpec, x: Double, y: Double, angle: Double = 0.0, bonesOn: Boolean = true): StandBody {
        // Фигура мельче одной клетки решётки выходит пустой («квадрат 2» — одна клетка, и ту снимает обрезка висячих).
        require(shape.count > 0) { "фигура ${shape.id} пуста: в ней нет ни одной клетки" }
        val (def, slot) = ordered(shape.definition(x + frame.jiggle(bodies.size), y + frame.jiggle(bodies.size + 2), angle, frame))
        val id = if (bonesOn) world.addBody(def, BooleanArray(def.count) { true })
        else world.addBody(def, BooleanArray(def.count) { true }, BooleanArray(def.count))
        return StandBody(world, bodies.size, id, shape, def, slot).also { it.bonesLive = bonesOn; bodies.add(it); assignMuscleFibers(it) }
    }

    /**
     * Волокно мышц тела (PhysicsWorld.setCellFiber, config/FiberParameters.kt): у каждого связного куска мышечных клеток —
     * его длинная ось в позе покоя, главная ось разброса (автор 20.11: «в стендах направление делай по принципу в какую
     * сторону кластер мышц длиннее»; в игре направление даст ген клетки). Кусок — по связям фигуры, номера групп мышц не
     * в счёт: группа — команда сценария, а не ткань (у червя «бегущей волны» четверть тела поперёк бывает шире, чем
     * вдоль, а мышца целиком — вдоль тела). Кусок из одной клетки — без волокна: сокращается по площади.
     */
    private fun assignMuscleFibers(b: StandBody) {
        if (!STAND_FIBERS) return
        val sh = b.shape
        val count = sh.count
        val seen = BooleanArray(count)
        val stack = IntArray(count); val members = IntArray(count)
        val first = b.first
        for (k0 in 0 until count) {
            if (seen[k0] || sh.muscle[k0] < 0) continue
            var top = 0; var size = 0
            stack[top++] = k0; seen[k0] = true
            while (top > 0) {
                val k = stack[--top]; members[size++] = k
                for (q in sh.adjStart[k] until sh.adjStart[k + 1]) {
                    val o = sh.adj[q]
                    if (!seen[o] && sh.muscle[o] >= 0) { seen[o] = true; stack[top++] = o }
                }
            }
            if (size < 2) continue
            // Поза покоя — как её получил мир (описание тела после поворота и рамки сцены): по ней связь считает угол к волокну.
            var mx = 0.0; var my = 0.0
            for (q in 0 until size) { val d = b.particle(members[q]) - first; mx += b.def.x[d]; my += b.def.y[d] }
            mx /= size; my /= size
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (q in 0 until size) {
                val d = b.particle(members[q]) - first
                val dx = b.def.x[d] - mx; val dy = b.def.y[d] - my
                sxx += dx * dx; syy += dy * dy; sxy += dx * dy
            }
            val th = 0.5 * StrictMath.atan2(2.0 * sxy, sxx - syy)
            val fx = StrictMath.cos(th).toFloat(); val fy = StrictMath.sin(th).toFloat()
            for (q in 0 until size) world.setCellFiber(b.particle(members[q]), fx, fy)
        }
    }

    private val ossifying = ArrayList<StandBody>()

    /**
     * Ткань становится костью: с этого тика костные клетки фигуры включаются в кость — тем же
     * вызовом, что у игры (setBone каждый тик, пока клетка не включится).
     */
    fun ossify(b: StandBody) { if (b !in ossifying) ossifying.add(b); world.wake(b.first) }

    private fun advanceOssify() {
        if (ossifying.isEmpty()) return
        val st = world.state
        for (b in ossifying) for (k in 0 until b.count) {
            val p = b.particle(k)
            if (b.shape.bone[k] && !st.cellDead[p] && !st.cellUnborn[p] && !world.isBone(p)) world.setBone(p)
        }
        // Кость включилась целиком: после шага этого тика монитор берёт её позу за образец.
        val it = ossifying.iterator()
        while (it.hasNext()) { val b = it.next(); if (ossified(b)) { b.bonesLive = true; b.ossifiedAt = tick + 1; it.remove() } }
    }

    /** Все ли живые костные клетки тела уже включены в кость. */
    fun ossified(b: StandBody): Boolean {
        val st = world.state
        for (k in 0 until b.count) { val p = b.particle(k); if (b.shape.bone[k] && !st.cellDead[p] && !world.isBone(p)) return false }
        return true
    }

    /**
     * Импульс, момент импульса относительно начала координат и масса всех живых подвижных частиц
     * мира: px, py, L, m — в единицах игры и связях за тик (масса клетки радиуса 0.5 — единица).
     */
    fun momentum(): DoubleArray {
        val st = world.state
        var mx = 0.0; var my = 0.0; var l = 0.0; var mass = 0.0
        for (i in 0 until st.n) {
            if (st.cellDead[i] || st.restInvMass[i] == 0.0f) continue
            val m = 1.0 / st.restInvMass[i]
            val vx = toLinksPerTick(sceneVx(i)); val vy = toLinksPerTick(sceneVy(i))
            mx += m * vx; my += m * vy; l += m * (gameX(i) * vy - gameY(i) * vx); mass += m
        }
        return doubleArrayOf(mx, my, l, mass)
    }

    /**
     * Растущее тело, как организм игры: вся раскладка заведена сразу, рождена одна клетка
     * [seed] (зигота), кости выключены. Остальные клетки рождаются по плану роста.
     */
    fun growing(shape: ShapeSpec, x: Double, y: Double, angle: Double = 0.0, seed: Int,
                start: Int = 0, period: Int = 2, batch: Int = 1): StandBody {
        // Зигота стоит в точке подсадки: тело сдвинуто так, чтобы её клетка легла в (x, y).
        val cs = kotlin.math.cos(angle); val sn = kotlin.math.sin(angle)
        val sx = shape.x[seed] * cs - shape.y[seed] * sn
        val sy = shape.x[seed] * sn + shape.y[seed] * cs
        val (def, slot) = ordered(shape.definition(x - sx + frame.jiggle(bodies.size), y - sy + frame.jiggle(bodies.size + 2), angle, frame))
        val born = BooleanArray(def.count).also { it[slot?.get(seed) ?: seed] = true }
        val id = world.addBody(def, born, BooleanArray(def.count))
        val body = StandBody(world, bodies.size, id, shape, def, slot)
        body.growth = Growth(shape.growthFrom(seed), start, period, batch)
        bodies.add(body)
        assignMuscleFibers(body)
        return body
    }

    private fun advanceGrowth() {
        val st = world.state
        for (b in bodies) {
            val g = b.growth ?: continue
            val boneStage = g.order.boneStage
            // Кость — как WorldPhysics.syncSlot: каждый тик, пока костная клетка не включена. У тела
            // генома — не раньше стадии, на которой клетка становится костью.
            if (b.shape.boneCount > 0) for (k in 0 until b.count) {
                val p = b.particle(k)
                if (!b.shape.bone[k] || st.cellUnborn[p] || st.cellDead[p] || world.isBone(p)) continue
                if (boneStage == null || boneStage[k] in 0..g.stage) world.setBone(p)
            }
            if (g.done || tick < g.start || (tick - g.start) % g.period != 0) continue
            if (boneStage != null) {
                // Стадии генома: всё, что рождается на очередной стадии, — разом (как в игре за один тик).
                g.stage++
                while (g.next < g.order.order.size && g.order.depth[g.order.order[g.next]] <= g.stage) {
                    birth(b, g.order.order[g.next], g); g.next++
                }
                continue
            }
            var left = if (g.batch > 0) g.batch else Int.MAX_VALUE
            val layer = g.order.depth[g.order.order[g.next]]
            while (!g.done && left > 0) {
                val k = g.order.order[g.next]
                if (g.batch == 0 && g.order.depth[k] != layer) break
                g.next++; left--
                birth(b, k, g)
            }
        }
    }

    private fun birth(b: StandBody, k: Int, g: Growth) {
        val st = world.state
        val p = b.particle(k)
        if (!st.cellUnborn[p]) return
        val parent = g.order.parent[k]
        if (parent >= 0 && !st.cellUnborn[b.particle(parent)] && !st.cellDead[b.particle(parent)]) {
            world.activateFromParent(p, b.particle(parent))
            births.note(this, b, k)
            return
        }
        // Родитель погиб: по позе живых соседей, как делает игра; без живых соседей клетке негде родиться.
        val s = b.shape
        val hasNeighbor = (s.adjStart[k] until s.adjStart[k + 1]).any { val q = b.particle(s.adj[it]); !st.cellUnborn[q] && !st.cellDead[q] }
        if (hasNeighbor) { world.activate(p); births.note(this, b, k) } else g.skipped++
    }

    // --- свободные частицы, рельеф, рамка ---

    /**
     * Свободная частица в точке игры со скоростью в связях за тик. Возвращает номер библиотеки.
     * Радиус по умолчанию — от вариации сцены: у вариаций с радиусами частицы тоже разные.
     */
    fun ball(x0: Double, y0: Double, vx: Double = 0.0, vy: Double = 0.0, radius: Double = Variation.current.ballRadius(balls.size)): Int {
        val k = PHYSICS_PER_GAME
        val x = x0 + frame.jiggle(balls.size + 1); val y = y0 + frame.jiggle(balls.size + 4)
        val id = if (frame.still) world.addFree((x * k).toFloat(), (y * k).toFloat(), (radius * k).toFloat(), linksPerTick(vx).toFloat(), linksPerTick(vy).toFloat())
        else world.addFree((frame.x(x, y) * k).toFloat(), (frame.y(x, y) * k).toFloat(), (radius * k).toFloat(), linksPerTick(frame.dx(vx, vy)).toFloat(), linksPerTick(frame.dy(vx, vy)).toFloat())
        balls.add(id)
        val p = world.freeParticle(id)
        val inv = world.state.restInvMass[p]
        if (inv > 0.0) injected += 0.5 * (world.vx[p] * world.vx[p] + world.vy[p] * world.vy[p]) / inv
        return id
    }

    /** Неподвижная частица рельефа или стенки: бесконечная масса, общая группа касаний, спит сразу. */
    fun rock(x: Double, y: Double, radius: Double = TERRAIN_RADIUS): Int {
        val k = PHYSICS_PER_GAME
        val id = if (frame.still) world.addFixed((x * k).toFloat(), (y * k).toFloat(), (radius * k).toFloat()) else world.addFixed((frame.x(x, y) * k).toFloat(), (frame.y(x, y) * k).toFloat(), (radius * k).toFloat())
        val p = world.freeParticle(id)
        world.setContactGroup(p, TERRAIN_GROUP)
        world.sleepNow(p)
        fixed.add(id)
        return id
    }

    /**
     * Рельеф из клеток карты [cols] × [rows] с углом в ([originX], [originY]): клетка твёрдая,
     * где [solid] вернул true. В мир физики идёт только кромка (клетка, у которой хоть один из
     * восьми соседей свободен), и каждая её клетка покрыта частицами сплошь: TERRAIN_SUB × TERRAIN_SUB
     * штук с малым случайным сдвигом ([jitter]). Толща в мир физики не идёт — до неё не добраться.
     */
    fun terrain(originX: Double, originY: Double, cols: Int, rows: Int, jitter: Boolean = true, seed: Long = 3L,
                solid: (ix: Int, iy: Int) -> Boolean): StandTerrain {
        val t = StandTerrain(originX, originY, cols, rows)
        for (iy in 0 until rows) for (ix in 0 until cols) t.solid[iy * cols + ix] = solid(ix, iy)
        val rnd = Random(seed)
        val sub = TERRAIN_STEP / TERRAIN_SUB
        for (iy in 0 until rows) for (ix in 0 until cols) {
            if (!t.solid[iy * cols + ix] || t.isBulk(ix, iy)) continue
            for (sy in 0 until TERRAIN_SUB) for (sx in 0 until TERRAIN_SUB) {
                val dx = if (jitter) (rnd.nextFloat() - 0.5f) * TERRAIN_JITTER else 0.0
                val dy = if (jitter) (rnd.nextFloat() - 0.5f) * TERRAIN_JITTER else 0.0
                rock(t.centerX(ix) + (sx - (TERRAIN_SUB - 1) / 2.0) * sub + dx, t.centerY(iy) + (sy - (TERRAIN_SUB - 1) / 2.0) * sub + dy)
            }
        }
        terrains.add(t)
        return t
    }

    /** Рамка мира [width] × [height] клеток игры со стенкой из неподвижных частиц вдоль неё — как в игре. */
    fun arena(width: Int, height: Int) {
        val k = PHYSICS_PER_GAME
        if (frame.still) world.setBounds(0.0f, 0.0f, (width * k).toFloat(), (height * k).toFloat())
        else {
            // Рамка мира — прямоугольник по осям: повернуть её можно только на четверть оборота.
            if (!frame.square) throw FrameUnfit("рамку мира нельзя повернуть на угол, не кратный четверти оборота")
            val ax = frame.x(0.0, 0.0); val ay = frame.y(0.0, 0.0)
            val bx = frame.x(width.toDouble(), height.toDouble()); val by = frame.y(width.toDouble(), height.toDouble())
            world.setBounds((minOf(ax, bx) * k).toFloat(), (minOf(ay, by) * k).toFloat(), (maxOf(ax, bx) * k).toFloat(), (maxOf(ay, by) * k).toFloat())
        }
        // Та же раскладка, что WorldTerrainManager.forEachWallParticle: шаг 1/1.5 вплотную к рамке.
        for (i in 1 until width / 2 * 3) rock(i / 1.5, 0.5)
        for (i in 1 until height / 2 * 3) rock(0.5, i / 1.5)
        for (i in 1 until height / 2 * 3) rock(width - 0.5, i / 1.5)
        for (i in 1 until width / 2 * 3) rock(i / 1.5, height - 0.5)
        view = doubleArrayOf(0.0, 0.0, width.toDouble(), height.toDouble())
    }

    // --- воздействия ---

    /** Центр масс живых клеток тела в единицах игры, в координатах сцены. */
    fun centerOf(b: StandBody): DoubleArray {
        val c = worldCenterOf(b)
        if (!frame.still) { val x = c[0]; val y = c[1]; c[0] = frame.sceneX(x, y); c[1] = frame.sceneY(x, y) }
        return c
    }

    /** Центр масс живых клеток тела в единицах игры, в координатах мира физики. */
    private fun worldCenterOf(b: StandBody): DoubleArray {
        val st = world.state
        var sx = 0.0; var sy = 0.0; var m = 0.0
        val first = b.first
        for (k in 0 until b.count) {
            val p = first + k
            if (st.cellDead[p]) continue
            val w = 1.0 / st.restInvMass[p]
            sx += w * world.x[p]; sy += w * world.y[p]; m += w
        }
        if (m == 0.0) return doubleArrayOf(0.0, 0.0)
        return doubleArrayOf(sx / m / PHYSICS_PER_GAME, sy / m / PHYSICS_PER_GAME)
    }

    /** Толчок телу: скорость ([vx], [vy]) связей за тик всем клеткам и вращение [spin] радиан за тик вокруг центра масс. */
    fun kick(b: StandBody, vx: Double, vy: Double, spin: Double = 0.0) {
        val st = world.state
        val c = worldCenterOf(b)
        val cx = c[0] * PHYSICS_PER_GAME; val cy = c[1] * PHYSICS_PER_GAME
        val lx = if (frame.still) linksPerTick(vx) else linksPerTick(frame.dx(vx, vy))
        val ly = if (frame.still) linksPerTick(vy) else linksPerTick(frame.dy(vx, vy))
        val omega = frame.spin * spin / io.github.some_example_name.physics.DT
        val first = b.first
        var before = 0.0; var after = 0.0
        for (k in 0 until b.count) {
            val p = first + k
            if (st.cellDead[p]) continue
            val m = 1.0 / st.restInvMass[p]
            before += 0.5 * m * (world.vx[p] * world.vx[p] + world.vy[p] * world.vy[p])
            val nx = lx - omega * (world.y[p] - cy)
            val ny = ly + omega * (world.x[p] - cx)
            world.setVelocity(p, nx.toFloat(), ny.toFloat())
            after += 0.5 * m * (nx * nx + ny * ny)
        }
        world.wake(first)
        if (after > before) injected += after - before
    }

    /** Команда мышечной группе тела: 0.5 — сократиться вдвое, 1 — покой, 1.5 — расслабиться. */
    fun muscle(b: StandBody, group: Int, command: Float) {
        b.command[group] = command
        for (k in 0 until b.count) if (b.shape.muscle[k] == group) {
            val p = b.particle(k)
            if (!world.state.cellDead[p]) world.setCellScale(p, command)
        }
    }

    /** Команда всем мышцам тела. */
    fun muscles(b: StandBody, command: Float) { for (g in 0 until b.shape.muscleGroups) muscle(b, g, command) }

    // --- опора о дно, зажимы, разрезы ---

    /**
     * Хватка клетки [cell] тела о дно, 0..1 — как присоска игры (SuctionCup: трение по импульсу
     * нейрона, PhysicsWorld.setGrip). 1 — клетку не сдвинуть ничем, 0 — отпущена.
     */
    fun grip(b: StandBody, cell: Int, grip: Float) {
        val p = b.particle(cell)
        if (world.state.cellDead[p]) return
        world.setGrip(p, grip)
        world.wake(p)
    }

    /** Хватка всем клеткам [cells] тела. */
    fun grip(b: StandBody, cells: IntArray, grip: Float) { for (c in cells) grip(b, c, grip) }

    /**
     * Зажим: клетки [cells] тела держатся хваткой 1 и каждый тик переставляются в свои места,
     * сдвинутые на ([dx], [dy]) единиц игры от позы, в которой их зажали. Так тело растягивают,
     * сжимают и скручивают снаружи с заданным ходом, а не с заданной силой: сколько ни
     * сопротивляйся ткань, зажим придёт, куда ведёт сценарий. Снимается [release].
     */
    inner class Clamp(val body: StandBody, val cells: IntArray) {
        private val homeX = DoubleArray(cells.size) { gameX(body.particle(cells[it])) }
        private val homeY = DoubleArray(cells.size) { gameY(body.particle(cells[it])) }
        private val cx = homeX.average(); private val cy = homeY.average()
        var held = true
            private set

        init { for (c in cells) world.setGrip(body.particle(c), 1f) }

        /** Поставить зажим: сдвиг ([dx], [dy]) и поворот на [angle] радиан вокруг своей середины. */
        fun moveTo(dx: Double, dy: Double, angle: Double = 0.0) {
            if (!held) return
            val k = PHYSICS_PER_GAME
            val cs = kotlin.math.cos(angle); val sn = kotlin.math.sin(angle)
            for (i in cells.indices) {
                val p = body.particle(cells[i])
                if (world.state.cellDead[p]) continue
                val rx = homeX[i] - cx; val ry = homeY[i] - cy
                val tx = cx + rx * cs - ry * sn + dx; val ty = cy + rx * sn + ry * cs + dy
                clampTravel = maxOf(clampTravel, Math.hypot(tx - homeX[i], ty - homeY[i]))
                if (frame.still) world.place(p, (tx * k).toFloat(), (ty * k).toFloat()) else world.place(p, (frame.x(tx, ty) * k).toFloat(), (frame.y(tx, ty) * k).toFloat())
                world.setVelocity(p, 0.0f, 0.0f)
                // Будится кусок КАЖДОЙ клетки зажима, а не только первой: тело под зажимом могло
                // порваться, и тогда клетки зажима лежат в разных кусках. 15.11 зажим двигал клетки
                // спящего куска, и правило «спящее не двигается» ловило сам стенд, а не физику.
                world.wake(p)
            }
        }

        fun release() {
            if (!held) return
            held = false
            for (c in cells) { val p = body.particle(c); if (!world.state.cellDead[p]) { world.setGrip(p, 0f); world.wake(p) } }
        }
    }

    fun clamp(b: StandBody, cells: IntArray) = Clamp(b, cells)

    /** На сколько единиц игры зажимы сцены увели свои клетки от места, где их зажали: наибольшее за сцену. */
    var clampTravel = 0.0
        private set

    /**
     * Сколько связей и клеток сцена порвала и убила сама (разрез, гибель по сценарию): монитор
     * правил эти повреждения телу в вину не ставит.
     */
    var scriptedTorn = 0
        private set
    var scriptedDead = 0
        private set

    /**
     * Убить клетки [cells] тела — тем же вызовом, каким игра убивает клетку (голод, поедание):
     * связи гаснут, тело делится на куски, у каждого куска свой запас среды.
     */
    fun killCells(b: StandBody, cells: IntArray) {
        val st = world.state; val baked = st.baked
        for (c in cells) {
            val p = b.particle(c)
            if (st.cellDead[p] || st.cellUnborn[p]) continue
            for (s in baked.vertStart[p] until baked.vertStart[p + 1]) if (!baked.edgeDead[baked.vertEdge[s]]) scriptedTorn++
            world.kill(p)
            scriptedDead++
        }
    }

    /**
     * Разрезать тело: погасить живые связи между клетками, для которых [pick] вернул true, — тем
     * же вызовом, каким решатель рвёт перетянутую связь. Клетки остаются живы. Возвращает, сколько
     * связей разрезано.
     */
    fun cut(b: StandBody, pick: (a: Int, c: Int) -> Boolean): Int {
        val st = world.state; val baked = st.baked
        val first = b.first
        var n = 0
        // Связи — фигуры, а не описания тела: [pick] выбирает по клеткам фигуры, а при перенумерации
        // номера клеток описания другие. Порядок связей у фигуры и у описания один.
        for (e in 0 until b.shape.linkCount) {
            val a = b.shape.linkA[e]; val c = b.shape.linkB[e]
            if (!pick(a, c)) continue
            val edge = baked.edgeOf(b.particle(a), b.particle(c))
            if (edge < 0 || baked.edgeDead[edge]) continue
            st.killEdgeNow(edge); n++
        }
        scriptedTorn += n
        if (n > 0) world.wake(first)
        return n
    }

    /** Сколько отдельных кусков (островов связей) сейчас у живых клеток тела. */
    fun pieces(b: StandBody): Int {
        val st = world.state
        val seen = HashSet<Int>()
        val first = b.first
        for (k in 0 until b.count) { val p = first + k; if (!st.cellDead[p] && !st.cellUnborn[p]) seen.add(st.organismOf[p]) }
        return seen.size
    }

    // --- мышь: та же формула, что UserCommandManager игры ---

    var grabbedBody: StandBody? = null
        private set
    var grabbedCell = -1
        private set
    var mouseX = 0.0
        private set
    var mouseY = 0.0
        private set

    fun grab(b: StandBody, cell: Int) {
        grabbedBody = b; grabbedCell = cell
        val p = b.particle(cell)
        mouseX = gameX(p); mouseY = gameY(p)
    }

    fun dragTo(x: Double, y: Double) { mouseX = x; mouseY = y }

    fun release() { grabbedBody = null; grabbedCell = -1 }

    private fun applyMouse() {
        val b = grabbedBody ?: return
        val p = b.particle(grabbedCell)
        val st = world.state
        if (st.cellDead[p]) { release(); return }
        val k = PHYSICS_PER_GAME
        // Курсор — в координатах мира: игра считает тягу в своих мировых координатах, и вдали от
        // начала координат её float-формула теряет точность ровно так же (система отсчёта «сдвиг»).
        val mouseX = if (frame.still) mouseX else frame.x(this.mouseX, this.mouseY)
        val mouseY = if (frame.still) mouseY else frame.y(this.mouseX, this.mouseY)
        if (st.bonesRigid && world.isBone(p)) {
            // Кость тянется целиком, раз в тик (WorldPhysics.pullBone).
            world.pullToward(p, (mouseX * k).toFloat(), (mouseY * k).toFloat())
            return
        }
        // UserCommandManager.dragParticle: половина прежней скорости плюс доля расстояния до курсора,
        // во float и в единицах скорости игры — как считает игра.
        val vg = PHYSICS_VELOCITY_PER_GAME
        val vx = (world.vx[p] / vg).toFloat() * 0.5f + (mouseX - world.x[p] / k).toFloat() * 0.02f
        val vy = (world.vy[p] / vg).toFloat() * 0.5f + (mouseY - world.y[p] / k).toFloat() * 0.02f
        world.setVelocity(p, (vx * vg).toFloat(), (vy * vg).toFloat())
        world.wake(p)
    }

    // --- сценарий по тикам ---

    /** Действие ровно на тике [tick] (до шага мира). */
    fun at(tick: Int, action: () -> Unit) { if (tick > scriptEnd) scriptEnd = tick; script.add { t -> if (t == tick) action() } }

    /** Действие на каждом тике из [from]..[to]. */
    fun during(from: Int, to: Int, action: (tick: Int) -> Unit) { if (to > scriptEnd) scriptEnd = to; script.add { t -> if (t in from..to) action(t) } }

    /** Величина, за которой следит сценарий: считается каждый тик после шага мира. */
    fun probe(name: String, value: () -> Double) { probes[name] = value }

    /** Наименьшее значение пробы за тики [from]..[to] не больше [bound]. */
    fun expectMinAtMost(text: String, probe: String, from: Int, to: Int, bound: Double) { expectations.add(Expectation(text, probe, from, to, useMin = true, atMost = true, bound = bound)) }
    /** Наибольшее значение пробы за тики [from]..[to] не меньше [bound]. */
    fun expectMaxAtLeast(text: String, probe: String, from: Int, to: Int, bound: Double) { expectations.add(Expectation(text, probe, from, to, useMin = false, atMost = false, bound = bound)) }
    /** Наибольшее значение пробы за тики [from]..[to] не больше [bound]. */
    fun expectMaxAtMost(text: String, probe: String, from: Int, to: Int, bound: Double) { expectations.add(Expectation(text, probe, from, to, useMin = false, atMost = true, bound = bound)) }
    /** Наименьшее значение пробы за тики [from]..[to] не меньше [bound]. */
    fun expectMinAtLeast(text: String, probe: String, from: Int, to: Int, bound: Double) { expectations.add(Expectation(text, probe, from, to, useMin = true, atMost = false, bound = bound)) }

    // --- что в сцене обязано случиться ---
    //
    // Правила монитора отвечают на вопрос «не случилось ли плохого». Но сцена, в которой не случилось
    // вообще ничего (тела не долетели друг до друга, росток не дорос до стены), на этот вопрос отвечает
    // «нет» и светится зелёным, ничего не проверив. Поэтому каждая сцена объявляет, что в ней обязано
    // произойти, и монитор судит это отдельным правилом «сцена не вхолостую». Сцена, где по замыслу не
    // происходит ничего (покой), говорит об этом прямо — [idle]. Сцена без объявления не проходит.

    val premises = ArrayList<Premise>()
    /** Сцена объявила, что в ней по замыслу ничего не происходит, и почему. */
    var idleReason: String? = null
        private set
    internal val touch = TouchProbe(this)

    /** В сцене обязано случиться [check] — хотя бы на одном тике. Иначе сцена прошла вхолостую. */
    fun must(text: String, check: () -> Boolean) { premises.add(Premise(text, false, check)) }
    /** В сцене не должно случиться [check] ни на одном тике: это условие постановки, а не свойство физики. */
    fun mustNever(text: String, check: () -> Boolean) { premises.add(Premise(text, true, check)) }
    /** В сцене по замыслу ничего не происходит (покой): проверять нечего, кроме тишины. */
    fun idle(reason: String) { idleReason = reason }

    /** Тела [a] и [b] обязаны коснуться друг друга (круги касания их клеток перекрылись). */
    fun mustTouch(a: StandBody, b: StandBody, text: String = "тела коснулись друг друга", slack: Double = TOUCH_SLACK) = must(text) { touch.bodies(a, b, slack) }
    /** Тело обязано коснуться рельефа или стенки. */
    fun mustTouchTerrain(b: StandBody, text: String = "тело коснулось рельефа", slack: Double = TOUCH_SLACK) = must(text) { touch.terrain(b, slack) }
    /** До тела обязана долететь хотя бы одна свободная частица. */
    fun mustTouchBalls(b: StandBody, text: String = "частица коснулась тела", slack: Double = TOUCH_SLACK) = must(text) { touch.balls(b, slack) }
    /** Тела [a] и [b] по постановке не касаются («рядом», «пролетает мимо»). */
    fun mustNotTouch(a: StandBody, b: StandBody, text: String = "тела коснулись, а по постановке не должны") = mustNever(text) { touch.bodies(a, b, extra = 0.0, moving = false) }
    /** Тело по постановке не касается рельефа и стенки («покой рядом со стеной»). */
    fun mustNotTouchTerrain(b: StandBody, text: String = "тело коснулось рельефа, а по постановке не должно") = mustNever(text) { touch.terrain(b, extra = 0.0, moving = false) }
    /**
     * Тело обязано повернуться хотя бы на [radians]: по направлению от центра масс к самой дальней
     * от него клетке. Вращению на месте ни сдвиг центра, ни изменение формы не свидетели.
     */
    fun mustTurn(b: StandBody, radians: Double, text: String = "тело повернулось") {
        val c0 = centerOf(b)
        var far = 0; var best = -1.0
        for (k in 0 until b.count) {
            val d = Math.hypot(gameX(b.particle(k)) - c0[0], gameY(b.particle(k)) - c0[1])
            if (d > best) { best = d; far = k }
        }
        val ux = (gameX(b.particle(far)) - c0[0]) / best; val uy = (gameY(b.particle(far)) - c0[1]) / best
        val limit = Math.cos(radians)
        must(text) {
            val c = centerOf(b)
            val vx = gameX(b.particle(far)) - c[0]; val vy = gameY(b.particle(far)) - c[1]
            val len = Math.hypot(vx, vy)
            len > 1e-9 && (ux * vx + uy * vy) / len <= limit
        }
    }
    /** Не меньше доли [share] тел сцены обязаны с кем-нибудь столкнуться: с другим телом, рельефом или стенкой. */
    fun mustCollide(share: Double = 0.5, text: String = "столкнулось не меньше ${Math.round(share * 100)}% тел") = must(text) { touch.metShare() >= share }
    /** Тело обязано сдвинуться от старта хотя бы на [links] связей (по центру масс живых клеток). */
    fun mustMove(b: StandBody, links: Double, text: String = "тело сдвинулось с места") {
        val start = centerOf(b)
        must(text) { val c = centerOf(b); Math.hypot(c[0] - start[0], c[1] - start[1]) >= links * GAME_LINK }
    }
    /**
     * Форма тела обязана измениться хотя бы на долю [share] (мышцы, зажимы, удар): наибольшее
     * отклонение длины связи от позы покоя описания. Мышца, слишком слабая, чтобы согнуть своё
     * тело, ничего не проверяет — и сцена честно говорит «вхолостую».
     */
    fun mustDeform(b: StandBody, share: Double, text: String = "форма тела изменилась хотя бы на ${Math.round(share * 100)}%") =
        must(text) { linkStrain(b) >= share }

    /**
     * УСЛОВИЕ ПО УМОЛЧАНИЮ — для сцены, которая своего не объявила. Выводится из состава сцены:
     * всё, что поставлено, чтобы столкнуться, обязано столкнуться, а одинокое тело — сдвинуться,
     * изменить форму или вырасти. Вызывается после сборки сцены (StandRun). Сцена покоя и сцена
     * «рядом, не касаясь» под это не подходят и обязаны сказать о себе сами ([idle], [mustNotTouch]) —
     * иначе они честно покраснеют как пустые.
     */
    fun defaultPremise() {
        if (premises.isNotEmpty() || idleReason != null) return
        // Сцена без единого воздействия — ни толчка, ни действия по тикам, ни роста — это покой по
        // определению: в ней проверяется, что само собой ничего не начинается.
        if (scriptEnd == 0 && injected == 0.0 && bodies.none { it.growth != null }) {
            idle("в сцене нет ни одного воздействия: проверяется покой")
            return
        }
        val rocks = fixed.isNotEmpty()
        when {
            bodies.size == 2 && balls.isEmpty() -> mustTouch(bodies[0], bodies[1], "по умолчанию: два тела сцены коснулись друг друга")
            bodies.size == 1 && balls.isEmpty() && rocks -> mustTouchTerrain(bodies[0], "по умолчанию: тело коснулось рельефа или стенки")
            bodies.size == 1 && balls.isNotEmpty() -> mustTouchBalls(bodies[0], "по умолчанию: частица коснулась тела")
            bodies.size >= 3 -> mustCollide(0.5, "по умолчанию: не меньше половины тел с чем-нибудь столкнулись")
            bodies.size == 2 -> mustCollide(0.5, "по умолчанию: хотя бы одно из двух тел с чем-нибудь столкнулось")
            bodies.size == 1 -> {
                val b = bodies[0]
                val start = centerOf(b)
                must("по умолчанию: тело сдвинулось на полсвязи, изменило форму на 2%, выросло, было разрезано или сдвинуто зажимом") {
                    val c = centerOf(b)
                    (b.growth?.done == true) || scriptedTorn > 0 || scriptedDead > 0 || clampTravel >= 0.25 * GAME_LINK ||
                        Math.hypot(c[0] - start[0], c[1] - start[1]) >= 0.5 * GAME_LINK || linkStrain(b) >= 0.02
                }
            }
            // Тел нет: сцена из одних свободных частиц. Частицы могут появляться и по ходу сцены (поток).
            else -> must("по умолчанию: частицы коснулись друг друга или рельефа") { balls.isNotEmpty() && (touch.ballsEachOther() || touch.ballsTerrain()) }
        }
    }

    /** Наибольшее отклонение длины живой связи тела от позы покоя описания, доля. */
    fun linkStrain(b: StandBody): Double {
        val st = world.state; val def = b.def
        val first = b.first
        var worst = 0.0
        for (e in 0 until def.linkCount) {
            val i = first + def.linkA[e]; val j = first + def.linkB[e]
            if (st.cellDead[i] || st.cellDead[j] || st.cellUnborn[i] || st.cellUnborn[j]) continue
            val rx = (def.x[def.linkA[e]] - def.x[def.linkB[e]]).toDouble(); val ry = (def.y[def.linkA[e]] - def.y[def.linkB[e]]).toDouble()
            val rest = sqrt(rx * rx + ry * ry)
            if (rest < 1e-12) continue
            val dx = st.px[i] - st.px[j]; val dy = st.py[i] - st.py[j]
            val strain = kotlin.math.abs(sqrt(dx * dx + dy * dy) / rest - 1.0)
            if (strain > worst) worst = strain
        }
        return worst
    }

    /** Один тик: действия сценария, рост, мышь — затем шаг мира, как в тике игры. */
    fun step() {
        births.clear()
        for (s in script) s(tick)
        advanceGrowth()
        advanceOssify()
        applyMouse()
        // Пока у сцены есть несработавшие условия, проба касаний помнит состояние на начало тика.
        if (premises.any { it.tick < 0 }) touch.beforeStep()
        val t0 = System.nanoTime()
        if (reference > 0) world.stepReference(reference) else world.step()
        lastStepNanos = System.nanoTime() - t0
        tick++
    }

    /**
     * Сколько длился последний шаг мира физики, без действий сценария. Нужен только замеру
     * производительности (StandsPerf); на логику сцены и на правила время не влияет.
     */
    var lastStepNanos = 0L
        private set

    // --- чтение ---

    /** Кинетическая энергия живых подвижных частиц в единицах мира физики. */
    fun kinetic(): Double {
        val st = world.state
        var e = 0.0
        for (i in 0 until st.n) {
            if (st.cellDead[i] || st.restInvMass[i] == 0.0f) continue
            e += 0.5 * (st.vx[i] * st.vx[i] + st.vy[i] * st.vy[i]) / st.restInvMass[i]
        }
        return e
    }

    /** Расстояние между клетками [a] и [b] тела в единицах игры. */
    fun distance(body: StandBody, a: Int, b: Int): Double {
        val pa = body.particle(a); val pb = body.particle(b)
        val dx = world.x[pa] - world.x[pb]; val dy = world.y[pa] - world.y[pb]
        return sqrt(dx * dx + dy * dy) / PHYSICS_PER_GAME
    }

    /** Положение частицы в единицах игры, в координатах сцены. */
    fun gameX(p: Int): Double = if (frame.still) world.x[p] / PHYSICS_PER_GAME else frame.sceneX(world.x[p] / PHYSICS_PER_GAME, world.y[p] / PHYSICS_PER_GAME)
    fun gameY(p: Int): Double = if (frame.still) world.y[p] / PHYSICS_PER_GAME else frame.sceneY(world.x[p] / PHYSICS_PER_GAME, world.y[p] / PHYSICS_PER_GAME)

    /** Скорость частицы в единицах мира физики, в осях сцены. */
    fun sceneVx(p: Int): Double = if (frame.still) world.vx[p].toDouble() else frame.sceneDx(world.vx[p].toDouble(), world.vy[p].toDouble())
    fun sceneVy(p: Int): Double = if (frame.still) world.vy[p].toDouble() else frame.sceneDy(world.vx[p].toDouble(), world.vy[p].toDouble())

    /** Задать частице скорость ([vx], [vy]) в единицах мира физики, в осях сцены. */
    fun setSceneVelocity(p: Int, vx: Double, vy: Double) {
        if (frame.still) world.setVelocity(p, vx.toFloat(), vy.toFloat()) else world.setVelocity(p, frame.dx(vx, vy).toFloat(), frame.dy(vx, vy).toFloat())
    }

    /** Рамка содержимого сцены в единицах игры: minX, minY, maxX, maxY по живым частицам. */
    fun contentBox(): DoubleArray {
        val st = world.state
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0 until st.n) {
            if (st.cellDead[i]) continue
            val x = gameX(i); val y = gameY(i)
            if (x < x0) x0 = x; if (x > x1) x1 = x
            if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        for (b in bodies) if (b.growth != null) {
            // Растущее тело: обзор сразу под взрослую форму, а не под одну зиготу.
            for (k in 0 until b.count) {
                val wx = b.def.x[k] / PHYSICS_PER_GAME; val wy = b.def.y[k] / PHYSICS_PER_GAME
                val x = if (frame.still) wx else frame.sceneX(wx, wy); val y = if (frame.still) wy else frame.sceneY(wx, wy)
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        if (x0 > x1) return doubleArrayOf(-5.0, -5.0, 5.0, 5.0)
        return doubleArrayOf(x0 - 1.0, y0 - 1.0, x1 + 1.0, y1 + 1.0)
    }
}
