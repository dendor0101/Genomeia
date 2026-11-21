package io.github.some_example_name.physics.lab.stands

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * КИРПИЧИ КАТАЛОГА: общие режимы и расстановки, из которых семейства собирают сцены.
 *
 * Сцена каталога — это «фигура × режим»: одна и та же фигура проходит покой, удары четырёх
 * сил, скользящий удар и мышь. Режимы описаны один раз здесь, чтобы «лёгкое касание» значило
 * одно и то же у квадрата, у звезды и у тела с костями.
 */

/** Сила удара: скорость сближения в связях за тик и что при ней разрешено. */
internal class Hit(val id: String, val title: String, val speed: Double, val limits: Limits, val expect: String) {
    /** Удар, после которого ждать сна и прежней формы нельзя: сцена идёт CRASH_TICKS, от неё нужно только затухание. */
    val rough: Boolean get() = speed >= 3.0
}

internal val TAP = Hit("tap", "лёгкое касание", 0.15, Limits.INTACT,
    "Тела мягко соприкасаются и расходятся. Ничего не рвётся и не гибнет, клетки не входят в чужую ткань, к концу всё стоит и спит.")
internal val BUMP = Hit("bump", "толчок", 0.6, Limits.INTACT,
    "Тела сталкиваются и отскакивают, ткань пружинит. Ничего не рвётся и не гибнет, проникновений нет, к концу всё стоит и спит.")
internal val HIT = Hit("hit", "сильный удар", 2.0, Limits.hit(2.0),
    "Быстрое столкновение: ткань сильно сминается. Местный разрыв допустим — до 4% связей и 2% клеток; больше — ткань слишком хрупкая. Проникновений нет; к концу всё успокоилось.")
internal val RAM = Hit("ram", "таран", 4.0, Limits.hit(4.0),
    "Удар на половине потолка скорости: ткань вправе порваться заметно — до 36% связей и 18% клеток, — но не разлететься целиком. Тела не проходят друг сквозь друга, энергия не берётся из ничего, движение обломков затухает.")
internal val CRASH = Hit("crash", "таран на пределе", 6.0, Limits.CRASH,
    "Удар почти на потолке скорости: ткань вправе порваться и потерять клетки. Но тела не проходят друг сквозь друга, энергия не берётся из ничего, движение обломков затухает.")

/** Удар на самом потолке скорости решателя. В общий перечень не входит: им бьют только в семействе таранов и на телах геномов. */
internal val MAX_HIT = Hit("max", "таран на потолке скорости", SPEED_CAP, Limits.CRASH,
    "Удар на самом потолке скорости решателя: ткань вправе порваться и потерять клетки. Но тела не проходят друг сквозь друга, " +
        "скорость ни у кого не превышает потолка, энергия не берётся из ничего, движение обломков затухает.")

/** Все силы удара по возрастанию. */
internal val HITS = listOf(TAP, BUMP, HIT, RAM, CRASH)

/** Удары, после которых тело обязано остаться целым или почти целым: без таранов. */
internal val SOFT_HITS = listOf(TAP, BUMP, HIT)

/**
 * Сколько телу лететь до первого касания, в единицах игры: три связи. Тела на старте точно не
 * касаются, а «лёгкое касание» (0.15 связи за тик при торможении средой) до соседа долетает.
 */
internal const val START_GAP = 3 * GAME_LINK

/** Длительность сцены с разрушением: спать обломкам не обязательно, хватает увидеть затухание. */
internal const val CRASH_TICKS = 600

/**
 * Сколько тиков сцене нужно, чтобы тело со скоростью [speed] связей за тик остановилось и
 * уснуло. Замер 15.11: среда гасит скорость в e раз за время, которое растёт с размером тела
 * (сопротивление идёт с контура, масса — с площади): квадрат 6 — 85 тиков, квадрат 12 — 130;
 * нить и одиночная клетка, которых тормозит одна изотропная часть, — 330. Сон наступает через
 * 60 тиков покоя ниже 0.001 связи за тик. Берётся с запасом: сцена, где всё уснуло раньше,
 * заканчивается досрочно (StandRun). [slow] — на сцене есть нить или свободные частицы.
 */
internal fun settle(speed: Double, slow: Boolean = false): Int =
    ((if (slow) 330.0 else 160.0) * ln(maxOf(speed, 0.02) / 0.0005) + 150).toInt()

/**
 * Два тела друг против друга по оси X: [a] слева летит со скоростью [speed] связей за тик в
 * стоящее [b]. [offset] — сдвиг первого тела по Y (скользящий удар), [spin] — его вращение,
 * радиан за тик.
 */
internal fun StandWorld.duel(a: ShapeSpec, b: ShapeSpec, speed: Double, offset: Double = 0.0, spin: Double = 0.0,
                             angleA: Double = 0.0, angleB: Double = 0.0): Pair<StandBody, StandBody> {
    // До первого касания ровно START_GAP по настоящему контуру (Placement.kt), а не по рамке фигуры.
    val apart = separation(a, b, CONTACT_DISTANCE, offset, angleA, angleB) + START_GAP
    val first = body(a, -apart / 2, offset, angleA)
    val second = body(b, apart / 2, 0.0, angleB)
    if (speed != 0.0 || spin != 0.0) kick(first, speed, 0.0, spin)
    return first to second
}

/** Скорость центра масс тела, связей за тик. */
internal fun StandWorld.speedOf(b: StandBody): Double {
    val st = world.state
    var px = 0.0; var py = 0.0; var m = 0.0
    val first = b.first
    for (k in 0 until b.count) {
        val p = first + k
        if (st.cellDead[p]) continue
        val w = 1.0 / st.restInvMass[p]
        px += w * st.vx[p]; py += w * st.vy[p]; m += w
    }
    return if (m == 0.0) 0.0 else toLinksPerTick(sqrt(px * px + py * py) / m)
}

/**
 * Ожидание «удар состоялся»: стоявшее тело [target] получило хотя бы [share] скорости, которую
 * дал бы ему упругий удар тела из [hitterCells] клеток. Лёгкое тело тяжёлому много скорости не передаст
 * (нить из 12 клеток кругу из 127 — шестую часть своей), поэтому порог идёт с отношением масс;
 * у равных тел это просто [share] скорости налетевшего. Массы — числом клеток: клетки одинаковые.
 */
internal fun StandWorld.expectPushed(target: StandBody, speed: Double, until: Int, share: Double = 0.1, hitterCells: Int = target.count) {
    val ratio = minOf(1.0, 2.0 * hitterCells / (hitterCells + target.count))
    probe("скорость второго тела") { speedOf(target) }
    expectMaxAtLeast("стоявшее тело получило толчок", "скорость второго тела", 1, until, share * speed * ratio)
}

/** Мышь ведёт клетку [cell] тела по точкам пути: по [ticksPerPoint] тиков на точку, затем отпускает. */
internal fun StandWorld.mousePath(b: StandBody, cell: Int, start: Int, ticksPerPoint: Int, points: List<DoubleArray>) {
    at(start) { grab(b, cell) }
    points.forEachIndexed { k, p -> at(start + k * ticksPerPoint) { dragTo(p[0], p[1]) } }
    at(start + points.size * ticksPerPoint) { release() }
}

/** Точки окружности радиуса [r] вокруг ([cx], [cy]) — путь мыши по кругу. */
internal fun circlePath(cx: Double, cy: Double, r: Double, steps: Int, turns: Double = 1.0): List<DoubleArray> =
    (0..steps).map { k ->
        val a = 2 * Math.PI * turns * k / steps
        doubleArrayOf(cx + r * cos(a), cy + r * sin(a))
    }

/** Список сцен семейства с общим названием. */
/**
 * Вариации, с которыми собирается каталог: -Pvary=base,len (свойство stands.vary). По умолчанию —
 * все. Одна «base» — прежний каталог на ровной решётке: так сверяют, что правка стендов не
 * сдвинула старые сцены.
 */
val ACTIVE_VARIATIONS: List<Variation> = System.getProperty("stands.vary").orEmpty().split(',')
    .mapNotNull { Variation.byId(it.trim()) }.ifEmpty { Variation.ALL }

internal class Family(val name: String) {
    val list = ArrayList<Scenario>()

    /**
     * Сцена во всех вариациях [vary]: номер вариации — [id] с окончанием «@вариация», у ровной
     * решётки — без окончания. Сцена из одних свободных частиц берёт Variation.BALLS, сцена с
     * телом генома — Variation.ONLY_BASE.
     */
    fun add(id: String, title: String, about: String, expect: String, ticks: Int, limits: Limits,
            vary: List<Variation> = Variation.ALL, setup: StandWorld.() -> Unit) {
        for (v in vary) if (v in ACTIVE_VARIATIONS) list.add(Scenario(id + v.suffix, name, title, about, expect, ticks, limits, v, id, setup))
    }
}

/**
 * Ожидание «тело дошло до рельефа»: хоть раз за [until] тиков клетка контура тела оказалась на
 * расстоянии касания от частицы рельефа. Без него сцена, где тело остановилось в воде, не
 * долетев до стены, проходила бы все правила, ничего не проверив.
 */
internal fun StandWorld.expectReached(b: StandBody, until: Int) {
    var rocks: DoubleArray? = null
    val outline = (0 until b.count).filter { b.shape.isOutline(it) }.toIntArray()
    // Порог — расстояние касания самой крупной пары «клетка контура — частица рельефа» плюс запас.
    val touch = (b.shape.contact.max() + TERRAIN_RADIUS * ShapeSpec.LONE_CONTACT_SCALE).let { pairDistance(it, CONTACT_DISTANCE) }
    probe("до рельефа") {
        val st = world.state
        val r = rocks ?: DoubleArray(2 * fixed.size).also { a ->
            for (k in fixed.indices) { val p = world.freeParticle(fixed[k]); a[2 * k] = st.px[p]; a[2 * k + 1] = st.py[p] }
            rocks = a
        }
        var best = Double.MAX_VALUE
        for (c in outline) {
            val p = b.particle(c)
            if (st.cellDead[p]) continue
            val x = st.px[p]; val y = st.py[p]
            var k = 0
            while (k < r.size) {
                val dx = x - r[k]; val dy = y - r[k + 1]
                val d = dx * dx + dy * dy
                if (d < best) best = d
                k += 2
            }
        }
        if (best == Double.MAX_VALUE) Double.NaN else sqrt(best) / PHYSICS_PER_GAME
    }
    // Запас в полрадиуса клетки: на быстром ударе отскок случается внутри тика, и положения на
    // границах тиков самого близкого подхода не застают (костный кирпич о зубцы: 0.64 при пороге 0.6).
    mustTouchTerrain(b, "тело дошло до рельефа")
}

// --- кирпичи новых семейств: встречные удары, куски тел, зажимы ---

/**
 * Два тела летят друг в друга по оси X, каждое со скоростью [each] связей за тик: скорость
 * сближения вдвое больше. До первого касания — START_GAP, как у [duel].
 */
internal fun StandWorld.duelBoth(a: ShapeSpec, b: ShapeSpec, each: Double, offset: Double = 0.0, angleA: Double = 0.0, angleB: Double = 0.0): Pair<StandBody, StandBody> {
    val apart = separation(a, b, CONTACT_DISTANCE, offset, angleA, angleB) + START_GAP
    val first = body(a, -apart / 2, offset, angleA)
    val second = body(b, apart / 2, 0.0, angleB)
    kick(first, each, 0.0)
    kick(second, -each, 0.0)
    return first to second
}

/** Скорость ([vx], [vy]) связей за тик клеткам [cells] тела: толчок одному куску разрезанного тела. */
internal fun StandWorld.kickCells(b: StandBody, cells: IntArray, vx: Double, vy: Double) {
    val st = world.state
    val lx = linksPerTick(vx); val ly = linksPerTick(vy)
    var before = 0.0; var after = 0.0
    for (c in cells) {
        val p = b.particle(c)
        if (st.cellDead[p]) continue
        val m = 1.0 / st.restInvMass[p]
        before += 0.5 * m * (st.vx[p] * st.vx[p] + st.vy[p] * st.vy[p])
        setSceneVelocity(p, lx, ly)
        after += 0.5 * m * (lx * lx + ly * ly)
        world.wake(p)
    }
    if (after > before) addEnergy(after - before)
}

/** Середина живых клеток [cells] тела в единицах игры. */
internal fun StandWorld.centerOfCells(b: StandBody, cells: IntArray): DoubleArray {
    val st = world.state
    var sx = 0.0; var sy = 0.0; var n = 0
    for (c in cells) {
        val p = b.particle(c)
        if (st.cellDead[p]) continue
        sx += gameX(p); sy += gameY(p); n++
    }
    return if (n == 0) doubleArrayOf(0.0, 0.0) else doubleArrayOf(sx / n, sy / n)
}

/**
 * Проба «на сколько связей клетки [cells] тела ушли от места, где стояли на тике [from]» и ожидание,
 * что за тики [from]..[until] ни одна не ушла дальше [links]: кусок, которого никто не трогал,
 * и присоска, которая держит, обязаны стоять. Меряется каждая живая клетка от своего места, а
 * не середина набора: середина сдвигается и оттого, что одна из клеток погибла.
 */
internal fun StandWorld.expectStill(name: String, b: StandBody, cells: IntArray, from: Int, until: Int, links: Double) {
    var home: DoubleArray? = null
    probe(name) {
        if (tick < from) 0.0
        else {
            val st = world.state
            val h = home ?: DoubleArray(2 * cells.size).also { a ->
                for (i in cells.indices) { val p = b.particle(cells[i]); a[2 * i] = st.px[p]; a[2 * i + 1] = st.py[p] }
                home = a
            }
            var worst = 0.0
            for (i in cells.indices) {
                val p = b.particle(cells[i])
                if (st.cellDead[p]) continue
                val dx = st.px[p] - h[2 * i]; val dy = st.py[p] - h[2 * i + 1]
                val d = dx * dx + dy * dy
                if (d > worst) worst = d
            }
            sqrt(worst) / PHYSICS_LINK
        }
    }
    expectMaxAtMost("клетки, которых не трогали, остались на месте", name, from, until, links)
}

/** То же, но наоборот: клетки [cells] обязаны уйти от места хотя бы на [links] связей. */
internal fun StandWorld.expectTravelled(name: String, b: StandBody, cells: IntArray, from: Int, until: Int, links: Double, text: String = "тело сдвинулось") {
    var home: DoubleArray? = null
    probe(name) {
        if (tick < from) 0.0
        else {
            val c = centerOfCells(b, cells)
            val h = home ?: c.also { home = it }
            Math.hypot(c[0] - h[0], c[1] - h[1]) / GAME_LINK
        }
    }
    expectMaxAtLeast(text, name, from, until, links)
}

/**
 * Проба «зазор между телами» (наименьшее расстояние между кругами касания клеток контура двух
 * тел, связей) и ожидание «тела встретились»: хоть раз за [until] тиков зазор не больше [links].
 * Без него сцена, где снаряд пролетел мимо, проходила бы все правила, ничего не проверив.
 */
/**
 * [speed] — скорость сближения, связей за тик: проба видит тела только на границах тиков, а на
 * быстром ударе сближение и отскок умещаются внутри одного тика, поэтому порог шире на путь за тик.
 */
internal fun StandWorld.expectMet(a: StandBody, b: StandBody, until: Int, speed: Double = 0.0, name: String = "зазор между телами, связей") {
    probe(name) { clearance(a, b) / GAME_LINK }
    // Не ожидание, а условие сцены: если тела не встретились, виновата постановка, и сцена прошла вхолостую.
    mustTouch(a, b, "тела встретились")
}

/** Клетки фигуры у её края со стороны ([dx], [dy]) — не дальше [depth] связей от самой крайней: за них берётся зажим. */
internal fun edgeCells(s: ShapeSpec, dx: Double, dy: Double, depth: Double = 0.6): IntArray {
    var far = -Double.MAX_VALUE
    for (k in 0 until s.count) far = maxOf(far, s.lx[k] * dx + s.ly[k] * dy)
    return (0 until s.count).filter { s.lx[it] * dx + s.ly[it] * dy >= far - depth * GAME_LINK - 1e-9 }.toIntArray()
}

/** Все клетки фигуры по одну сторону от прямой через её середину: [dx], [dy] — куда смотрит эта сторона. */
internal fun sideCells(s: ShapeSpec, dx: Double, dy: Double, from: Double = 0.0): IntArray =
    (0 until s.count).filter { s.lx[it] * dx + s.ly[it] * dy > from + 1e-9 }.toIntArray()

/** Плавный ход от 0 до 1 за тики [from]..[to] (косинусом: без рывка в начале и в конце). */
internal fun ramp(tick: Int, from: Int, to: Int): Double = when {
    tick <= from -> 0.0
    tick >= to -> 1.0
    else -> 0.5 - 0.5 * cos(Math.PI * (tick - from) / (to - from))
}

/**
 * Проба «частиц за рамкой» и ожидание, что ни одна живая подвижная частица не вышла из
 * прямоугольника ([minX], [minY])–([maxX], [maxY]) единиц игры: рамка мира, полость рельефа.
 */
internal fun StandWorld.expectAllInside(minX: Double, minY: Double, maxX: Double, maxY: Double, until: Int, name: String = "частиц за рамкой") {
    probe(name) {
        val st = world.state
        var out = 0
        for (i in 0 until st.n) {
            if (st.cellDead[i] || st.cellUnborn[i] || st.restInvMass[i] == 0.0) continue
            val x = gameX(i); val y = gameY(i)
            if (x < minX || x > maxX || y < minY || y > maxY) out++
        }
        out.toDouble()
    }
    expectMaxAtMost("никто не вышел за рамку", name, 1, until, 0.0)
}
