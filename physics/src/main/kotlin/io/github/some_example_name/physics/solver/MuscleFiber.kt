package io.github.some_example_name.physics

import kotlin.math.sqrt

/*
 * МЫШЦА С ВОЛОКНОМ — закон и значения: config/FiberParameters.kt. Здесь — как он ложится на связи и треугольники.
 *
 * Волокно — у клетки (cellFiberX/Y: единичный вектор в позе покоя тела; 0 — волокна нет, сокращение по площади).
 * Задаёт клиент (PhysicsWorld.setCellFiber): игра — направление мотонейрона кластера мышц (MotorNeuron, раз при
 * подсадке тела), стенды — длинная ось связного куска мышц (StandWorld.assignMuscleFibers). Без волокон мир идёт
 * побитово прежним путём (fibersOn). Связь и треугольник берут закон той клетки, которая задаёт им множитель мышцы.
 * Множитель связи — слабейший из действующих множителей её концов, треугольника — слабейший из трёх; действующий
 * множитель клетки — сильнейший из её собственного и соседей по треугольнику (MuscleDriver.effectiveSource); волокно
 * берётся у клетки-источника этого сильнейшего (cellScaleSrc). Так кольцо ткани, которое сокращается вместе с
 * мышцей, сокращается по её же волокну, а на стыке двух мышц — по волокну той, что задаёт множитель.
 *
 * Сжатие (s < 1) — F = s вдоль волокна и s^−k поперёк (k = BodyState.fiberThicken): длины связей и площадь от одного
 * F, поэтому согласованы — ткань не дерётся сама с собой. Ведут его ядра решателя: у связи хранится cos² угла к
 * волокну в позе покоя (conFiberC2; 1 — волокна нет: ядро идёт прежним путём побитово), у треугольника — признак
 * «по волокну» (triFiberById, по постоянному номеру — переживает перепаковку рабочих списков). Связь поперёк волокна
 * при k > 0 удлиняется, а это не множитель «расслабления», поэтому закон не сворачивается в conCellScale.
 *
 * Расслабление (s > 1) длин не меняет — мышца только расслабляется (решение автора 21.11): связь мягче на растяжение
 * (ConstraintSolver.relaxedLinkCorrection) и рвётся не раньше 25% растяжения. С волокном мягче только вдоль волокна
 * (fiberRelaxedSoftness, по тому же cos² conFiberC2, в ядре), а запас на разрыв — у всей расслабленной мышцы: где её
 * тянут вбок (сокращающаяся соседняя сторона, удар), поперечная связь иначе рвалась бы на 5%, как обычная ткань, —
 * reference.md, находка 34: это 86% лишних разрывов варианта «всё только вдоль». Треугольник расслабляется целиком,
 * как без волокна. (До находки 34 расслабление вдоль волокна сворачивалось в множитель связи, как его ввёл мотонейрон
 * игры, — вместе с пределом разрыва.)
 *
 * conFiberC2 и triFiberById — производные: пересчитываются вместе с множителями (recomputeCellScales,
 * refreshCellScaleAround) и в состояние мира не входят; в снимке — только волокна клеток.
 *
 * Слияние 21.11: волокно пришло с двух сторон — мотонейрон игры (alpha-0.2.4: одноосный закон, свёрнутый в множители
 * связей и треугольников) и стенды (perf-experiment, находка 29: закон с утолщением в ядрах). Оставлен один путь — этот;
 * одноосный закон игры — тот же закон при k = 0 (reference.md, находка 33).
 */

/** Квадрат множителя поперёк волокна, s^−2k. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun fiberAcross2(s: Double, k: Double): Double =
    if (k == 0.0) 1.0 else if (k == 1.0) 1.0 / (s * s) else if (k == 0.5) 1.0 / s else StrictMath.pow(s, -2.0 * k)

/** Множитель длины связи под командой s < 1: |F·u|, F = s вдоль волокна и s^−k поперёк; [c2] — cos²θ. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun fiberLinkFactor(s: Double, c2: Double, k: Double): Double =
    sqrt(s * s * c2 + fiberAcross2(s, k) * (1.0 - c2))

/** Множитель площади треугольника по волокну под командой s < 1: det F = s^(1−k). */
internal fun fiberAreaFactor(s: Double, k: Double): Double =
    if (k == 0.0) s else if (k == 1.0) 1.0 else if (k == 0.5) sqrt(s) else StrictMath.pow(s, 1.0 - k)

/**
 * Поправка связи мышцы с волокном под командой s < 1 (связь не вдоль волокна: cos²θ < 1). Как прежде, мышца тянет
 * отстающую ткань не сильнее потолка (MUSCLE_MAX_PULL); связь поперёк волокна, которую мышца удлиняет (k > 0), она
 * толкает тоже не сильнее потолка, а сжатие ниже длины покоя держит обычная ткань.
 */
internal fun fiberLinkCorrection(len: Double, rest: Double, s: Double, w: Double, alpha: Double, c2: Double, k: Double): Double {
    val target = rest * fiberLinkFactor(s, c2, k)
    val cap = MUSCLE_MAX_PULL * rest
    var over = len - target
    if (over > cap) {
        // MX_SPLIT (временный ключ, MuscleExperiment.kt): связь, удлинённая волокном (цель длиннее покоя), сверх цели —
        // обычная ткань, без потолка.
        over = if (target < rest) cappedPull(len, rest, target) else if (MX_SPLIT >= 1) over else cap
    }
    else if (over < 0.0 && target > rest) {
        val tissue = if (len < rest) len - rest else 0.0
        val muscle = maxOf(len, rest) - target
        over = tissue + (if (muscle < -cap) -cap else muscle)
    }
    return -over / (w + alpha)
}

/** Связь [c] сокращается по волокну: волокна заданы, команда — сжатие, и связь к волокну под углом. */
@Suppress("NOTHING_TO_INLINE")
private inline fun BodyState.linkAlongFiber(c: Int, scale: Double): Boolean =
    fibersOn && scale < 1.0 && c < conFiberC2.size && conFiberC2[c] < 1f

/** Доля длины покоя связи [c] для предела раздавливания: min(1, множитель длины). Без волокна — min(1, s). */
internal fun BodyState.limitShrink(c: Int, scale: Double): Double =
    if (linkAlongFiber(c, scale)) minOf(1.0, fiberLinkFactor(scale, conFiberC2[c].toDouble(), fiberThicken))
    else minOf(1.0, scale)

/** Длина покоя связи [c] для предела растяжения и разрыва: удлинённая поперёк волокна. Без волокна — [rest]. */
internal fun BodyState.limitRest(c: Int, rest: Double, scale: Double): Double =
    if (linkAlongFiber(c, scale)) rest * maxOf(1.0, fiberLinkFactor(scale, conFiberC2[c].toDouble(), fiberThicken))
    else rest

/** Волокно клетки, задающей множитель клетке [v] (cellScaleSrc): x и y; оба 0 — волокна нет. */
@Suppress("NOTHING_TO_INLINE")
private inline fun BodyState.drivingFiberIndex(v: Int): Int {
    val src = if (v < cellScaleSrc.size) cellScaleSrc[v] else v
    return if (src in 0 until cellFiberX.size) src else -1
}

/** Конец связи [c], чей действующий множитель — множитель связи (слабейший; при равных — первый). */
@Suppress("NOTHING_TO_INLINE")
private inline fun BodyState.drivingEnd(a: Int, b: Int): Int {
    val e = cellScaleEff
    if (a >= e.size || b >= e.size) return a
    val ea = e[a]; val eb = e[b]
    return if (strongerScale(ea, eb) == ea && ea != eb) b else a
}

/** cos² угла между связью [c] в позе покоя и волокном клетки, задающей ей множитель; 1 — волокна нет. */
private fun BodyState.linkFiberC2(c: Int): Float {
    val i = conA[c]; val j = conB[c]
    val f = drivingFiberIndex(drivingEnd(i, j))
    if (f < 0) return 1f
    val fx = cellFiberX[f]; val fy = cellFiberY[f]
    if (fx == 0f && fy == 0f) return 1f
    val dx = (body.x[j] - body.x[i]).toDouble(); val dy = (body.y[j] - body.y[i]).toDouble()
    val l2 = dx * dx + dy * dy
    if (l2 < 1e-24) return 1f
    val a = fx * dx + fy * dy
    return (a * a / l2).toFloat()
}

/** Треугольник [t] (рабочий номер) сокращается по волокну: у клетки, задающей ему множитель, есть волокно. */
internal fun BodyState.triangleFiber(t: Int): Boolean {
    val i0 = triA[t]; val i1 = triB[t]; val i2 = triC[t]
    if (boneOf[i0] >= 0 || boneOf[i1] >= 0 || boneOf[i2] >= 0) return false
    val e = cellScaleEff
    if (i0 >= e.size || i1 >= e.size || i2 >= e.size) return false
    // Тот же выбор, что weakerScale(weakerScale(e0, e1), e2) в triangleCellScale.
    var d = if (strongerScale(e[i0], e[i1]) == e[i0] && e[i0] != e[i1]) i1 else i0
    if (strongerScale(e[d], e[i2]) == e[d] && e[d] != e[i2]) d = i2
    val f = drivingFiberIndex(d)
    return f >= 0 && (cellFiberX[f] != 0f || cellFiberY[f] != 0f)
}

/** Признак «по волокну» у треугольника рабочего номера [t] — по его постоянному номеру. */
internal fun BodyState.refreshTriangleFiber(t: Int) {
    if (!fibersOn) return
    val id = triId[t]
    if (triFiberById.size <= id) triFiberById = triFiberById.copyOf(maxOf(body.triCount, id + 1))
    triFiberById[id] = triangleFiber(t)
}

/**
 * Массив cos² — на все связи. Зовётся в однопоточной части шага мышц (advanceCellScales) до пересчёта колец:
 * кольца пересчитываются и на воркерах (ParallelMuscles), и расширять общий массив там было бы гонкой. По той же
 * причине здесь расширяется и признак треугольников.
 */
internal fun BodyState.ensureFiberSize() {
    if (!fibersOn) return
    if (conFiberC2.size < conA.size) {
        // Ещё не посчитанные — «вдоль волокна» (как мышца без волокна), а не «поперёк» (нули копии).
        val old = conFiberC2.size
        conFiberC2 = conFiberC2.copyOf(conA.size)
        java.util.Arrays.fill(conFiberC2, old, conFiberC2.size, 1f)
    }
    if (triFiberById.size < body.triCount) triFiberById = triFiberById.copyOf(body.triCount)
}

/** cos² у связи [c] — вместе с её множителем мышцы (refreshCellScaleAround, recomputeCellScales). */
internal fun BodyState.refreshLinkFiber(c: Int) {
    if (!fibersOn) return
    if (conFiberC2.size < conA.size) ensureFiberSize()
    conFiberC2[c] = linkFiberC2(c)
}

/** Рост (BodyGrowth): множитель длины связи [c] под мышцей — по волокну |F·u|, без волокна min(1, s). */
internal fun BodyState.muscleLengthFactor(c: Int, scale: Double): Double {
    if (!fibersOn || scale >= 1.0) return minOf(1.0, scale)
    refreshLinkFiber(c)
    val c2 = conFiberC2[c]
    if (c2 >= 1f) return minOf(1.0, scale)
    return fiberLinkFactor(scale, c2.toDouble(), fiberThicken)
}

/** Рост: множитель удвоенной площади треугольника [w] под мышцей — по волокну det F, без волокна s². */
internal fun BodyState.triangleAreaFactor(w: Int, scale: Double): Double {
    if (fibersOn && scale < 1.0) {
        refreshTriangleFiber(w)
        if (triFiberById[triId[w]]) return fiberAreaFactor(scale, fiberThicken)
    }
    val a = minOf(1.0, scale)
    return a * a
}

/**
 * Мягкость связи расслабленной мышцы с волокном под командой [s] > 1: |F·e| одноосного растяжения вдоль волокна,
 * [c2] — cos² угла связи к волокну в позе покоя. Вдоль волокна — s (мягче, как без волокна), поперёк — 1 (обычная
 * ткань). Это только мягкость (ConstraintSolver.tensileCompliance): предел длины и разрыва — по s у всей мышцы.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun fiberRelaxedSoftness(s: Double, c2: Double): Double = sqrt(1.0 + (s * s - 1.0) * c2)
