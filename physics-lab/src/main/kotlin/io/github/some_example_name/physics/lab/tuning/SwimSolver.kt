package io.github.some_example_name.physics.lab.tuning

import io.github.some_example_name.physics.CANCEL_INTERNAL_SPIN
import io.github.some_example_name.physics.FLAP_COMPLIANCE
import io.github.some_example_name.physics.SOLVER_ITERS
import io.github.some_example_name.physics.SWEEP_FLIP
import io.github.some_example_name.physics.BoundaryContacts
import io.github.some_example_name.physics.applyPlasticity
import io.github.some_example_name.physics.countInverted
import io.github.some_example_name.physics.lab.session.DemoSettings
import io.github.some_example_name.physics.InternalMomentum
import io.github.some_example_name.physics.applyMediumDrag
import io.github.some_example_name.physics.applyNormalDrag
import io.github.some_example_name.physics.applyViscosity
import io.github.some_example_name.physics.bendCompliance
import io.github.some_example_name.physics.cancelInternalSpin
import io.github.some_example_name.physics.clampSpeed
import io.github.some_example_name.physics.comX
import io.github.some_example_name.physics.comY
import io.github.some_example_name.physics.integrate
import io.github.some_example_name.physics.markInternal
import io.github.some_example_name.physics.muscleScale
import io.github.some_example_name.physics.projectBone
import io.github.some_example_name.physics.solveAreas
import io.github.some_example_name.physics.solveBend
import io.github.some_example_name.physics.solveConstraints
import io.github.some_example_name.physics.solveFlaps
import io.github.some_example_name.physics.solveLinkMaxLength
import io.github.some_example_name.physics.updateVelocities
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Независимое зеркало численного конвейера для подбора мышц и сверки ядра.
 * Изначально понадобилось, потому что const val основного решателя нельзя менять
 * во время подбора. Сейчас сохраняется также как независимая проверка рефакторинга.
 * При умолчаниях и одинаковой модели среды позиции/скорости обязаны совпадать до
 * последнего бита: SwimTuner не начинает подбор без verifyAgainstDemo(). Топология
 * берётся из настоящего ядра через [Topology], численные формулы проверяются отдельно.
 */
class SwimSolver(private val topo: Topology, var p: SwimParams) {

    private val n = topo.n
    val px = DoubleArray(n)
    val py = DoubleArray(n)
    private val prevX = DoubleArray(n)
    private val prevY = DoubleArray(n)
    val vx = DoubleArray(n)
    val vy = DoubleArray(n)
    /**
     * Обратная масса из радиуса клетки — как buildMasses() в демо.
     *
     * Масса пропорциональна ПЛОЩАДИ (квадрату радиуса) и нормирована на среднюю, чтобы
     * подобранные константы не зависели от того, какого размера клетки сохранил редактор.
     */
    private val invMass = run {
        var meanArea = 0.0
        for (k in 0 until n) { val r = topo.restR[k].toDouble(); meanArea += r * r }
        meanArea /= n
        DoubleArray(n) { i ->
            val r = topo.restR[i].toDouble()
            val m = if (meanArea > 0.0) (r * r) / meanArea else 1.0
            if (m > 1e-12) 1.0 / m else 1.0
        }
    }
    private val matchWeight = DoubleArray(n) { 1.0 }

    private val muscleActivation = DoubleArray(topo.muscleCount)
    private val muscleTarget = DoubleArray(topo.muscleCount)

    /** PER_EDGE: запас у каждого ребра. */
    private val flowEx = DoubleArray(topo.boundCount)
    private val flowEy = DoubleArray(topo.boundCount)

    /** GLOBAL_RESERVOIR: один вектор скорости увлечённой среды на организм. */
    private val flowVX = DoubleArray(topo.organismCount)
    private val flowVY = DoubleArray(topo.organismCount)
    private val comAccX = DoubleArray(topo.organismCount)
    private val comAccY = DoubleArray(topo.organismCount)

    // --- самоконтакт границы: та же реализация, что в демо ---
    private val radius = DoubleArray(n) { topo.restR[it].toDouble() }

    /** Масса организма — знаменатель скорости центра масс, см. запас среды. */
    private val organismMass = DoubleArray(topo.organismCount).also { a ->
        for (i in 0 until n) if (invMass[i] > 0.0) a[topo.organismOf[i]] += 1.0 / invMass[i]
    }
    /**
     * СВОИ копии длин и площадей покоя: пластичность их МЕНЯЕТ, а Topology общая на
     * все потоки подбора и только для чтения. См. applyPlasticity.
     */
    private val conRest = topo.conRest.copyOf()
    private val triRestArea2 = topo.triRestArea2.copyOf()

    private val conMaxLen = DoubleArray(topo.conCount) { c ->
        p.linkMaxStretch * conRest[c]
    }
    val contacts = BoundaryContacts.build(
        n, topo.conA, topo.conB, topo.conCount,
        topo.boundA, topo.boundB, topo.boundCount, topo.boundOpp, radius,
        p.contactScale, p.ccdCore, p.contactRestitution, p.contactFriction,
        topo.restX, topo.restY,
        topo.meanLinkLength.toDouble(), p.contactMaxStep, topo.isFree,
        config = DemoSettings.fromEnvironment().contacts,
    )

    /**
     * УГОЛ ЛОСКУТА. Зеркало демо, см. DemoSession.buildFlaps — там же и замеры,
     * из-за которых это появилось. Здесь топология неизменна, поэтому список
     * строится один раз.
     */
    private val flapA: IntArray
    private val flapB: IntArray
    private val flapRest: DoubleArray

    init {
        val use = HashMap<Long, Int>(topo.triA.size * 3)
        fun key(a: Int, b: Int): Long {
            val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
            return (lo shl 32) or hi
        }
        for (t in topo.triA.indices) {
            use.merge(key(topo.triA[t], topo.triB[t]), 1, Int::plus)
            use.merge(key(topo.triB[t], topo.triC[t]), 1, Int::plus)
            use.merge(key(topo.triC[t], topo.triA[t]), 1, Int::plus)
        }
        val bare = HashMap<Int, MutableList<Int>>()
        for (c in topo.conA.indices) {
            val i = topo.conA[c]; val j = topo.conB[c]
            if ((use[key(i, j)] ?: 0) > 0) continue
            bare.getOrPut(i) { ArrayList() }.add(j)
            bare.getOrPut(j) { ArrayList() }.add(i)
        }
        val a = ArrayList<Int>(); val b = ArrayList<Int>(); val r = ArrayList<Double>()
        for ((_, list) in bare) {
            if (list.size < 2) continue
            for (p in list.indices) for (q in p + 1 until list.size) {
                val i = list[p]; val j = list[q]
                if (i == j) continue
                val dx = (topo.restX[i] - topo.restX[j]).toDouble()
                val dy = (topo.restY[i] - topo.restY[j]).toDouble()
                a.add(i); b.add(j); r.add(kotlin.math.sqrt(dx * dx + dy * dy))
            }
        }
        flapA = a.toIntArray(); flapB = b.toIntArray(); flapRest = r.toDoubleArray()
    }

    /** Зеркало демо: снятие импульса и момента внутренних стадий. См. InternalMomentum. */
    private val internalMom = InternalMomentum(n)

    private fun markInternal() {
        internalMom.mark(px, py, topo.organismOf, topo.organismCount)
    }

    private fun cancelInternalSpin() {
        internalMom.cancel(topo.organismOf, topo.organismCount, px, py, prevX, prevY, invMass)
    }

    private fun solveFlaps(h: Double) {
        if (flapA.isEmpty()) return
        val alpha = DemoConst.FLAP_COMPLIANCE / (h * h)
        for (c in flapA.indices) {
            val i = flapA[c]; val j = flapB[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w == 0.0) continue
            var dx = px[i] - px[j]
            var dy = py[i] - py[j]
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            val rest = flapRest[c]
            if (len >= rest || len < 1e-12) continue
            dx /= len; dy /= len
            val dL = -(len - rest) / (w + alpha)
            px[i] += dx * dL * wi; py[i] += dy * dL * wi
            px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
        }
    }

    private fun solveLinkMaxLength() {
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w == 0.0) continue
            var dx = px[i] - px[j]
            var dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            val max = conMaxLen[c]
            if (len <= max || len < 1e-12) continue
            dx /= len; dy /= len
            // Тот же потолок поправки за подшаг, что и у контакта, и по той же
            // причине: стадия ЖЁСТКАЯ, перерастяжение она снимает целиком за один
            // подшаг, а updateVelocities делит поправку на крошечное h. При загибе
            // связи растягиваются сильно, и отсюда шёл остаток выброса скорости.
            // Делится по паре в тех же долях, поэтому импульс пары сохраняется.
            var dL = -(len - max) / w
            val capL = p.contactMaxStep * topo.meanLinkLength / w
            if (dL < -capL) dL = -capL
            px[i] += dx * dL * wi; py[i] += dy * dL * wi
            px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
        }
    }

    private fun clampSpeed(dt: Double) {
        val maxV = p.maxSpeedCellsPerTick * topo.meanLinkLength / dt
        val maxV2 = maxV * maxV
        for (i in 0 until n) {
            val v2 = vx[i] * vx[i] + vy[i] * vy[i]
            if (v2 <= maxV2) continue
            val s = maxV / sqrt(v2)
            vx[i] *= s; vy[i] *= s
        }
    }

    private val boneDx = DoubleArray(topo.maxBoneSize)
    private val boneDy = DoubleArray(topo.maxBoneSize)

    var diverged = false
        private set

    private var gaitFrame = 0

    fun reset() {
        topo.conRest.copyInto(conRest); topo.triRestArea2.copyInto(triRestArea2)
        conHold.fill(0); triHold.fill(0)
        for (c in conMaxLen.indices) conMaxLen[c] = p.linkMaxStretch * conRest[c]
        for (i in 0 until n) {
            px[i] = topo.restX[i].toDouble(); py[i] = topo.restY[i].toDouble()
            prevX[i] = px[i]; prevY[i] = py[i]
            vx[i] = 0.0; vy[i] = 0.0
            matchWeight[i] = 1.0
        }
        flowEx.fill(0.0); flowEy.fill(0.0)
        flowVX.fill(0.0); flowVY.fill(0.0)
        muscleActivation.fill(0.0); muscleTarget.fill(0.0)
        muscleActivationPrev.fill(0.0); muscleFrac = 1.0
        gaitFrame = 0
        diverged = false
    }

    // =================================================================
    //  СТАДИИ. Порядок и арифметика — как в DemoSession.simulate().
    // =================================================================

    /** Активация на начало тика: длина покоя доезжает по подшагам, зеркало демо. */
    private val muscleActivationPrev = DoubleArray(topo.muscleCount)
    private var muscleFrac = 1.0

    private fun muscleScale(m: Int): Double {
        if (m < 0) return 1.0
        val a = muscleActivationPrev[m] + (muscleActivation[m] - muscleActivationPrev[m]) * muscleFrac
        return 1.0 - a * (1.0 - p.muscleContraction)
    }

    /**
     * Гравитации и пола здесь нет НАМЕРЕННО, и на побитовую сверку это не влияет:
     * в демо GRAVITY = 0 (прибавление нуля к float точное), GROUND_Y = -100, то есть
     * условие пола не срабатывает никогда, а значит и applyRestitution холостая.
     * Появится гравитация — эти строки надо будет вернуть и сверку перепроверить.
     */
    private fun integrate(h: Double) {
        for (i in 0 until n) {
            prevX[i] = px[i]; prevY[i] = py[i]
            if (invMass[i] == 0.0) continue
            px[i] += vx[i] * h
            py[i] += vy[i] * h
        }
    }

    /**
     * Направление обхода ЧЕРЕДУЕТСЯ — симметричный Гаусс-Зейдель, как в DemoSession.
     * Без этого копия расходится с демо на ПЕРВОМ же кадре, потому что порядок обхода
     * задаёт знак углового дрейфа.
     */
    private var sweepBackwards = false

    /**
     * САМАЯ СМЯТАЯ И САМАЯ РАСТЯНУТАЯ СВЯЗЬ ЗА ПРОГОН. По ним видно, порвало бы тело в
     * настоящем движке или нет: там связь рвётся при сжатии ниже LINK_CRUSH_RATIO своей
     * НЫНЕШНЕЙ длины покоя (с поправкой на сокращение мышцы) и при растяжении выше
     * LINK_MAX_STRETCH + LINK_TEAR_STRAIN от длины покоя БЕЗ поправки.
     *
     * Зеркало само не рвётся: топология в нём неизменна, иначе оно перестало бы быть
     * зеркалом одной сцены. Поэтому подбор смотрит на эти два числа, а окончательная
     * проверка «не рвётся» всё равно делается на настоящем теле, см. PERF_GAIT.
     */
    var worstCrush = 1.0
        private set
    var worstStretch = 1.0
        private set

    fun resetStress() { worstCrush = 1.0; worstStretch = 1.0 }

    /** Зовётся раз в подшаг, после позиционных стадий. */
    private fun trackStress() {
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            val rest = conRest[c]
            if (rest < 1e-12) continue
            val crush = len / (rest * muscleScale(topo.conMuscle[c]))
            if (crush < worstCrush) worstCrush = crush
            val stretch = len / rest
            if (stretch > worstStretch) worstStretch = stretch
        }
    }

    private fun solveConstraints(h: Double) {
        val alpha = p.softCompliance / (h * h)
        val order = if (sweepBackwards) (topo.conCount - 1) downTo 0 else 0 until topo.conCount
        for (c in order) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w == 0.0) continue
            var dx = px[i] - px[j]
            var dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-9) continue
            dx /= len; dy /= len
            val rest = conRest[c] * muscleScale(topo.conMuscle[c])
            val dL = -(len - rest) / (w + alpha)
            px[i] += dx * dL * wi; py[i] += dy * dL * wi
            px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
        }
    }

    /**
     * Изгиб границы: обычное ограничение расстояния между соседями через одну.
     * Стадия ставится ПОСЛЕ площадей и перед проекцией кости — там же, где живут
     * остальные позиционные ограничения.
     */
    private fun solveBend(h: Double) {
        if (p.bendCompliance < 0.0) return
        val alpha = p.bendCompliance / (h * h)
        for (c in 0 until topo.bendCount) {
            val i = topo.bendA[c]; val j = topo.bendB[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w == 0.0) continue
            var dx = px[i] - px[j]
            var dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-12) continue
            dx /= len; dy /= len
            val dL = -(len - topo.bendRest[c]) / (w + alpha)
            px[i] += dx * dL * wi; py[i] += dy * dL * wi
            px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
        }
    }

    private fun solveAreas(h: Double) {
        val alpha = p.areaCompliance / (h * h)
        // -1 значит «не разделять»: тогда ветка ниже выбирает то же самое число, и
        // поведение совпадает с демо до бита.
        val alphaInv = if (p.areaComplianceInverted >= 0.0) p.areaComplianceInverted / (h * h) else alpha
        val maxStep = p.areaMaxStep * topo.meanLinkLength
        for (t in 0 until topo.triCount) {
            val i0 = topo.triA[t]; val i1 = topo.triB[t]; val i2 = topo.triC[t]
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
            if (denom < 1e-12) continue

            val s = muscleScale(topo.triMuscle[t])
            val restArea2 = triRestArea2[t].toDouble() * s * s
            val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            // Плавный переход убирает ступеньку жёсткости ровно на нулевой площади.
            val a = if (p.areaSmoothRamp) {
                val t = (area2 / restArea2).coerceIn(0.0, 1.0)
                alphaInv + (alpha - alphaInv) * t * t
            } else if (area2 < 0.0) alphaInv else alpha
            var dL = -(area2 - restArea2) / (denom + a)
            // Потолок на норму поправки: |dL| * sqrt(denom) и есть её длина.
            if (maxStep > 0.0) {
                val corr = abs(dL) * sqrt(denom)
                if (corr > maxStep) dL *= maxStep / corr
            }

            px[i0] += w0 * dL * g0x; py[i0] += w0 * dL * g0y
            px[i1] += w1 * dL * g1x; py[i1] += w1 * dL * g1y
            px[i2] += w2 * dL * g2x; py[i2] += w2 * dL * g2y
        }
    }

    private fun projectBone(b: Int) {
        val ids = topo.rigidBones[b]
        val q0x = topo.boneRestQx[b]
        val q0y = topo.boneRestQy[b]
        val ox = px[ids[0]]; val oy = py[ids[0]]

        var cx = 0.0; var cy = 0.0; var wsum = 0.0
        for (i in ids) {
            val w = matchWeight[i]
            cx += w * (px[i] - ox); cy += w * (py[i] - oy)
            wsum += w
        }
        cx /= wsum; cy /= wsum

        var s = 0.0; var t = 0.0
        for (k in ids.indices) {
            val i = ids[k]
            val w = matchWeight[i]
            val ppx = (px[i] - ox) - cx; val ppy = (py[i] - oy) - cy
            s += w * (q0x[k] * ppx + q0y[k] * ppy)
            t += w * (q0x[k] * ppy - q0y[k] * ppx)
        }
        val norm = sqrt(s * s + t * t)
        if (norm < 1e-9) return
        val cos = s / norm; val sin = t / norm

        var sdx = 0.0; var sdy = 0.0
        for (k in ids.indices) {
            val i = ids[k]
            val tx = ox + cx + cos * q0x[k] - sin * q0y[k]
            val ty = oy + cy + sin * q0x[k] + cos * q0y[k]
            boneDx[k] = tx - px[i]; boneDy[k] = ty - py[i]
            val w = matchWeight[i]
            sdx += w * boneDx[k]; sdy += w * boneDy[k]
        }
        val mdx = sdx / wsum; val mdy = sdy / wsum

        // Потолок на смещение за подшаг — как BONE_MAX_STEP в демо. Масштабируется
        // весь набор ОДНИМ множителем, поэтому обнулённая сумма остаётся нулём.
        var maxD2 = 0.0
        for (k in ids.indices) {
            val dx = boneDx[k] - mdx
            val dy = boneDy[k] - mdy
            val d2 = dx * dx + dy * dy
            if (d2 > maxD2) maxD2 = d2
        }
        var scale = 1.0
        val cap = p.boneMaxStep * topo.meanLinkLength
        if (cap > 0.0 && maxD2 > cap * cap) scale = cap / sqrt(maxD2)

        for (k in ids.indices) {
            val i = ids[k]
            px[i] += (boneDx[k] - mdx) * scale
            py[i] += (boneDy[k] - mdy) * scale
        }
    }

    private fun updateVelocities(h: Double) {
        for (i in 0 until n) {
            if (invMass[i] == 0.0) { vx[i] = 0.0; vy[i] = 0.0; continue }
            vx[i] = (px[i] - prevX[i]) / h
            vy[i] = (py[i] - prevY[i]) / h
        }
    }

    private fun applyViscosity(h: Double) {
        var k = p.viscosity * h
        if (k > 0.5) k = 0.5
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w == 0.0) continue
            var nx = px[j] - px[i]
            var ny = py[j] - py[i]
            val len = sqrt(nx * nx + ny * ny)
            if (len < 1e-9) continue
            nx /= len; ny /= len
            val dv = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
            val si = k * wi / w; val sj = k * wj / w
            vx[i] += dv * nx * si; vy[i] += dv * ny * si
            vx[j] -= dv * nx * sj; vy[j] -= dv * ny * sj
        }
    }

    /**
     * Анизотропное сопротивление среды. Три модели памяти среды — см. [FlowModel].
     *
     * Общая часть у всех трёх одна и та же: гасится только НОРМАЛЬНАЯ к ребру
     * составляющая относительной скорости, сила пропорциональна длине ребра,
     * коэффициент линейный плюс квадратичный.
     */
    private fun applyNormalDrag(h: Double) {
        var kd = p.flowDecay * h
        if (kd > 1.0) kd = 1.0

        // Импульс, переданный телу за этот вызов. Нужен только общему запасу:
        // ровно он и уходит в среду с обратным знаком.
        var impX = 0.0
        var impY = 0.0

        val global = p.flowModel == FlowModel.GLOBAL_RESERVOIR
        val tracking = p.flowModel == FlowModel.GLOBAL_TRACKING
        val perEdge = p.flowModel == FlowModel.PER_EDGE
        var kf = p.flowEntrain * h
        if (kf > 1.0) kf = 1.0

        if (tracking) {
            // Запас догоняет скорость ЦЕНТРА МАСС СВОЕГО организма, а не всего мира.
            // Одно среднее на всех было прямой ошибкой: тела делили запас и толкали
            // друг друга на любом расстоянии. См. flowVX в DemoSession.
            for (o in 0 until topo.organismCount) { comAccX[o] = 0.0; comAccY[o] = 0.0 }
            for (i in 0 until n) {
                val o = topo.organismOf[i]
                if (invMass[i] <= 0.0) continue
                val mw = 1.0 / invMass[i]
                comAccX[o] += mw * vx[i]; comAccY[o] += mw * vy[i]
            }

            // Демпфер между телом и запасом: за шаг относительная скорость падает на
            // kf*(1 + flowMass). Выше единицы это переброс через ноль, то есть раскачка,
            // поэтому коэффициент здесь и ограничивается — устойчиво при любых массах.
            val limit = 0.9 / (1.0 + p.flowMass)
            val kfe = if (kf > limit) limit else kf

            for (o in 0 until topo.organismCount) {
                val mo = organismMass[o]
                if (mo <= 0.0) continue
                val comVX = comAccX[o] / mo
                val comVY = comAccY[o] / mo
                val dfx = (comVX - flowVX[o]) * kfe
                val dfy = (comVY - flowVY[o]) * kfe
                flowVX[o] += dfx
                flowVY[o] += dfy
                comAccX[o] = dfx      // переиспользуем под плату
                comAccY[o] = dfy
                // Рассеяние в объём: только сток.
                flowVX[o] -= flowVX[o] * kd
                flowVY[o] -= flowVY[o] * kd
            }

            // Разгон запаса телу СТОИТ: flowMass * dFlow на каждую частицу.
            val pay = p.flowMass
            for (i in 0 until n) {
                val o = topo.organismOf[i]
                vx[i] -= pay * comAccX[o]; vy[i] -= pay * comAccY[o]
            }
        }

        for (e in 0 until topo.boundCount) {
            val i = topo.boundA[e]; val j = topo.boundB[e]
            val ex = px[j] - px[i]
            val ey = py[j] - py[i]
            val len = sqrt(ex * ex + ey * ey)
            if (len < 1e-9) continue

            val nx = -ey / len
            val ny = ex / len

            val vmx = (vx[i] + vx[j]) * 0.5
            val vmy = (vy[i] + vy[j]) * 0.5
            val vnAbs = vmx * nx + vmy * ny

            var vn = vnAbs
            if (perEdge) {
                flowEx[e] += (vnAbs * nx - flowEx[e]) * kf
                flowEy[e] += (vnAbs * ny - flowEy[e]) * kf
                flowEx[e] -= flowEx[e] * kd
                flowEy[e] -= flowEy[e] * kd
                vn = vnAbs - (flowEx[e] * nx + flowEy[e] * ny)
            } else if (global || tracking) {
                // Скорость ребра ОТНОСИТЕЛЬНО увлечённой среды.
                val o = topo.organismOf[i]
                vn = (vmx - flowVX[o]) * nx + (vmy - flowVY[o]) * ny
            }

            var k = (p.normalDrag + p.normalDragQuadratic * abs(vn)) * len * h
            if (k > 0.5) k = 0.5

            val dv = -vn * k
            vx[i] += dv * nx; vy[i] += dv * ny
            vx[j] += dv * nx; vy[j] += dv * ny
            impX += 2 * dv * nx
            impY += 2 * dv * ny

            // Трение ВДОЛЬ ребра. См. TANGENT_DRAG в DemoSession.
            if (p.tangentDrag > 0.0) {
                val tx = ex / len; val ty = ey / len
                val vt = (vmx - flowVX[topo.organismOf[i]]) * tx + (vmy - flowVY[topo.organismOf[i]]) * ty
                var kt = p.tangentDrag * p.normalDrag * len * h
                if (kt > 0.5) kt = 0.5
                val dvt = -vt * kt
                vx[i] += dvt * tx; vy[i] += dvt * ty
                vx[j] += dvt * tx; vy[j] += dvt * ty
                impX += 2 * dvt * tx
                impY += 2 * dvt * ty
            }
        }

        if (global) {
            // Обмен симметричный: тело получило impX, среда потеряла ровно столько же.
            // Масса среды — flowMass масс тела, тело это n частиц массой 1.
            val mf = p.flowMass * n
            // GLOBAL_RESERVOIR остался только для сравнения в стендах и общий на мир.
            flowVX[0] -= impX / mf
            flowVY[0] -= impY / mf
            flowVX[0] -= flowVX[0] * kd
            flowVY[0] -= flowVY[0] * kd
        }
    }

    private fun applyMediumDrag(h: Double) {
        var keep = 1.0 - p.mediumDrag * h
        if (keep < 0.0) keep = 0.0
        for (i in 0 until n) { vx[i] *= keep; vy[i] *= keep }
    }

    // =================================================================
    //  КАДР
    // =================================================================

    /** [gait] — крутить автоматический гребок; иначе мышцы отпущены. */
    fun frame(dt: Double, substeps: Int, gait: Boolean) {
        muscleTarget.fill(0.0)
        if (gait) {
            val duty = (p.gaitPeriod * p.gaitDuty).toInt().coerceAtLeast(1)
            if (gaitFrame % p.gaitPeriod < duty) muscleTarget.fill(1.0)
            gaitFrame++
        }
        System.arraycopy(muscleActivation, 0, muscleActivationPrev, 0, muscleActivation.size)
        for (m in muscleActivation.indices) {
            val target = muscleTarget[m]
            val rate = if (target > muscleActivation[m]) p.muscleRateContract else p.muscleRateRelax
            var k = rate * dt
            if (k > 1.0) k = 1.0
            muscleActivation[m] += (target - muscleActivation[m]) * k
        }

        val h = dt / substeps
        for (s in 0 until substeps) {
            muscleFrac = (s + 1).toDouble() / substeps
            integrate(h)
            // Фаза чередования — как в демо, см. SWEEP_FLIP.
            if (DemoConst.SWEEP_FLIP == 1) sweepBackwards = !sweepBackwards
            if (DemoConst.CANCEL_INTERNAL_SPIN) markInternal()
            // Цикл по всем ограничениям сразу, включая контакт — зеркало демо.
            for (iter in 0 until DemoConst.SOLVER_ITERS) {
                if (DemoConst.SWEEP_FLIP == 2) sweepBackwards = !sweepBackwards
                solveConstraints(h)
                solveFlaps(h)
                solveLinkMaxLength()
                solveAreas(h)
                solveBend(h)
                for (b in topo.rigidBones.indices) projectBone(b)
                solveLinkMaxLength()
                if (p.contactsOn) {
                    contacts.prepare(px, py, prevX, prevY, vx, vy, invMass, continuing = iter > 0)
                    if (DemoConst.CANCEL_INTERNAL_SPIN) internalMom.noteContacts(contacts)
                    contacts.updateBones(px, py, invMass, topo.boneOf, topo.rigidBones)
                    contacts.solveContacts(px, py, vx, vy, invMass, h)
                }
            }
            // Предел длины ещё раз, уже после контактов — зеркало демо: проекция
            // кости способна растянуть связь, а от него зависит непроницаемость.
            solveLinkMaxLength()
            trackStress()
            if (DemoConst.CANCEL_INTERNAL_SPIN) cancelInternalSpin()
            if (p.contactsOn) {
                contacts.prepare(px, py, prevX, prevY, vx, vy, invMass, continuing = true)
                contacts.updateBones(px, py, invMass, topo.boneOf, topo.rigidBones)
                contacts.solveContacts(px, py, vx, vy, invMass, h)
            }
            updateVelocities(h)
            // Отскок — зеркало демо, см. solveRestitution.
            if (p.contactsOn) contacts.solveRestitution(px, py, vx, vy, invMass)
            applyViscosity(h)
            applyNormalDrag(h)
            applyMediumDrag(h)
            clampSpeed(dt)
        }
        applyPlasticity(dt)
    }

    /**
     * ПЛАСТИЧЕСКОЕ ТЕЧЕНИЕ ТКАНИ. Копия стадии из демо, см. DemoSession.applyPlasticity.
     * Зовётся раз в тик, после всех подшагов.
     */
    private val conHold = ByteArray(topo.conCount)
    private val triHold = ByteArray(topo.triCount)

    private fun applyPlasticity(dt: Double) {
        if (p.plasticRate <= 0.0) return
        var k = p.plasticRate * dt
        if (k > 1.0) k = 1.0
        for (c in 0 until topo.conCount) {
            if (topo.conMuscle[c] >= 0) continue
            val target = conRest[c]
            if (target < 1e-12) continue
            val dx = px[topo.conA[c]] - px[topo.conB[c]]
            val dy = py[topo.conA[c]] - py[topo.conB[c]]
            val len = sqrt(dx * dx + dy * dy)
            val strain = len / target - 1.0
            val over = when {
                strain > p.plasticYield -> strain - p.plasticYield
                strain < -p.plasticYield -> strain + p.plasticYield
                else -> 0.0
            }
            if (over == 0.0) { conHold[c] = 0; continue }
            if (conHold[c] < p.plasticHold) { conHold[c]++; continue }
            conRest[c] *= 1.0 + over * k
            conMaxLen[c] = p.linkMaxStretch * conRest[c]
        }
        for (t in 0 until topo.triCount) {
            if (topo.triMuscle[t] >= 0) continue
            val rest2 = triRestArea2[t].toDouble()
            if (kotlin.math.abs(rest2) < 1e-18) continue
            val i0 = topo.triA[t]; val i1 = topo.triB[t]; val i2 = topo.triC[t]
            val area2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) -
                (py[i1] - py[i0]) * (px[i2] - px[i0])
            // ВЫВЕРНУТЫМ ТЕЧЬ НЕЛЬЗЯ: подогнав площадь покоя под сложенное состояние,
            // пластика УЗАКОНИТ складку, и та не разойдётся уже никогда. Проверка
            // ловила это прямо: складка 50% и 75% переставали расходиться за 8 секунд.
            if (area2 <= 0.0) continue
            val strain = area2 / rest2 - 1.0
            val over = when {
                strain > p.plasticYield -> strain - p.plasticYield
                strain < -p.plasticYield -> strain + p.plasticYield
                else -> 0.0
            }
            if (over == 0.0) { triHold[t] = 0; continue }
            if (triHold[t] < p.plasticHold) { triHold[t]++; continue }
            triRestArea2[t] = (triRestArea2[t] * (1.0 + over * k)).toFloat()
        }
    }

    /** Держать мышцу 0 сокращённой — режим сверки с демо и ручных замеров. */
    fun frameHoldMuscle0(dt: Double, substeps: Int, hold: Boolean) {
        muscleTarget.fill(0.0)
        if (hold && muscleTarget.isNotEmpty()) muscleTarget[0] = 1.0
        System.arraycopy(muscleActivation, 0, muscleActivationPrev, 0, muscleActivation.size)
        for (m in muscleActivation.indices) {
            val target = muscleTarget[m]
            val rate = if (target > muscleActivation[m]) p.muscleRateContract else p.muscleRateRelax
            var k = rate * dt
            if (k > 1.0) k = 1.0
            muscleActivation[m] += (target - muscleActivation[m]) * k
        }
        val h = dt / substeps
        for (s in 0 until substeps) {
            muscleFrac = (s + 1).toDouble() / substeps
            integrate(h)
            // Фаза чередования — как в демо, см. SWEEP_FLIP.
            if (DemoConst.SWEEP_FLIP == 1) sweepBackwards = !sweepBackwards
            if (DemoConst.CANCEL_INTERNAL_SPIN) markInternal()
            // Цикл по всем ограничениям сразу, включая контакт — зеркало демо.
            for (iter in 0 until DemoConst.SOLVER_ITERS) {
                if (DemoConst.SWEEP_FLIP == 2) sweepBackwards = !sweepBackwards
                solveConstraints(h)
                solveFlaps(h)
                solveLinkMaxLength()
                solveAreas(h)
                solveBend(h)
                for (b in topo.rigidBones.indices) projectBone(b)
                solveLinkMaxLength()
                if (p.contactsOn) {
                    contacts.prepare(px, py, prevX, prevY, vx, vy, invMass, continuing = iter > 0)
                    if (DemoConst.CANCEL_INTERNAL_SPIN) internalMom.noteContacts(contacts)
                    contacts.updateBones(px, py, invMass, topo.boneOf, topo.rigidBones)
                    contacts.solveContacts(px, py, vx, vy, invMass, h)
                }
            }
            // Предел длины ещё раз, уже после контактов — зеркало демо: проекция
            // кости способна растянуть связь, а от него зависит непроницаемость.
            solveLinkMaxLength()
            trackStress()
            if (DemoConst.CANCEL_INTERNAL_SPIN) cancelInternalSpin()
            if (p.contactsOn) {
                contacts.prepare(px, py, prevX, prevY, vx, vy, invMass, continuing = true)
                contacts.updateBones(px, py, invMass, topo.boneOf, topo.rigidBones)
                contacts.solveContacts(px, py, vx, vy, invMass, h)
            }
            updateVelocities(h)
            // Отскок — зеркало демо, см. solveRestitution.
            if (p.contactsOn) contacts.solveRestitution(px, py, vx, vy, invMass)
            applyViscosity(h)
            applyNormalDrag(h)
            applyMediumDrag(h)
            clampSpeed(dt)
        }
        applyPlasticity(dt)
    }

    /** Жёстко поставить тело в повёрнутую позу покоя с твердотельной скоростью. */
    fun setRigidRotation(angle: Double, omega: Double, cx: Double, cy: Double) {
        val cs = kotlin.math.cos(angle); val sn = kotlin.math.sin(angle)
        for (i in 0 until n) {
            val qx = topo.restX[i].toDouble() - cx; val qy = topo.restY[i].toDouble() - cy
            px[i] = cx + cs * qx - sn * qy
            py[i] = cy + sn * qx + cs * qy
            vx[i] = -omega * (py[i] - cy)
            vy[i] = omega * (px[i] - cx)
        }
    }

    fun dragOnly(h: Double) = applyNormalDrag(h)

    /** Активация первой мышцы. Нужна замеру, чтобы отличить гребок от зажатой позы. */
    fun activation0(): Double = if (muscleActivation.isEmpty()) 0.0 else muscleActivation[0].toDouble()

    /**
     * Сколько треугольников ВЫВЕРНУТО наизнанку.
     *
     * Знаковая площадь ушла в минус — ткань прошла сама через себя.
     *
     * ЗДЕСЬ РАНЬШЕ БЫЛО НАПИСАНО НЕВЕРНО: будто ограничение площади к знаку безразлично
     * и вывернутый треугольник назад не выправит. На самом деле restArea2 положительна,
     * area2 знаковая, значит цель у ограничения это +restArea2, и для вывернутого
     * треугольника невязка огромна, а знак поправки правильный — выправлять оно умеет.
     * Мешала ему только податливость, и это чинится отдельной веткой: см.
     * AREA_COMPLIANCE_INVERTED в DemoSession и стенд FoldRecovery.
     *
     * В подборе меряется потому, что сильным сокращением мышцы расплачиваются именно
     * этим: площадь покоя едет как s^2, и при s = 0.165 треугольник сжимается до 2.7%
     * своей площади, после чего часть из них проскакивает через ноль. Первая версия
     * поиска этого не видела вовсе и выдала значения, дающие 18 вывернутых из 520.
     */
    fun countInverted(): Int {
        var c = 0
        for (t in 0 until topo.triCount) {
            val i0 = topo.triA[t]; val i1 = topo.triB[t]; val i2 = topo.triC[t]
            val a2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) - (py[i1] - py[i0]) * (px[i2] - px[i0])
            if (a2 < 0) c++
        }
        return c
    }

    // --- измерители ---
    fun comX(): Double { var s = 0.0; for (i in 0 until n) s += px[i]; return s / n }
    fun comY(): Double { var s = 0.0; for (i in 0 until n) s += py[i]; return s / n }
    fun momX(): Double { var s = 0.0; for (i in 0 until n) s += vx[i]; return s }
    fun momY(): Double { var s = 0.0; for (i in 0 until n) s += vy[i]; return s }
    /**
     * НЕВЯЗКА СВЯЗЕЙ: корень из среднего квадрата (len - rest), в долях средней связи.
     *
     * Прямая мера СХОДИМОСТИ. Податливость задаёт целевую жёсткость материала, но
     * добирается решатель до неё только за несколько подшагов. Мало подшагов — ткань
     * получается мягче, чем задано, и невязка большая. Именно этим и надо выбирать
     * число подшагов, а не туннелированием: его закрывает CCD.
     */
    fun linkResidual(): Double {
        var s = 0.0
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d = sqrt(dx * dx + dy * dy) - conRest[c] * muscleScale(topo.conMuscle[c])
            s += d * d
        }
        return sqrt(s / topo.conCount) / topo.meanLinkLength
    }

    fun kinetic(): Double {
        var e = 0.0
        for (i in 0 until n) e += 0.5 * (vx[i] * vx[i] + vy[i] * vy[i])
        return e
    }

    fun momentum() = sqrt(momX() * momX() + momY() * momY())

    /** Момент импульса относительно центра масс, за вычетом поступательного движения. */
    fun angularMomentum(): Double {
        val cx = comX(); val cy = comY()
        val vxm = momX() / n; val vym = momY() / n
        var l = 0.0
        for (i in 0 until n) {
            l += (px[i] - cx) * (vy[i] - vym) - (py[i] - cy) * (vx[i] - vxm)
        }
        return l
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

    fun maxSpeed(): Double {
        var mx = 0.0
        for (i in 0 until n) {
            val s = vx[i].toDouble() * vx[i] + vy[i].toDouble() * vy[i]
            if (s > mx) mx = s
        }
        return sqrt(mx)
    }

    /** Расходимость: NaN или заведомо нефизичная скорость. Кандидат такой отвергается. */
    fun checkDiverged(limit: Double = 50.0): Boolean {
        for (i in 0 until n) {
            if (px[i].isNaN() || py[i].isNaN() || vx[i].isNaN() || vy[i].isNaN()) { diverged = true; return true }
        }
        if (maxSpeed() > limit) { diverged = true; return true }
        return false
    }
}
