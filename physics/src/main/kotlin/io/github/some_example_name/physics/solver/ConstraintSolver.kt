package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

/** Текущая податливость изгиба. Отдельным методом — его читает сверка в SwimTuner. */
fun BodyState.bendCompliance(): Float = BEND_LEVELS[bendLevel]

fun BodyState.solveConstraints(h: Float) {
    if (conCount == 0) return
    val alpha = environment.softCompliance / (h * h)
    // ВСЁ В ЛОКАЛЬНЫЕ. Стадия идёт 64 раза за тик по пяти тысячам связей, и на таком
    // числе проходов заметно даже обращение к полю: у lateinit оно с проверкой.
    val px = px; val py = py; val invMass = invMass
    val conA = conA; val conB = conB; val conRest = conRest; val conMuscle = conMuscle
    val conDead = conDead; val ms = muscleScaleOf; val cs = if (cellScalesOn) conCellScale else null
    // Мягкость (BodyState.cellSoftness) — своим ядром; множители мышцы при ней ведутся всегда.
    val boundary = conBoundary
    val soft = if (softnessOn) conSoftness else null; val cellScaleAll = conCellScale
    val fiber = if (fibersOn) conFiberC2 else NO_FLOATS; val fiberK = fiberThicken // волокно мышцы (MuscleFiber.kt)
    val plan = distanceSweepOrder
    // План целого графа не должен оставлять цену умерших связей после упаковки.
    // Для укороченного списка используем плотный проход ниже; reset возвращает
    // исходный граф и снова делает план применимым. Порогов по доле смертей нет.
    if (plan != null && conCount == plan.forward.size) {
        val order = plan.forward
        val planA = plan.particleA; val planB = plan.particleB
        // Обратить весь список достаточно: последовательность связей КАЖДОЙ
        // частицы тоже обратится. Второй план с теми же зависимостями не нужен.
        if (sweepBackwards) {
            for (k in order.lastIndex downTo 0) {
                val c = order[k]
                if (soft != null) solveDistanceConstraintSoft(c, k, px, py, invMass, planA, planB, conRest, conMuscle, conDead, ms, cellScaleAll, soft, boundary, alpha, fiber, fiberK)
                else if (cs == null) solveDistanceConstraint(c, k, px, py, invMass, planA, planB, conRest, conMuscle, conDead, ms, alpha)
                else solveDistanceConstraintScaled(c, k, px, py, invMass, planA, planB, conRest, conMuscle, conDead, ms, cs, boundary, alpha, fiber, fiberK)
            }
        } else {
            for (k in order.indices) {
                val c = order[k]
                if (soft != null) solveDistanceConstraintSoft(c, k, px, py, invMass, planA, planB, conRest, conMuscle, conDead, ms, cellScaleAll, soft, boundary, alpha, fiber, fiberK)
                else if (cs == null) solveDistanceConstraint(c, k, px, py, invMass, planA, planB, conRest, conMuscle, conDead, ms, alpha)
                else solveDistanceConstraintScaled(c, k, px, py, invMass, planA, planB, conRest, conMuscle, conDead, ms, cs, boundary, alpha, fiber, fiberK)
            }
        }
        return
    }
    // Направление прежнее, но шаг каждого цикла постоянный. Так JIT видит
    // обычный счётный цикл; формула ниже разворачивается Kotlin-компилятором.
    if (sweepBackwards) {
        for (c in conCount - 1 downTo 0) {
            if (soft != null) solveDistanceConstraintSoft(c, c, px, py, invMass, conA, conB, conRest, conMuscle, conDead, ms, cellScaleAll, soft, boundary, alpha, fiber, fiberK)
            else if (cs == null) solveDistanceConstraint(c, c, px, py, invMass, conA, conB, conRest, conMuscle, conDead, ms, alpha)
            else solveDistanceConstraintScaled(c, c, px, py, invMass, conA, conB, conRest, conMuscle, conDead, ms, cs, boundary, alpha, fiber, fiberK)
        }
    } else {
        for (c in 0 until conCount) {
            if (soft != null) solveDistanceConstraintSoft(c, c, px, py, invMass, conA, conB, conRest, conMuscle, conDead, ms, cellScaleAll, soft, boundary, alpha, fiber, fiberK)
            else if (cs == null) solveDistanceConstraint(c, c, px, py, invMass, conA, conB, conRest, conMuscle, conDead, ms, alpha)
            else solveDistanceConstraintScaled(c, c, px, py, invMass, conA, conB, conRest, conMuscle, conDead, ms, cs, boundary, alpha, fiber, fiberK)
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun solveDistanceConstraint(
    c: Int, endpoint: Int, px: FloatArray, py: FloatArray, invMass: FloatArray,
    conA: IntArray, conB: IntArray, conRest: FloatArray, conMuscle: IntArray,
    conDead: BooleanArray, ms: FloatArray, alpha: Float,
) {
    // Порванная связь не тянет ни мгновения — даже до пересборки топологии.
    if (conDead[c]) return
    val i = conA[endpoint]; val j = conB[endpoint]
    val wi = invMass[i]; val wj = invMass[j]
    val w = wi + wj
    if (w == 0.0f) return
    var dx = px[i] - px[j]
    var dy = py[i] - py[j]
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1e-12f) return
    // ОДНО ДЕЛЕНИЕ НА СВЯЗЬ ВМЕСТО ДВУХ (24.11.2026, находка 41): 1/len и два умножения, а не dx /= len; dy /= len.
    // Закон тот же, биты другие. Так же во всех ядрах связей и изгибов. Деление, в отличие от умножения, у процессора
    // не конвейерное, а на телефоне (Galaxy A21s, ядра без внеочередного исполнения) ещё и стоит на пути каждой связи.
    val inv = 1.0f / len
    dx *= inv; dy *= inv
    val m = conMuscle[c]
    val rest = conRest[c]
    val scale = if (m < 0) 1.0f else ms[m]
    var over = len - rest * scale
    // Мышца тянет отстающую ткань не сильнее связи, растянутой на MUSCLE_MAX_PULL — см. там.
    if (scale < 1.0f && over > MUSCLE_MAX_PULL * rest) over = MUSCLE_MAX_PULL * rest
    val dL = -over / (w + alpha)
    px[i] += dx * dL * wi; py[i] += dy * dL * wi
    px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
}

/**
 * Ядро с командой [cs] мышцы клетки. Закон сокращения и расслабления задаёт muscleLinkCorrection.
 * Отдельной функцией, а не параметром прежней: мир без множителей клеток (стенд) идёт
 * прежним байткодом — лишнее чтение и параметр в самом горячем ядре стоили ~5% тика.
 */
internal fun solveDistanceConstraintScaled(
    c: Int, endpoint: Int, px: FloatArray, py: FloatArray, invMass: FloatArray,
    conA: IntArray, conB: IntArray, conRest: FloatArray, conMuscle: IntArray,
    conDead: BooleanArray, ms: FloatArray, cs: FloatArray, boundary: BooleanArray, alpha: Float,
    fiber: FloatArray = NO_FLOATS, // волокно мышцы (MuscleFiber.kt); без волокон — пустой массив
    fiberK: Float = 0.0f, // показатель утолщения поперёк волокна (BodyState.fiberThicken)
) {
    if (conDead[c]) return
    val i = conA[endpoint]; val j = conB[endpoint]
    val wi = invMass[i]; val wj = invMass[j]
    val w = wi + wj
    if (w == 0.0f) return
    var dx = px[i] - px[j]
    var dy = py[i] - py[j]
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1e-12f) return
    val inv = 1.0f / len // одно деление, см. solveDistanceConstraint
    dx *= inv; dy *= inv
    val m = conMuscle[c]
    val scale = (if (m < 0) 1.0f else ms[m]) * cs[c]
    val dL = muscleLinkCorrection(len, conRest[c], scale, w, alpha, boundary, c, fiber, fiberK)
    px[i] += dx * dL * wi; py[i] += dy * dL * wi
    px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
}

/**
 * То же ядро с мягкостью ткани (BodyState.cellSoftness): податливость × множитель связи
 * [soft]. Множитель мышцы [cs] при мягкости ведётся всегда (refreshCellScaleRing), поэтому
 * ядро одно на оба. Отдельной функцией по той же причине, что и Scaled: мир без мягких
 * клеток не читает лишний массив.
 */
internal fun solveDistanceConstraintSoft(
    c: Int, endpoint: Int, px: FloatArray, py: FloatArray, invMass: FloatArray,
    conA: IntArray, conB: IntArray, conRest: FloatArray, conMuscle: IntArray,
    conDead: BooleanArray, ms: FloatArray, cs: FloatArray, soft: FloatArray, boundary: BooleanArray, alpha: Float,
    fiber: FloatArray = NO_FLOATS, // волокно мышцы (MuscleFiber.kt); без волокон — пустой массив
    fiberK: Float = 0.0f, // показатель утолщения поперёк волокна (BodyState.fiberThicken)
) {
    if (conDead[c]) return
    val i = conA[endpoint]; val j = conB[endpoint]
    val wi = invMass[i]; val wj = invMass[j]
    val w = wi + wj
    if (w == 0.0f) return
    var dx = px[i] - px[j]
    var dy = py[i] - py[j]
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1e-12f) return
    val inv = 1.0f / len // одно деление, см. solveDistanceConstraint
    dx *= inv; dy *= inv
    val m = conMuscle[c]
    val scale = (if (m < 0) 1.0f else ms[m]) * cs[c]
    val dL = muscleLinkCorrection(len, conRest[c], scale, w, alpha * soft[c], boundary, c, fiber, fiberK)
    px[i] += dx * dL * wi; py[i] += dy * dL * wi
    px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
}

/** solveDistanceConstraintScaled без мышц-кластеров (conMuscle < 0): см. solveBlockLinksCells. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun solveDistanceConstraintCells(
    c: Int, px: FloatArray, py: FloatArray, invMass: FloatArray, conA: IntArray, conB: IntArray,
    conRest: FloatArray, conDead: BooleanArray, cs: FloatArray, boundary: BooleanArray, alpha: Float,
    fiber: FloatArray = NO_FLOATS, // волокно мышцы (MuscleFiber.kt); без волокон — пустой массив
    fiberK: Float = 0.0f, // показатель утолщения поперёк волокна (BodyState.fiberThicken)
) {
    if (conDead[c]) return
    val i = conA[c]; val j = conB[c]
    val wi = invMass[i]; val wj = invMass[j]
    val w = wi + wj
    if (w == 0.0f) return
    var dx = px[i] - px[j]
    var dy = py[i] - py[j]
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1e-12f) return
    val inv = 1.0f / len // одно деление, см. solveDistanceConstraint
    dx *= inv; dy *= inv
    val dL = muscleLinkCorrection(len, conRest[c], cs[c].toFloat(), w, alpha, boundary, c, fiber, fiberK)
    px[i] += dx * dL * wi; py[i] += dy * dL * wi
    px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
}

/** solveDistanceConstraintSoft без мышц-кластеров (conMuscle < 0): см. solveBlockLinksCells. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun solveDistanceConstraintSoftCells(
    c: Int, px: FloatArray, py: FloatArray, invMass: FloatArray, conA: IntArray, conB: IntArray,
    conRest: FloatArray, conDead: BooleanArray, cs: FloatArray, soft: FloatArray, boundary: BooleanArray, alpha: Float,
    fiber: FloatArray = NO_FLOATS, // волокно мышцы (MuscleFiber.kt); без волокон — пустой массив
    fiberK: Float = 0.0f, // показатель утолщения поперёк волокна (BodyState.fiberThicken)
) {
    if (conDead[c]) return
    val i = conA[c]; val j = conB[c]
    val wi = invMass[i]; val wj = invMass[j]
    val w = wi + wj
    if (w == 0.0f) return
    var dx = px[i] - px[j]
    var dy = py[i] - py[j]
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1e-12f) return
    val inv = 1.0f / len // одно деление, см. solveDistanceConstraint
    dx *= inv; dy *= inv
    val dL = muscleLinkCorrection(len, conRest[c], cs[c].toFloat(), w, alpha * soft[c], boundary, c, fiber, fiberK)
    px[i] += dx * dL * wi; py[i] += dy * dL * wi
    px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
}

fun BodyState.solveLinkMaxLength() {
    if (tearingOn) SealStats.tearingOnTicks++ else SealStats.tearingOffTicks++
    val w = activeWorkers
    val blocksLayout = constraintBlocks
    // ПОРЯДОК ПО БЛОКАМ — ПРИ ЛЮБОМ ЧИСЛЕ ОРГАНИЗМОВ (25.11, находка 42): раньше при одном организме проход шёл общим
    // списком контура, при нескольких — по блокам, и тело из двух блоков в стенде (одно тело) и в игре (тело и пул
    // свободных частиц) правилось в разном порядке — гребок рыбы A-11 расходился с 16-го подшага первого тика.
    if (w != null && (organismCount > 1 || blocksLayout != null) && !dbgSealOn && !(DEBUG_CHECKS && dbgLinkExtremaOn)) {
        // По организмам: связи контура разных организмов не делят частиц. См. ParallelLimits.
        parallelPhysics(w).solveGlobalLimits()
        return
    }
    // Без воркеров — тот же порядок, что у них: по блокам, затем швы, затем хвост прохода
    // (ParallelPhysics.globalLimitsByBlocks).
    if (blocksLayout != null && !dbgSealOn && !(DEBUG_CHECKS && dbgLinkExtremaOn)) {
        blocksLayout.beginLimitPass()
        for (bucket in 0 until blocksLayout.buckets) {
            if (bucket < blocksLayout.seam && blocksLayout.awake[bucket] == 0) continue
            solveLimitBucketSerial(blocksLayout, bucket, -1)
        }
        solveLimitPassTail(blocksLayout)
        blocksLayout.endLimitPass()
        return
    }
    // Всё в локальные: при MAXLEN_EARLY стадия идёт 144 раза за тик.
    val px = px; val py = py; val invMass = invMass
    val conA = conA; val conB = conB; val conRest = conRest; val conMuscle = conMuscle
    val conDead = conDead; val ms = muscleScaleOf; val cs = if (cellScalesOn) conCellScale else null
    // Список дополняется killEdgeNow прямо в этом проходе. Внутренние связи
    // по-прежнему участвуют в solveConstraints, но здесь их координаты не читаем.
    val limits = boundaryLinkLimits
    limits.beginPass()
    var cursor = 0
    while (cursor < limits.count) {
        val c = limits.ids[cursor++]
        if (c < 0) continue
        if (conDead[c]) continue
        val i = conA[c]; val j = conB[c]
        val dx = px[i] - px[j]
        val dy = py[i] - py[j]
        // КОРЕНЬ БЕРЁТСЯ ТОЛЬКО КОГДА НУЖЕН.
        //
        // Стадия идёт 144 раза за тик по связям текущего контура, и у подавляющего
        // большинства из них всё в порядке: ни сжатия до порога, ни растяжения сверх
        // предела. А корень считался ВСЕГДА и стоит 13–15 тактов латентности в
        // зависимой цепочке. Все пороги сравниваются с длиной, то есть с квадратом
        // длины сравниваются их квадраты — ровно то же самое, без корня.
        val len2 = dx * dx + dy * dy
        val mus = conMuscle[c]
        val base = if (mus < 0) 1.0f else ms[mus]
        val scale = if (cs == null) base else base * cs[c]
        val rest = conRest[c]
        val restScale = rest * limitShrink(c, scale)
        val crushLim = LINK_CRUSH_RATIO * restScale
        val max = linkMaxLength(limitRest(c, rest, scale), scale)
        val crushed = tearingOn && len2 < crushLim * crushLim
        val over = len2 > max * max
        // Запас до разрыва за прогон — только когда он кому-то интересен: у худших
        // значений корень всё равно придётся взять, но это единицы связей за тик.
        // Запас до порогов копится В КВАДРАТАХ, корень берётся один раз при чтении:
        // сравнение «ближе ли этот запас прежнего» от возведения в квадрат не меняется.
        if (DEBUG_CHECKS && dbgLinkExtremaOn) {
            if (invMass[i] + invMass[j] == 0.0f) continue
            val cr2 = len2 / (restScale * restScale)
            if (cr2 < dbgWorstCrush2) dbgWorstCrush2 = cr2
            val st2 = len2 / (rest * rest)
            if (st2 > dbgWorstStretch2) dbgWorstStretch2 = st2
        }
        if (!crushed && !over) continue
        // Массы нужны только если есть нарушение. Пустой проход не читает
        // ещё два значения по индексам концов; порядок поправок остаётся прежним.
        if (invMass[i] + invMass[j] == 0.0f) continue
        solveLinkLimitViolation(c, len2, crushed, scale)
    }
    limits.endPass()
}

/**
 * Одна связь общего прохода предела в одном потоке — тело цикла solveLinkMaxLength
 * (без отладочного разбора крайних значений). Им параллельный проход дорабатывает
 * организмы, остановленные перед событием топологии.
 */
internal fun BodyState.solveLimitItemSerial(c: Int) {
    if (c < 0 || conDead[c]) return
    val i = conA[c]; val j = conB[c]
    val dx = px[i] - px[j]
    val dy = py[i] - py[j]
    val len2 = dx * dx + dy * dy
    val mus = conMuscle[c]
    val base = if (mus < 0) 1.0f else muscleScaleOf[mus]
    val scale = if (cellScalesOn) base * conCellScale[c] else base
    val rest = conRest[c]
    val restScale = rest * limitShrink(c, scale)
    val crushLim = LINK_CRUSH_RATIO * restScale
    val max = linkMaxLength(limitRest(c, rest, scale), scale)
    val crushed = tearingOn && len2 < crushLim * crushLim
    val over = len2 > max * max
    if (!crushed && !over) return
    if (invMass[i] + invMass[j] == 0.0f) return
    solveLinkLimitViolation(c, len2, crushed, scale)
}

/**
 * Набор [bucket] общего прохода пределов в одном потоке со связи [from] (-1 — с начала): обход без воркеров и
 * доработка набора, остановленного воркером перед событием. Края, открытые разрывами этого прохода, пропускаются —
 * их проверит хвост прохода (solveLimitPassTail). Плотный список набора читается без копии: он пересобирается
 * только вызовом dense, а события лишь дописывают связный список и помечают его. Связь остановки жива и стоит на
 * своём месте: событие другого набора трогает только свой набор и швы, а в начале списка — только удаляет мёртвые.
 */
internal fun BodyState.solveLimitBucketSerial(layout: ConstraintBlocks, bucket: Int, from: Int) {
    val items = layout.limits.dense(bucket); val n = layout.limits.denseCount[bucket]
    var x = 0
    if (from >= 0) { while (x < n && items[x] != from) x++; if (x == n) x = 0 }
    while (x < n) {
        val c = items[x++]
        if (layout.passAddedN > 0 && layout.addedInPass(c)) continue
        solveLimitItemSerial(c)
    }
}

/** Хвост общего прохода пределов: края, открытые его разрывами, в порядке событий; дописанные по пути — тоже. */
internal fun BodyState.solveLimitPassTail(layout: ConstraintBlocks) {
    var k = 0
    while (k < layout.passAddedN) solveLimitItemSerial(layout.passAdded[k++])
}

/** Старый формат сигнала клетки 0.5..1.5 остаётся совместим с геномами и снимками. */
private fun relaxation(scale: Float): Float = ((scale - 1.0f) * 2.0f).coerceAtMost(1.0f)

// При +1 податливость под нагрузкой вчетверо выше. Сжатие и нулевой сигнал — прежний материал.
internal fun tensileCompliance(alpha: Float, scale: Float): Float =
    alpha * (1.0f + 3.0f * relaxation(scale))

private const val RELAXED_MAX_STRETCH = 1.25f

internal fun linkMaxLength(rest: Float, scale: Float): Float =
    if (scale <= 1.0f) LINK_MAX_STRETCH * rest
    else rest * (LINK_MAX_STRETCH + (RELAXED_MAX_STRETCH - LINK_MAX_STRETCH) * relaxation(scale))

@Suppress("NOTHING_TO_INLINE")
internal inline fun muscleLinkCorrection(len: Float, rest: Float, scale: Float, w: Float, alpha: Float,
                                         boundary: BooleanArray, c: Int, fiber: FloatArray = NO_FLOATS, fiberK: Float = 0.0f): Float {
    // [fiber] без волокон — пустой массив, а не null: проверка `c < fiber.size` даёт то же «волокна нет», а проверка
    // на null внутри цикла заставляла бы читать длину массива на каждой связи мышцы (см. lengthsBeforeLoop).
    if (scale <= 1.0f) {
        // Мышца с волокном (MuscleFiber.kt): своя цель у каждой связи не вдоль волокна.
        if (scale < 1.0f && c < fiber.size && fiber[c] < 1f) return fiberLinkCorrection(len, rest, scale, w, alpha, fiber[c].toFloat(), fiberK)
        var over = len - rest * scale
        // Мышца тянет отстающую ткань не сильнее связи, растянутой на MUSCLE_MAX_PULL — см. там.
        if (scale < 1.0f && over > MUSCLE_MAX_PULL * rest) over = MUSCLE_MAX_PULL * rest
        return -over / (w + alpha)
    }
    // Расслабленная мышца с волокном мягче только вдоль волокна, а предел длины и разрыва — у всей мышцы (MuscleFiber.kt).
    val soft = if (c < fiber.size && fiber[c] < 1f) fiberRelaxedSoftness(scale, fiber[c].toFloat()) else scale
    return relaxedLinkCorrection(len, rest, scale, w, alpha, boundary, c, soft)
}

/** Связь расслабленной мышцы: мягче на растяжение по [soft] (с волокном — только вдоль него), предел длины — по [scale]. */
internal fun relaxedLinkCorrection(len: Float, rest: Float, scale: Float, w: Float, alpha: Float,
                                   boundary: BooleanArray, c: Int, soft: Float = scale): Float {
    val stretch = len - rest
    if (stretch <= 0.0f) return -stretch / (w + alpha)
    val elastic = -stretch / (w + tensileCompliance(alpha, soft))
    // У контура предел и разрыв решаются вместе следующей стадией. У внутренней связи её нет.
    if (len <= LINK_MAX_STRETCH * rest || boundary[c]) return elastic
    return minOf(elastic, (linkMaxLength(rest, scale) - len) / w)
}

/**
 * ДОРАСТАЮЩАЯ СВЯЗЬ ВНЕ ПРЕДЕЛА И РАЗРЫВА (деление, 02.11). Её длина покоя сама идёт от
 * фактической к цели (easeNewborn): новорождённая у родителя далеко от части соседей, и падающий
 * вслед за длиной покоя предел рвал бы связь «растяжением», хотя её никто не тянул (A-11: ~980 из
 * ~1030 разрывов роста — молодые связи, растяжение до 4.3 длины покоя; у правильной треугольной
 * сетки расстояние от родителя до общих соседей равно длине связи, и разрывов нет). Читается
 * только на редком пути нарушения.
 */
internal fun BodyState.isGrowingLink(c: Int): Boolean = c < conGrowTarget.size && conGrowTarget[c] > 0.0f

/** Редкие разрывы отдельно от цикла: меньше байткода и живых локальных в обычном проходе. */
fun BodyState.solveLinkLimitViolation(c: Int, len2: Float, crushed: Boolean, scale: Float) {
    if (isGrowingLink(c)) return
    val i = conA[c]; val j = conB[c]
    val mus = conMuscle[c]
    val restL = limitRest(c, conRest[c], scale) // удлинённая поперёк волокна (MuscleFiber.kt)
    val max = linkMaxLength(restL, scale)
    val len = sqrt(len2)
    // РАЗДАВЛЕННАЯ СВЯЗЬ РВЁТСЯ ТАК ЖЕ, КАК ПЕРЕРАСТЯНУТАЯ. См. LINK_CRUSH_RATIO.
    if (crushed) {
        linkTorn[c] = true; conDead[c] = true; tearsPending = true; killEdgeNow(conEdge[c])
        crushedCell[i] = true; crushedCell[j] = true
        dbgTornCrush++; if (mus >= 0) dbgTornCrushMuscle++
        dbgTear(c, i, j, "сжатие", len / (conRest[c] * minOf(1.0f, scale)))
        return
    }
    if (dbgSealOn && conBoundary[c]) dbgSeal(c, i, j, len)
    @Suppress("UNUSED_EXPRESSION")
    // ЩЕЛЬ СТАЛА ПРОХОДИМОЙ — РВЁМ. Проверка идёт ДО выхода по пределу длины:
    // щель открывается раньше, чем связь дотягивается до своего потолка.
    // См. membranePassTear.
    if (tearingOn && membranePassTear && conBoundary[c] &&
        len > (contacts?.contactRadiusOf(i) ?: 0.0f) +
            (contacts?.contactRadiusOf(j) ?: 0.0f) + 2.0f * dbgSealMinR) {
        linkTorn[c] = true; conDead[c] = true; tearsPending = true; killEdgeNow(conEdge[c])
        membranePassCount++; SealStats.torn++
        dbgTear(c, i, j, "щель", len / conRest[c])
        return
    }
    if (len < 1e-12f) return
    val overStrain = (len - max) / conRest[c]
    if (overStrain > dbgMaxOverStrain) dbgMaxOverStrain = overStrain
    // Пик недобора за всё время: сколько связь хотела сверх предела.
    val tear = if (conTendon[c]) linkTearStrain * tendonTear else linkTearStrain
    if (len - LINK_MAX_STRETCH * restL > tear * restL) {
        linkTorn[c] = true
        if (tearingOn) { conDead[c] = true; tearsPending = true; killEdgeNow(conEdge[c]) }
        dbgTornPull++; if (mus >= 0) dbgTornPullMuscle++
        dbgTear(c, i, j, "растяжение", len / conRest[c])
    }
    // МЕМБРАНА ХРУПЧЕ ТКАНИ. См. MEMBRANE_TEAR_STRAIN.
    if (tearingOn && membraneTearStrain >= 0.0f && conBoundary[c] && !conDead[c] &&
        len - max > membraneTearStrain * restL) {
        linkTorn[c] = true; conDead[c] = true; tearsPending = true; killEdgeNow(conEdge[c])
        membraneTearCount++
    }
    applyLinkLimitCorrection(i, j, len, max)
    linkCapHits++
}

/**
 * Поправка перерастянутой связи до предела. Общая для обычного прохода и для
 * параллельного (ParallelBlocks): одна и та же арифметика — одни и те же биты.
 *
 * Тот же потолок поправки за подшаг, что и у контакта, и по той же
 * причине: стадия ЖЁСТКАЯ, перерастяжение она снимает целиком за один
 * подшаг, а updateVelocities делит поправку на крошечное h. При загибе
 * связи растягиваются сильно, и отсюда шёл остаток выброса скорости.
 * Делится по паре в тех же долях, поэтому импульс пары сохраняется.
 */
internal fun BodyState.applyLinkLimitCorrection(i: Int, j: Int, len: Float, max: Float) {
    var wi = invMass[i]; var wj = invMass[j]
    // КОСТНЫЙ КОНЕЦ СВЯЗИ ПРЕДЕЛ НЕ ДВИГАЕТ (16.11.2026). Предел стоит ПОСЛЕ проекции кости — нарочно,
    // чтобы последнее слово было за сшитой мембраной. Но поправку он делил между концами по массам
    // клеток, и клетку кости вытягивало из кости вслед за мягкой соседкой: до следующей проекции кость
    // стояла смятой, а контакты и скорости считались по смятой. Стенд free/hit/plates-plates (две
    // костные пластины с мягким суставом, удар 2 связи за тик): кость искажена на 0.18 связи при
    // допуске 0.05; трассировка — искажение растёт в подшагах без единого контакта и без обрезки.
    // Теперь у связи «кость — мягкая клетка» всю поправку берёт мягкая клетка, а кость остаётся в позе.
    // Отдача кости при этом не прикладывается; её за тик возвращает организму учёт импульса
    // (TickMomentum) — обе клетки в одном организме.
    if (bonesRigid) {
        val boneI = boneOf[i] >= 0; val boneJ = boneOf[j] >= 0
        if (boneI && !boneJ && wj > 0.0f) wi = 0.0f else if (boneJ && !boneI && wi > 0.0f) wj = 0.0f
    }
    val w = wi + wj
    val dx = (px[i] - px[j]) / len; val dy = (py[i] - py[j]) / len
    var dL = -(len - max) / w
    val capL = LINK_MAX_STEP * linkScale / w
    if (dL < -capL) dL = -capL
    px[i] += dx * dL * wi; py[i] += dy * dL * wi
    px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
}

/**
 * Даст ли нарушение предела событие топологии — разрыв, раздавливание или пометку
 * порванной — без единой записи. Условия и их порядок те же, что в
 * solveLinkLimitViolation; параллельный проход по такому нарушению останавливает
 * блок, и событие исполняется в одном потоке в исходном порядке. См. ParallelBlocks.
 */
internal fun BodyState.limitViolationIsEvent(c: Int, len2: Float, crushed: Boolean, scale: Float): Boolean {
    if (crushed) return true
    val i = conA[c]; val j = conB[c]
    val len = sqrt(len2)
    if (tearingOn && membranePassTear && conBoundary[c] &&
        len > (contacts?.contactRadiusOf(i) ?: 0.0f) +
            (contacts?.contactRadiusOf(j) ?: 0.0f) + 2.0f * dbgSealMinR) return true
    if (len < 1e-12f) return false
    val restL = limitRest(c, conRest[c], scale) // удлинённая поперёк волокна (MuscleFiber.kt)
    val max = linkMaxLength(restL, scale)
    val tear = if (conTendon[c]) linkTearStrain * tendonTear else linkTearStrain
    if (len - LINK_MAX_STRETCH * restL > tear * restL) return true
    return tearingOn && membraneTearStrain >= 0.0f && conBoundary[c] && !conDead[c] &&
        len - max > membraneTearStrain * restL
}

/**
 * Односторонняя: только не даёт сойтись ближе позы покоя. Створки по всему миру (эталон, тесты) — тот же закон, что
 * solveBlockFlaps: у новорождённой — взрослый угол при нынешних длинах, не длиннее взрослой.
 */
fun BodyState.solveFlaps(h: Float) {
    if (flapCount == 0) return
    val alpha = FLAP_COMPLIANCE / (h * h)
    val ramp = if (rampN > 0) contactRamp else null
    for (c in 0 until flapCount) {
        val i = flapA[c]; val j = flapB[c]
        val wi = invMass[i]; val wj = invMass[j]
        val w = wi + wj
        if (w == 0.0f) continue
        var dx = px[i] - px[j]
        var dy = py[i] - py[j]
        val len = sqrt(dx * dx + dy * dy)
        var rest = flapRest[c]
        if (ramp != null && (ramp[i] < 1f || ramp[j] < 1f || ramp[flapV[c]] < 1f)) rest = minOf(rest, flapAngleRest(flapV[c], i, j))
        if (len >= rest || len < 1e-12f) continue
        dx /= len; dy /= len
        val dL = -(len - rest) / (w + alpha)
        px[i] += dx * dL * wi; py[i] += dy * dL * wi
        px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
    }
}

fun BodyState.solveBend(h: Float) {
    val compliance = bendCompliance()
    if (compliance < 0.0f) return
    val alpha = compliance / (h * h)
    for (c in 0 until bendCount) {
        val i = bendA[c]; val j = bendB[c]
        val wi = invMass[i]; val wj = invMass[j]
        val w = wi + wj
        if (w == 0.0f) continue
        var dx = px[i] - px[j]
        var dy = py[i] - py[j]
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-12f) continue
        val inv = 1.0f / len // одно деление, см. solveDistanceConstraint
        dx *= inv; dy *= inv
        val dL = -(len - bendRest[c]) / (w + alpha)
        px[i] += dx * dL * wi; py[i] += dy * dL * wi
        px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
    }
}

fun BodyState.solveAreas(h: Float) {
    val alpha = environment.areaCompliance / (h * h)
    // Вывернутым треугольникам своя, нулевая — см. AREA_COMPLIANCE_INVERTED.
    val alphaInverted = AREA_COMPLIANCE_INVERTED / (h * h)
    val maxStep = AREA_MAX_STEP * linkScale
    // Всё в локальные: стадия идёт 64 раза за тик по трём тысячам треугольников.
    val px = px; val py = py; val invMass = invMass
    val ta = triA; val tb = triB; val tc = triC
    val rest2 = triRestArea2; val tm = triMuscle; val ms = muscleScaleOf; val ts = if (cellScalesOn) triCellScale else null
    val ks = if (softnessOn) triSoftness else null
    val alive = baked.triAlive; val tid = triId
    val fibersOn = fibersOn; val fiberK = fiberThicken // волокно мышцы (MuscleFiber.kt)
    for (t in 0 until triCount) {
        // Треугольник умирает вместе с любым своим ребром И СРАЗУ, ещё до пересборки:
        // держать площадь ткани, которой уже нет, он не должен. См. tearQueue.
        if (!alive[tid[t]]) continue
        val i0 = ta[t]; val i1 = tb[t]; val i2 = tc[t]
        val x0 = px[i0]; val y0 = py[i0]
        val x1 = px[i1]; val y1 = py[i1]
        val x2 = px[i2]; val y2 = py[i2]

        val g0x = y1 - y2; val g0y = x2 - x1
        val g1x = y2 - y0; val g1y = x0 - x2
        val g2x = y0 - y1; val g2y = x1 - x0

        val w0 = invMass[i0]; val w1 = invMass[i1]; val w2 = invMass[i2]
        val denom = w0 * (g0x * g0x + g0y * g0y) +
            w1 * (g1x * g1x + g1y * g1y) +
            w2 * (g2x * g2x + g2y * g2y)
        if (denom < 1e-18f) continue

        // Площадь покоя едет вместе с длинами: иначе мышца тянет треугольник вниз,
        // а несжимаемая площадь держит его на месте, и ткань запирает.
        val m = tm[t]
        val s0 = if (m < 0) 1.0f else ms[m]
        val s = if (ts == null) s0 else s0 * ts[t]
        val active = minOf(1.0f, s)
        // Мышца с волокном (MuscleFiber.kt): площадь × s^(1−k).
        val fiberArea = s < 1.0f && fibersOn && triId[t] < triFiberById.size && triFiberById[triId[t]]
        val restArea2 = if (fiberArea) rest2[t].toFloat() * fiberAreaFactor(s, fiberK) else rest2[t].toFloat() * active * active
        val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
        // Мягкость ткани (BodyState.cellSoftness) — множитель податливости; вывернутым своя, нулевая.
        val material = if (ks == null) alpha else alpha * ks[t]
        val alphaT = if (s > 1.0f && area2 > restArea2) tensileCompliance(material, s) else material
        // Плавный переход вместо ступеньки — см. AREA_SMOOTH_RAMP.
        val a = if (AREA_SMOOTH_RAMP) {
            val q = (area2 / restArea2).coerceIn(0.0f, 1.0f)
            alphaInverted + (alphaT - alphaInverted) * q * q
        } else if (area2 < 0.0f) alphaInverted else alphaT
        var excess = area2 - restArea2
        // Площадь мышцы тянет не сильнее удвоенной доли площади покоя — см. MUSCLE_MAX_PULL.
        if (if (fiberArea) fiberAreaFactor(s, fiberK) < 1.0f else active < 1.0f) { val cap = 2.0f * MUSCLE_MAX_PULL * rest2[t]; if (excess > cap) excess = cap }
        var dL = -excess / (denom + a)
        // Потолок на длину поправки — см. AREA_MAX_STEP.
        if (maxStep > 0.0f) dL = capAreaStep(dL, denom, maxStep)

        px[i0] += w0 * dL * g0x; py[i0] += w0 * dL * g0y
        px[i1] += w1 * dL * g1x; py[i1] += w1 * dL * g1y
        px[i2] += w2 * dL * g2x; py[i2] += w2 * dL * g2y
    }
}

/**
 * Потолок на длину поправки площади (AREA_MAX_STEP): длина поправки — |dL| · sqrt(denom).
 *
 * КОРЕНЬ — ТОЛЬКО ЗА ПОТОЛКОМ (24.11.2026, находка 41). Раньше корень брали у каждого треугольника на каждом подшаге,
 * хотя потолок срабатывает лишь при сильном сжатии. Сравнение квадратов решает то же без корня; срезанная поправка
 * считается прежней формулой. Биты те же, кроме самой границы, где округление квадратов может решить иначе — тогда
 * поправка отличается множителем, равным единице до последнего знака.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun capAreaStep(dL: Float, denom: Float, maxStep: Float): Float {
    if (dL * dL * denom <= maxStep * maxStep) return dL
    return dL * (maxStep / (abs(dL) * sqrt(denom)))
}
