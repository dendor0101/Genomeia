package io.github.some_example_name.lwjgl3.demo

import java.lang.reflect.Method
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Стенд ЧЕСТНОСТИ для RealBodyDemo.
 *
 * Ничего не переписывает: вызывает приватные стадии НАСТОЯЩЕГО решателя через
 * рефлексию и мерит сохранение импульса, момента и энергии между ними.
 * Поэтому все цифры относятся к тому коду, который реально крутится в демо.
 */
object Probe {
    lateinit var demo: RealBodyDemo
    lateinit var body: BodyFile

    var n = 0
    lateinit var px: DoubleArray
    lateinit var py: DoubleArray
    lateinit var vx: DoubleArray
    lateinit var vy: DoubleArray
    lateinit var prevX: DoubleArray
    lateinit var prevY: DoubleArray
    lateinit var invMass: DoubleArray
    lateinit var restInvMass: DoubleArray
    lateinit var matchWeight: DoubleArray
    lateinit var muscleActivation: DoubleArray
    lateinit var muscleTarget: DoubleArray
    lateinit var rigidBones: Array<IntArray>
    lateinit var organismOf: IntArray
    var organismCount = 0
    lateinit var boundA: IntArray
    lateinit var boundB: IntArray
    var boundCount = 0
    var conCount = 0

    private val methods = HashMap<String, Method>()

    private fun m(name: String, vararg types: Class<*>): Method = methods.getOrPut(name) {
        RealBodyDemo::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
    }

    private fun <T> field(name: String): T {
        val f = RealBodyDemo::class.java.getDeclaredField(name)
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(demo) as T
    }

    private fun setField(name: String, v: Any) {
        val f = RealBodyDemo::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(demo, v)
    }

    /** Физические константы демо теперь Double. Для float-полей есть constFloat. */
    fun const(name: String): Double {
        val f = RealBodyDemo::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.getDouble(null)
    }

    fun constFloat(name: String): Float {
        val f = RealBodyDemo::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.getFloat(null)
    }

    /** Текущая податливость изгиба контура в демо: это состояние, а не константа. */
    fun bendCompliance(): Double =
        RealBodyDemo::class.java.getDeclaredMethod("bendCompliance")
            .apply { isAccessible = true }.invoke(demo) as Double

    fun constBool(name: String): Boolean {
        val f = RealBodyDemo::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.getBoolean(null)
    }

    fun constInt(name: String): Int {
        val f = RealBodyDemo::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.getInt(null)
    }

    fun boot(path: String) {
        demo = RealBodyDemo(path)
        m("buildFromFile").invoke(demo)
        body = field("body")
        n = field("n")
        px = field("px"); py = field("py")
        vx = field("vx"); vy = field("vy")
        prevX = field("prevX"); prevY = field("prevY")
        invMass = field("invMass"); matchWeight = field("matchWeight")
        restInvMass = field("restInvMass")
        muscleActivation = field("muscleActivation")
        muscleTarget = field("muscleTarget")
        rigidBones = field("rigidBones")
        organismOf = field("organismOf"); organismCount = field("organismCount")
        boundA = field("boundA"); boundB = field("boundB")
        boundCount = field("boundCount")
        conCount = field("conCount")
        setField("dragId", -1)
    }

    /** Позиции и скорости в начальное состояние (аналог reset без камеры). */
    fun resetState() {
        for (i in 0 until n) {
            px[i] = body.x[i].toDouble(); py[i] = body.y[i].toDouble()
            vx[i] = 0.0; vy[i] = 0.0
            // Массу берём ТУ ЖЕ, что считает демо, а не зашитую единицу. Раньше здесь
            // стояло 1.0, и когда демо начало считать её из радиуса, разошлось на
            // один ulp — этого хватило, чтобы за 200 кадров траектории разъехались.
            invMass[i] = restInvMass[i]; matchWeight[i] = 1.0
            prevX[i] = px[i]; prevY[i] = py[i]
        }
        muscleActivation.fill(0.0)
        muscleTarget.fill(0.0)
        resetFlow()
    }

    // --- стадии решателя ---
    fun integrate(h: Double) { m("integrate", Double::class.java).invoke(demo, h) }
    fun solveConstraints(h: Double) { m("solveConstraints", Double::class.java).invoke(demo, h) }
    fun solveAreas(h: Double) { m("solveAreas", Double::class.java).invoke(demo, h) }
    fun projectBones() { for (b in rigidBones.indices) m("projectBone", Int::class.java).invoke(demo, b) }
    fun updateVelocities(h: Double) { m("updateVelocities", Double::class.java).invoke(demo, h) }
    fun applyViscosity(h: Double) { m("applyViscosity", Double::class.java).invoke(demo, h) }
    fun applyNormalDrag(h: Double) { m("applyNormalDrag", Double::class.java).invoke(demo, h) }
    fun applyRestitution() { m("applyRestitution").invoke(demo) }
    fun applyMediumDrag(h: Double) { m("applyMediumDrag", Double::class.java).invoke(demo, h) }
    fun updateMuscles(dt: Double) { m("updateMuscles", Double::class.java).invoke(demo, dt) }

    /**
     * Переворачивает направление обхода связей — то же, что делает simulate() каждый
     * подшаг. Без этого стенд мерил бы АЛГОРИТМ, КОТОРОГО В ДЕМО НЕТ: чередование
     * живёт в simulate(), а сюда стадии зовутся напрямую.
     */
    fun flipSweep() {
        val f = RealBodyDemo::class.java.getDeclaredField("sweepBackwards").apply { isAccessible = true }
        f.setBoolean(demo, !f.getBoolean(demo))
    }

    /** Настоящий simulate() демо, без пересборки стадий. */
    private fun simulate() { m("simulate").invoke(demo) }

    /**
     * Один кадр.
     *
     * При hydro = true зовётся НАСТОЯЩИЙ simulate() демо, а не пересобранный из стадий.
     * Раньше здесь был ручной список, и он ровно так и подвёл: в simulate() добавились
     * контакты, предел длины связи и потолок скорости, а список о них не знал — стенд
     * стал мерить конвейер, которого в демо нет. Пересобирать порядок стадий вручную
     * можно только там, где нужно вклиниться МЕЖДУ ними (замер утечки по стадиям), и
     * нигде больше.
     *
     * hydro = false — намеренно урезанный диагностический режим «внешних сил нет».
     * Он и обязан отличаться от демо, поэтому собирается вручную.
     */
    fun frame(dt: Double, sub: Int, contract: Boolean, hydro: Boolean = true) {
        muscleTarget.fill(0.0)
        if (contract && muscleTarget.isNotEmpty()) muscleTarget[0] = 1.0
        updateMuscles(dt)
        if (hydro) { simulate(); return }
        val h = dt / sub
        for (s in 0 until sub) {
            integrate(h)
            flipSweep()
            solveConstraints(h)
            solveAreas(h)
            projectBones()
            updateVelocities(h)
            applyViscosity(h)
            applyRestitution()
            applyMediumDrag(h)
        }
    }

    // --- измерители ---
    fun comX(): Double { var s = 0.0; for (i in 0 until n) s += px[i]; return s / n }
    fun comY(): Double { var s = 0.0; for (i in 0 until n) s += py[i]; return s / n }
    fun pX(): Double { var s = 0.0; for (i in 0 until n) s += vx[i]; return s }
    fun pY(): Double { var s = 0.0; for (i in 0 until n) s += vy[i]; return s }

    /** Момент импульса относительно центра масс, за вычетом поступательного движения. */
    fun angMom(): Double {
        val cx = comX(); val cy = comY()
        var vxm = 0.0; var vym = 0.0
        for (i in 0 until n) { vxm += vx[i]; vym += vy[i] }
        vxm /= n; vym /= n
        var l = 0.0
        for (i in 0 until n) {
            val rx = px[i] - cx; val ry = py[i] - cy
            l += rx * (vy[i] - vym) - ry * (vx[i] - vxm)
        }
        return l
    }

    fun kinetic(): Double {
        var e = 0.0
        for (i in 0 until n) e += 0.5 * (vx[i].toDouble() * vx[i] + vy[i].toDouble() * vy[i])
        return e
    }

    fun inertia(): Double {
        val cx = comX(); val cy = comY()
        var j = 0.0
        for (i in 0 until n) {
            val rx = px[i] - cx; val ry = py[i] - cy
            j += rx * rx + ry * ry
        }
        return j
    }

    fun snapshotPos(): DoubleArray {
        val out = DoubleArray(2 * n)
        for (i in 0 until n) { out[2 * i] = px[i]; out[2 * i + 1] = py[i] }
        return out
    }

    /** Линейный и угловой позиционный импульс стадии: сумма dx и сумма r x dx. */
    fun positionalImpulse(before: DoubleArray): DoubleArray {
        var cx = 0.0; var cy = 0.0
        for (i in 0 until n) { cx += before[2 * i]; cy += before[2 * i + 1] }
        cx /= n; cy /= n
        var sx = 0.0; var sy = 0.0; var l = 0.0
        for (i in 0 until n) {
            val dx = px[i] - before[2 * i]
            val dy = py[i] - before[2 * i + 1]
            sx += dx; sy += dy
            val rx = before[2 * i] - cx; val ry = before[2 * i + 1] - cy
            l += rx * dy - ry * dx
        }
        return doubleArrayOf(sx, sy, l)
    }

    fun countInverted(): Int {
        var c = 0
        for (t in 0 until body.triCount) {
            val i0 = body.triA[t]; val i1 = body.triB[t]; val i2 = body.triC[t]
            val a2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) - (py[i1] - py[i0]) * (px[i2] - px[i0])
            if (a2 < 0f) c++
        }
        return c
    }

    fun maxSpeed(): Double {
        var mx = 0.0
        for (i in 0 until n) {
            val s = sqrt(vx[i].toDouble() * vx[i] + vy[i].toDouble() * vy[i])
            if (s > mx) mx = s
        }
        return mx
    }

    /** Скорость увлечённой среды. Теперь ОДИН вектор на организм, а не массив по рёбрам. */
    /** Наибольший запас среди организмов: теперь их несколько. */
    fun flowPeak(): Double {
        val fx = flowField("flowVX").get(demo) as DoubleArray
        val fy = flowField("flowVY").get(demo) as DoubleArray
        var mx = 0.0
        for (o in fx.indices) mx = maxOf(mx, sqrt(fx[o] * fx[o] + fy[o] * fy[o]))
        return mx
    }

    private fun flowField(name: String) =
        RealBodyDemo::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** Запас среды теперь МАССИВ — по одному на организм, а не один на мир. */
    fun resetFlow() {
        (flowField("flowVX").get(demo) as DoubleArray).fill(0.0)
        (flowField("flowVY").get(demo) as DoubleArray).fill(0.0)
    }
}

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
