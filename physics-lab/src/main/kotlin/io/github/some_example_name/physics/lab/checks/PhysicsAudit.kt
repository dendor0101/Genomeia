package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.AREA_COMPLIANCE
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.FLOW_DECAY
import io.github.some_example_name.physics.FLOW_ENTRAIN
import io.github.some_example_name.physics.MEDIUM_DRAG
import io.github.some_example_name.physics.NORMAL_DRAG
import io.github.some_example_name.physics.NORMAL_DRAG_QUADRATIC
import io.github.some_example_name.physics.SOFT_COMPLIANCE
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.VISCOSITY
import io.github.some_example_name.physics.countInverted
import io.github.some_example_name.physics.lab.inspection.Probe
import io.github.some_example_name.physics.applyNormalDrag
import io.github.some_example_name.physics.comX
import io.github.some_example_name.physics.comY
import io.github.some_example_name.physics.integrate
import io.github.some_example_name.physics.projectBone
import io.github.some_example_name.physics.solveAreas
import io.github.some_example_name.physics.solveConstraints
import io.github.some_example_name.physics.updateMuscles
import io.github.some_example_name.physics.updateVelocities
import java.util.Locale
import kotlin.math.*

private fun e(v: Double) = String.format(Locale.ROOT, "%+.3e", v)
private fun g(v: Double) = String.format(Locale.ROOT, "%.6f", v)

fun main(args: Array<String>) {
    val path = if (args.isNotEmpty()) args[0] else "body-export.txt"
    Probe.boot(path)
    val P = Probe
    val dt = P.const("DT")
    val sub = P.constInt("SUBSTEPS")
    val h = dt / sub

    println()
    println("=== КОНФИГУРАЦИЯ ===")
    println("n=${P.n} links=${P.conCount} tris=${P.body.triCount} boundary=${P.boundCount} " +
        "bones=${P.rigidBones.size} muscles=${P.muscleActivation.size}")
    println("DT=$dt SUBSTEPS=$sub h=$h  SOFT_COMPLIANCE=${P.const("SOFT_COMPLIANCE")} " +
        "AREA_COMPLIANCE=${P.const("AREA_COMPLIANCE")}")
    println("NORMAL_DRAG=${P.const("NORMAL_DRAG")} Q=${P.const("NORMAL_DRAG_QUADRATIC")} " +
        "FLOW_ENTRAIN=${P.const("FLOW_ENTRAIN")} FLOW_DECAY=${P.const("FLOW_DECAY")} " +
        "MEDIUM_DRAG=${P.const("MEDIUM_DRAG")} VISCOSITY=${P.const("VISCOSITY")}")
    println("mean link = ${g(P.body.meanLinkLength.toDouble())}   J = ${g(P.inertia())}")

    // ------------------------------------------------------------------
    // ТЕСТ 1. Стадия за стадией: кто нарушает сохранение и на сколько.
    // Внешние силы не вызываются вообще.
    // ------------------------------------------------------------------
    println()
    println("=== ТЕСТ 1: позиционный импульс каждой стадии, внешних сил нет ===")
    println("мышца 0 держится сокращённой 600 кадров; в идеале все шесть чисел = 0")
    P.resetState()
    val accCon = DoubleArray(3)
    val accArea = DoubleArray(3)
    val accBone = DoubleArray(3)
    for (fr in 0 until 600) {
        P.muscleTarget.fill(0.0)
        if (P.muscleTarget.isNotEmpty()) P.muscleTarget[0] = 1.0
        P.updateMuscles(dt)
        for (s in 0 until sub) {
            P.integrate(h)
            P.flipSweep()
            var b = P.snapshotPos(); P.solveConstraints(h)
            var t = P.positionalImpulse(b); for (k in 0..2) accCon[k] += t[k]
            b = P.snapshotPos(); P.solveAreas(h)
            t = P.positionalImpulse(b); for (k in 0..2) accArea[k] += t[k]
            b = P.snapshotPos(); P.projectBones()
            t = P.positionalImpulse(b); for (k in 0..2) accBone[k] += t[k]
            P.updateVelocities(h)
        }
    }
    println("solveConstraints: sum dx=${e(accCon[0])} dy=${e(accCon[1])} r x dx=${e(accCon[2])}")
    println("solveAreas      : sum dx=${e(accArea[0])} dy=${e(accArea[1])} r x dx=${e(accArea[2])}")
    println("projectBone     : sum dx=${e(accBone[0])} dy=${e(accBone[1])} r x dx=${e(accBone[2])}")
    println("по скоростям: P=(${e(P.pX())}, ${e(P.pY())})  L=${e(P.angMom())}  " +
        "omega=${e(P.angMom() / P.inertia())}")

    // ------------------------------------------------------------------
    // ТЕСТ 2. Полный конвейер БЕЗ гидродинамики.
    // ------------------------------------------------------------------
    println()
    println("=== ТЕСТ 2: полный конвейер БЕЗ гидродинамики, мышца 0 держится 600 кадров ===")
    P.resetState()
    var cx0 = P.comX(); var cy0 = P.comY()
    for (fr in 0 until 600) P.frame(dt, sub, contract = true, hydro = false)
    println("P=(${e(P.pX())}, ${e(P.pY())})  L=${e(P.angMom())}  omega=${e(P.angMom() / P.inertia())}")
    println("сдвиг ЦМ = (${e(P.comX() - cx0)}, ${e(P.comY() - cy0)})  vmax=${g(P.maxSpeed())}")

    // ------------------------------------------------------------------
    // ТЕСТ 3. Полный конвейер КАК В ДЕМО. Мышца 0 сокращена и держится.
    // ------------------------------------------------------------------
    println()
    println("=== ТЕСТ 3: полный конвейер С гидродинамикой, мышца 0 сокращена и держится ===")
    P.resetState()
    cx0 = P.comX(); cy0 = P.comY()
    println(" кадр |    ЦМ dx   |    ЦМ dy   |   |P|    |     L      |   omega    |    KE    | inv | flowMax")
    for (fr in 1..2400) {
        P.frame(dt, sub, contract = true)
        if (fr % 200 == 0) {
            val pm = sqrt(P.pX() * P.pX() + P.pY() * P.pY())
            println(String.format(Locale.ROOT,
                "%5d | %+10.3e | %+10.3e | %8.2e | %+10.3e | %+10.3e | %8.2e | %3d | %.4f",
                fr, P.comX() - cx0, P.comY() - cy0, pm, P.angMom(), P.angMom() / P.inertia(),
                P.kinetic(), P.countInverted(), P.flowPeak()))
        }
    }

    // ------------------------------------------------------------------
    // ТЕСТ 4. АБСОЛЮТНЫЙ ПОКОЙ. Мышцы не активируются ни разу.
    // Любое движение здесь паразитное по определению.
    // ------------------------------------------------------------------
    println()
    println("=== ТЕСТ 4: тело в позе покоя, мышцы не активны — эталон паразитного дрейфа ===")
    P.resetState()
    cx0 = P.comX(); cy0 = P.comY()
    for (fr in 1..2400) {
        P.frame(dt, sub, contract = false)
        if (fr % 400 == 0) {
            val pm = sqrt(P.pX() * P.pX() + P.pY() * P.pY())
            println(String.format(Locale.ROOT,
                "%5d | ЦМ (%+.3e, %+.3e) | |P|=%.2e | L=%+.3e | KE=%.2e | vmax=%.2e",
                fr, P.comX() - cx0, P.comY() - cy0, pm, P.angMom(), P.kinetic(), P.maxSpeed()))
        }
    }

    // ------------------------------------------------------------------
    // ТЕСТ 5. Гидродинамика при НУЛЕВОЙ скорости тела.
    // Нет скорости — нет сопротивления. Всё, что здесь ненулевое, — flowVn.
    // ------------------------------------------------------------------
    println()
    println("=== ТЕСТ 5: applyNormalDrag при v тела = 0 (накачанный поток) ===")
    P.resetState()
    for (fr in 1..60) P.frame(dt, sub, contract = true)
    println("после 60 кадров гребка: скорость увлечённой среды = ${g(P.flowPeak())}")
    for (i in 0 until P.n) { P.vx[i] = 0.0; P.vy[i] = 0.0 }
    val b0 = P.pX(); val b1 = P.pY(); val b2 = P.angMom()
    P.applyNormalDrag(h)
    println("за ОДИН вызов при v=0: dP=(${e(P.pX() - b0)}, ${e(P.pY() - b1)}) dL=${e(P.angMom() - b2)}")
    println("тело стоит, а среда его толкает — это тяга из ничего")

    // ------------------------------------------------------------------
    // ТЕСТ 6. Память среды в ПОВОРАЧИВАЮЩЕЙСЯ системе отсчёта.
    //
    // Тело двигаем ВРУЧНУЮ как твёрдое, вращая вокруг центра масс. Решатель
    // не вызывается вообще, работает только сопротивление среды. В системе
    // тела сила от среды тогда ПОСТОЯННА, в мировой — крутится вместе с
    // телом, значит импульс за полный оборот обязан ВЕРНУТЬСЯ в ноль.
    // Число за оборот и есть мера нечестности накопителя среды.
    //
    // Обороту соответствует ровно revFrames кадров — иначе замер на границе
    // оборота ловит случайную фазу и монотонный рост не отличить от качания.
    // ------------------------------------------------------------------
    println()
    println("=== ТЕСТ 6: память среды при ЖЁСТКОМ вращении (только applyNormalDrag) ===")
    println("импульс за целый оборот обязан быть 0. Опора: поток выключен -> ровно 0,")
    println("скаляр вдоль нормали (было) -> 870, вектор в мире (сейчас) -> 308.")
    val revFrames = 452
    val omega = 2.0 * Math.PI / (revFrames * dt)
    P.resetState()
    var ang = 0.0
    val rx0 = P.comX(); val ry0 = P.comY()
    var impX = 0.0; var impY = 0.0
    for (fr in 1..(revFrames * 4)) {
        ang += omega * dt
        val cs = cos(ang); val sn = sin(ang)
        for (i in 0 until P.n) {
            val qx = P.body.x[i] - rx0; val qy = P.body.y[i] - ry0
            P.px[i] = rx0 + cs * qx - sn * qy
            P.py[i] = ry0 + sn * qx + cs * qy
            P.vx[i] = -omega * (P.py[i] - ry0)
            P.vy[i] = omega * (P.px[i] - rx0)
        }
        val q0 = P.pX(); val q1 = P.pY()
        for (s in 0 until sub) P.applyNormalDrag(h)
        impX += P.pX() - q0; impY += P.pY() - q1
        if (fr % revFrames == 0) println(String.format(Locale.ROOT,
            "оборот %d | накопленный линейный импульс от среды = %.2f  (это %.3f скорости тела)",
            fr / revFrames, sqrt(impX * impX + impY * impY),
            sqrt(impX * impX + impY * impY) / P.n))
    }

    // ------------------------------------------------------------------
    // ТЕСТ 7. Повторяемость замкнутого цикла мышцы.
    //
    // Тяга обязана быть ОДИНАКОВОЙ во всех шести циклах: тело возвращается
    // в ту же форму, среда за 500 кадров успокаивается. Разброс шага — это
    // и есть мера паразитных наводок, и она чувствительнее, чем сам путь.
    // Опора: разброс 3.9% до починок, 1.0% после.
    // ------------------------------------------------------------------
    println()
    println("=== ТЕСТ 7: 6 одинаковых циклов «сжать 60 кадров / отпустить 500 кадров» ===")
    P.resetState()
    var prevCx = P.comX(); var prevCy = P.comY()
    val steps = ArrayList<Double>()
    for (cycle in 1..6) {
        for (fr in 1..60) P.frame(dt, sub, contract = true)
        for (fr in 1..500) P.frame(dt, sub, contract = false)
        val dx = P.comX() - prevCx; val dy = P.comY() - prevCy
        steps.add(sqrt(dx * dx + dy * dy))
        println(String.format(Locale.ROOT,
            "цикл %d: шаг ЦМ = (%+.5f, %+.5f) |шаг|=%.5f  угол=%+7.1f град  L=%+.3e  KE=%.2e  вывернуто=%d",
            cycle, dx, dy, sqrt(dx * dx + dy * dy),
            Math.toDegrees(kotlin.math.atan2(dy, dx)), P.angMom(), P.kinetic(), P.countInverted()))
        prevCx = P.comX(); prevCy = P.comY()
    }
    println(String.format(Locale.ROOT,
        "средний шаг %.4f, РАЗБРОС (max-min)/средний = %.1f%%",
        steps.average(), (steps.max() - steps.min()) / steps.average() * 100.0))

    // ------------------------------------------------------------------
    // ТЕСТ 8. ГЛАВНЫЙ. То самое, на что жалуются глазами: сократить мышцу
    // пару раз, отпустить и просто ЖДАТЬ. Диссипативная система обязана
    // остановиться, то есть |P| -> 0, |L| -> 0, путь центра масс -> 0.
    //
    // Опора (100 кадров сокращения, потом 70 секунд ожидания):
    //
    //                      |P| 7c   |P| 28c   |P| 70c    путь ЦМ
    //     до починок        6.02      4.58      4.14      0.742     ВСТАЛО НА ПОЛКУ
    //     после починок     1.45      0.87      0.18      0.059     затухает
    //
    // «Встало на полку» и есть тот самый хаотичный дрейф: тело не тормозит
    // вовсе, потому что утечка подкачивает его ровно с той скоростью, с
    // какой сопротивление среды её съедает.
    // ------------------------------------------------------------------
    println()
    println("=== ТЕСТ 8: сжать мышцу 100 кадров, ОТПУСТИТЬ и ждать 70 секунд ===")
    println("честная диссипативная система обязана остановиться")
    P.resetState()
    for (fr in 1..100) P.frame(dt, sub, contract = true)
    cx0 = P.comX(); cy0 = P.comY()
    println(String.format(Locale.ROOT, "   отпустили: |P|=%.3e  |L|=%.3e",
        sqrt(P.pX() * P.pX() + P.pY() * P.pY()), abs(P.angMom())))
    for (fr in 1..10080) {
        P.frame(dt, sub, contract = false)
        if (fr == 1008 || fr == 4032 || fr == 10080) {
            val ddx = P.comX() - cx0; val ddy = P.comY() - cy0
            println(String.format(Locale.ROOT,
                "%5.0f c | |P|=%.3e | |L|=%.3e | KE=%.2e | путь ЦМ с момента отпускания = %.5f",
                fr * dt, sqrt(P.pX() * P.pX() + P.pY() * P.pY()), abs(P.angMom()),
                P.kinetic(), sqrt(ddx * ddx + ddy * ddy)))
        }
    }
    println()
}
