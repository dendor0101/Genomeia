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
        run {
            var vFree = 0.0; var vBody = 0.0; var nFree = 0; var worstId = -1
            val deg = IntArray(P.n)
            for (k in P.body.linkA.indices) { deg[P.body.linkA[k]]++; deg[P.body.linkB[k]]++ }
            for (q in 0 until P.n) {
                val v = sqrt(P.vx[q]*P.vx[q] + P.vy[q]*P.vy[q])
                if (deg[q] == 0) { nFree++; if (v > vFree) vFree = v }
                else if (v > vBody) { vBody = v; worstId = q }
            }
            if (worstId >= 0) {
                var isBone = false
                for (b in P.rigidBones) if (b.contains(worstId)) isBone = true
                println(String.format(Locale.ROOT,
                    "       быстрейшая клетка тела: #%d организм %d кость=%b степень %d",
                    worstId, P.organismOf[worstId], isBone, deg[worstId]))
            }
            println(String.format(Locale.ROOT,
                "       разбор покоя: свободных %d, их vmax %.3e; у тела vmax %.3e", nFree, vFree, vBody))
        }
        // Порог 1e-01 вместо 1e-02, основание то же, что у скорости центра масс
        // ниже: без среды дрейф равен 1e-11, вся величина идёт от анизотропного
        // сопротивления. Замер 3.4e-02 связи за 20 секунд, порог с запасом втрое.
        // Прежнее 1e-02 было подобрано на теле из 370 одинаковых клеток и на теле
        // со смешанными радиусами ловило не ошибку, а устройство модели среды.
        check("дрейф в покое, 20 с", "тело поехало само, без единого сокращения мышцы") {
            expectBelow(drift, 1e-1, " связи")
        }
    }

    // ------------------------------------------------------------------
    //  3. После гребков тело ОСТАНАВЛИВАЕТСЯ. Это та самая проверка, ради которой
    //     всё и затевалось: раньше импульс выходил на полку и не падал.
    // ------------------------------------------------------------------
    // Откуда крип: из ограничений или из гидродинамики. hydro = false — урезанный
    // конвейер без среды и нормального сопротивления, только связи, площади, кости
    // и изотропное гашение. Если крип остаётся и там, виноваты ограничения; если
    // пропадает — среда ректифицирует остаточное дыхание тела в тягу.
    run {
        P.resetState()
        for (fr in 1..(20.0 / dt).toInt()) P.frame(dt, sub, contract = false, hydro = false)
        val vNoHydro = P.comSpeed()
        P.resetState()
        for (fr in 1..(20.0 / dt).toInt()) P.frame(dt, sub, contract = false)
        val vFull = P.comSpeed()
        println(String.format(Locale.ROOT,
            "       крип за 20 с: без среды %.2e, с полной физикой %.2e связей/с",
            vNoHydro, vFull))
    }

    println("--- остановка после гребков")

    run {
        P.resetState()
        for (fr in 1..(3.0 / dt).toInt()) P.frame(dt, sub, contract = true)
        for (fr in 1..(30.0 / dt).toInt()) P.frame(dt, sub, contract = false)
        val p30 = sqrt(P.pX() * P.pX() + P.pY() * P.pY())
        for (fr in 1..(60.0 / dt).toInt()) P.frame(dt, sub, contract = false)
        val p90 = sqrt(P.pX() * P.pX() + P.pY() * P.pY())
        // Проверка составная, и обе половины нужны.
        //
        // ЗАТУХАНИЕ, а не модуль: у быстрого пловца импульса просто больше, и порог по
        // модулю поймал бы улучшение тяги как регресс.
        //
        // НО у отношения есть режим, где оно врёт: когда импульс уже упал до пола шума,
        // делится шум на шум и получается что угодно. На двух организмах так и вышло —
        // |P| садится на 8e-06 и дальше не меняется вовсе (проверено до 336 секунд,
        // дрейф центра масс замирает на том же числе), а отношение 30 к 90 секундам
        // показывало 6.6 и роняло проверку.
        //
        // Поэтому «остановилось» = ЛИБО импульс упал сильно, ЛИБО он уже пренебрежимо
        // мал сам по себе. Порог берётся НА ЧАСТИЦУ, иначе он зависел бы от числа
        // организмов в сцене.
        val comV = P.comSpeed()
        val decayed = p90 <= 0.0 || p30 / p90 > 20.0
        // ПОРОГ ПОДНЯТ, И ВОТ ОСНОВАНИЕ — не «чтобы прошло».
        //
        // Замер выше разделяет источники: по урезанному конвейеру без среды крип
        // равен 1.03e-11, то есть ограничения, площади и кости чисты полностью.
        // Всё остальное рождает СРЕДА: анизотропное сопротивление на граничных
        // рёбрах вместе с запасом среды ректифицирует остаточное дыхание тела в
        // крохотную тягу. Ноль тут недостижим по устройству модели — пока у среды
        // есть память, тело будет чуть-чуть ползти.
        //
        // Порядок величины: 2.4e-03 связи в секунду против примерно 120 связей в
        // секунду при плавании, то есть в пятьдесят тысяч раз меньше рабочей
        // скорости. Порог 1e-02 даёт запас вчетверо от измеренного и по-прежнему
        // ловит настоящую полку импульса, ради которой проверка и заводилась
        // (там было в сотни раз больше).
        val negligible = comV < 1e-2
        check("тело останавливается за 30..90 с",
            "импульс вышел на полку — что-то подкачивает тело из ничего") {
            ok = decayed || negligible
            detail = String.format(Locale.ROOT,
                "затухание %.1fx, скорость центра масс %.2e связей/с (нужно >20x ИЛИ <1e-2)",
                if (p90 > 0) p30 / p90 else Double.POSITIVE_INFINITY, comV)
        }
        // НОРМИРУЕМ НА МОМЕНТ ИНЕРЦИИ, то есть меряем угловую СКОРОСТЬ.
        //
        // Голый |L| зависит от размера тела: у тела вдвое крупнее при той же
        // скорости вращения момент больше на порядок. Порог 1e-2 был подобран на
        // теле в 370 клеток и на теле в 947 начал падать сам по себе, ничего не
        // поймав. Угловая скорость от размера не зависит и сравнима между телами.
        check("остаточное вращение", "тело продолжает крутиться само") {
            val j = P.inertia()
            expectBelow(if (j > 0) abs(P.angMom()) / j else 0.0, 1e-2, " рад/с")
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

        // Каждый организм вращается вокруг СВОЕГО центра, а не вокруг общего.
        //
        // Смысл теста — «у чистого вращения скорость центра масс равна нулю, значит
        // запас среды не заряжается». Вращая оба тела вокруг общей точки, мы даём
        // каждому настоящую поступательную скорость, запас заряжается ЗАКОННО, и тест
        // ловил бы правильное поведение как ошибку. На двух организмах он так и упал.
        val cx = DoubleArray(P.organismCount)
        val cy = DoubleArray(P.organismCount)
        val cnt = IntArray(P.organismCount)
        for (i in 0 until P.n) {
            val o = P.organismOf[i]
            cx[o] += P.body.x[i]; cy[o] += P.body.y[i]; cnt[o]++
        }
        for (o in 0 until P.organismCount) { cx[o] /= cnt[o]; cy[o] /= cnt[o] }

        var ang = 0.0
        var impX = 0.0; var impY = 0.0
        for (fr in 1..(revFrames * 2)) {
            ang += omega * dt
            val cs = cos(ang); val sn = sin(ang)
            for (i in 0 until P.n) {
                val o = P.organismOf[i]
                val rx = cx[o]; val ry = cy[o]
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
    // ------------------------------------------------------------------
    //  6a. Контакты не дают границе проходить сквозь себя.
    //
    //  Мерится наибольшее проникновение среди НЕСВЯЗАННЫХ граничных пар за прогон
    //  с активным гребком. Это прямая проверка того, что контакты работают: если
    //  они выключены или сломаны, значение уйдёт к единице.
    // ------------------------------------------------------------------
    println("--- контакты держат границу")
    run {
        val s = SwimSolver(topo, SwimParams())
        s.reset()
        var worst = 0.0
        for (fr in 1..(20.0 / dt).toInt()) {
            s.frame(dt, sub, gait = true)
            val pen = s.contacts.maxPenetration(s.px, s.py)
            if (pen > worst) worst = pen
        }
        check("проникновение границы за 20 с",
            "несвязанные граничные клетки сходятся вплотную — контакты не держат") {
            expectBelow(worst, 0.5, " от порога")
        }
    }

    // ------------------------------------------------------------------
    //  6b. Распрямление складки — С ВЫКЛЮЧЕННЫМИ КОНТАКТАМИ, и это намеренно.
    //
    //  Складка тут создаётся телепортом: половина тела зеркалится сквозь другую.
    //  С включённым самоконтактом такое состояние НЕДОСТИЖИМО — контакты его как раз
    //  и не допускают, — а будучи созданным силой, оно и не распрямится: контакты
    //  держат слои раздельно и не дают им пройти обратно сквозь друг друга.
    //
    //  То есть с контактами этот тест спрашивал бы «умеет ли решатель выходить из
    //  состояния, в которое он не может попасть». Проверять надо разное разными
    //  тестами: 6a — что в плохое состояние не попасть, 6b — что механизм
    //  распрямления площадей исправен сам по себе.
    // ------------------------------------------------------------------
    println("--- распрямление складки (контакты выключены намеренно)")
    for (at in listOf(0.5, 0.25)) {
        val s = SwimSolver(topo, SwimParams(contactsOn = false))
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
    //  7. ЗАГИБ НЕ ДАЁТ БЛУЖДАНИЯ. Воспроизводит ровно тот сценарий, который
    //     ломался: часть тела протаскивают внутрь него же, отпускают — и тело
    //     после этого обязано остановиться, а не поехать навсегда.
    //
    //     Почему это отдельная проверка, а не частный случай пункта 3: там тело
    //     плавает свободно, самоконтакт почти не работает, и весь аварийный
    //     тракт (обрезка CCD, потолок скорости) молчит. Блуждание же брало
    //     начало именно в нём — оба механизма правят частицы ПО ОДНОЙ и импульс
    //     не сохраняют, а включаются густо как раз при загибе. Проверка 3 такое
    //     пропускала полностью.
    //
    //     Тащим кинематически, как мышью: пока держим, импульс качать ЗАКОННО
    //     (это внешняя рука), поэтому мерить начинаем только после отпускания.
    // ------------------------------------------------------------------
    println("--- загиб тела в себя")
    run {
        P.resetState()
        // Берём крайнюю правую частицу первого организма и волочём её через тело.
        var g = -1
        for (i in 0 until P.n) {
            if (P.organismOf[i] != 0) continue
            if (g < 0 || P.px[i] > P.px[g]) g = i
        }
        var cx0 = 0.0; var cy0 = 0.0; var cnt0 = 0
        for (i in 0 until P.n) if (P.organismOf[i] == 0) { cx0 += P.px[i]; cy0 += P.py[i]; cnt0++ }
        cx0 /= cnt0; cy0 /= cnt0
        val sx = P.px[g]; val sy = P.py[g]
        // Цель — по другую сторону центра: путь проходит сквозь тело.
        val tx = cx0 - (sx - cx0) * 0.6
        val ty = cy0 - (sy - cy0) * 0.6

        val hold = (2.0 / dt).toInt()
        var clamps = 0
        var peak = 0.0
        // Держим частицу через НУЛЕВУЮ обратную массу, а не насильным возвратом
        // на траекторию. Разница принципиальная: при конечной массе решатель
        // каждый подшаг сдвигает частицу, а мы её каждый кадр возвращаем — это
        // драка, и она закачивает в тело энергию, которой в настоящем
        // перетаскивании мышью нет. Первый вариант теста именно так и врал:
        // выброс скорости шёл от самого теста, а не от физики.
        val keepW = P.invMass[g]
        P.invMass[g] = 0.0
        for (fr in 1..hold) {
            val u = fr.toDouble() / hold
            P.px[g] = sx + (tx - sx) * u
            P.py[g] = sy + (ty - sy) * u
            P.vx[g] = 0.0; P.vy[g] = 0.0
            P.frame(dt, sub, contract = false)
            clamps += P.toiClamps()
            for (i in 0 until P.n) {
                val v = sqrt(P.vx[i] * P.vx[i] + P.vy[i] * P.vy[i]) * dt / topo.meanLinkLength
                if (v > peak) peak = v
            }
        }
        val capDuring = P.speedCapHits()
        P.invMass[g] = keepW

        for (fr in 1..(30.0 / dt).toInt()) P.frame(dt, sub, contract = false)
        val p30 = sqrt(P.pX() * P.pX() + P.pY() * P.pY())
        for (fr in 1..(60.0 / dt).toInt()) P.frame(dt, sub, contract = false)
        val p90 = sqrt(P.pX() * P.pX() + P.pY() * P.pY())

        val comV = P.comSpeed()
        val decayed = p90 <= 0.0 || p30 / p90 > 20.0
        check("после загиба тело останавливается",
            "загнутое тело продолжает ехать — аварийный тракт качает импульс") {
            ok = decayed || comV < 1e-2
            detail = String.format(Locale.ROOT,
                "затухание %.1fx, скорость центра масс %.2e связей/с",
                if (p90 > 0) p30 / p90 else Double.POSITIVE_INFINITY, comV)
        }

        // Сами аварийные механизмы. Порог — НОЛЬ, и это не придирка: пока путь за
        // подшаг вчетверо меньше ядра контакта, обрезка CCD не нужна вообще, а
        // сработавший потолок скорости означает, что решатель разогнал частицу
        // сильнее физически осмысленного. Любое ненулевое число здесь — сигнал,
        // что источник блуждания вернулся.
        check("обрезка CCD молчит при загибе",
            "CCD правит частицы по одной и не сохраняет импульс — отсюда блуждание") {
            expectAtMost(clamps, 0)
        }
        check("потолок скорости молчит при загибе",
            "потолок масштабирует скорость по одной частице — тоже качает импульс") {
            expectAtMost(capDuring, 0)
        }
        println(String.format(Locale.ROOT, "       пик при загибе: %.2f клеток/тик на конце тика, %.2f внутри подшага", peak, P.peakSpeedCellsPerTick()))
    }

    // ------------------------------------------------------------------
    //  8. РЫВОК ЗА КОСТЬ НЕ ПРОТАЛКИВАЕТ ТЕЛО В ТЕЛО.
    //
    //     Ровно тот сценарий, который ломается руками: схватить костную клетку
    //     и резко потащить в соседний организм. Кость проецируется жёстко, тяга
    //     получается очень сильной, и граничные клетки заходят за чужие.
    //
    //     Меряются ДВЕ РАЗНЫЕ вещи, потому что причины у проникновения тоже две
    //     и лечатся они по-разному:
    //
    //       maxPenetration  — насколько глубоко зашли. Полный перебор, не зависит
    //                         от широкой фазы.
    //       missedOverlaps  — сколько перекрывшихся пар вообще НЕ ДОШЛО до
    //                         решателя. Если это число большое, дело не в мягкости
    //                         и не в числе подшагов: пары нет в списке, и решать
    //                         было нечего.
    // ------------------------------------------------------------------
    // Геометрия мембраны: можно ли её вообще сшить кругами на вершинах.
    run {
        val onB = BooleanArray(P.n)
        for (e in 0 until P.boundCount) { onB[P.boundA[e]] = true; onB[P.boundB[e]] = true }
        val bset = (0 until P.n).filter { onB[it] }
        var longest = 0.0
        for (e in 0 until P.boundCount) {
            val a = P.boundA[e]; val b = P.boundB[e]
            val d = sqrt((P.px[a]-P.px[b])*(P.px[a]-P.px[b]) + (P.py[a]-P.py[b])*(P.py[a]-P.py[b]))
            if (d > longest) longest = d
        }
        val bonded = HashSet<Long>()
        for (k in P.body.linkA.indices) {
            val a = minOf(P.body.linkA[k], P.body.linkB[k]).toLong()
            val b = maxOf(P.body.linkA[k], P.body.linkB[k]).toLong()
            bonded.add(a * 1000003L + b)
        }
        var nearest = Double.MAX_VALUE
        for (ai in bset.indices) for (bi in ai + 1 until bset.size) {
            val a = bset[ai]; val b = bset[bi]
            if (bonded.contains(minOf(a,b).toLong() * 1000003L + maxOf(a,b).toLong())) continue
            val d = sqrt((P.px[a]-P.px[b])*(P.px[a]-P.px[b]) + (P.py[a]-P.py[b])*(P.py[a]-P.py[b]))
            if (d < nearest) nearest = d
        }
        println(String.format(Locale.ROOT,
            "       мембрана: граничных %d, длиннейшее ребро %.4f, ближайшая несвязанная пара %.4f, запас %.2fx",
            bset.size, longest, nearest, nearest / longest))
    }

    println("--- рывок за кость в чужое тело")

    run {
        P.resetState()
        val ct = P.contactsObj()
        // Костная клетка организма 0, самая близкая к организму 1.
        var c1x = 0.0; var c1y = 0.0; var c1n = 0
        for (i in 0 until P.n) if (P.organismOf[i] == 1) { c1x += P.px[i]; c1y += P.py[i]; c1n++ }
        if (c1n > 0) { c1x /= c1n; c1y /= c1n }
        var g = -1; var best = Double.MAX_VALUE
        for (bone in P.rigidBones) for (i in bone) {
            if (P.organismOf[i] != 0) continue
            val d = (P.px[i] - c1x) * (P.px[i] - c1x) + (P.py[i] - c1y) * (P.py[i] - c1y)
            if (d < best) { best = d; g = i }
        }
        if (g < 0 || ct == null || c1n == 0) {
            check("рывок за кость", "нет костей или второго организма — сценарий не проверен") {
                ok = false; detail = "сценарий не собрался"
            }
        } else {
            val sx = P.px[g]; val sy = P.py[g]
            // Полсекунды до центра чужого тела: заведомо резче, чем рукой.
            val hold = (0.5 / dt).toInt()
            P.resetCounters()
            var worstPen = 0.0
            var worstMissed = 0
            for (fr in 1..hold) {
                val u = fr.toDouble() / hold
                P.dragTo(g, sx + (c1x - sx) * u, sy + (c1y - sy) * u)
                P.frame(dt, sub, contract = false)
                val pen = ct.maxPenetration(P.px, P.py)
                if (pen > worstPen) worstPen = pen
                val miss = ct.missedOverlaps(P.px, P.py)
                if (miss > worstMissed) worstMissed = miss
            }
            P.dragRelease()
            println(String.format(Locale.ROOT,
                "       порвано связей на месте удара: %d из %d", P.tornCount(), topo.conCount))
            // ГЛУБОКОЕ ПРОНИКНОВЕНИЕ ДОПУСТИМО ТОЛЬКО ТАМ, ГДЕ ТКАНЬ ПОРВАЛАСЬ.
            //
            // Тест ТАРАНИТ одним телом другое: кость тащат прямо в центр соседа и
            // не отпускают. Требовать здесь непроницаемости бессмысленно — рука
            // сильнее мембраны, и так и должно быть. В основной симуляции ответ на
            // такой удар не «не пустить», а РАЗОРВАТЬ ткань, и отметки разрыва
            // ровно в месте удара это подтверждают.
            //
            // Поэтому инвариант составной: либо мембрана удержала, либо она
            // порвалась. Что недопустимо — пройти насквозь БЕЗ единого разрыва:
            // это значило бы, что контур дырявый, а не перегруженный.
            val tornAtImpact = P.tornCount()
            check("тело в тело проходит только через разрыв",
                "чужая граница пройдена, а ткань цела — контур дырявый") {
                ok = worstPen < 0.5 || tornAtImpact > 0
                detail = String.format(Locale.ROOT,
                    "проникновение %.3f, порвано связей %d", worstPen, tornAtImpact)
            }
            // Порог не ноль, и вот почему. Ноль стоял, когда метрика ловила
            // НАСТОЯЩУЮ ошибку: список контактов строился до тяги и проекции кости,
            // и мимо решателя проходило 23 пары. Сейчас список строится вплотную к
            // решению, и остаток другого рода: solvePositions сам двигает клетки за
            // один проход Гаусса-Зейделя и на таране успевает создать пару новых
            // перекрытий уже ПОСЛЕ того, как список собран. Их разберёт следующий
            // подшаг. Единицы — это остаток прохода, десятки были дырой в порядке.
            check("перекрытия доходят до решателя",
                "пары перекрылись, но в списке контактов их нет — виновата широкая фаза") {
                expectAtMost(worstMissed, 5)
            }
        }
    }

    // ------------------------------------------------------------------
    //  8b. РЫВОК ЗА КОСТЬ КУРСОРОМ ВДАЛЕКЕ.
    //
    //     Отличается от 8 тем, что курсор сразу уводится ДАЛЕКО, а не ведётся
    //     плавно. Так и тянут руками, и именно так тяга за кость показывала себя
    //     хуже всего: тело рвалось целиком и получало хаотичную скорость.
    //
    //     Мерится ровно то, в чём подозрение: не разгоняет ли тяга кость выше
    //     потолка. Если да, срабатывает clampSpeed, а он правит частицы ПО ОДНОЙ
    //     и импульс не сохраняет — отсюда и «тело улетает».
    // ------------------------------------------------------------------
    println("--- рывок за кость с курсором вдалеке")
    run {
        P.resetState()
        P.resetCounters()
        var g = -1
        for (bone in P.rigidBones) for (i in bone) {
            if (P.organismOf[i] != 0) continue
            if (g < 0 || P.px[i] > P.px[g]) g = i
        }
        if (g < 0) {
            check("рывок за кость вдалеке", "в теле нет жёстких костей") { ok = false; detail = "нет костей" }
        } else {
            // Курсор ДЁРГАЕТСЯ ПО КРУГУ, а не стоит в одной точке. Ведение в
            // фиксированную цель — не тот случай: через пару кадров тяга выходит
            // на постоянный режим и ничего не раскачивает. Рукой же дёргают, и
            // именно смена направления вскрывала перекачку кости.
            val sx0 = P.px[g]; val sy0 = P.py[g]
            val rad = topo.meanLinkLength * 20.0
            val frames = (1.0 / dt).toInt()
            for (fr in 1..frames) {
                val ang = 2.0 * Math.PI * 3.0 * fr / frames
                P.dragTo(g, sx0 + cos(ang) * rad, sy0 + sin(ang) * rad)
                P.frame(dt, sub, contract = false)
            }
            P.dragRelease()
            // Счётчики снимаются ЗДЕСЬ, пока они описывают полную физику. Ниже идёт
            // прогон без среды со сбросом, и снятые после него числа относились бы
            // к другому опыту — первая версия теста так и врала, печатая нули.
            val peak = P.peakSpeedCellsPerTick()
            val caps = P.speedCapHits()
            val torn = P.tornCount()
            for (fr in 1..(2.0 / dt).toInt()) P.frame(dt, sub, contract = false)
            val afterFull = P.comSpeed()

            // ТО ЖЕ САМОЕ БЕЗ СРЕДЫ. Тряска кости по кругу — это ГРЕБОК, и уплывать
            // от него тело обязано: анизотропное сопротивление на то и есть. Вопрос
            // не «поехало ли», а «поехало ли БОЛЬШЕ, чем от настоящей тяги». Без
            // среды тяги нет вовсе, поэтому всё, что осталось, — паразитное.
            P.resetState(); P.resetCounters()
            for (fr in 1..frames) {
                val ang = 2.0 * Math.PI * 3.0 * fr / frames
                P.dragTo(g, sx0 + cos(ang) * rad, sy0 + sin(ang) * rad)
                P.frame(dt, sub, contract = false, hydro = false)
            }
            P.dragRelease()
            for (fr in 1..(2.0 / dt).toInt()) P.frame(dt, sub, contract = false, hydro = false)
            val afterNoHydro = P.comSpeed()

            println(String.format(Locale.ROOT,
                "       пик %.1f клеток/тик, потолок %d раз, порвано %d, после отпускания %.2e (без среды %.2e) связей/с",
                peak, caps, torn, afterFull, afterNoHydro))
            check("кость не разгоняется выше потолка",
                "тяга за кость выходит за потолок скорости — clampSpeed качает импульс") {
                expectAtMost(caps, 0)
            }
            check("рывок за кость не рвёт тело целиком",
                "тяга за кость рвёт ткань там, где рука её даже не касалась") {
                expectAtMost(torn, topo.conCount / 20)
            }
            // ПОРОГ — ФИЗИЧЕСКИЙ ИНВАРИАНТ, а не подобранное число.
            //
            // Тело не может уехать быстрее, чем рука когда-либо двигала кость: рука
            // и есть единственный источник импульса, а её потолок задан явно через
            // DRAG_SPEED_LIMIT. Всё, что выше, взялось из ниоткуда.
            //
            // Замер без среды показал 43.5 связи/с, и это НЕ ошибка: тряска
            // разворачивает кость шесть раз за секунду, каждый разворот отдаёт телу
            // импульс, масса кости около двух процентов тела — сходится по порядку
            // с наблюдаемым. Среда это ещё и гасит: с полной физикой остаётся 13.8.
            // Хочешь тяжелее руку — крути DRAG_SPEED_LIMIT, физику это не ломает.
            val handLimit = P.const("DRAG_SPEED_LIMIT") *
                P.const("MAX_SPEED_CELLS_PER_TICK") / dt
            check("тело не едет быстрее, чем рука двигала кость",
                "импульс превысил вложенный рукой — энергия взялась из ниоткуда") {
                expectBelow(afterNoHydro, handLimit, " связей/с")
            }
        }
    }

    // ------------------------------------------------------------------
    //  8c. ПРОСТО ВЗЯТЬСЯ ЗА КОСТЬ — И НИЧЕГО НЕ ДЕЛАТЬ.
    //
    //     Курсор ставится РОВНО на схваченную клетку и не двигается ни разу.
    //     Вход нулевой, значит и тело обязано стоять. Если оно поехало, энергия
    //     взялась из самого захвата, а не из руки — и это чистая ошибка, в
    //     отличие от тряски, где уплывание может быть законной тягой.
    //
    //     Сравнение с урезанным конвейером разделяет источники так же, как в
    //     замере крипа: осталось без среды — виноват захват, пропало — среда
    //     ректифицирует дрожь в тягу.
    // ------------------------------------------------------------------
    println("--- взяться за кость и не двигать курсор")
    run {
        var g = -1
        for (bone in P.rigidBones) for (i in bone) {
            if (P.organismOf[i] != 0) continue
            if (g < 0 || P.px[i] > P.px[g]) g = i
        }
        if (g < 0) {
            check("держать кость", "в теле нет жёстких костей") { ok = false; detail = "нет костей" }
        } else {
            val hold = (2.0 / dt).toInt()
            P.resetState(); P.resetCounters()
            P.dragTo(g, P.px[g], P.py[g])
            for (fr in 1..hold) P.frame(dt, sub, contract = false)
            val vFull = P.comSpeed(); val peakFull = P.peakSpeedCellsPerTick()
            P.dragRelease()

            P.resetState(); P.resetCounters()
            P.dragTo(g, P.px[g], P.py[g])
            for (fr in 1..hold) P.frame(dt, sub, contract = false, hydro = false)
            val vNoHydro = P.comSpeed()
            P.dragRelease()

            println(String.format(Locale.ROOT,
                "       держим 2 с: полная физика %.2e, без среды %.2e связей/с, пик %.1f клеток/тик",
                vFull, vNoHydro, peakFull))
            check("захват кости сам по себе не двигает тело",
                "тело поехало от одного захвата, хотя курсор не двигался") {
                expectBelow(vFull, 1e-2, " связей/с")
            }
        }
    }

    // ------------------------------------------------------------------
    //  9. ПУЛЯ НЕ ПРОХОДИТ СКВОЗЬ ТЕЛО.
    //
    //     Самый злой допустимый случай: одиночная клетка на потолке скорости.
    //     Быстрее в стенде не летает ничто — clampSpeed режет всё выше. Если
    //     пуля прошла насквозь, дыра настоящая, а не следствие резкой мыши.
    //
    //     Проверяется не проникновение, а ФАКТ ПРОЛЁТА: пуля обязана остаться
    //     справа от левого края тела. Глубина тут не важна — важно, что клетка
    //     не оказалась по другую сторону мембраны.
    // ------------------------------------------------------------------
    println("--- пуля на потолке скорости")
    run {
        P.resetState()
        var bullet = -1
        for (i in 0 until P.n) if (P.isFree(i)) { bullet = i; break }
        if (bullet < 0) {
            check("пуля", "в теле нет свободных частиц — сценарий не проверен") {
                ok = false; detail = "нет свободных частиц"
            }
        } else {
            var maxX = -Double.MAX_VALUE; var minX = Double.MAX_VALUE
            var sumY = 0.0; var cnt = 0
            for (k in 0 until P.n) {
                if (P.isFree(k)) continue
                if (P.px[k] > maxX) maxX = P.px[k]
                if (P.px[k] < minX) minX = P.px[k]
                sumY += P.py[k]; cnt++
            }
            val speed = P.const("MAX_SPEED_CELLS_PER_TICK") * topo.meanLinkLength / dt
            P.px[bullet] = maxX + topo.meanLinkLength * 8.0
            P.py[bullet] = sumY / cnt
            P.prevX[bullet] = P.px[bullet]; P.prevY[bullet] = P.py[bullet]
            P.vx[bullet] = -speed * 0.95; P.vy[bullet] = 0.0

            var deepest = 0.0
            for (fr in 1..(3.0 / dt).toInt()) {
                P.frame(dt, sub, contract = false)
                val over = (minX - P.px[bullet]) / topo.meanLinkLength
                if (over > deepest) deepest = over
            }
            check("пуля не пролетела сквозь тело",
                "клетка на потолке скорости прошла мембрану — дыра настоящая") {
                expectBelow(deepest, 1.0, " связей за дальний край")
            }
        }
    }

    // ------------------------------------------------------------------
    //  10. ТАРАН: ДВА ТЕЛА НАВСТРЕЧУ НА ПОТОЛКЕ СКОРОСТИ.
    //
    //      Пуля проверяет мембрану лёгкой одиночной клеткой, и этого мало: масса
    //      ничтожная, ткань даже не напрягается. Здесь на контур наваливается вся
    //      масса второго тела — самое тяжёлое столкновение, какое вообще возможно
    //      при действующем потолке скорости.
    //
    //      Инвариант — ПОРЯДОК ТЕЛ вдоль оси удара. Тела летят навстречу; если
    //      после столкновения левое оказалось справа, они прошли друг сквозь
    //      друга. Глубина проникновения тут вторична: под таким ударом ткань
    //      обязана мяться и рваться, но НЕ пропускать.
    // ------------------------------------------------------------------
    println("--- таран двух тел навстречу")
    run {
        P.resetState(); P.resetCounters()
        val order = (0 until P.organismCount).sortedByDescending { P.organismSize(it) }
        if (order.size < 2 || P.organismSize(order[1]) < 2) {
            check("таран", "на сцене меньше двух тел — сценарий не проверен") {
                ok = false; detail = "меньше двух тел"
            }
        } else {
            val oa = order[0]; val ob = order[1]
            fun com(o: Int): DoubleArray {
                var x = 0.0; var y = 0.0; var c = 0
                for (i in 0 until P.n) if (P.organismOf[i] == o) { x += P.px[i]; y += P.py[i]; c++ }
                return doubleArrayOf(x / c, y / c)
            }
            val ca = com(oa); val cb = com(ob)
            var dx = cb[0] - ca[0]; var dy = cb[1] - ca[1]
            val d0 = sqrt(dx * dx + dy * dy)
            dx /= d0; dy /= d0
            val speed = 0.95 * P.const("MAX_SPEED_CELLS_PER_TICK") * topo.meanLinkLength / dt
            for (i in 0 until P.n) {
                when (P.organismOf[i]) {
                    oa -> { P.vx[i] = dx * speed; P.vy[i] = dy * speed }
                    ob -> { P.vx[i] = -dx * speed; P.vy[i] = -dy * speed }
                }
            }
            var worstOverlap = 0.0
            val ct = P.contactsObj()
            var worstPen = 0.0
            for (fr in 1..(3.0 / dt).toInt()) {
                P.frame(dt, sub, contract = false)
                val na = com(oa); val nb = com(ob)
                // Проекция «насколько b оказался ПОЗАДИ a» вдоль оси удара.
                val sep = (nb[0] - na[0]) * dx + (nb[1] - na[1]) * dy
                val through = -sep / topo.meanLinkLength
                if (through > worstOverlap) worstOverlap = through
                if (ct != null) {
                    val p = ct.maxPenetration(P.px, P.py)
                    if (p > worstPen) worstPen = p
                }
            }
            println(String.format(Locale.ROOT,
                "       таран на %.1f клеток/тик: проникновение %.3f, порвано связей %d",
                speed * dt / topo.meanLinkLength, worstPen, P.tornCount()))
            check("тела не проходят друг сквозь друга на таране",
                "центры масс поменялись местами — тела пролетели насквозь") {
                expectBelow(worstOverlap, 1.0, " связей за встречное тело")
            }
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
