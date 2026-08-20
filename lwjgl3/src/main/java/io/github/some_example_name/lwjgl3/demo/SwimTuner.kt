package io.github.some_example_name.lwjgl3.demo

import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ПОДБОР ПАРАМЕТРОВ ПЛАВАНИЯ эволюционным поиском.
 *
 * Ищет режим, в котором тело за счёт гребков плывёт максимально быстро И при этом
 * не блуждает. Второе — не пожелание, а жёсткое условие: любой прирост скорости,
 * купленный паразитным дрейфом, штрафом съедается.
 *
 * ЧЕТЫРЕ МЕТРИКИ НА КАНДИДАТА
 * ---------------------------
 *   speed       путь центра масс за целое число циклов гребка, делённый на время.
 *               Берётся ЧИСТОЕ СМЕЩЕНИЕ, а не длина траектории: нам нужно, чтобы тело
 *               куда-то приплыло, а не дёргалось на месте.
 *
 *   glide       какую долю своей скорости тело сохраняет, когда гребки прекратились.
 *               Это и есть тот самый эффект «несёт разогнанный поток», ради которого
 *               память среды вообще нужна. 1.0 — накат идёт с той же скоростью,
 *               0.0 — тело встало как вкопанное.
 *
 *   residualP   импульс тела через 25 секунд после последнего гребка. У честной
 *               диссипативной системы обязан уйти в ноль. Если он вышел на полку —
 *               значит что-то подкачивает тело из ничего. Именно так выглядел
 *               исходный баг: 6.02 -> 4.58 -> 4.14 и дальше не падает.
 *
 *   restDrift   смещение центра масс за 20 секунд, когда мышцы НЕ ТРОГАЛИ ни разу.
 *               Тело в позе покоя обязано стоять. Ловит утечки решателя.
 *
 *   rotKick     импульс, который среда выдаёт телу за оборот на ЧИСТОМ жёстком
 *               вращении, в единицах скорости. Обязан быть нулём: сила от среды в
 *               системе тела постоянна, в мировой крутится, за оборот интеграл
 *               сворачивается. Ловит память среды, привязанную к геометрии.
 *
 * ОЦЕНКА
 * ------
 *      score = speed * (1 + Wglide * glide) / (1 + Wp*residualP + Wr*restDrift + Wk*rotKick)
 *
 * Числитель — то, что нужно; знаменатель — то, чем нельзя за это платить. Веса
 * задаются аргументами, разбор оценки печатается по каждому кандидату-лидеру, чтобы
 * было видно, за что именно он выиграл.
 *
 * ПОЧЕМУ ЭТО НЕ ОБМАНЕШЬ. Дрейф от утечек и накат от памяти среды выглядят на глаз
 * одинаково, но по замеру различаются точно: накат ЗАТУХАЕТ (residualP -> 0), а
 * утечка выходит на полку. Плюс restDrift и rotKick меряют движение при полном
 * отсутствии гребков, где законной тяги нет вообще ни грамма.
 *
 * ЗАПУСК
 *      gradlew :lwjgl3:swimTune
 *      gradlew :lwjgl3:swimTune --args="--pop 64 --gen 60"
 */

// =====================================================================
//  ГЕНЫ
// =====================================================================

/**
 * Один подбираемый параметр. Ген хранится нормированным в [0,1], а разворачивается
 * в значение по своей шкале: положительные физические величины — логарифмически,
 * потому что осмысленный диапазон у них в разы, а не в единицах.
 */
private class Gene(
    val name: String,
    val lo: Double,
    val hi: Double,
    val log: Boolean = true,
    val integer: Boolean = false,
) {
    fun decode(g: Double): Double {
        val t = g.coerceIn(0.0, 1.0)
        val v = if (log) exp(ln(lo) + t * (ln(hi) - ln(lo))) else lo + t * (hi - lo)
        return if (integer) v.roundToInt().toDouble() else v
    }

    fun encode(v: Double): Double {
        val c = v.coerceIn(lo, hi)
        return if (log) (ln(c) - ln(lo)) / (ln(hi) - ln(lo)) else (c - lo) / (hi - lo)
    }
}

/** Какую модель памяти среды ищем. Меняется аргументом --flow. */
private var SEARCH_FLOW_MODEL = FlowModel.GLOBAL_TRACKING

private val GENES = listOf(
    Gene("normalDrag", 1.0, 400.0),
    Gene("normalDragQuadratic", 0.2, 30000.0),
    Gene("mediumDrag", 0.0002, 3.0),
    Gene("viscosity", 5.0, 1500.0),
    Gene("flowMass", 0.02, 30.0),
    Gene("flowDecay", 0.02, 30.0),
    Gene("muscleContraction", 0.15, 0.92, log = false),
    Gene("muscleRateContract", 1.0, 120.0),
    Gene("muscleRateRelax", 0.2, 60.0),
    Gene("gaitPeriod", 8.0, 900.0, integer = true),
    Gene("gaitDuty", 0.05, 0.85, log = false),
    Gene("flowEntrain", 0.03, 60.0),
    // Изгиб границы. Диапазон широкий и включает «почти выключено»: стенд FoldRecovery
    // показал, что жёсткий изгиб съедает до 73% тяги, так что поиску нужна свобода
    // отказаться от него, если он не окупается.
    Gene("bendCompliance", 1e-6, 1e-1),
)

/**
 * SOFT_COMPLIANCE и AREA_COMPLIANCE в поиск НЕ входят намеренно.
 *
 * Это не параметры существа, а параметры честности счёта, и они уже подобраны по
 * своим замерам. Пустив их в поиск, я дал бы алгоритму возможность вернуть ноль:
 * там «тяга» по метрике больше, потому что утечка гонит тело вперёд. Штрафы это
 * поймали бы, но проверять устойчивость штрафов лучше отдельно, а не смешивая с
 * подбором гидродинамики.
 */
private fun decode(g: DoubleArray): SwimParams {
    fun v(i: Int) = GENES[i].decode(g[i])
    return SwimParams(
        normalDrag = v(0),
        normalDragQuadratic = v(1),
        mediumDrag = v(2),
        viscosity = v(3),
        flowMass = v(4),
        flowDecay = v(5),
        muscleContraction = v(6),
        muscleRateContract = v(7),
        muscleRateRelax = v(8),
        gaitPeriod = v(9).toInt().coerceAtLeast(4),
        gaitDuty = v(10),
        flowEntrain = v(11),
        bendCompliance = v(12),
        flowModel = SEARCH_FLOW_MODEL,
    )
}

private fun encode(p: SwimParams): DoubleArray = doubleArrayOf(
    GENES[0].encode(p.normalDrag),
    GENES[1].encode(p.normalDragQuadratic),
    GENES[2].encode(p.mediumDrag),
    GENES[3].encode(p.viscosity),
    GENES[4].encode(p.flowMass),
    GENES[5].encode(p.flowDecay),
    GENES[6].encode(p.muscleContraction),
    GENES[7].encode(p.muscleRateContract),
    GENES[8].encode(p.muscleRateRelax),
    GENES[9].encode(p.gaitPeriod.toDouble()),
    GENES[10].encode(p.gaitDuty),
    GENES[11].encode(p.flowEntrain),
    GENES[12].encode(if (p.bendCompliance > 0) p.bendCompliance else 1e-1),
)

// =====================================================================
//  ЗАМЕР
// =====================================================================

private class Metrics(
    val speed: Double,
    val glide: Double,
    val residualP: Double,
    /** Он же на 25 секундах: пара к позднему, по ней видно, затухает или стоит. */
    val residualP25: Double,
    val restDrift: Double,
    val rotKick: Double,
    /** Размах активации мышцы в замерном окне: 0 — гребка нет, поза зажата. */
    val swing: Double,
    /** Доля ВЫВЕРНУТЫХ треугольников на пике. Порча формы, назад не выворачивается. */
    val inverted: Double,
    val valid: Boolean,
) {
    fun score(w: Weights): Double {
        if (!valid) return 0.0
        val bonus = 1.0 + w.glide * min(glide, 1.5)
        val penalty = 1.0 + w.residual * residualP + w.rest * restDrift + w.rot * rotKick +
            w.inverted * inverted
        return speed * bonus / penalty
    }
}

private class Weights(
    val glide: Double = 0.5,
    val residual: Double = 3.0,
    val rest: Double = 10.0,
    val rot: Double = 1.0,
    /** Вес вывернутых треугольников. Большой намеренно: это порча формы, а не шум. */
    val inverted: Double = 50.0,
)

private const val DT = 1.0 / 144.0
private const val SUBSTEPS = 4

private class Evaluator(val topo: Topology) {
    private val local = ThreadLocal.withInitial { SwimSolver(topo, SwimParams()) }

    fun measure(p: SwimParams): Metrics {
        val s = local.get()
        s.p = p

        // --- 1. ПЛАВАНИЕ, В УСТОЙЧИВОМ РЕЖИМЕ. ---
        //
        // Сначала РАЗГОН, и только потом замер. Без разгона метрику легко обмануть, и
        // первый прогон поиска её обманул: он нашёл MUSCLE_RATE_RELAX = 0.4 при периоде
        // 16 кадров, то есть мышца не успевала разжаться за возвратную фазу вообще, плюс
        // MEDIUM_DRAG = 0.0085. Гребка не было ни одного — было одно сокращение и
        // свободный выкат почти без торможения, а замер записал это в «скорость».
        //
        // Замер после разгона такое не берёт: разовый толчок за 10 секунд успевает
        // погаснуть, и в среднюю скорость попадает только то, что гребки поддерживают
        // ПОСТОЯННО. Считается по целому числу циклов, чтобы фаза не шумела.
        val period = p.gaitPeriod
        val warmupFrames = ((6.0 / DT / period).toInt().coerceAtLeast(1)) * period
        val measureCycles = (10.0 / (period * DT)).roundToInt().coerceAtLeast(1)
        val measureFrames = measureCycles * period

        s.reset()
        for (fr in 1..warmupFrames) {
            s.frame(DT, SUBSTEPS, gait = true)
            if (fr % 256 == 0 && s.checkDiverged()) return invalid()
        }
        if (s.checkDiverged()) return invalid()

        val sx = s.comX(); val sy = s.comY()
        // Размах активации — диагностика того, что гребок ЕСТЬ, а не поза зажата.
        var actMin = 1.0; var actMax = 0.0
        // Вывернутые треугольники: доля от общего числа, пик за замерное окно.
        var invPeak = 0
        for (fr in 1..measureFrames) {
            s.frame(DT, SUBSTEPS, gait = true)
            val a = s.activation0()
            if (a < actMin) actMin = a
            if (a > actMax) actMax = a
            val inv = s.countInverted()
            if (inv > invPeak) invPeak = inv
            if (fr % 256 == 0 && s.checkDiverged()) return invalid()
        }
        if (s.checkDiverged()) return invalid()
        val swimDx = s.comX() - sx; val swimDy = s.comY() - sy
        val speed = sqrt(swimDx * swimDx + swimDy * swimDy) / (measureFrames * DT)
        val swing = actMax - actMin
        val inverted = invPeak.toDouble() / topo.triCount

        // --- 2. НАКАТ. Гребки прекращены, 3 секунды. ---
        val coastFrames = (3.0 / DT).toInt()
        val cx = s.comX(); val cy = s.comY()
        for (fr in 1..coastFrames) s.frame(DT, SUBSTEPS, gait = false)
        if (s.checkDiverged()) return invalid()
        val coastDx = s.comX() - cx; val coastDy = s.comY() - cy
        val coastDist = sqrt(coastDx * coastDx + coastDy * coastDy)
        // Доля сохранённой скорости. Если тело почти не плыло, доля не определена.
        val glide = if (speed > 1e-6) coastDist / (speed * (coastFrames * DT)) else 0.0

        // --- 3. ОСТАНОВКА. Импульс обязан уйти в ноль. ---
        //
        // Мерится ДВАЖДЫ, и это не роскошь. При малом сопротивлении среды тело
        // законно катится очень долго, и один замер не отличает «медленно затухает»
        // от «вышло на полку» — а это ровно та разница, которую мы и ловим.
        // В штраф идёт ПОЗДНИЙ замер: к 50 секундам законный накат уже кончился.
        val settleFrames = (25.0 / DT).toInt()
        for (fr in 1..settleFrames) s.frame(DT, SUBSTEPS, gait = false)
        if (s.checkDiverged()) return invalid()
        val residualP25 = s.momentum()
        for (fr in 1..settleFrames) s.frame(DT, SUBSTEPS, gait = false)
        if (s.checkDiverged()) return invalid()
        val residualP = s.momentum()

        // --- 4. ПОКОЙ. Мышцы не трогали ни разу. ---
        s.reset()
        val rx = s.comX(); val ry = s.comY()
        val restFrames = (20.0 / DT).toInt()
        for (fr in 1..restFrames) s.frame(DT, SUBSTEPS, gait = false)
        if (s.checkDiverged()) return invalid()
        val rdx = s.comX() - rx; val rdy = s.comY() - ry
        val restDrift = sqrt(rdx * rdx + rdy * rdy) / topo.meanLinkLength

        // --- 5. ЖЁСТКОЕ ВРАЩЕНИЕ. Только среда, решателя нет. ---
        val revFrames = 452
        val omega = 2.0 * Math.PI / (revFrames * DT)
        s.reset()
        val ccx = s.comX(); val ccy = s.comY()
        var ang = 0.0
        var impX = 0.0; var impY = 0.0
        val h = DT / SUBSTEPS
        for (fr in 1..(revFrames * 2)) {
            ang += omega * DT
            s.setRigidRotation(ang, omega, ccx, ccy)
            val b0 = s.momX(); val b1 = s.momY()
            for (sub in 0 until SUBSTEPS) s.dragOnly(h)
            impX += s.momX() - b0; impY += s.momY() - b1
        }
        val rotKick = sqrt(impX * impX + impY * impY) / topo.n

        if (speed.isNaN() || glide.isNaN() || residualP.isNaN() ||
            restDrift.isNaN() || rotKick.isNaN() || swing.isNaN()
        ) return invalid()

        return Metrics(speed, glide, residualP, residualP25, restDrift, rotKick, swing, inverted, valid = true)
    }

    private fun invalid() = Metrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, valid = false)
}

// =====================================================================
//  СВЕРКА КОПИИ С НАСТОЯЩИМ ДЕМО
// =====================================================================

/**
 * Копия конвейера обязана совпасть с RealBodyDemo ПОБИТОВО при значениях констант
 * демо и flowModel = PER_EDGE. Иначе подбор оптимизирует не то, что потом поедет.
 */
private fun verifyAgainstDemo(topo: Topology, path: String): Double {
    val P = Probe
    P.boot(path)
    P.resetState()

    val ref = SwimParams(
        normalDrag = P.const("NORMAL_DRAG"),
        normalDragQuadratic = P.const("NORMAL_DRAG_QUADRATIC"),
        mediumDrag = P.const("MEDIUM_DRAG"),
        viscosity = P.const("VISCOSITY"),
        flowDecay = P.const("FLOW_DECAY"),
        flowEntrain = P.const("FLOW_ENTRAIN"),
        flowMass = P.const("FLOW_MASS"),
        muscleContraction = P.const("MUSCLE_CONTRACTION"),
        muscleRateContract = P.const("MUSCLE_RATE_CONTRACT"),
        muscleRateRelax = P.const("MUSCLE_RATE_RELAX"),
        softCompliance = P.const("SOFT_COMPLIANCE"),
        areaCompliance = P.const("AREA_COMPLIANCE"),
        areaComplianceInverted = P.const("AREA_COMPLIANCE_INVERTED"),
        areaMaxStep = P.const("AREA_MAX_STEP"),
        areaSmoothRamp = P.constBool("AREA_SMOOTH_RAMP"),
        // Изгиб контура в демо переключается клавишей, то есть это состояние, а не
        // константа. Читаем ТЕКУЩЕЕ значение, иначе сверка проверяла бы не тот режим.
        bendCompliance = P.bendCompliance(),
        flowModel = FlowModel.GLOBAL_TRACKING,
    )
    val s = SwimSolver(topo, ref)
    s.reset()

    var maxDiff = 0.0
    var firstBad = -1
    var firstBadDiff = 0.0
    for (fr in 1..400) {
        P.frame(DT, SUBSTEPS, contract = true)
        s.frameHoldMuscle0(DT, SUBSTEPS, hold = true)
        var frameDiff = 0.0
        for (i in 0 until P.n) {
            frameDiff = maxOf(frameDiff, abs(P.px[i] - s.px[i]).toDouble())
            frameDiff = maxOf(frameDiff, abs(P.py[i] - s.py[i]).toDouble())
            frameDiff = maxOf(frameDiff, abs(P.vx[i] - s.vx[i]).toDouble())
            frameDiff = maxOf(frameDiff, abs(P.vy[i] - s.vy[i]).toDouble())
        }
        // Первый кадр с расхождением — самая полезная улика. Разошлись на первом же
        // кадре и крупно — стадии РАЗНЫЕ. Совпадали сотню кадров и поехали потихоньку —
        // отличается один литерал или порядок операций, а раздула это уже сама система.
        if (firstBad < 0 && frameDiff > 0.0) { firstBad = fr; firstBadDiff = frameDiff }
        maxDiff = maxOf(maxDiff, frameDiff)
    }
    if (firstBad > 0) println("  впервые разошлись на кадре $firstBad, расхождение там ${sci(firstBadDiff)}")
    return maxDiff
}

// =====================================================================
//  ЭВОЛЮЦИЯ
// =====================================================================

private class Individual(val g: DoubleArray) {
    var m: Metrics? = null
    var score = 0.0
}

private fun fmt(v: Double, d: Int = 4) = String.format(Locale.ROOT, "%.${d}f", v)
private fun sci(v: Double) = String.format(Locale.ROOT, "%.3e", v)

private fun reportRow(tag: String, m: Metrics, score: Double): String = String.format(
    Locale.ROOT,
    "%-26s | %8.5f | %6.3f | %9.3e | %9.3e | %9.3e | %9.3e | %5.3f | %5.1f%% | %9.5f",
    tag, m.speed, m.glide, m.residualP25, m.residualP, m.restDrift, m.rotKick, m.swing,
    m.inverted * 100.0, score
)

private const val HEADER =
    "                           |   скор.  | накат |  |P| 25c  |  |P| 50c  | покой дрф | пов.толч. | размх | вывер |    оценка"

fun main(args: Array<String>) {
    var path = "body-export.txt"
    var pop = 48
    var gens = 40
    var seed = 12345L
    val w = run {
        var glide = 0.5; var residual = 3.0; var rest = 10.0; var rot = 1.0; var inverted = 50.0
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--pop" -> pop = args[++i].toInt()
                "--gen" -> gens = args[++i].toInt()
                "--seed" -> seed = args[++i].toLong()
                "--w-glide" -> glide = args[++i].toDouble()
                "--w-residual" -> residual = args[++i].toDouble()
                "--w-rest" -> rest = args[++i].toDouble()
                "--w-rot" -> rot = args[++i].toDouble()
                "--w-inverted" -> inverted = args[++i].toDouble()
                "--flow" -> SEARCH_FLOW_MODEL = FlowModel.valueOf(args[++i].uppercase())
                else -> if (!args[i].startsWith("--")) path = args[i]
            }
            i++
        }
        Weights(glide, residual, rest, rot, inverted)
    }

    val topo = Topology.load(path)
    println()
    println("=== ПОДБОР ПАРАМЕТРОВ ПЛАВАНИЯ ===")
    println("тело: n=${topo.n} links=${topo.conCount} tris=${topo.triCount} " +
        "boundary=${topo.boundCount} bones=${topo.rigidBones.size} muscles=${topo.muscleCount}")
    println("популяция $pop, поколений $gens, seed $seed, модель среды $SEARCH_FLOW_MODEL")
    println("веса: накат ${w.glide}, ост.импульс ${w.residual}, покой ${w.rest}, " +
        "поворот ${w.rot}, вывернутые ${w.inverted}")

    // --- сверка копии ---
    val diff = verifyAgainstDemo(topo, path)
    println()
    println("сверка копии конвейера с RealBodyDemo (400 кадров, GLOBAL_TRACKING): " +
        "max расхождение = ${sci(diff)}" +
        if (diff == 0.0) "  ПОБИТОВО СОВПАЛО" else "  ВНИМАНИЕ: копия расходится с демо")
    if (diff != 0.0) {
        println("подбор остановлен: оптимизировать копию, которая ведёт себя иначе, бессмысленно")
        return
    }

    val ev = Evaluator(topo)
    val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(16)
    val poolExec = Executors.newFixedThreadPool(threads)
    println("потоков: $threads")

    fun evaluateAll(list: List<Individual>) {
        val tasks = list.filter { it.m == null }.map { ind ->
            java.util.concurrent.Callable {
                ind.m = ev.measure(decode(ind.g))
                ind.score = ind.m!!.score(w)
            }
        }
        poolExec.invokeAll(tasks)
    }

    // --- опорные точки: как сейчас в демо (память среды по рёбрам) и она же
    //     с общим запасом среды. Обе считаются вне поиска, для сравнения. ---
    // ОПОРНАЯ ТОЧКА — значения, которые стояли в демо ДО подбора, вместе со старой
    // моделью памяти среды. Это то, с чем сравнивается всё остальное; не путать с
    // значениями по умолчанию SwimParams, которые теперь равны НАЙДЕННЫМ.
    val demoParams = SwimParams(
        normalDrag = 15.0, normalDragQuadratic = 900.0, mediumDrag = 0.1, viscosity = 200.0,
        flowEntrain = 0.2, flowDecay = 1.0,
        muscleContraction = 0.4, muscleRateContract = 25.0, muscleRateRelax = 1.0,
        gaitPeriod = 480, gaitDuty = 1.0 / 3.0,
        flowModel = FlowModel.PER_EDGE,
    )
    val mDemo = ev.measure(demoParams)
    val mDemoNoFlow = ev.measure(demoParams.copy(flowModel = FlowModel.NONE))
    val mDemoGlobal = ev.measure(demoParams.copy(flowModel = FlowModel.GLOBAL_RESERVOIR, flowMass = 1.0))
    val mDemoTrack = ev.measure(demoParams.copy(flowModel = FlowModel.GLOBAL_TRACKING, flowMass = 1.0))

    println()
    println("=== ОПОРНЫЕ ТОЧКИ (параметры демо, меняется ТОЛЬКО модель памяти среды) ===")
    println(HEADER)
    println(reportRow("память по рёбрам (было)", mDemo, mDemo.score(w)))
    println(reportRow("памяти нет вовсе", mDemoNoFlow, mDemoNoFlow.score(w)))
    println(reportRow("общий запас (от рёбер)", mDemoGlobal, mDemoGlobal.score(w)))
    println(reportRow("общий запас (от ЦМ)", mDemoTrack, mDemoTrack.score(w)))

    // --- эволюция ---
    val rnd = Random(seed)
    var population = ArrayList<Individual>(pop)
    // Нулевое поколение включает точку демо, чтобы поиск заведомо стартовал не хуже неё.
    population.add(Individual(encode(demoParams.copy(flowModel = SEARCH_FLOW_MODEL))))
    while (population.size < pop) {
        population.add(Individual(DoubleArray(GENES.size) { rnd.nextDouble() }))
    }
    evaluateAll(population)

    var best = population.maxByOrNull { it.score }!!
    println()
    println("=== ЭВОЛЮЦИЯ ===")
    println("покол. |  лучшая  |  медиана | брак |   скор.  | накат |  ост. |P|  | пов.толч.")

    fun tournament(): Individual {
        var a = population[rnd.nextInt(population.size)]
        for (k in 0 until 2) {
            val b = population[rnd.nextInt(population.size)]
            if (b.score > a.score) a = b
        }
        return a
    }

    for (gen in 1..gens) {
        val next = ArrayList<Individual>(pop)
        // Элитизм: два лучших переезжают без изменений, вместе с готовым замером.
        population.sortedByDescending { it.score }.take(2).forEach {
            val cl = Individual(it.g.copyOf()); cl.m = it.m; cl.score = it.score
            next.add(cl)
        }
        while (next.size < pop) {
            val p1 = tournament(); val p2 = tournament()
            val child = DoubleArray(GENES.size)
            for (k in child.indices) {
                // BLX-0.5: потомок берётся из интервала родителей, расширенного наружу.
                val lo = minOf(p1.g[k], p2.g[k]); val hi = maxOf(p1.g[k], p2.g[k])
                val d = hi - lo
                child[k] = (lo - 0.5 * d + rnd.nextDouble() * (d * 2.0)).coerceIn(0.0, 1.0)
                if (rnd.nextDouble() < 0.30) {
                    // Мутация гауссова по НОРМИРОВАННОМУ гену, поэтому для
                    // логарифмических параметров это множитель, а не добавка.
                    child[k] = (child[k] + rnd.nextDouble(-1.0, 1.0) * 0.12).coerceIn(0.0, 1.0)
                }
            }
            next.add(Individual(child))
        }
        evaluateAll(next)
        population = next

        val cur = population.maxByOrNull { it.score }!!
        if (cur.score > best.score) { best = Individual(cur.g.copyOf()).also { it.m = cur.m; it.score = cur.score } }
        val sorted = population.map { it.score }.sorted()
        val median = sorted[sorted.size / 2]
        val bad = population.count { it.m?.valid != true }
        val bm = best.m!!
        println(String.format(Locale.ROOT,
            "%6d | %8.5f | %8.5f | %4d | %8.5f | %5.3f | %9.3e | %9.3e",
            gen, best.score, median, bad, bm.speed, bm.glide, bm.residualP, bm.rotKick))
    }
    poolExec.shutdown()
    poolExec.awaitTermination(1, TimeUnit.MINUTES)

    // --- итог ---
    val bp = decode(best.g)
    val bm = best.m!!
    println()
    println("=== ИТОГ ===")
    println(HEADER)
    println(reportRow("память по рёбрам (было)", mDemo, mDemo.score(w)))
    println(reportRow("памяти нет вовсе", mDemoNoFlow, mDemoNoFlow.score(w)))
    println(reportRow("общий запас (от рёбер)", mDemoGlobal, mDemoGlobal.score(w)))
    println(reportRow("общий запас (от ЦМ)", mDemoTrack, mDemoTrack.score(w)))
    println(reportRow("НАЙДЕНО", bm, best.score))
    println()
    // Остаточный импульс НЕЛЬЗЯ сравнивать в лоб: у быстрого пловца его просто больше,
    // потому что он больше набрал. Честный показатель — во сколько раз он падает с 25-й
    // секунды к 50-й. У утечки этот множитель около единицы (стоит на полке), у честного
    // наката он большой.
    fun decay(m: Metrics) = if (m.residualP > 1e-12) m.residualP25 / m.residualP else Double.NaN
    println(String.format(Locale.ROOT,
        "скорость в %.2f раза выше текущего демо, накат в %.1f раза длиннее, " +
            "дрейф в покое в %.0f раз меньше, толчок на повороте в %.0f раз меньше",
        if (mDemo.speed > 0) bm.speed / mDemo.speed else 0.0,
        if (mDemo.glide > 0) bm.glide / mDemo.glide else 0.0,
        if (bm.restDrift > 0) mDemo.restDrift / bm.restDrift else Double.POSITIVE_INFINITY,
        if (bm.rotKick > 0) mDemo.rotKick / bm.rotKick else Double.POSITIVE_INFINITY))
    println(String.format(Locale.ROOT,
        "затухание импульса с 25-й по 50-ю секунду: было в %.2f раза, стало в %.2f раза " +
            "(чем больше, тем честнее — единица означает полку)",
        decay(mDemo), decay(bm)))

    println()
    println("=== НАЙДЕННЫЕ ЗНАЧЕНИЯ (готово к переносу в RealBodyDemo) ===")
    println("        private const val NORMAL_DRAG = ${fmt(bp.normalDrag.toDouble(), 2)}f")
    println("        private const val NORMAL_DRAG_QUADRATIC = ${fmt(bp.normalDragQuadratic.toDouble(), 1)}f")
    println("        private const val MEDIUM_DRAG = ${fmt(bp.mediumDrag.toDouble(), 4)}f")
    println("        private const val VISCOSITY = ${fmt(bp.viscosity.toDouble(), 1)}f")
    println("        private const val FLOW_MASS = ${fmt(bp.flowMass.toDouble(), 3)}f")
    println("        private const val FLOW_ENTRAIN = ${fmt(bp.flowEntrain.toDouble(), 3)}f")
    println("        private const val FLOW_DECAY = ${fmt(bp.flowDecay.toDouble(), 3)}f")
    println("        private const val MUSCLE_CONTRACTION = ${fmt(bp.muscleContraction.toDouble(), 3)}f")
    println("        private const val MUSCLE_RATE_CONTRACT = ${fmt(bp.muscleRateContract.toDouble(), 2)}f")
    println("        private const val MUSCLE_RATE_RELAX = ${fmt(bp.muscleRateRelax.toDouble(), 3)}f")
    println("        private const val GAIT_PERIOD = ${bp.gaitPeriod}")
    println("        private const val GAIT_DUTY = ${fmt(bp.gaitDuty.toDouble(), 3)}f")
    println()
}
