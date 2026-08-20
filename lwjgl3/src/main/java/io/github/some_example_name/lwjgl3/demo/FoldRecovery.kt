package io.github.some_example_name.lwjgl3.demo

import java.util.Locale

/**
 * ЗАСТРЕВАНИЕ ВЫВЕРНУТОЙ ТКАНИ: распрямляется ли складка сама.
 *
 * Ткань складывают НАСИЛЬНО и заведомо злым способом, потом отпускают и смотрят,
 * уходят ли вывернутые треугольники. Складка делается зеркалом: половина тела
 * отражается на другую половину, то есть каждый треугольник в ней гарантированно
 * получает отрицательную знаковую площадь, и вдобавок ткань лежит сама на себе —
 * ровно та ситуация, которая в демо и залипает.
 *
 * ЧТО ПРОВЕРЯЕТСЯ. Ограничение площади вывернутый треугольник ВЫПРАВЛЯТЬ УМЕЕТ:
 * restArea2 положительна, area2 знаковая, значит цель у ограничения это +restArea2,
 * и для вывернутого треугольника невязка огромна и знак поправки правильный. Вопрос
 * только в том, хватает ли ей силы против остальной ткани — и не мешает ли ей
 * податливость, которую мы поставили ради честности счёта.
 *
 * Отсюда варианты:
 *   1. старое: AREA_COMPLIANCE = 1e-6 всем треугольникам — застревает;
 *   2. жёстко всем: AREA_COMPLIANCE = 0 — складку лечит, но возвращает угловую утечку;
 *   3. РАЗДЕЛЬНО: мягко нормальным, жёстко вывернутым — лечит и то и другое.
 *
 * Обоснование третьего не «так лучше работает», а в том, зачем податливость вообще
 * нужна: она фильтрует шум float РЯДОМ С РАВНОВЕСИЕМ. Вывернутый треугольник от
 * равновесия бесконечно далеко, его невязка на четыре порядка больше шума.
 *
 * ВТОРАЯ ЧАСТЬ СТЕНДА — про ДЁРГАНЬЕ, которое у жёсткой ветки появляется. Оно тоже
 * распадается на две разные болезни (рывок и дребезг) и лечится двумя разными
 * рычагами: потолком на длину поправки и плавным переходом между ветками.
 * См. AREA_MAX_STEP и AREA_SMOOTH_RAMP в RealBodyDemo.
 */
private const val DT = 1.0 / 144.0
private const val SUBSTEPS = 4

/**
 * Складывает тело зеркалом относительно вертикали, проходящей через долю [at] его
 * ширины. Всё, что правее, отражается влево — ткань ложится сама на себя.
 */
private fun fold(s: SwimSolver, topo: Topology, at: Double) {
    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    for (i in 0 until topo.n) {
        if (s.px[i] < minX) minX = s.px[i]
        if (s.px[i] > maxX) maxX = s.px[i]
    }
    val line = minX + (maxX - minX) * at
    for (i in 0 until topo.n) {
        if (s.px[i] > line) s.px[i] = line - (s.px[i] - line)
        s.vx[i] = 0.0; s.vy[i] = 0.0
    }
}

private fun run(
    topo: Topology,
    label: String,
    params: SwimParams,
    foldAt: Double,
    frames: Int,
): IntArray {
    val s = SwimSolver(topo, params)
    s.reset()
    fold(s, topo, foldAt)
    val after = s.countInverted()
    val marks = intArrayOf(60, 300, 1200, frames)
    val out = IntArray(marks.size + 1)
    out[0] = after
    var mi = 0
    for (fr in 1..frames) {
        // Мышцы не трогаем: смотрим ЧИСТОЕ распрямление, без помощи от активности.
        s.frameHoldMuscle0(DT, SUBSTEPS, hold = false)
        if (mi < marks.size && fr == marks[mi]) { out[mi + 1] = s.countInverted(); mi++ }
    }
    println(String.format(Locale.ROOT, "%-40s | %6d | %6d | %6d | %6d | %6d",
        label, out[0], out[1], out[2], out[3], out[4]))
    return out
}

fun main(args: Array<String>) {
    val path = if (args.isNotEmpty()) args[0] else "body-export.txt"
    val topo = Topology.load(path)

    // ------------------------------------------------------------------
    //  ГДЕ ОГРАНИЧЕНИЯ ПЛОЩАДИ НЕТ ВООБЩЕ.
    //
    //  Заворот без единого вывернутого треугольника означает, что он произошёл там,
    //  где треугольников нет. Отросток шириной в одну клетку в триангуляцию не
    //  попадает: три взаимно связанные клетки в нём не находятся, значит нет ни
    //  площади, ни ограничения на неё. Такая полоса может сложиться пополам, и ни
    //  одна проверка знака площади этого не заметит — замечать нечего.
    //
    //  Это ровно те места, которыми организм гребёт: хвост и плавники.
    // ------------------------------------------------------------------
    run {
        val triCountOfLink = HashMap<Long, Int>(topo.triCount * 3)
        fun key(a: Int, b: Int): Long {
            val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
            return (lo shl 32) or hi
        }
        for (t in 0 until topo.triCount) {
            triCountOfLink.merge(key(topo.triA[t], topo.triB[t]), 1, Int::plus)
            triCountOfLink.merge(key(topo.triB[t], topo.triC[t]), 1, Int::plus)
            triCountOfLink.merge(key(topo.triC[t], topo.triA[t]), 1, Int::plus)
        }
        var bare = 0
        for (e in 0 until topo.boundCount) {
            if ((triCountOfLink[key(topo.boundA[e], topo.boundB[e])] ?: 0) == 0) bare++
        }
        // Вершины, не входящие ни в один треугольник, — это уже совсем свободная нить.
        val inTri = BooleanArray(topo.n)
        for (t in 0 until topo.triCount) {
            inTri[topo.triA[t]] = true; inTri[topo.triB[t]] = true; inTri[topo.triC[t]] = true
        }
        val bareVerts = inTri.count { !it }

        println()
        println("=== ГДЕ ОГРАНИЧЕНИЯ ПЛОЩАДИ НЕТ ===")
        println("граничных рёбер всего ${topo.boundCount}, из них БЕЗ треугольника $bare " +
            "(${bare * 100 / topo.boundCount}%)")
        println("вершин, не входящих ни в один треугольник: $bareVerts из ${topo.n}")
        println("в этих местах знак площади не определён, и заворот там не поймать ничем —")
        println("ловить нечего. Это хвост и плавники, то есть гребущая поверхность.")
    }

    println()
    println("=== РАСПРЯМЛЕНИЕ ВЫВЕРНУТОЙ ТКАНИ ===")
    println("тело: ${topo.n} частиц, ${topo.triCount} треугольников")
    println("складка делается зеркалом, мышцы не активны, считаются вывернутые треугольники")
    println()

    for (foldAt in listOf(0.75, 0.5, 0.25)) {
        println("--- складываем ${((1 - foldAt) * 100).toInt()}% тела " +
            "(зеркало на ${(foldAt * 100).toInt()}% ширины)")
        println(String.format(Locale.ROOT, "%-40s | %6s | %6s | %6s | %6s | %6s",
            "вариант", "сразу", "0.4 c", "2 c", "8 c", "35 c"))
        // Каждый вариант задаётся ЯВНО, а не «умолчаниями плюс правка». Умолчания
        // SwimParams следуют за RealBodyDemo и меняются вместе с ним — если опереться
        // на них, подписи строк однажды разойдутся с тем, что реально считается.
        // Ровно это здесь и случилось, когда раздельная податливость стала умолчанием.
        val old = SwimParams(
            areaComplianceInverted = -1.0, areaMaxStep = 0.0, areaSmoothRamp = false)
        run(topo, "старое: площадь 1e-6 всем", old, foldAt, 5040)
        run(topo, "жёстко всем (площадь 0)", old.copy(areaCompliance = 0.0), foldAt, 5040)
        run(topo, "раздельно, без сглаживаний", old.copy(areaComplianceInverted = 0.0),
            foldAt, 5040)
        run(topo, "как сейчас (раздельно+плавно+потолок)", SwimParams(), foldAt, 5040)
        println()
    }

    println("Ноль в последнем столбце — складка разошлась полностью.")
    println("Число, которое не падает, — та самая застрявшая форма.")

    // ------------------------------------------------------------------
    //  ЦЕНА ЖЁСТКОСТИ. Складку она чинит, но вывернутые треугольники возникают и в
    //  обычной работе — при сильном сокращении мышцы. А жёсткая площадь возвращает
    //  УГЛОВУЮ утечку, ради которой податливость и вводилась. Значит одной колонки
    //  мало, нужны обе сразу.
    // ------------------------------------------------------------------
    println()
    println("=== ПОДБОР ПОДАТЛИВОСТИ ДЛЯ ВЫВЕРНУТЫХ ===")
    println("слева — распрямление складки, справа — угловая утечка при удержании мышцы.")
    println("нужно и то и другое: складка должна расходиться, а тело не крутиться.")
    println()
    println(String.format(Locale.ROOT, "%-14s | %8s | %8s | %11s | %11s",
        "площадь выв.", "складка", "складка", "|L| мышца", "omega, рад/с"))
    println(String.format(Locale.ROOT, "%-14s | %8s | %8s | %11s | %11s",
        "", "50% 8c", "75% 8c", "600 кадров", ""))

    for (aInv in listOf(-1.0, 0.0, 1e-9, 1e-8, 1e-7, 3e-7)) {
        val p = SwimParams(areaComplianceInverted = aInv)

        fun foldLeft(at: Double): Int {
            val s = SwimSolver(topo, p)
            s.reset(); fold(s, topo, at)
            for (fr in 1..1200) s.frameHoldMuscle0(DT, SUBSTEPS, hold = false)
            return s.countInverted()
        }

        // Угловая утечка: мышца держится сокращённой, внешних сил нет, момент
        // относительно центра масс обязан остаться нулём.
        val s = SwimSolver(topo, p)
        s.reset()
        for (fr in 1..600) s.frameHoldMuscle0(DT, SUBSTEPS, hold = true)
        val l = s.angularMomentum()
        val om = l / s.inertia()

        println(String.format(Locale.ROOT, "%-14s | %8d | %8d | %11.3e | %11.3e",
            if (aInv < 0) "как обычной" else String.format(Locale.ROOT, "%.0e", aInv),
            foldLeft(0.5), foldLeft(0.25), kotlin.math.abs(l), om))
    }
    println()
    println("«как обычной» — прежнее поведение, вывернутым та же 1e-6.")

    // ------------------------------------------------------------------
    //  ДЁРГАНЬЕ ПРИ РАСПРЯМЛЕНИИ. Две разные болезни, лечатся разным.
    //
    //  РЫВОК: жёсткая ветка выправляет треугольник за один подшаг целиком, а
    //  updateVelocities делит смещение на h = 1/576 и делает из него скорость.
    //  Видно по пику |v| и по пику кинетической энергии.
    //
    //  ДРЕБЕЗГ: между мягкой и жёсткой ветками разрыв жёсткости в 55 раз ровно на
    //  нулевой площади, и треугольник около неё скачет из ветки в ветку каждый
    //  подшаг. Видно по числу ПЕРЕЩЁЛКИВАНИЙ — сколько раз треугольники меняли знак
    //  площади за всё распрямление.
    // ------------------------------------------------------------------
    println()
    println("=== ДЁРГАНЬЕ ПРИ РАСПРЯМЛЕНИИ (складка 50%, 8 секунд) ===")
    println("пик |v| и пик энергии — это рывок; перещёлкивания — дребезг;")
    println("кадров до нуля — не стало ли лечение слишком вялым.")
    println()
    println(String.format(Locale.ROOT, "%-34s | %9s | %9s | %10s | %8s",
        "вариант", "пик |v|", "пик KE", "перещёлк.", "кадров"))

    fun jerk(label: String, p: SwimParams) {
        val s = SwimSolver(topo, p)
        s.reset()
        fold(s, topo, 0.5)
        var peakV = 0.0
        var peakKE = 0.0
        var flips = 0
        var framesToZero = -1
        var prevInv = s.countInverted()
        for (fr in 1..1152) {
            s.frameHoldMuscle0(DT, SUBSTEPS, hold = false)
            val v = s.maxSpeed()
            if (v > peakV) peakV = v
            val ke = s.kinetic()
            if (ke > peakKE) peakKE = ke
            val inv = s.countInverted()
            // Модуль изменения числа вывернутых за кадр: устойчивое распрямление даёт
            // монотонное падение, дребезг — скачки в обе стороны.
            flips += kotlin.math.abs(inv - prevInv)
            prevInv = inv
            if (inv == 0 && framesToZero < 0) framesToZero = fr
        }
        println(String.format(Locale.ROOT, "%-34s | %9.3f | %9.2f | %10d | %8s",
            label, peakV, peakKE, flips,
            if (framesToZero < 0) "не дошло" else framesToZero.toString()))
    }

    val cur = SwimParams(areaMaxStep = 0.0, areaSmoothRamp = false)
    jerk("ступенька, без потолка (было)", cur)
    jerk("+ плавный переход", cur.copy(areaSmoothRamp = true))
    jerk("+ потолок 0.5 связи", cur.copy(areaMaxStep = 0.5))
    jerk("+ потолок 0.2 связи", cur.copy(areaMaxStep = 0.2))
    jerk("+ потолок 0.05 связи", cur.copy(areaMaxStep = 0.05))
    jerk("плавный + потолок 0.2 (стоит сейчас)", cur.copy(areaSmoothRamp = true, areaMaxStep = 0.2))
    jerk("плавный + потолок 0.05", cur.copy(areaSmoothRamp = true, areaMaxStep = 0.05))
    println()
    println("Для сравнения, СТАРОЕ поведение (мягко всем, застревало):")
    jerk("площадь 1e-6 всем", cur.copy(areaComplianceInverted = -1.0))

    // ------------------------------------------------------------------
    //  ЗАВОРОТ БЕЗ ЕДИНОГО ВЫВЕРНУТОГО ТРЕУГОЛЬНИКА.
    //
    //  Знаковая площадь ЛОКАЛЬНА: сложенный пополам лист нигде не выворачивается
    //  наизнанку, каждый треугольник в нём положителен. Поэтому такой заворот не
    //  ловится ограничением площади ВООБЩЕ, сколько его ни ужесточай — и ужесточение
    //  ветки для вывернутых тут не поможет по построению.
    //
    //  Единственное дешёвое лекарство — сделать сам ИЗГИБ контура дорогим.
    //
    //  Тест: контур насильно скручивается (граничные вершины смещаются по синусоиде
    //  поперёк тела), тело отпускается. Меряется, насколько ушла форма границы от позы
    //  покоя и вернулась ли она. Плюс цена: тяга за гребок и остаточный импульс.
    // ------------------------------------------------------------------
    println()
    println("=== ИЗГИБ ГРАНИЦЫ: сопротивление завороту против цены в тяге ===")
    println("пар изгиба: ${topo.bendCount} на ${topo.boundCount} граничных рёбер " +
        "(${topo.bendCount * 100 / maxOf(1, topo.conCount)}% к числу связей)")
    println()
    println(String.format(Locale.ROOT, "%-24s | %10s | %10s | %10s | %10s",
        "податливость изгиба", "искажение", "верн. за 2с", "тяга/цикл", "ост. |P|"))

    fun bendTest(label: String, bendC: Double) {
        val p = SwimParams(bendCompliance = bendC)

        // 1. Скручиваем контур и смотрим, как далеко форма уходит и возвращается ли.
        val s = SwimSolver(topo, p)
        s.reset()
        val rx = DoubleArray(topo.n) { s.px[it] }
        val ry = DoubleArray(topo.n) { s.py[it] }
        for (e in 0 until topo.boundCount) {
            val i = topo.boundA[e]
            s.px[i] += kotlin.math.sin(s.py[i] * 9.0) * topo.meanLinkLength * 3.0
        }
        fun distortion(): Double {
            var m = 0.0
            for (i in 0 until topo.n) {
                val dx = s.px[i] - rx[i]; val dy = s.py[i] - ry[i]
                m += dx * dx + dy * dy
            }
            return kotlin.math.sqrt(m / topo.n) / topo.meanLinkLength
        }
        val peak = distortion()
        for (fr in 1..288) s.frameHoldMuscle0(DT, SUBSTEPS, hold = false)
        val left = distortion()

        // 2. Цена: тяга за цикл гребка и остаточный импульс после него.
        val t = SwimSolver(topo, p)
        t.reset()
        for (fr in 1..600) t.frame(DT, SUBSTEPS, gait = true)
        val cx = t.comX(); val cy = t.comY()
        for (fr in 1..600) t.frame(DT, SUBSTEPS, gait = true)
        val dx = t.comX() - cx; val dy = t.comY() - cy
        val thrust = kotlin.math.sqrt(dx * dx + dy * dy)
        for (fr in 1..2000) t.frame(DT, SUBSTEPS, gait = false)

        println(String.format(Locale.ROOT, "%-24s | %10.3f | %10.3f | %10.5f | %10.3e",
            label, peak, left, thrust, t.momentum()))
    }

    bendTest("выключено", -1.0)
    bendTest("1e-3 (очень мягко)", 1e-3)
    bendTest("1e-4", 1e-4)
    bendTest("1e-5", 1e-5)
    bendTest("1e-6", 1e-6)
    println()
    println("«искажение» — насколько форма ушла сразу после скручивания, в клетках;")
    println("«верн. за 2с» — сколько осталось через 2 секунды. Меньше значит лучше держит.")
    println("«тяга/цикл» — путь центра масс за 600 кадров гребли; вот ею и платим.")
    println()
}
