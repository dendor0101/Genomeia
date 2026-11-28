package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.BodyState
import io.github.some_example_name.physics.lab.io.PlayerLog
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * ПОРОГИ ПРАВИЛ. Одни на все сцены; сцена выбирает только, какие правила к ней применимы
 * (Limits). Порог — сигнализация, а не физическая константа: менять его можно только с
 * записью причины здесь же.
 */

/** Скорость (связей за тик, среднеквадратичная по массе), ниже которой движение считается шумом покоя. */
const val NOISE_SPEED = 0.02

/** Запас на рост энергии закрытой сцены: округление и обмен с запасом среды тела. */
const val ENERGY_SLACK = 1.05

/**
 * «Успокоилась»: среднее смещение за тик за последние CALM_WINDOW тиков не больше CALM_SPEED
 * связей — либо движение затухает: за последнее окно оно хотя бы на CALM_DECAY меньше, чем за
 * предыдущее. Второе условие нужно ради честного дрейфа: среда гасит скорость тела примерно
 * втрое за сто тиков, а одиночной клетки и нити — втрое за 330 (замер 15.11 на квадрате и
 * нити), и ждать полной остановки пришлось бы тысячи тиков. Незатухающее движение — дрожь,
 * ползучесть, «вечный двигатель» — не проходит ни по одному из условий.
 */
const val CALM_SPEED = 0.005
const val CALM_WINDOW = 60
const val CALM_DECAY = 0.9

/** Клетка сидит в своей ткани дольше стольких тиков подряд — нарушение; короткая складка на ударе — нет. */
const val SELF_TICKS = 5

/** «Вернулось к форме»: живые связи целого тела отличаются от исходной длины не больше чем на эту долю. */
const val SHAPE_STRAIN = 0.05

/** То же для расстояний между дальними клетками тела: ловит складку и изгиб, при которых длины связей целы. */
const val POSE_STRAIN = 0.08

/** Расстояния между клетками одной кости — не дальше этой доли от задуманных. */
const val BONE_STRAIN = 0.05

/** Треугольник вывернут дольше стольких тиков подряд — нарушение (решатель сам рушит его на одиннадцатом). */
const val INVERSION_TICKS = 5

/** Центр частицы ближе этой доли радиуса к центру частицы рельефа — «вошла в рельеф». */
const val TERRAIN_DEPTH = 0.5

/**
 * ДРОЖЬ (дребезг). Частица дрожит, когда раз за разом разворачивает своё смещение назад: за
 * последние восемь тиков не меньше JITTER_FLIPS разворотов круче 120°, и каждое из двух
 * смещений вокруг разворота крупнее JITTER_STEP связи за тик (мельче глазом не видно даже
 * вблизи). Упругое колебание ткани после удара сюда не попадает: у него период в десятки тиков,
 * и за восемь тиков направление меняется раз, а не четыре. Нарушение — частица дрожит дольше
 * JITTER_TICKS тиков подряд: короткий дребезг касания в момент удара не в счёт.
 *
 * Зачем отдельное правило, если есть «сцена успокоилась»: то судит среднее по всей сцене и
 * только в конце, а дрожь бывает у десятка клеток (сустав под мышцей, угол у стенки) и
 * посреди сцены, пока мышца держит.
 */
const val JITTER_STEP = 0.01
const val JITTER_FLIPS = 4
const val JITTER_TICKS = 20

/**
 * РОЖДЕНИЕ НА СВОЁМ МЕСТЕ (Rule.BIRTH, меры — BirthLog). Новорождённая судится в миг рождения, до шага мира; обе меры —
 * в её длинах связи:
 *  - до ближайшей рождённой клетки своего тела — не дальше BIRTH_GAP;
 *  - связи, с которыми она родилась, и стороны треугольников, оживших вместе с ней, длиннее своих длин в описании не
 *    больше чем на BIRTH_EXCESS.
 * Запас нужен честному делению: клетка рождается почти в родителе (DIVISION_START_FRACTION), и её связь к соседу длиннее
 * положенной на расстояние до родителя; у двух связанных клеток одной стадии, рождённых каждая в своём родителе, — на
 * два таких расстояния. Порог выбран по прогону всех сцен роста 20.11 (BirthLog); больше — значит, часть тела уже не
 * там, где ей быть по позе.
 */
const val BIRTH_GAP = 2.0
const val BIRTH_EXCESS = 3.0

/** Правила монитора. Порядок — как их показывает окно. */
enum class Rule(val title: String) {
    NUMBERS("числа конечны"),
    ENERGY("энергия не растёт"),
    SPEED_CAP("потолок скорости молчит"),
    FOREIGN("нет клеток в чужой ткани"),
    CROSS("никто не проходит сквозь ткань"),
    SELF("нет клеток в своей ткани"),
    TERRAIN("ничто не входит в рельеф"),
    TEARS("разрывы в пределах допуска"),
    DEATHS("гибель клеток в пределах допуска"),
    INVERSION("треугольники не вывернуты"),
    /** Новорождённая клетка — у своего тела и без дальних связей (BirthLog, BIRTH_GAP, BIRTH_EXCESS). Судится у растущих тел. */
    BIRTH("клетки рождаются на своём месте"),
    BONES("кости держат форму"),
    STILL("спящее не двигается"),
    JITTER("нет дрожи"),
    /**
     * Сцена проверила то, ради чего поставлена: случилось всё, что она объявила обязательным
     * (StandWorld.must), и не случилось того, чего по постановке быть не должно. Нарушение — не
     * ошибка физики, а пустая сцена: остальные её правила зелёные просто потому, что ничего не было.
     */
    PREMISE("сцена не вхолостую"),
    EXPECT("ожидание сцены"),
    SHAPE("тело вернулось к форме"),
    CALM("сцена успокоилась"),
    SLEEP("всё уснуло"),
    REPEAT("повтор побитово"),
    WORKERS("один поток = воркеры"),
    /** Та же сцена сдвинутая, повёрнутая, отражённая и перенумерованная даёт тот же итог (Symmetry.kt). Судит пакетный прогон с -Psymmetry. */
    SYMMETRY("симметрия: сдвиг, поворот, отражение, перенумерация"),
    /** Быстрый решатель сходится с простым эталонным по крупным величинам (reference/). Судит пакетный прогон с -Preference. */
    REFERENCE("сверка с эталонным решателем"),
}

class RuleResult(val rule: Rule) {
    /** Применимо ли правило к этой сцене. */
    var applies = true
    /** Судится только в конце прогона и ещё не оценено. */
    var pending = false
    var violations = 0
    var firstTick = -1
    /** Что именно нарушено — для окна и отчёта. */
    var text = ""
    val failed: Boolean get() = applies && violations > 0
}

/** Отметка нарушения на сцене, в единицах игры. */
class Mark(val rule: Rule, val x: Double, val y: Double)

/**
 * МОНИТОР ПРАВИЛ: смотрит на мир после каждого тика и отвечает «прошло или нет» по чётким
 * условиям. Он не знает, что за сцена идёт, и ничего не меняет в мире — только читает.
 * Поэтому один и тот же монитор годится для сцены из двух квадратов, для набитой полости и
 * для растущего тела, а после смены физики (вплоть до переписывания на другом языке) сцены
 * проверяются теми же правилами: побитовой сверки со старым решателем для этого не нужно.
 *
 * Цена: проход по частицам и живым треугольникам раз в тик, без выделений в цикле.
 */
class RuleMonitor(private val w: StandWorld, val limits: Limits, private val ticks: Int) {
    val results = EnumMap<Rule, RuleResult>(Rule::class.java).also { m -> for (r in Rule.values()) m[r] = RuleResult(r) }
    /** Отметки нарушений последнего тика — для рисования. */
    val marks = ArrayList<Mark>()

    // --- числа прогона: в отчёт и в окно ---
    var torn = 0; private set
    var died = 0; private set
    var capHits = 0; private set
    /** Наибольшее отношение кинетической энергии к запасу сцены. */
    var peakEnergy = 0.0; private set
    /** Наибольшая скорость частицы, связей за тик. */
    var maxSpeed = 0.0; private set
    /** Сколько «частица × тик» пришлось на клетки в чужой ткани и самый долгий заход одной клетки. */
    var foreignTicks = 0; private set
    var foreignLongest = 0; private set
    /** Сколько раз частица за тик пересекла живую связь чужого тела. */
    var crossings = 0; private set
    /** Сколько «частица × тик» пришлось на дрожь и самая долгая дрожь одной частицы, тиков. */
    var jitterTicks = 0; private set
    var jitterLongest = 0; private set
    /** Сколько связей и клеток тела могут потерять по допускам сцены; MAX_VALUE — сколько угодно. */
    var allowedTorn = 0; private set
    var allowedDead = 0; private set
    /** Сколько всего связей у тел сцены и сколько клеток и свободных частиц — от них считаются допуски. */
    var linksTotal = 0; private set
    var cellsTotal = 0; private set
    /** Тик последнего разрыва, не заданного сценарием; -1 — разрывов не было. */
    var lastTearTick = -1; private set
    var selfTicks = 0; private set
    var selfLongest = 0; private set
    var terrainTicks = 0; private set
    var invertedPeak = 0; private set
    var invertedLongest = 0; private set
    var boneWorst = 0.0; private set
    var shapeWorst = 0.0; private set
    var poseWorst = 0.0; private set
    /** Худшие меры рождений (BirthLog): зазор до тела и лишняя длина новых связей — в длинах связи новорождённой. */
    var birthGapWorst = 0.0; private set
    var birthExcessWorst = 0.0; private set
    /** Среднее смещение за тик в конце прогона, связей. */
    var endSpeed = 0.0; private set
    /** Сколько живых подвижных частиц не спит сейчас. */
    var awake = 0; private set
    /** Тик, с которого всё спит и не просыпалось; -1 — кто-то не спит. */
    var sleepTick = -1; private set
    /** Смещение за тик (связей, среднеквадратичное) и энергия к запасу — по тикам, для графика. */
    val speed = FloatArray(ticks + 1)
    val energy = FloatArray(ticks + 1)
    /** Отпечаток положений и скоростей после каждого тика — для сверки повтора. */
    val hashes = LongArray(ticks + 1)
    var finished = false; private set

    private var keBase = 0.0
    private var inj0 = 0.0
    private var torn0 = 0
    private var cap0 = 0

    private var prevDead = BooleanArray(0)
    private var prevAsleep = BooleanArray(0)
    private var prevX = DoubleArray(0)
    private var prevY = DoubleArray(0)
    private var bodyOf = IntArray(0)
    private var foreignRun = IntArray(0)
    private var selfRun = IntArray(0)
    private var invRun = IntArray(0)
    // Дрожь: прошлое смещение частицы, развороты за последние восемь тиков (по биту на тик) и длина серии.
    private var jitDX = DoubleArray(0)
    private var jitDY = DoubleArray(0)
    private var jitBits = ByteArray(0)
    private var jitRun = IntArray(0)

    private var gridStart = IntArray(0)
    private var gridFill = IntArray(0)
    private var gridItems = IntArray(0)

    // Живые связи тика и их сетка — для проверки «никто не проходит сквозь ткань».
    private var linkA = IntArray(0)
    private var linkB = IntArray(0)
    private var linkStart = IntArray(0)
    private var linkFill = IntArray(0)
    private var linkItems = IntArray(0)

    private class BoneRef(val body: StandBody, val a: IntArray, val b: IntArray, val rest: DoubleArray) {
        /** Взять расстояния покоя с нынешней позы тела — для кости, включённой посреди сцены. */
        fun capture(st: BodyState) {
            val first = body.first
            for (k in a.indices) {
                val i = first + a[k]; val j = first + b[k]
                rest[k] = Math.hypot((st.px[i] - st.px[j]).toDouble(), (st.py[i] - st.py[j]).toDouble())
            }
        }
    }
    private class ShapeRef(val body: StandBody, val linkRest: DoubleArray, val farA: IntArray, val farB: IntArray, val farRest: DoubleArray)
    private val bones = ArrayList<BoneRef>()
    private val shapes = ArrayList<ShapeRef>()

    private var fixedX = DoubleArray(0)
    private var fixedY = DoubleArray(0)
    private var fixedR = DoubleArray(0)
    private var fixedParticle = IntArray(0)
    private val fixedCells = HashMap<Long, IntArray>()
    private var fixedIndexed = -1

    private val samples = HashMap<String, DoubleArray>()

    /** После сборки сцены и до первого тика: запомнить, от чего считать. */
    fun begin() {
        val st = w.world.state
        keBase = w.kinetic(); inj0 = w.injected
        torn0 = st.tornTotal; cap0 = st.speedCapHits
        grow(st.n)
        for (i in 0 until st.n) {
            prevDead[i] = st.cellDead[i]; prevX[i] = st.px[i].toDouble(); prevY[i] = st.py[i].toDouble()
            prevAsleep[i] = !st.cellDead[i] && asleep(st, i)
        }
        for (b in w.bodies) { addBones(b); addShape(b) }
        for (name in w.probes.keys) samples[name] = DoubleArray(ticks + 1) { Double.NaN }

        results[Rule.ENERGY]!!.applies = limits.energy == Energy.CLOSED
        results[Rule.SPEED_CAP]!!.applies = !limits.capHits
        results[Rule.TERRAIN]!!.applies = w.fixed.isNotEmpty()
        linksTotal = w.bodies.sumOf { it.def.linkCount }
        cellsTotal = w.bodies.sumOf { it.count } + w.balls.size
        allowedTorn = if (limits.tornShare >= 1.0) Int.MAX_VALUE else (limits.tornShare * linksTotal).toInt()
        allowedDead = if (limits.deadShare >= 1.0) Int.MAX_VALUE else (limits.deadShare * cellsTotal).toInt()
        results[Rule.TEARS]!!.applies = limits.tornShare < 1.0
        results[Rule.DEATHS]!!.applies = limits.deadShare < 1.0
        results[Rule.JITTER]!!.applies = limits.jitter
        results[Rule.PREMISE]!!.pending = true
        // Сцены, которые стартуют уже в касании (покой внахлёст), выполняют своё условие до первого тика.
        checkPremises(0)
        results[Rule.INVERSION]!!.applies = limits.inversions
        results[Rule.BIRTH]!!.applies = w.bodies.any { it.growth != null }
        results[Rule.BONES]!!.applies = bones.isNotEmpty()
        results[Rule.EXPECT]!!.let { it.applies = w.expectations.isNotEmpty() || w.bodies.any { b -> b.growth != null }; it.pending = it.applies }
        results[Rule.SHAPE]!!.let { it.applies = limits.shape && shapes.isNotEmpty(); it.pending = it.applies }
        results[Rule.CALM]!!.let { it.applies = limits.calm; it.pending = it.applies }
        results[Rule.SLEEP]!!.let { it.applies = limits.sleep; it.pending = it.applies }
        // Сверки повтора выставляет пакетный прогон; в окне их нет.
        results[Rule.REPEAT]!!.applies = false
        results[Rule.WORKERS]!!.applies = false
        results[Rule.SYMMETRY]!!.applies = false
        results[Rule.REFERENCE]!!.applies = false
        hashes[0] = PlayerLog.stateHash(st.n, st.px, st.py, st.vx, st.vy)
    }

    private fun grow(n: Int) {
        if (prevDead.size >= n) return
        val cap = maxOf(n, prevDead.size * 2)
        val old = prevDead.size
        prevDead = prevDead.copyOf(cap); prevAsleep = prevAsleep.copyOf(cap)
        prevX = prevX.copyOf(cap); prevY = prevY.copyOf(cap)
        bodyOf = IntArray(cap); foreignRun = foreignRun.copyOf(cap); selfRun = selfRun.copyOf(cap)
        jitDX = jitDX.copyOf(cap); jitDY = jitDY.copyOf(cap); jitBits = jitBits.copyOf(cap); jitRun = jitRun.copyOf(cap)
        // Новые места — «мёртвые»: появившаяся там частица не считается погибшей и ожившей.
        for (i in old until cap) prevDead[i] = true
    }

    private fun asleep(st: BodyState, i: Int): Boolean {
        val o = st.organismOf[i]
        return o >= 0 && st.sleep.isAsleep(o)
    }

    /**
     * Что сказать о сдвинувшейся спящей частице. Клетка кости — особый случай: кость проецируется
     * целиком, а спит ли она, решатель судит по первой её клетке. Если тело порвано и кость
     * осталась в двух кусках, бодрствующий кусок двигает клетки спящего — тогда это не ошибка сна,
     * а кость, которую не разделили вместе с телом. Сообщение говорит, какой из случаев перед нами.
     */
    private fun stillText(st: BodyState, i: Int): String {
        val b = st.boneOf[i]
        if (b < 0) return "спящая частица $i сдвинулась"
        val head = st.rigidBones[b][0]
        return if (st.organismOf[head] != st.organismOf[i])
            "спящая частица $i сдвинулась: она в кости $b, а кость начинается в другом куске тела (частица $head)"
        else "спящая частица $i сдвинулась (клетка кости $b)"
    }

    private fun addBones(b: StandBody) {
        for (cluster in b.def.boneClusters) {
            if (cluster.size < 3) continue
            val m = cluster.size
            val anchors = intArrayOf(0, m / 3, 2 * m / 3, m - 1).distinct()
            val pa = ArrayList<Int>(); val pb = ArrayList<Int>(); val rest = ArrayList<Double>()
            for (k in 0 until m) for (anchor in anchors) {
                if (anchor == k) continue
                val i = cluster[k]; val j = cluster[anchor]
                val d = dist(b, i, j)
                if (d < 1e-9) continue
                pa.add(i); pb.add(j); rest.add(d)
            }
            bones.add(BoneRef(b, pa.toIntArray(), pb.toIntArray(), rest.toDoubleArray()))
        }
    }

    private fun addShape(b: StandBody) {
        val def = b.def
        val linkRest = DoubleArray(def.linkCount) { dist(b, def.linkA[it], def.linkB[it]) }
        // Дальние пары: клетки через равные промежутки по номеру, каждая — с клеткой на полтела дальше.
        // Только у жёсткой по построению фигуры (ShapeSpec.rigid): нить или тело с шарниром
        // гнётся даром, прежней позы у него нет, и судить его можно лишь по длинам связей.
        val take = if (b.shape.rigid) minOf(24, def.count) else 0
        val fa = ArrayList<Int>(); val fb = ArrayList<Int>(); val fr = ArrayList<Double>()
        for (s in 0 until take) {
            val i = (s.toLong() * def.count / take).toInt()
            val j = (((s + take / 2) % take).toLong() * def.count / take).toInt()
            val d = dist(b, i, j)
            if (i == j || d < 2 * PHYSICS_LINK) continue
            fa.add(i); fb.add(j); fr.add(d)
        }
        shapes.add(ShapeRef(b, linkRest, fa.toIntArray(), fb.toIntArray(), fr.toDoubleArray()))
    }

    /** Расстояние между клетками тела в позе покоя описания, единицы мира физики. */
    private fun dist(b: StandBody, i: Int, j: Int): Double {
        val dx = (b.def.x[i] - b.def.x[j]).toDouble(); val dy = (b.def.y[i] - b.def.y[j]).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun fail(rule: Rule, tick: Int, text: String, count: Int = 1) {
        val r = results[rule]!!
        if (!r.applies) return
        if (r.violations == 0) { r.firstTick = tick; r.text = text }
        r.violations += count
    }

    private fun mark(rule: Rule, st: BodyState, i: Int) {
        if (marks.size < 400) marks.add(Mark(rule, st.px[i] / PHYSICS_PER_GAME, st.py[i] / PHYSICS_PER_GAME))
    }

    /** После шага мира: [tick] — номер только что посчитанного тика, с единицы. */
    fun afterTick() {
        val tick = w.tick
        val st = w.world.state
        val n = st.n
        grow(n)
        marks.clear()

        // Раньше всего остального: проверке нужны положения прошлого тика, а цикл ниже их затирает.
        fillBodies(st, n)
        checkCrossing(st, n, tick)

        // --- числа, энергия, скорость, сон, гибель ---
        var ke = 0.0; var mass = 0.0; var maxV2 = 0.0; var disp = 0.0
        var awakeNow = 0; var badNumber = -1
        var jitterNow = 0
        val jitterStep2 = (JITTER_STEP * PHYSICS_LINK) * (JITTER_STEP * PHYSICS_LINK)
        for (i in 0 until n) {
            val x = st.px[i]; val y = st.py[i]; val vx = st.vx[i]; val vy = st.vy[i]
            if (!(x.isFinite() && y.isFinite() && vx.isFinite() && vy.isFinite())) { if (badNumber < 0) badNumber = i; continue }
            val dead = st.cellDead[i]
            if (dead && !prevDead[i] && !st.cellUnborn[i]) {
                died++
                if (marks.size < 400) marks.add(Mark(Rule.DEATHS, prevX[i] / PHYSICS_PER_GAME, prevY[i] / PHYSICS_PER_GAME))
            }
            if (!dead && st.restInvMass[i] != 0.0f) {
                val m = 1.0 / st.restInvMass[i]
                val v2 = vx * vx + vy * vy
                ke += 0.5 * m * v2; mass += m
                if (v2 > maxV2) maxV2 = v2.toDouble()
                val sleeping = asleep(st, i)
                if (!sleeping) awakeNow++
                if (!prevDead[i]) {
                    val dx = x - prevX[i]; val dy = y - prevY[i]
                    disp += m * (dx * dx + dy * dy)
                    if (sleeping && prevAsleep[i] && (dx != 0.0 || dy != 0.0)) {
                        fail(Rule.STILL, tick, if (results[Rule.STILL]!!.violations == 0) stillText(st, i) else "")
                        mark(Rule.STILL, st, i)
                    }
                    // Дрожь: разворот смещения круче 120° при заметных смещениях до и после.
                    val qx = jitDX[i]; val qy = jitDY[i]
                    val now2 = dx * dx + dy * dy; val was2 = qx * qx + qy * qy
                    var bits = (jitBits[i].toInt() shl 1) and 0xFF
                    if (now2 > jitterStep2 && was2 > jitterStep2 && dx * qx + dy * qy < -0.5 * sqrt(now2 * was2)) bits = bits or 1
                    jitBits[i] = bits.toByte(); jitDX[i] = dx; jitDY[i] = dy
                    if (Integer.bitCount(bits) >= JITTER_FLIPS) {
                        jitterTicks++
                        val run = ++jitRun[i]
                        if (run > jitterLongest) jitterLongest = run
                        if (run >= JITTER_TICKS) { jitterNow++; mark(Rule.JITTER, st, i) }
                    } else jitRun[i] = 0
                } else { jitBits[i] = 0; jitRun[i] = 0; jitDX[i] = 0.0; jitDY[i] = 0.0 }
                prevAsleep[i] = sleeping
            } else prevAsleep[i] = false
            prevDead[i] = dead; prevX[i] = x.toDouble(); prevY[i] = y.toDouble()
        }
        if (badNumber >= 0) fail(Rule.NUMBERS, tick, "не число у частицы $badNumber")
        if (jitterNow > 0) fail(Rule.JITTER, tick, "частиц дрожит дольше $JITTER_TICKS тиков: $jitterNow", jitterNow)

        val speedNow = toLinksPerTick(sqrt(maxV2))
        if (speedNow > maxSpeed) maxSpeed = speedNow
        val stepSpeed = if (mass > 0.0) sqrt(disp / mass) / PHYSICS_LINK else 0.0
        if (tick <= ticks) speed[tick] = stepSpeed.toFloat()

        // Запас энергии: то, что было на старте, плюс внесённое сценарием, плюс шум покоя.
        val noise = linksPerTick(NOISE_SPEED)
        val allowed = (keBase + (w.injected - inj0)) * ENERGY_SLACK + 0.5 * mass * noise * noise
        val ratio = if (allowed > 0.0) ke / allowed else 0.0
        if (ratio > peakEnergy) peakEnergy = ratio
        if (tick <= ticks) energy[tick] = ratio.toFloat()
        if (ratio > 1.0) fail(Rule.ENERGY, tick, "энергия в %.2f раза больше внесённой".format(ratio))

        val caps = st.speedCapHits - cap0
        if (caps > capHits) { fail(Rule.SPEED_CAP, tick, "потолок скорости сработал", caps - capHits); capHits = caps }

        // Разрезы и гибель, заданные самим сценарием, в счёт повреждений не идут.
        val tornNow = maxOf(0, st.tornTotal - torn0 - w.scriptedTorn)
        if (tornNow > torn) {
            lastTearTick = tick
            if (tornNow > allowedTorn) fail(Rule.TEARS, tick,
                if (allowedTorn == 0) "порвана связь" else "порвано связей $tornNow из $linksTotal при допуске $allowedTorn", tornNow - maxOf(torn, allowedTorn))
            torn = tornNow
        }
        val diedOwn = maxOf(0, died - w.scriptedDead)
        if (diedOwn > allowedDead && results[Rule.DEATHS]!!.violations < diedOwn - allowedDead)
            fail(Rule.DEATHS, tick, if (allowedDead == 0) "погибла клетка" else "погибло клеток $diedOwn из $cellsTotal при допуске $allowedDead",
                diedOwn - allowedDead - results[Rule.DEATHS]!!.violations)

        awake = awakeNow
        if (awakeNow == 0) { if (sleepTick < 0) sleepTick = tick } else sleepTick = -1

        // --- ткань, рельеф, вывернутые треугольники, кости ---
        checkTissue(st, n, tick)
        checkTerrain(st, n, tick)
        checkInversions(st, tick)
        checkBirths(st, tick)
        for (ref in bones) {
            val body = ref.body
            // Кость, которую включил сценарий (ткань стала костью), застывает в позе на момент превращения: от неё и судится.
            if (body.ossifiedAt == tick) ref.capture(st)
            if (body.growth == null && body.bonesLive) checkBone(st, ref, tick)
        }

        checkPremises(tick)
        for ((name, value) in w.probes) samples[name]?.let { if (tick <= ticks) it[tick] = value() }
        if (tick <= ticks) hashes[tick] = PlayerLog.stateHash(n, st.px, st.py, st.vx, st.vy)
    }

    /** Рождения шага (BirthLog): далеко от тела или с дальними связями — нарушение; отметка — на новорождённой. */
    private fun checkBirths(st: BodyState, tick: Int) {
        val log = w.births
        var bad = 0; var text = ""
        for (q in 0 until log.count) {
            val gap = log.gap[q]; val excess = log.excess[q]
            if (gap > birthGapWorst) birthGapWorst = gap
            if (excess > birthExcessWorst) birthExcessWorst = excess
            val far = gap > BIRTH_GAP
            if (!far && excess <= BIRTH_EXCESS) continue
            bad++
            if (text.isEmpty()) text = if (far) "клетка родилась в %.1f своей связи от тела".format(gap)
            else "при рождении связь или сторона треугольника длиннее положенной на %.1f связи".format(excess)
            // Отметки — на новорождённой и на обоих концах её худшей связи: видно, между чем она протянулась.
            mark(Rule.BIRTH, st, log.particle[q])
            if (log.worstA[q] >= 0) { mark(Rule.BIRTH, st, log.worstA[q]); mark(Rule.BIRTH, st, log.worstB[q]) }
        }
        if (bad > 0) fail(Rule.BIRTH, tick, text, bad)
    }

    /** Условия сцены: каждое проверяется до первого срабатывания. */
    private fun checkPremises(tick: Int) {
        for (p in w.premises) if (p.tick < 0 && p.check()) p.tick = tick
    }

    private fun fillBodies(st: BodyState, n: Int) {
        java.util.Arrays.fill(bodyOf, 0, n, -1)
        for (b in w.bodies) java.util.Arrays.fill(bodyOf, b.first, b.first + b.count, b.index)
    }

    private fun linked(st: BodyState, i: Int, j: Int): Boolean {
        val b = st.baked
        for (q in b.vertStart[i] until b.vertStart[i + 1]) if (b.vertOther[q] == j) return true
        return false
    }

    /** Строго внутри треугольника, с отступом от сторон в 2% площади: касание края — не проникновение. */
    private fun inside(st: BodyState, px: Double, py: Double, a: Int, b: Int, c: Int): Boolean {
        val ax = st.px[a]; val ay = st.py[a]; val bx = st.px[b]; val by = st.py[b]; val cx = st.px[c]; val cy = st.py[c]
        val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (abs(area) < 1e-14) return false
        val eps = 0.02 * abs(area)
        val d1 = (px - ax) * (by - ay) - (py - ay) * (bx - ax)
        val d2 = (px - bx) * (cy - by) - (py - by) * (cx - bx)
        val d3 = (px - cx) * (ay - cy) - (py - cy) * (ax - cx)
        return (d1 > eps && d2 > eps && d3 > eps) || (d1 < -eps && d2 < -eps && d3 < -eps)
    }

    /**
     * Клетка внутри живого треугольника: чужого тела — проникновение; своего, не соседнего по
     * связям, — складка ткани в себя; неподвижная частица рельефа внутри треугольника — ткань
     * обтекла рельеф. Треугольники раскладываются по сетке, проверка — по ячейке частицы.
     */
    private fun checkTissue(st: BodyState, n: Int, tick: Int) {
        val body = st.body
        val alive = st.baked.triAlive
        val tc = body.triCount
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (t in 0 until tc) {
            if (!alive[t]) continue
            val a = body.triA[t]; val b = body.triB[t]; val c = body.triC[t]
            val lx = minOf(st.px[a], st.px[b], st.px[c]); val hx = maxOf(st.px[a], st.px[b], st.px[c])
            val ly = minOf(st.py[a], st.py[b], st.py[c]); val hy = maxOf(st.py[a], st.py[b], st.py[c])
            if (lx < x0) x0 = lx.toDouble(); if (hx > x1) x1 = hx.toDouble()
            if (ly < y0) y0 = ly.toDouble(); if (hy > y1) y1 = hy.toDouble()
        }
        if (x0 > x1 || !(x0.isFinite() && x1.isFinite() && y0.isFinite() && y1.isFinite())) return
        var size = 2.5 * PHYSICS_LINK
        var cols = ((x1 - x0) / size).toInt() + 1; var rows = ((y1 - y0) / size).toInt() + 1
        // Разлетевшаяся сцена: ячейки крупнее, чтобы сетка не росла без меры.
        while (cols.toLong() * rows > (1 shl 18)) { size *= 2; cols = ((x1 - x0) / size).toInt() + 1; rows = ((y1 - y0) / size).toInt() + 1 }
        val cells = cols * rows
        if (gridStart.size < cells + 1) { gridStart = IntArray(cells + 1); gridFill = IntArray(cells + 1) }
        java.util.Arrays.fill(gridStart, 0, cells + 1, 0)
        fun cellX(x: Double) = ((x - x0) / size).toInt().coerceIn(0, cols - 1)
        fun cellY(y: Double) = ((y - y0) / size).toInt().coerceIn(0, rows - 1)
        for (pass in 0..1) {
            if (pass == 1) {
                for (k in 0 until cells) gridStart[k + 1] += gridStart[k]
                if (gridItems.size < gridStart[cells]) gridItems = IntArray(gridStart[cells] * 2)
                System.arraycopy(gridStart, 0, gridFill, 0, cells + 1)
            }
            for (t in 0 until tc) {
                if (!alive[t]) continue
                val a = body.triA[t]; val b = body.triB[t]; val c = body.triC[t]
                val cx0 = cellX(minOf(st.px[a], st.px[b], st.px[c]).toDouble()); val cx1 = cellX(maxOf(st.px[a], st.px[b], st.px[c]).toDouble())
                val cy0 = cellY(minOf(st.py[a], st.py[b], st.py[c]).toDouble()); val cy1 = cellY(maxOf(st.py[a], st.py[b], st.py[c]).toDouble())
                for (gy in cy0..cy1) for (gx in cx0..cx1) {
                    val k = gy * cols + gx
                    if (pass == 0) gridStart[k + 1]++ else gridItems[gridFill[k]++] = t
                }
            }
        }
        var foreignNow = 0; var selfNow = 0; var rockNow = 0
        for (i in 0 until n) {
            if (st.cellDead[i]) { foreignRun[i] = 0; selfRun[i] = 0; continue }
            val px = st.px[i]; val py = st.py[i]
            val rock = st.restInvMass[i] == 0.0f
            var foreign = false; var self = false
            if (px >= x0 && px <= x1 && py >= y0 && py <= y1) {
                val k = cellY(py.toDouble()) * cols + cellX(px.toDouble())
                for (s in gridStart[k] until gridStart[k + 1]) {
                    val t = gridItems[s]
                    val a = body.triA[t]; val b = body.triB[t]; val c = body.triC[t]
                    if (i == a || i == b || i == c) continue
                    val same = bodyOf[i] >= 0 && bodyOf[i] == bodyOf[a]
                    if (same) {
                        if (self || linked(st, i, a) || linked(st, i, b) || linked(st, i, c)) continue
                        if (inside(st, px.toDouble(), py.toDouble(), a, b, c)) self = true
                    } else if (!foreign && inside(st, px.toDouble(), py.toDouble(), a, b, c)) foreign = true
                }
            }
            if (rock) {
                if (foreign) { rockNow++; mark(Rule.TERRAIN, st, i) }
                continue
            }
            if (foreign) {
                foreignNow++; foreignRun[i]++
                if (foreignRun[i] > foreignLongest) foreignLongest = foreignRun[i]
                mark(Rule.FOREIGN, st, i)
            } else foreignRun[i] = 0
            if (self) {
                selfTicks++; selfRun[i]++
                if (selfRun[i] > selfLongest) selfLongest = selfRun[i]
                if (selfRun[i] > SELF_TICKS) { selfNow++; mark(Rule.SELF, st, i) }
            } else selfRun[i] = 0
        }
        if (foreignNow > 0) { foreignTicks += foreignNow; fail(Rule.FOREIGN, tick, "клеток в чужой ткани: $foreignNow", foreignNow) }
        if (selfNow > 0) fail(Rule.SELF, tick, "клеток в своей ткани дольше $SELF_TICKS тиков: $selfNow", selfNow)
        if (rockNow > 0) { terrainTicks += rockNow; fail(Rule.TERRAIN, tick, "частиц рельефа внутри ткани: $rockNow", rockNow) }
    }

    /**
     * Никто не проходит сквозь ткань: за тик частица не пересекает живую связь чужого тела.
     * Правило «клетка внутри чужого треугольника» этого не ловит: пуля или клетка на большой
     * скорости проскакивает тонкую ленту целиком за один тик и внутри ткани не задерживается.
     * Считается точно, с учётом того, что за тик движутся и частица, и связь: момент, когда
     * частица оказывается на прямой связи, — корень квадратного уравнения, и пересечение есть,
     * если в этот момент она лежит между концами связи. Связи раскладываются по сетке вместе
     * с путём, пройденным за тик; частица смотрит ячейки своего пути.
     */
    private fun checkCrossing(st: BodyState, n: Int, tick: Int) {
        // Одно тело и ни одной свободной частицы — пересекать некому.
        if (w.bodies.size + (if (w.balls.isEmpty()) 0 else 1) < 2) return
        val baked = st.baked
        var m = 0
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0 until n) {
            if (bodyOf[i] < 0 || st.cellDead[i] || prevDead[i]) continue
            for (q in baked.vertStart[i] until baked.vertStart[i + 1]) {
                val j = baked.vertOther[q]
                if (j <= i || baked.edgeDead[baked.vertEdge[q]] || st.cellDead[j] || prevDead[j]) continue
                if (m == linkA.size) { val cap = maxOf(256, m * 2); linkA = linkA.copyOf(cap); linkB = linkB.copyOf(cap) }
                linkA[m] = i; linkB[m] = j; m++
                val lx = minOf(minOf(st.px[i].toDouble(), st.px[j].toDouble()), minOf(prevX[i], prevX[j])); val hx = maxOf(maxOf(st.px[i].toDouble(), st.px[j].toDouble()), maxOf(prevX[i], prevX[j]))
                val ly = minOf(minOf(st.py[i].toDouble(), st.py[j].toDouble()), minOf(prevY[i], prevY[j])); val hy = maxOf(maxOf(st.py[i].toDouble(), st.py[j].toDouble()), maxOf(prevY[i], prevY[j]))
                if (lx < x0) x0 = lx; if (hx > x1) x1 = hx
                if (ly < y0) y0 = ly; if (hy > y1) y1 = hy
            }
        }
        if (m == 0 || !(x0.isFinite() && x1.isFinite() && y0.isFinite() && y1.isFinite())) return
        var size = 2.5 * PHYSICS_LINK
        var cols = ((x1 - x0) / size).toInt() + 1; var rows = ((y1 - y0) / size).toInt() + 1
        while (cols.toLong() * rows > (1 shl 18)) { size *= 2; cols = ((x1 - x0) / size).toInt() + 1; rows = ((y1 - y0) / size).toInt() + 1 }
        val cells = cols * rows
        if (linkStart.size < cells + 1) { linkStart = IntArray(cells + 1); linkFill = IntArray(cells + 1) }
        java.util.Arrays.fill(linkStart, 0, cells + 1, 0)
        fun cellX(x: Double) = ((x - x0) / size).toInt().coerceIn(0, cols - 1)
        fun cellY(y: Double) = ((y - y0) / size).toInt().coerceIn(0, rows - 1)
        for (pass in 0..1) {
            if (pass == 1) {
                for (k in 0 until cells) linkStart[k + 1] += linkStart[k]
                if (linkItems.size < linkStart[cells]) linkItems = IntArray(linkStart[cells] * 2)
                System.arraycopy(linkStart, 0, linkFill, 0, cells + 1)
            }
            for (e in 0 until m) {
                val i = linkA[e]; val j = linkB[e]
                val cx0 = cellX(minOf(minOf(st.px[i].toDouble(), st.px[j].toDouble()), minOf(prevX[i], prevX[j]))); val cx1 = cellX(maxOf(maxOf(st.px[i].toDouble(), st.px[j].toDouble()), maxOf(prevX[i], prevX[j])))
                val cy0 = cellY(minOf(minOf(st.py[i].toDouble(), st.py[j].toDouble()), minOf(prevY[i], prevY[j]))); val cy1 = cellY(maxOf(maxOf(st.py[i].toDouble(), st.py[j].toDouble()), maxOf(prevY[i], prevY[j])))
                for (gy in cy0..cy1) for (gx in cx0..cx1) {
                    val k = gy * cols + gx
                    if (pass == 0) linkStart[k + 1]++ else linkItems[linkFill[k]++] = e
                }
            }
        }
        var now = 0
        for (i in 0 until n) {
            // Неподвижная частица рельефа — тоже в счёт: нить или тонкая лента, протащенная через неё, прошла сквозь
            // рельеф, и правило «частица рельефа внутри ткани» этого не видит (у нити треугольников нет).
            if (st.cellDead[i] || prevDead[i]) continue
            val px = st.px[i].toDouble(); val py = st.py[i].toDouble(); val qx = prevX[i]; val qy = prevY[i]
            val lx = minOf(px, qx); val hx = maxOf(px, qx); val ly = minOf(py, qy); val hy = maxOf(py, qy)
            if (hx < x0 || lx > x1 || hy < y0 || ly > y1) continue
            val mine = bodyOf[i]
            var hit = false
            search@ for (gy in cellY(ly)..cellY(hy)) for (gx in cellX(lx)..cellX(hx)) {
                val k = gy * cols + gx
                for (s in linkStart[k] until linkStart[k + 1]) {
                    val e = linkItems[s]
                    val a = linkA[e]
                    if (bodyOf[a] == mine) continue
                    if (crossed(st, i, a, linkB[e])) { hit = true; break@search }
                }
            }
            if (hit) { now++; mark(Rule.CROSS, st, i) }
        }
        if (now > 0) { crossings += now; fail(Rule.CROSS, tick, "частиц прошло сквозь чужую связь: $now", now) }
    }

    /** Пересекла ли частица [i] связь [a]–[b] за тик: все три точки идут по прямой от прошлого положения к нынешнему. */
    private fun crossed(st: BodyState, i: Int, a: Int, b: Int): Boolean {
        val ex = prevX[b] - prevX[a]; val ey = prevY[b] - prevY[a]
        val wx = prevX[i] - prevX[a]; val wy = prevY[i] - prevY[a]
        val dex = st.px[b] - st.px[a] - ex; val dey = st.py[b] - st.py[a] - ey
        val dwx = st.px[i] - st.px[a] - wx; val dwy = st.py[i] - st.py[a] - wy
        // f(t) = cross(e(t), w(t)) = qa t² + qb t + qc; частица на прямой связи, когда f = 0.
        val qc = ex * wy - ey * wx
        val qb = ex * dwy - ey * dwx + dex * wy - dey * wx
        val qa = dex * dwy - dey * dwx
        if (qc == 0.0) return false
        var t1 = -1.0; var t2 = -1.0
        if (abs(qa) < 1e-12 * (abs(qb) + abs(qc))) {
            if (qb != 0.0) t1 = -qc / qb
        } else {
            val disc = qb * qb - 4 * qa * qc
            if (disc < 0.0) return false
            val root = sqrt(disc)
            t1 = (-qb - root) / (2 * qa); t2 = (-qb + root) / (2 * qa)
        }
        return between(t1, ex, ey, wx, wy, dex, dey, dwx, dwy) || between(t2, ex, ey, wx, wy, dex, dey, dwx, dwy)
    }

    /** В момент [t] тика частица лежит на связи строго между её концами. */
    private fun between(t: Double, ex: Double, ey: Double, wx: Double, wy: Double, dex: Double, dey: Double, dwx: Double, dwy: Double): Boolean {
        if (!(t > 0.0 && t <= 1.0)) return false
        val lx = ex + t * dex; val ly = ey + t * dey
        val len2 = lx * lx + ly * ly
        if (len2 < 1e-18) return false
        val s = ((wx + t * dwx) * lx + (wy + t * dwy) * ly) / len2
        return s > 0.0 && s < 1.0
    }

    private fun cellKey(ix: Int, iy: Int): Long = (ix.toLong() shl 32) xor (iy.toLong() and 0xffffffffL)

    private fun indexFixed(st: BodyState) {
        fixedIndexed = w.fixed.size
        val m = w.fixed.size
        fixedX = DoubleArray(m); fixedY = DoubleArray(m); fixedR = DoubleArray(m); fixedParticle = IntArray(m)
        val lists = HashMap<Long, ArrayList<Int>>()
        for (k in 0 until m) {
            val p = w.world.freeParticle(w.fixed[k])
            fixedParticle[k] = p
            fixedX[k] = st.px[p].toDouble(); fixedY[k] = st.py[p].toDouble(); fixedR[k] = st.radius[p].toDouble()
            lists.getOrPut(cellKey(Math.floor(fixedX[k] / PHYSICS_PER_GAME).toInt(), Math.floor(fixedY[k] / PHYSICS_PER_GAME).toInt())) { ArrayList() }.add(k)
        }
        fixedCells.clear()
        for ((key, list) in lists) fixedCells[key] = list.toIntArray()
    }

    /** Подвижная частица в толще рельефа или глубже половины радиуса в его частице. */
    private fun checkTerrain(st: BodyState, n: Int, tick: Int) {
        if (w.fixed.isEmpty()) return
        if (fixedIndexed != w.fixed.size) indexFixed(st)
        var now = 0
        for (i in 0 until n) {
            if (st.cellDead[i] || st.restInvMass[i] == 0.0f) continue
            val x = st.px[i]; val y = st.py[i]
            val gx = x / PHYSICS_PER_GAME; val gy = y / PHYSICS_PER_GAME
            var hit = false
            // Центр внутри твёрдой клетки карты — кромки или толщи. Круги касания кромки выступают за
            // границу клетки наружу, так что центр по эту сторону границы — это частица, ушедшая в
            // рельеф глубже собственного радиуса касания.
            // Карта рельефа задана в координатах сцены; сетка неподвижных частиц ниже — в координатах мира.
            val tx = if (w.frame.still) gx else w.frame.sceneX(gx, gy); val ty = if (w.frame.still) gy else w.frame.sceneY(gx, gy)
            for (t in w.terrains) if (t.isSolid(t.cellOfX(tx), t.cellOfY(ty))) { hit = true; break }
            if (!hit) {
                val ix = Math.floor(gx).toInt(); val iy = Math.floor(gy).toInt()
                search@ for (dy in -1..1) for (dx in -1..1) {
                    val list = fixedCells[cellKey(ix + dx, iy + dy)] ?: continue
                    for (k in list) {
                        val ex = x - fixedX[k]; val ey = y - fixedY[k]
                        val limit = TERRAIN_DEPTH * fixedR[k]
                        if (ex * ex + ey * ey < limit * limit) { hit = true; break@search }
                    }
                }
            }
            if (hit) { now++; mark(Rule.TERRAIN, st, i) }
        }
        if (now > 0) { terrainTicks += now; fail(Rule.TERRAIN, tick, "частиц в рельефе: $now", now) }
    }

    private fun checkInversions(st: BodyState, tick: Int) {
        val body = st.body
        val alive = st.baked.triAlive
        val tc = body.triCount
        if (invRun.size < tc) invRun = invRun.copyOf(maxOf(tc, invRun.size * 2))
        var count = 0
        for (t in 0 until tc) {
            if (!alive[t]) { invRun[t] = 0; continue }
            val a = body.triA[t]; val b = body.triB[t]; val c = body.triC[t]
            val area = (st.px[b] - st.px[a]) * (st.py[c] - st.py[a]) - (st.py[b] - st.py[a]) * (st.px[c] - st.px[a])
            if (area <= 0.0) {
                count++; invRun[t]++
                if (invRun[t] > invertedLongest) invertedLongest = invRun[t]
                if (invRun[t] == INVERSION_TICKS + 1) {
                    fail(Rule.INVERSION, tick, "треугольник вывернут дольше $INVERSION_TICKS тиков")
                    mark(Rule.INVERSION, st, a)
                }
            } else invRun[t] = 0
        }
        if (count > invertedPeak) invertedPeak = count
    }

    private fun checkBone(st: BodyState, ref: BoneRef, tick: Int) {
        val first = ref.body.first
        var worst = 0.0; var at = -1
        for (k in ref.a.indices) {
            val i = first + ref.a[k]; val j = first + ref.b[k]
            // Погибшая или ещё не включённая клетка в кость не входит: её расстояния не судятся.
            if (st.cellDead[i] || st.cellDead[j] || st.boneOf[i] < 0 || st.boneOf[j] < 0) continue
            val dx = st.px[i] - st.px[j]; val dy = st.py[i] - st.py[j]
            val strain = abs(sqrt(dx * dx + dy * dy) / ref.rest[k] - 1.0)
            if (strain > worst) { worst = strain; at = i }
        }
        if (worst > boneWorst) boneWorst = worst
        if (worst > BONE_STRAIN) {
            fail(Rule.BONES, tick, "кость искажена на %.0f%%".format(worst * 100))
            if (at >= 0) mark(Rule.BONES, st, at)
        }
    }

    /** Конец прогона: правила, которые судятся по итогу. */
    fun finish() {
        if (finished) return
        finished = true
        val tick = w.tick
        val st = w.world.state

        results[Rule.CALM]!!.pending = false
        val last = minOf(tick, ticks)
        fun mean(from: Int, to: Int): Double {
            var sum = 0.0; var cnt = 0
            for (t in maxOf(1, from)..to) { sum += speed[t]; cnt++ }
            return if (cnt > 0) sum / cnt else 0.0
        }
        // Всё спит — успокоилась по определению: в окно ещё попадает движение до сна.
        endSpeed = if (awake == 0) 0.0 else mean(last - CALM_WINDOW + 1, last)
        val before = mean(last - 2 * CALM_WINDOW + 1, last - CALM_WINDOW)
        if (endSpeed > CALM_SPEED && !(last >= 2 * CALM_WINDOW && endSpeed <= CALM_DECAY * before))
            fail(Rule.CALM, tick, "в конце движется и не затухает: %.4f связи за тик (окном раньше %.4f)".format(endSpeed, before))
        // Разрушение обязано кончиться вместе с ударом: ткань, которая продолжает рваться и в
        // последнем окне сцены, разрушает себя сама.
        if (lastTearTick > last - CALM_WINDOW && last >= 4 * CALM_WINDOW && w.scriptEnd < last - 2 * CALM_WINDOW)
            fail(Rule.CALM, tick, "ткань рвётся до самого конца сцены: последний разрыв на тике $lastTearTick")

        results[Rule.SLEEP]!!.pending = false
        // Оторванная клетка или обломок тормозятся одной изотропной средой и плывут тысячи тиков:
        // если допуски разрешили повреждение и оно случилось, сна от сцены не требуем — хватает затухания.
        val damaged = (torn > 0 && limits.tornShare > 0.0) || (died - w.scriptedDead > 0 && limits.deadShare > 0.0)
        if (awake > 0 && !damaged) fail(Rule.SLEEP, tick, "не спит частиц: $awake")

        results[Rule.SHAPE]!!.pending = false
        if (limits.shape) for (ref in shapes) checkShape(st, ref, tick)

        // Растущее тело: его кости судятся по итогу — выросли ли они в задуманную форму.
        for (ref in bones) if (ref.body.growth != null) checkBone(st, ref, tick)

        results[Rule.PREMISE]!!.pending = false
        if (w.premises.isEmpty() && w.idleReason == null)
            fail(Rule.PREMISE, tick, "постановка не сказала, что в сцене обязано случиться")
        for (p in w.premises) {
            if (!p.forbidden && p.tick < 0) fail(Rule.PREMISE, tick, "не случилось: ${p.text}")
            if (p.forbidden && p.tick >= 0) fail(Rule.PREMISE, p.tick, "случилось, а по постановке не должно: ${p.text}")
        }

        results[Rule.EXPECT]!!.pending = false
        for (b in w.bodies) {
            val g = b.growth ?: continue
            // Там, где допуски разрешают гибель (толпа на большой скорости), клетке может оказаться негде родиться:
            // её родительница и соседки погибли. Это следствие удара, а не ошибка роста.
            // То же, когда клетки убил сам сценарий (гибель посреди роста): дочерям погибших родиться негде.
            if (!g.done || (g.skipped > 0 && limits.deadShare <= 0.0 && w.scriptedDead == 0))
                fail(Rule.EXPECT, tick, "родились не все клетки: осталось ${g.order.order.size - g.next}, негде родиться ${g.skipped}")
        }
        for (e in w.expectations) {
            val values = samples[e.probe]
            if (values == null) { fail(Rule.EXPECT, tick, "${e.text}: нет пробы ${e.probe}"); continue }
            var best = if (e.useMin) Double.MAX_VALUE else -Double.MAX_VALUE
            for (t in maxOf(1, e.from)..minOf(e.to, minOf(tick, ticks))) {
                val v = values[t]
                if (v.isNaN()) continue
                if (e.useMin) { if (v < best) best = v } else if (v > best) best = v
            }
            val ok = if (e.atMost) best <= e.bound else best >= e.bound
            // Пороги сохранения — миллионные доли: три знака после запятой показали бы «вышло 0,000, нужно не больше 0,000».
            val form = if (abs(e.bound) < 0.01 && e.bound != 0.0) "%s: вышло %.2e, нужно %s %.2e" else "%s: вышло %.3f, нужно %s %.3f"
            if (!ok) fail(Rule.EXPECT, tick, form.format(e.text, best, if (e.atMost) "не больше" else "не меньше", e.bound))
        }
    }

    private fun checkShape(st: BodyState, ref: ShapeRef, tick: Int) {
        val b = ref.body
        val first = b.first
        // Тело с погибшими клетками или сокращённой мышцей к исходной форме не возвращается — не судится.
        for (k in 0 until b.count) if (st.cellDead[first + k]) return
        for (c in b.command) if (c != 1f) return
        var worst = 0.0; var at = -1
        for (k in ref.linkRest.indices) {
            val i = first + b.def.linkA[k]; val j = first + b.def.linkB[k]
            val e = st.baked.edgeOf(i, j)
            if (e < 0 || st.baked.edgeDead[e]) return   // порванное тело: форма не судится
            val dx = st.px[i] - st.px[j]; val dy = st.py[i] - st.py[j]
            val strain = abs(sqrt(dx * dx + dy * dy) / ref.linkRest[k] - 1.0)
            if (strain > worst) { worst = strain; at = i }
        }
        if (worst > shapeWorst) shapeWorst = worst
        if (worst > SHAPE_STRAIN) {
            fail(Rule.SHAPE, tick, "%s: связь отличается от исходной на %.0f%%".format(b.shape.name, worst * 100))
            if (at >= 0) mark(Rule.SHAPE, st, at)
        }
        var far = 0.0
        for (k in ref.farRest.indices) {
            val i = first + ref.farA[k]; val j = first + ref.farB[k]
            val dx = st.px[i] - st.px[j]; val dy = st.py[i] - st.py[j]
            val strain = abs(sqrt(dx * dx + dy * dy) / ref.farRest[k] - 1.0)
            if (strain > far) far = strain
        }
        if (far > poseWorst) poseWorst = far
        if (far > POSE_STRAIN) fail(Rule.SHAPE, tick, "%s: поза искажена на %.0f%%".format(b.shape.name, far * 100))
    }

    /** Пакетный прогон сообщает итог сверки повтора. */
    fun judgeRepeat(rule: Rule, divergedAt: Int) {
        val r = results[rule]!!
        r.applies = true
        if (divergedAt >= 0) { r.violations = 1; r.firstTick = divergedAt; r.text = "разошлось на тике $divergedAt" }
    }

    val passed: Boolean get() = results.values.none { it.failed }
    val failedRules: List<Rule> get() = results.values.filter { it.failed }.map { it.rule }
}
