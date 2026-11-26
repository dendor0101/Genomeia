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
internal inline fun fiberAcross2(s: Float, k: Float): Float =
    if (k == 0.0f) 1.0f else if (k == 1.0f) 1.0f / (s * s) else if (k == 0.5f) 1.0f / s else StrictMath.pow((s).toDouble(), (-2.0f * k).toDouble()).toFloat()

/** Множитель длины связи под командой s < 1: |F·u|, F = s вдоль волокна и s^−k поперёк; [c2] — cos²θ. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun fiberLinkFactor(s: Float, c2: Float, k: Float): Float =
    sqrt(s * s * c2 + fiberAcross2(s, k) * (1.0f - c2))

/** Множитель площади треугольника по волокну под командой s < 1: det F = s^(1−k). */
internal fun fiberAreaFactor(s: Float, k: Float): Float =
    if (k == 0.0f) s else if (k == 1.0f) 1.0f else if (k == 0.5f) sqrt(s) else StrictMath.pow((s).toDouble(), (1.0f - k).toDouble()).toFloat()

/**
 * Поправка связи мышцы с волокном под командой s < 1 (связь не вдоль волокна: cos²θ < 1). Как прежде, мышца тянет
 * отстающую ткань не сильнее потолка (MUSCLE_MAX_PULL); связь поперёк волокна, которую мышца удлиняет (k > 0), она
 * толкает тоже не сильнее потолка, а сжатие ниже длины покоя держит обычная ткань.
 */
internal fun fiberLinkCorrection(len: Float, rest: Float, s: Float, w: Float, alpha: Float, c2: Float, k: Float): Float {
    val target = rest * fiberLinkFactor(s, c2, k)
    val cap = MUSCLE_MAX_PULL * rest
    var over = len - target
    if (over > cap) over = cap
    else if (over < 0.0f && target > rest) {
        val tissue = if (len < rest) len - rest else 0.0f
        val muscle = maxOf(len, rest) - target
        over = tissue + (if (muscle < -cap) -cap else muscle)
    }
    return -over / (w + alpha)
}

/** Связь [c] сокращается по волокну: волокна заданы, команда — сжатие, и связь к волокну под углом. */
@Suppress("NOTHING_TO_INLINE")
private inline fun BodyState.linkAlongFiber(c: Int, scale: Float): Boolean =
    fibersOn && scale < 1.0f && c < conFiberC2.size && conFiberC2[c] < 1f

/** Доля длины покоя связи [c] для предела раздавливания: min(1, множитель длины). Без волокна — min(1, s). */
internal fun BodyState.limitShrink(c: Int, scale: Float): Float =
    if (linkAlongFiber(c, scale)) minOf(1.0f, fiberLinkFactor(scale, conFiberC2[c].toFloat(), fiberThicken))
    else minOf(1.0f, scale)

/** Длина покоя связи [c] для предела растяжения и разрыва: удлинённая поперёк волокна. Без волокна — [rest]. */
internal fun BodyState.limitRest(c: Int, rest: Float, scale: Float): Float =
    if (linkAlongFiber(c, scale)) rest * maxOf(1.0f, fiberLinkFactor(scale, conFiberC2[c].toFloat(), fiberThicken))
    else rest

// ЯДРА ВОЛОКНА — с массивами в параметрах (lengthsBeforeLoop, пункт 4): второй проход шага мышц (refreshAroundChanged)
// держит массивы в локальных и зовёт их на каждую связь и треугольник; одиночные пути — через обёртки ниже. Одна запись
// арифметики на оба пути.

/** Волокно клетки, задающей множитель клетке [v] ([srcOf] — cellScaleSrc): номер клетки с волокном или -1. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun drivingFiberIndexAt(v: Int, srcOf: IntArray, fx: FloatArray): Int {
    val src = if (v < srcOf.size) srcOf[v] else v
    return if (src in 0 until fx.size) src else -1
}

/** Конец связи (a, b), чей действующий множитель ([eff]) — множитель связи (слабейший; при равных — первый). */
@Suppress("NOTHING_TO_INLINE")
internal inline fun drivingEndAt(a: Int, b: Int, eff: FloatArray): Int {
    if (a >= eff.size || b >= eff.size) return a
    val ea = eff[a]; val eb = eff[b]
    return if (strongerScale(ea, eb) == ea && ea != eb) b else a
}

/**
 * cos² угла между связью [c] в позе покоя (bx, by — BodyDefinition.x, y) и волокном (fx, fy — cellFiberX, Y) клетки,
 * задающей ей множитель; 1 — волокна нет.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun linkFiberC2At(c: Int, ca: IntArray, cb: IntArray, eff: FloatArray, srcOf: IntArray,
                                  fx: FloatArray, fy: FloatArray, bx: FloatArray, by: FloatArray): Float {
    val i = ca[c]; val j = cb[c]
    val f = drivingFiberIndexAt(drivingEndAt(i, j, eff), srcOf, fx)
    if (f < 0) return 1f
    val x = fx[f]; val y = fy[f]
    if (x == 0f && y == 0f) return 1f
    val dx = (bx[j] - bx[i]).toFloat(); val dy = (by[j] - by[i]).toFloat()
    val l2 = dx * dx + dy * dy
    if (l2 < 1e-24f) return 1f
    val a = x * dx + y * dy
    return (a * a / l2).toFloat()
}

/** Треугольник [t] (рабочий номер) сокращается по волокну: у клетки, задающей ему множитель, есть волокно. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun triangleFiberAt(t: Int, ta: IntArray, tb: IntArray, tc: IntArray, bone: IntArray, eff: FloatArray,
                                    srcOf: IntArray, fx: FloatArray, fy: FloatArray): Boolean {
    val i0 = ta[t]; val i1 = tb[t]; val i2 = tc[t]
    if (bone[i0] >= 0 || bone[i1] >= 0 || bone[i2] >= 0) return false
    if (i0 >= eff.size || i1 >= eff.size || i2 >= eff.size) return false
    // Тот же выбор, что weakerScale(weakerScale(e0, e1), e2) в triangleCellScale.
    var d = if (strongerScale(eff[i0], eff[i1]) == eff[i0] && eff[i0] != eff[i1]) i1 else i0
    if (strongerScale(eff[d], eff[i2]) == eff[d] && eff[d] != eff[i2]) d = i2
    val f = drivingFiberIndexAt(d, srcOf, fx)
    return f >= 0 && (fx[f] != 0f || fy[f] != 0f)
}

/** cos² связи [c] к волокну: см. [linkFiberC2At]. */
private fun BodyState.linkFiberC2(c: Int): Float =
    linkFiberC2At(c, conA, conB, cellScaleEff, cellScaleSrc, cellFiberX, cellFiberY, body.x, body.y)

/** Треугольник [t] (рабочий номер) сокращается по волокну: см. [triangleFiberAt]. */
internal fun BodyState.triangleFiber(t: Int): Boolean =
    triangleFiberAt(t, triA, triB, triC, boneOf, cellScaleEff, cellScaleSrc, cellFiberX, cellFiberY)

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
internal fun BodyState.muscleLengthFactor(c: Int, scale: Float): Float {
    if (!fibersOn || scale >= 1.0f) return minOf(1.0f, scale)
    refreshLinkFiber(c)
    val c2 = conFiberC2[c]
    if (c2 >= 1f) return minOf(1.0f, scale)
    return fiberLinkFactor(scale, c2.toFloat(), fiberThicken)
}

/** Рост: множитель удвоенной площади треугольника [w] под мышцей — по волокну det F, без волокна s². */
internal fun BodyState.triangleAreaFactor(w: Int, scale: Float): Float {
    if (fibersOn && scale < 1.0f) {
        refreshTriangleFiber(w)
        if (triFiberById[triId[w]]) return fiberAreaFactor(scale, fiberThicken)
    }
    val a = minOf(1.0f, scale)
    return a * a
}

/**
 * Мягкость связи расслабленной мышцы с волокном под командой [s] > 1: |F·e| одноосного растяжения вдоль волокна,
 * [c2] — cos² угла связи к волокну в позе покоя. Вдоль волокна — s (мягче, как без волокна), поперёк — 1 (обычная
 * ткань). Это только мягкость (ConstraintSolver.tensileCompliance): предел длины и разрыва — по s у всей мышцы.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun fiberRelaxedSoftness(s: Float, c2: Float): Float = sqrt(1.0f + (s * s - 1.0f) * c2)
