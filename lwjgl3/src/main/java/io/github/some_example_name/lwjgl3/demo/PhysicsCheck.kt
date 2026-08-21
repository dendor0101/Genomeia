package io.github.some_example_name.lwjgl3.demo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * РЕГРЕССИОННАЯ ПРОВЕРКА ФИЗИКИ. Одна команда, PASS/FAIL, ненулевой код при провале.
 *
 *      gradlew :lwjgl3:physicsCheck
 *
 * ЗАЧЕМ, ЕСЛИ ЕСТЬ PhysicsAudit. Аудит печатает таблицы, которые надо ЧИТАТЬ ГЛАЗАМИ —
 * шесть штук, полторы сотни чисел. Пока правок мало, это работает; когда их много,
 * расхождение замечают через неделю по ощущению «что-то дрожит». За время работы над
 * стендом так и вышло: копия решателя трижды молча разошлась с демо, число подшагов
 * оказалось 32 вместо 16, а потолок тяги сменил смысл при смене числа подшагов. Каждое
 * из этих расхождений печаталось на экран и каждое было пропущено.
 *
 * ЧТО ЗДЕСЬ ПРОВЕРЯЕТСЯ И ЧТО НЕТ. Только величины, у которых есть ЗАКОН, а не вкус:
 * сохранение импульса, затухание, отсутствие движения из ничего. Скорость плавания и
 * ощущение от ткани сюда НЕ входят — это предмет подбора, у них нет правильного
 * значения, и порог по ним превратил бы проверку в тормоз для экспериментов.
 *
 * ПРО ПОРОГИ. Это не физические константы, а СИГНАЛИЗАЦИЯ. Взяты с запасом примерно
 * в три раза от измеренного, чтобы не срабатывать на шум одиночной траектории. Если
 * правка законно улучшила показатель — порог надо ужать, иначе он перестанет ловить.
 * Если законно ухудшила — это решение, и его надо записать здесь же, а не молча
 * поднять порог.
 */
private class Check(val name: String, val why: String) {
    var ok = true
    var detail = ""

    fun expectBelow(value: Double, limit: Double, unit: String = "") {
        if (!(value < limit)) ok = false
        detail = String.format(Locale.ROOT, "%.3e < %.3e%s", value, limit, unit)
    }

    fun expectExactly(value: Double, target: Double) {
        if (value != target) ok = false
        detail = String.format(Locale.ROOT, "%.3e == %.3e", value, target)
    }

    fun expectAtMost(value: Int, limit: Int) {
        if (value > limit) ok = false
        detail = "$value <= $limit"
    }
}

private val checks = ArrayList<Check>()

private fun check(name: String, why: String, body: Check.() -> Unit) {
    val c = Check(name, why)
    c.body()
    checks.add(c)
    println(String.format(Locale.ROOT, "  %-4s %-34s %s",
        if (c.ok) "OK" else "FAIL", name, c.detail))
    if (!c.ok) println("       ^ $why")
}

fun main(args: Array<String>) {
    val path = if (args.isNotEmpty()) args[0] else "body-export.txt"
    val P = Probe
    P.boot(path)
    val dt = P.const("DT")
    val sub = P.constInt("SUBSTEPS")
    val h = dt / sub
    val topo = Topology.load(path)

    println()
    println("=== РЕГРЕССИОННАЯ ПРОВЕРКА ФИЗИКИ ===")
    println("тело ${P.n} частиц, UPS ${Math.round(1.0 / dt)}, подшагов $sub, " +
        "итого ${Math.round(sub / dt)} подшагов/с")
    println()

    // ------------------------------------------------------------------
    //  1. Копия решателя совпадает с демо ПОБИТОВО.
    //     Самая ценная проверка: ловит любое расхождение SwimSolver с RealBodyDemo,
    //     из-за которого подбор параметров оптимизировал бы не то, что поедет.
    // ------------------------------------------------------------------
    println("--- копия решателя")
    run {
        P.resetState()
        val ref = SwimParams(flowModel = FlowModel.GLOBAL_TRACKING)
        val s = SwimSolver(topo, ref)
        s.reset()
        var maxDiff = 0.0
        for (fr in 1..200) {
            P.frame(dt, sub, contract = true)
            s.frameHoldMuscle0(dt, sub, hold = true)
            for (i in 0 until P.n) {
                maxDiff = maxOf(maxDiff, abs(P.px[i] - s.px[i]))
                maxDiff = maxOf(maxDiff, abs(P.vx[i] - s.vx[i]))
            }
        }
        check("SwimSolver == RealBodyDemo",
            "копия конвейера разошлась с демо: подбор оптимизирует не то, что поедет") {
            expectExactly(maxDiff, 0.0)
        }
    }

    // ------------------------------------------------------------------
    //  2. Тело в позе покоя стоит. Мышцы не трогали ни разу — любое движение
    //     здесь паразитное по определению.
    // ------------------------------------------------------------------
    println("--- покой")
    run {
        P.resetState()
        val cx = P.comX(); val cy = P.comY()
        for (fr in 1..(20.0 / dt).toInt()) P.frame(dt, sub, contract = false)
        val dx = P.comX() - cx; val dy = P.comY() - cy
        val drift = sqrt(dx * dx + dy * dy) / topo.meanLinkLength
        check("дрейф в покое, 20 с", "тело поехало само, без единого сокращения мышцы") {
            expectBelow(drift, 1e-2, " связи")
        }
    }

    // ------------------------------------------------------------------
    //  3. После гребков тело ОСТАНАВЛИВАЕТСЯ. Это та самая проверка, ради которой
    //     всё и затевалось: раньше импульс выходил на полку и не падал.
    // ------------------------------------------------------------------
    println("--- остановка после гребков")
    run {
        P.resetState()
        for (fr in 1..(3.0 / dt).toInt()) P.frame(dt, sub, contract = true)
        for (fr in 1..(30.0 / dt).toInt()) P.frame(dt, sub, contract = false)
        val p30 = sqrt(P.pX() * P.pX() + P.pY() * P.pY())
        for (fr in 1..(60.0 / dt).toInt()) P.frame(dt, sub, contract = false)
        val p90 = sqrt(P.pX() * P.pX() + P.pY() * P.pY())
        // Не абсолютное значение, а ЗАТУХАНИЕ: у быстрого пловца импульса просто больше,
        // и порог по модулю поймал бы улучшение тяги как регресс.
        val decay = if (p90 > 1e-300) p30 / p90 else Double.MAX_VALUE
        check("импульс падает за 30..90 с",
            "импульс вышел на полку — что-то подкачивает тело из ничего") {
            expectBelow(1.0 / decay, 1.0 / 20.0, " (затухание > 20x)")
        }
        check("остаточный |L|", "тело продолжает крутиться само") {
            expectBelow(abs(P.angMom()), 1e-2)
        }
    }

    // ------------------------------------------------------------------
    //  4. Среда не толкает тело на чистом вращении.
    // ------------------------------------------------------------------
    println("--- память среды")
    run {
        val revFrames = (2.0 * Math.PI / (2.0 * dt)).toInt()
        val omega = 2.0 * Math.PI / (revFrames * dt)
        P.resetState()
        val rx = P.comX(); val ry = P.comY()
        var ang = 0.0
        var impX = 0.0; var impY = 0.0
        for (fr in 1..(revFrames * 2)) {
            ang += omega * dt
            val cs = cos(ang); val sn = sin(ang)
            for (i in 0 until P.n) {
                val qx = P.body.x[i] - rx; val qy = P.body.y[i] - ry
                P.px[i] = rx + cs * qx - sn * qy
                P.py[i] = ry + sn * qx + cs * qy
                P.vx[i] = -omega * (P.py[i] - ry)
                P.vy[i] = omega * (P.px[i] - rx)
            }
            val b0 = P.pX(); val b1 = P.pY()
            for (s in 0 until sub) P.applyNormalDrag(h)
            impX += P.pX() - b0; impY += P.pY() - b1
        }
        val kick = sqrt(impX * impX + impY * impY) / P.n
        check("толчок среды за оборот",
            "среда выдаёт телу линейный импульс на чистом вращении — движение из ничего") {
            // Замер на текущих константах: 4.3e-02. Порог с запасом втрое.
            // Опора для сравнения: у памяти среды по рёбрам было 0.83, то есть в
            // двадцать раз хуже; ноль недостижим, пока у среды вообще есть память.
            expectBelow(kick, 1.5e-1, " скорости")
        }
    }

    // ------------------------------------------------------------------
    //  5. Утечка внутренних стадий. Все три обязаны сохранять центр масс.
    // ------------------------------------------------------------------
    println("--- утечка стадий (внешних сил нет)")
    run {
        P.resetState()
        val acc = DoubleArray(3)
        val names = arrayOf("solveConstraints", "solveAreas", "projectBone")
        for (fr in 0 until (10.0 / dt).toInt()) {
            P.muscleTarget.fill(0.0)
            if (P.muscleTarget.isNotEmpty()) P.muscleTarget[0] = 1.0
            P.updateMuscles(dt)
            for (s in 0 until sub) {
                P.integrate(h)
                P.flipSweep()
                var b = P.snapshotPos(); P.solveConstraints(h)
                acc[0] += sqrt(P.positionalImpulse(b).let { it[0] * it[0] + it[1] * it[1] })
                b = P.snapshotPos(); P.solveAreas(h)
                acc[1] += sqrt(P.positionalImpulse(b).let { it[0] * it[0] + it[1] * it[1] })
                b = P.snapshotPos(); P.projectBones()
                acc[2] += sqrt(P.positionalImpulse(b).let { it[0] * it[0] + it[1] * it[1] })
                P.updateVelocities(h)
            }
        }
        for (k in 0..2) {
            check(names[k], "стадия двигает центр масс — нарушено сохранение импульса") {
                expectBelow(acc[k], 1e-6)
            }
        }
    }

    // ------------------------------------------------------------------
    //  6. Сложенная ткань распрямляется.
    // ------------------------------------------------------------------
    println("--- распрямление складки")
    for (at in listOf(0.5, 0.25)) {
        val s = SwimSolver(topo, SwimParams())
        s.reset()
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        for (i in 0 until topo.n) {
            if (s.px[i] < minX) minX = s.px[i]
            if (s.px[i] > maxX) maxX = s.px[i]
        }
        val line = minX + (maxX - minX) * at
        for (i in 0 until topo.n) {
            if (s.px[i] > line) s.px[i] = line - (s.px[i] - line)
            s.vx[i] = 0.0; s.vy[i] = 0.0
        }
        for (fr in 1..(8.0 / dt).toInt()) s.frameHoldMuscle0(dt, sub, hold = false)
        check("складка ${((1 - at) * 100).toInt()}% расходится за 8 с",
            "ткань застряла сама в себе и не распрямляется") {
            expectAtMost(s.countInverted(), 0)
        }
    }

    // ------------------------------------------------------------------
    //  ИТОГ
    // ------------------------------------------------------------------
    val failed = checks.count { !it.ok }
    println()
    if (failed == 0) {
        println("ВСЁ ПРОШЛО: ${checks.size} проверок.")
    } else {
        println("ПРОВАЛЕНО $failed из ${checks.size}.")
        println()
        println("Порог — сигнализация, а не физическая константа. Если правка законно")
        println("улучшила показатель, порог надо УЖАТЬ. Если законно ухудшила — это")
        println("решение, и записать его надо здесь, рядом с проверкой, а не молча")
        println("поднять число.")
    }
    println()
    if (failed > 0) exitProcess(1)
}
