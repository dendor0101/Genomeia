package io.github.some_example_name.lwjgl3.demo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ПОДБОР UPS И SUBSTEPS.
 *
 * ГЛАВНОЕ, ЧТО НАДО ПОНИМАТЬ ПЕРЕД ЧТЕНИЕМ ТАБЛИЦ. Физический смысл есть только у
 * h = 1 / (UPS * SUBSTEPS). UPS и SUBSTEPS по отдельности — это способ поделить один
 * и тот же бюджет ПОДШАГОВ В СЕКУНДУ. Всё материальное зависит только от произведения,
 * а разделение решает, что будет привязано к тику: нейросеть, геном, управление.
 *
 * Отсюда два независимых замера, и путать их нельзя.
 *
 *   ЧАСТЬ А. Сколько подшагов в секунду вообще нужно. Меряется СХОДИМОСТЬ: податливость
 *   задаёт целевую жёсткость, но решатель добирается до неё за несколько подшагов, и
 *   при малом их числе ткань получается мягче, чем задано. Это и есть настоящий критерий
 *   выбора — прежний, про туннелирование, у движка с CCD больше не действующий.
 *
 *   ЧАСТЬ Б. Как делить найденный бюджет. Проверяется утверждение «важно только h»:
 *   при одном и том же произведении разные пары (UPS, SUBSTEPS) обязаны дать почти
 *   одинаковую физику. Если это так — UPS можно опускать до предела, который задаёт
 *   уже не физика, а КВАНТОВАНИЕ УПРАВЛЕНИЯ: активация мышцы меняется раз в тик.
 *
 * ЛОВУШКА, ИЗ-ЗА КОТОРОЙ ЗАМЕР ЛЕГКО ИСПОРТИТЬ. GAIT_PERIOD задан В КАДРАХ. Меняя UPS
 * и не трогая его, вы меняете период гребка В СЕКУНДАХ, то есть сравниваете разных
 * существ, а не разные настройки решателя. Здесь период держится в СЕКУНДАХ и
 * пересчитывается в кадры под каждый UPS.
 */
private const val GAIT_SECONDS = 104.0 / 144.0   // период гребка демо в секундах
private const val BODY_PERIODS = 14              // сколько циклов мерить тягу

private class Result(
    val ups: Int,
    val sub: Int,
    val residual: Double,
    val angular: Double,
    val thrust: Double,
    val coastP: Double,
    val invPeak: Int,
    val nanos: Double,
)

private fun measure(topo: Topology, ups: Int, sub: Int): Result {
    val dt = 1.0 / ups
    val period = (GAIT_SECONDS * ups).roundToInt().coerceAtLeast(2)
    val p = SwimParams(gaitPeriod = period)
    val s = SwimSolver(topo, p)

    // --- СХОДИМОСТЬ и УГЛОВАЯ УТЕЧКА: мышца держится сокращённой ---
    s.reset()
    val holdFrames = (4.0 * ups).roundToInt()
    var invPeak = 0
    for (fr in 1..holdFrames) {
        s.frameHoldMuscle0(dt, sub, hold = true)
        val inv = s.countInverted()
        if (inv > invPeak) invPeak = inv
    }
    val residual = s.linkResidual()
    val angular = abs(s.angularMomentum())

    // --- ТЯГА: разгон, потом замер по целому числу циклов ---
    s.reset()
    for (fr in 1..(4 * period)) s.frame(dt, sub, gait = true)
    val cx = s.comX(); val cy = s.comY()
    for (fr in 1..(BODY_PERIODS * period)) s.frame(dt, sub, gait = true)
    val dx = s.comX() - cx; val dy = s.comY() - cy
    val thrust = sqrt(dx * dx + dy * dy) / (BODY_PERIODS * period * dt)

    // --- ВЫБЕГ: импульс через 20 секунд после последнего гребка ---
    for (fr in 1..(20 * ups)) s.frame(dt, sub, gait = false)
    val coastP = s.momentum()

    // --- ЦЕНА: наносекунды физики на СЕКУНДУ симуляции ---
    val t = SwimSolver(topo, p)
    t.reset()
    for (fr in 1..(2 * ups)) t.frame(dt, sub, gait = true)   // прогрев
    var best = Double.MAX_VALUE
    for (a in 0 until 3) {
        val t0 = System.nanoTime()
        for (fr in 1..(3 * ups)) t.frame(dt, sub, gait = true)
        val el = (System.nanoTime() - t0) / 3.0
        if (el < best) best = el
    }

    return Result(ups, sub, residual, angular, thrust, coastP, invPeak, best)
}

private fun header() {
    println(String.format(Locale.ROOT, "%5s | %4s | %9s | %10s | %9s | %9s | %9s | %4s | %9s",
        "UPS", "sub", "подш./с", "невязка", "|L|", "тяга/с", "выбег |P|", "выв.", "мкс/с"))
}

private fun row(r: Result, mark: String = "") {
    println(String.format(Locale.ROOT,
        "%5d | %4d | %9d | %9.4f%% | %9.3e | %9.5f | %9.3e | %4d | %9.0f %s",
        r.ups, r.sub, r.ups * r.sub, r.residual * 100.0, r.angular,
        r.thrust, r.coastP, r.invPeak, r.nanos / 1000.0, mark))
}

fun main(args: Array<String>) {
    val path = if (args.isNotEmpty()) args[0] else "body-export.txt"
    val topo = Topology.load(path)

    println()
    println("=== ПОДБОР UPS И SUBSTEPS ===")
    println("тело: ${topo.n} частиц, ${topo.conCount} связей, ${topo.triCount} треугольников")
    println("период гребка держится равным ${String.format(Locale.ROOT, "%.3f", GAIT_SECONDS)} c " +
        "при любом UPS — иначе сравнивались бы разные существа")
    println()
    println("невязка — насколько связи НЕ додержали свою длину покоя, в долях связи.")
    println("Это прямая мера сходимости: мало подшагов — ткань мягче, чем задано.")
    println("мкс/с — микросекунд физики на секунду симуляции, один поток.")

    // ------------------------------------------------------------------
    //  ЧАСТЬ А. Сколько подшагов в секунду нужно.
    //  UPS зафиксирован, меняется только число подшагов.
    // ------------------------------------------------------------------
    println()
    println("=== А. СКОЛЬКО ПОДШАГОВ В СЕКУНДУ НУЖНО (UPS = 144, как в демо) ===")
    header()
    for (sub in listOf(1, 2, 4, 8, 16)) {
        row(measure(topo, 144, sub), if (sub == 4) "<- демо сейчас" else "")
    }

    // ------------------------------------------------------------------
    //  ЧАСТЬ Б. Как делить бюджет. Произведение одно и то же.
    // ------------------------------------------------------------------
    println()
    println("=== Б. ОДИН И ТОТ ЖЕ БЮДЖЕТ 576 подшагов/с, РАЗНОЕ ДЕЛЕНИЕ ===")
    println("если «важно только h» верно, все строки обязаны быть почти одинаковыми.")
    println("расхождения внизу таблицы — это уже не физика, а квантование управления.")
    header()
    for ((ups, sub) in listOf(288 to 2, 144 to 4, 72 to 8, 48 to 12, 36 to 16, 24 to 24, 18 to 32)) {
        row(measure(topo, ups, sub))
    }

    // ------------------------------------------------------------------
    //  ЧАСТЬ В. Кандидаты на рабочий режим при меньшем бюджете.
    // ------------------------------------------------------------------
    println()
    println("=== В. КАНДИДАТЫ НА РАБОЧИЙ РЕЖИМ ===")
    println("нейросеть и геном считаются раз в ТИК, поэтому чем ниже UPS, тем они дешевле.")
    header()
    for ((ups, sub) in listOf(
        60 to 4, 60 to 8, 45 to 8, 30 to 8, 30 to 16, 24 to 16, 20 to 16, 15 to 24
    )) {
        row(measure(topo, ups, sub))
    }
    println()
    println("Столбец «подш./с» — цена физики. Столбец UPS — цена ИИ и генома.")

    // ------------------------------------------------------------------
    //  ЗВОН ТКАНИ. Почему тело дрожит, когда его тянут.
    //
    //  Одну вершину смещают на две средние связи и отпускают. Дальше меряется, как
    //  затухают колебания: пиковая кинетическая энергия и сколько её остаётся через
    //  полсекунды и через две. Мышцы не активны, внешних сил нет — только ткань.
    //
    //  Проверяется подозрение на VISCOSITY. Она гасит СКОРОСТЬ ДЕФОРМАЦИИ, то есть
    //  ровно то, что звенит. В решателе она входит как k = VISCOSITY * h с потолком 0.5,
    //  и это важно: при 850 и h = 1/576 выходило 1.48, то есть потолок, максимум. При 10
    //  и h = 1/960 выходит 0.0104 — практически ничего. Разница в применённом
    //  демпфировании получается почти пятидесятикратной, хотя сама константа изменилась
    //  в 85 раз.
    // ------------------------------------------------------------------
    println()
    println("=== ЗВОН ТКАНИ ПРИ РЫВКЕ (одна вершина смещена на 2 связи и отпущена) ===")
    println(String.format(Locale.ROOT, "%-12s | %10s | %10s | %10s | %10s",
        "VISCOSITY", "k за подшаг", "пик KE", "KE 0.5 c", "KE 2 c"))

    val ups = Math.round(1.0 / DemoConst.DT).toInt()
    val sub = DemoConst.SUBSTEPS
    val h = DemoConst.DT / sub
    for (visc in listOf(10.0, 50.0, 200.0, 850.5)) {
        val s = SwimSolver(topo, SwimParams(viscosity = visc))
        s.reset()
        // Дёргаем вершину с самым дальним от центра положением — там отклик заметнее.
        var far = 0
        var best = -1.0
        for (i in 0 until topo.n) {
            val dx = s.px[i] - s.comX(); val dy = s.py[i] - s.comY()
            val d = dx * dx + dy * dy
            if (d > best) { best = d; far = i }
        }
        s.px[far] += 2.0 * topo.meanLinkLength

        var peak = 0.0
        var ke05 = 0.0
        var ke2 = 0.0
        val f05 = (0.5 * ups).toInt()
        val f2 = (2.0 * ups).toInt()
        for (fr in 1..f2) {
            s.frameHoldMuscle0(DemoConst.DT, sub, hold = false)
            val ke = s.kinetic()
            if (ke > peak) peak = ke
            if (fr == f05) ke05 = ke
        }
        ke2 = s.kinetic()
        println(String.format(Locale.ROOT, "%-12.1f | %10.4f | %10.3e | %10.3e | %10.3e",
            visc, kotlin.math.min(visc * h, 0.5), peak, ke05, ke2))
    }
    println()
    println("k за подшаг — это доля скорости деформации, снимаемая за подшаг, потолок 0.5.")
    println("Смотреть надо на него, а не на саму константу: она входит вместе с h.")
    println()
}
