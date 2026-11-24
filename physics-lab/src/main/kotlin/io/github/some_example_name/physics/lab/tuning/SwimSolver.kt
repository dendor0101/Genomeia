package io.github.some_example_name.physics.lab.tuning

import io.github.some_example_name.physics.ConstraintBlocks
import io.github.some_example_name.physics.CONSTRAINT_BLOCK_SIZE
import io.github.some_example_name.physics.CANCEL_INTERNAL_SPIN
import io.github.some_example_name.physics.FLAP_COMPLIANCE
import io.github.some_example_name.physics.MUSCLE_MAX_PULL
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
import io.github.some_example_name.physics.solveBoundaryOrientation
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
 * Разрушение здесь выключено, как tearingOn=false в сверке ядра. Поэтому стадия
 * удаления долгих инверсий, как и разрывы рёбер, в этом режиме не меняет топологию.
 */
class SwimSolver(private val topo: Topology, var p: SwimParams) {

    private val n = topo.n
    val px = FloatArray(n)
    val py = FloatArray(n)
    private val prevX = FloatArray(n)
    private val prevY = FloatArray(n)
    val vx = FloatArray(n)
    val vy = FloatArray(n)
    /**
     * Обратная масса из радиуса клетки — как buildMasses() в демо.
     *
     * Масса пропорциональна ПЛОЩАДИ (квадрату радиуса) и нормирована на среднюю, чтобы
     * подобранные константы не зависели от того, какого размера клетки сохранил редактор.
     */
    private val invMass = run {
        var meanArea = 0.0f
        for (k in 0 until n) { val r = topo.restR[k].toFloat(); meanArea += r * r }
        meanArea /= n
        FloatArray(n) { i ->
            val r = topo.restR[i].toFloat()
            val m = if (meanArea > 0.0f) (r * r) / meanArea else 1.0f
            if (m > 1e-12f) 1.0f / m else 1.0f
        }
    }
    private val matchWeight = FloatArray(n) { 1.0f }

    private val muscleActivation = FloatArray(topo.muscleCount)
    private val muscleTarget = FloatArray(topo.muscleCount)

    /** PER_EDGE: запас у каждого ребра. */
    private val flowEx = FloatArray(topo.boundCount)
    private val flowEy = FloatArray(topo.boundCount)

    /** GLOBAL_RESERVOIR: один вектор скорости увлечённой среды на организм. */
    private val flowVX = FloatArray(topo.organismCount)
    private val flowVY = FloatArray(topo.organismCount)
    private val comAccX = FloatArray(topo.organismCount)
    private val comAccY = FloatArray(topo.organismCount)

    // --- самоконтакт границы: та же реализация, что в демо ---
    private val radius = FloatArray(n) { topo.restR[it].toFloat() }

    /** Масса организма — знаменатель скорости центра масс, см. запас среды. */
    private val organismMass = FloatArray(topo.organismCount).also { a ->
        for (i in 0 until n) if (invMass[i] > 0.0f) a[topo.organismOf[i]] += 1.0f / invMass[i]
    }
    /**
     * СВОИ копии длин и площадей покоя: пластичность их МЕНЯЕТ, а Topology общая на
     * все потоки подбора и только для чтения. См. applyPlasticity.
     */
    private val conRest = topo.conRest.copyOf()
    private val triRestArea2 = topo.triRestArea2.copyOf()

    private val conMaxLen = FloatArray(topo.conCount) { c ->
        p.linkMaxStretch * conRest[c]
    }
    val contacts = BoundaryContacts.build(
        n, topo.conA, topo.conB, topo.conCount,
        topo.boundA, topo.boundB, topo.boundCount, topo.boundOpp, radius,
        p.contactScale, p.ccdCore, p.contactRestitution, p.contactFriction,
        topo.restX, topo.restY,
        topo.meanLinkLength.toFloat(), p.contactMaxStep, topo.contactProbes,
        config = DemoSettings.fromEnvironment().contacts,
    )

    /**
     * УГОЛ ЛОСКУТА. Зеркало демо, см. DemoSession.buildFlaps — там же и замеры,
     * из-за которых это появилось. Здесь топология неизменна, поэтому список
     * строится один раз.
     */
    private val flapA: IntArray
    private val flapB: IntArray
    private val flapRest: FloatArray

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
        val a = ArrayList<Int>(); val b = ArrayList<Int>(); val r = ArrayList<Float>()
        for ((_, list) in bare) {
            if (list.size < 2) continue
            for (p in list.indices) for (q in p + 1 until list.size) {
                val i = list[p]; val j = list[q]
                if (i == j) continue
                val dx = (topo.restX[i] - topo.restX[j]).toFloat()
                val dy = (topo.restY[i] - topo.restY[j]).toFloat()
                a.add(i); b.add(j); r.add(kotlin.math.sqrt(dx * dx + dy * dy))
            }
        }
        flapA = a.toIntArray(); flapB = b.toIntArray(); flapRest = r.toFloatArray()
    }

    private val blocks = ConstraintBlocks(n, topo.organismOf, CONSTRAINT_BLOCK_SIZE,
        topo.conCount, topo.triCount, topo.rigidBones.size).also { layout ->
        for (c in 0 until topo.conCount) layout.links.add(c, layout.blockOf(topo.conA[c], topo.conB[c]))
        for (c in topo.boundaryConstraints) layout.addLimit(c, topo.conA[c], topo.conB[c])
        for (t in 0 until topo.triCount) layout.areas.add(t, layout.blockOf(topo.triA[t], topo.triB[t], topo.triC[t]))
        layout.links.finishBuild(); layout.areas.finishBuild()
        for (c in 0 until topo.bendCount) layout.bends.add(c, layout.blockOf(topo.bendA[c], topo.bendB[c]))
        for (c in flapA.indices) layout.flaps.add(c, layout.blockOf(flapA[c], flapB[c]))
        for (b in topo.rigidBones.indices) {
            val ids = topo.rigidBones[b]
            var block = layout.particleBlock[ids[0]]
            for (i in ids) if (layout.particleBlock[i] != block) { block = layout.seamBucket[layout.particleBlock[ids[0]]]; break }
            layout.bones.add(b, block)
        }
    }

    private fun solveBlockedIteration(h: Float) {
        for (parity in 0..1) {
            var block = parity
            while (block < blocks.blockCount) { solveConstraintBlock(h, block); block += 2 }
        }
        for (bucket in blocks.seam until blocks.buckets) solveConstraintBlock(h, bucket)
        if (blocks.hasSeams()) solveLinkMaxLength()
    }

    private fun solveConstraintBlock(h: Float, block: Int) {
        solveConstraints(h, block)
        solveFlaps(h, block)
        solveLinkMaxLength(block)
        solveAreas(h, block)
        solveBend(h, block)
        var b = blocks.bones.head[block]
        while (b >= 0) { projectBone(b); b = blocks.bones.next[b] }
        solveLinkMaxLength(block)
    }

    /** Зеркало демо: снятие импульса и момента внутренних стадий. См. InternalMomentum. */
    private val internalMom = InternalMomentum(n, io.github.some_example_name.physics.SPIN_MIN_ARM * topo.meanLinkLength)
    private val tickMomentum = io.github.some_example_name.physics.TickMomentum(n, io.github.some_example_name.physics.SPIN_MIN_ARM * topo.meanLinkLength)

    private fun markInternal() {
        internalMom.mark(px, py, topo.organismOf, topo.organismCount)
    }

    private fun cancelInternalSpin() {
        internalMom.cancel(topo.organismOf, topo.organismCount, px, py, prevX, prevY, invMass)
    }

    private fun solveFlaps(h: Float, block: Int) {
        if (flapA.isEmpty()) return
        val alpha = DemoConst.FLAP_COMPLIANCE / (h * h)
        val list = blocks.flaps
        var cursor = list.head[block]
        while (cursor >= 0) {
            val c = cursor; cursor = list.next[cursor]
            val i = flapA[c]; val j = flapB[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w == 0.0f) continue
            var dx = px[i] - px[j]
            var dy = py[i] - py[j]
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            val rest = flapRest[c]
            if (len >= rest || len < 1e-12f) continue
            dx /= len; dy /= len
            val dL = -(len - rest) / (w + alpha)
            px[i] += dx * dL * wi; py[i] += dy * dL * wi
            px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
        }
    }

    private fun solveLinkMaxLength() = solveLinkMaxLength(-1)

    private fun solveLinkMaxLength(block: Int) {
        val list = blocks.limits
        var cursor = if (block < 0) 0 else list.head[block]
        while (if (block < 0) cursor < topo.boundaryConstraints.size else cursor >= 0) {
            val c = if (block < 0) topo.boundaryConstraints[cursor++] else cursor
            if (block >= 0) cursor = list.next[cursor]
            val i = topo.conA[c]; val j = topo.conB[c]
            var dx = px[i] - px[j]
            var dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            val max = conMaxLen[c]
            if (len <= max || len < 1e-12f) continue
            // Как в ядре: масса нужна лишь связи, нарушившей предел.
            var wi = invMass[i]; var wj = invMass[j]
            if (wi + wj == 0.0f) continue
            // Костный конец связи предел не двигает — как applyLinkLimitCorrection ядра.
            val boneI = topo.boneOf[i] >= 0; val boneJ = topo.boneOf[j] >= 0
            if (boneI && !boneJ && wj > 0.0f) wi = 0.0f else if (boneJ && !boneI && wi > 0.0f) wj = 0.0f
            val w = wi + wj
            dx /= len; dy /= len
            // Собственный потолок поправки длины, как LINK_MAX_STEP в ядре:
            // стадия ЖЁСТКАЯ, перерастяжение она снимает целиком за один
            // подшаг, а updateVelocities делит поправку на крошечное h. При загибе
            // связи растягиваются сильно, и отсюда шёл остаток выброса скорости.
            // Делится по паре в тех же долях, поэтому импульс пары сохраняется.
            var dL = -(len - max) / w
            val capL = p.linkMaxStep * topo.meanLinkLength / w
            if (dL < -capL) dL = -capL
            px[i] += dx * dL * wi; py[i] += dy * dL * wi
            px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
        }
    }

    /** Зеркало TickMotion ядра: положения в начале тика, затем дрожь скорости. */
    private val tickJitterX = FloatArray(n)
    private val tickJitterY = FloatArray(n)

    private fun beginTickMotion() {
        System.arraycopy(px, 0, tickJitterX, 0, n); System.arraycopy(py, 0, tickJitterY, 0, n)
    }

    private fun removeTickJitter(dt: Float) {
        val inv = 1.0f / dt
        for (i in 0 until n) {
            if (invMass[i] == 0.0f) { tickJitterX[i] = 0.0f; tickJitterY[i] = 0.0f; continue }
            val mx = (px[i] - tickJitterX[i]) * inv
            val my = (py[i] - tickJitterY[i]) * inv
            tickJitterX[i] = vx[i] - mx; tickJitterY[i] = vy[i] - my
            vx[i] = mx; vy[i] = my
        }
    }

    private fun restoreTickJitter() {
        for (i in 0 until n) { vx[i] += tickJitterX[i]; vy[i] += tickJitterY[i] }
    }

    private fun clampSpeed(dt: Float) {
        val maxV = p.maxSpeedCellsPerTick * topo.meanLinkLength / dt
        val maxV2 = maxV * maxV
        for (i in 0 until n) {
            val v2 = vx[i] * vx[i] + vy[i] * vy[i]
            if (v2 <= maxV2) continue
            val s = maxV / sqrt(v2)
            vx[i] *= s; vy[i] *= s
        }
    }

    private val boneDx = FloatArray(topo.maxBoneSize)
    private val boneDy = FloatArray(topo.maxBoneSize)

    var diverged = false
        private set

    private var gaitFrame = 0

    fun reset() {
        topo.conRest.copyInto(conRest); topo.triRestArea2.copyInto(triRestArea2)
        conHold.fill(0); triHold.fill(0)
        for (c in conMaxLen.indices) conMaxLen[c] = p.linkMaxStretch * conRest[c]
        for (i in 0 until n) {
            px[i] = topo.restX[i].toFloat(); py[i] = topo.restY[i].toFloat()
            prevX[i] = px[i]; prevY[i] = py[i]
            vx[i] = 0.0f; vy[i] = 0.0f
            matchWeight[i] = 1.0f
        }
        flowEx.fill(0.0f); flowEy.fill(0.0f)
        flowVX.fill(0.0f); flowVY.fill(0.0f)
        muscleActivation.fill(0.0f); muscleTarget.fill(0.0f)
        muscleActivationPrev.fill(0.0f); muscleFrac = 1.0f
        gaitFrame = 0
        diverged = false
    }

    // =================================================================
    //  СТАДИИ. Порядок и арифметика — как в DemoSession.simulate().
    // =================================================================

    /** Активация на начало тика: длина покоя доезжает по подшагам, зеркало демо. */
    private val muscleActivationPrev = FloatArray(topo.muscleCount)
    private var muscleFrac = 1.0f

    private fun muscleScale(m: Int): Float {
        if (m < 0) return 1.0f
        val a = muscleActivationPrev[m] + (muscleActivation[m] - muscleActivationPrev[m]) * muscleFrac
        return 1.0f - a * (1.0f - p.muscleContraction)
    }

    /**
     * Гравитации и пола здесь нет НАМЕРЕННО, и на побитовую сверку это не влияет:
     * в демо GRAVITY = 0 (прибавление нуля к float точное), GROUND_Y = -100, то есть
     * условие пола не срабатывает никогда, а значит и applyRestitution холостая.
     * Появится гравитация — эти строки надо будет вернуть и сверку перепроверить.
     */
    private fun integrate(h: Float) {
        for (i in 0 until n) {
            prevX[i] = px[i]; prevY[i] = py[i]
            if (invMass[i] == 0.0f) continue
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
    var worstCrush = 1.0f
        private set
    var worstStretch = 1.0f
        private set

    fun resetStress() { worstCrush = 1.0f; worstStretch = 1.0f }

    /** Зовётся раз в подшаг, после позиционных стадий. */
    private fun trackStress() {
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            val rest = conRest[c]
            if (rest < 1e-12f) continue
            val crush = len / (rest * muscleScale(topo.conMuscle[c]))
            if (crush < worstCrush) worstCrush = crush
            val stretch = len / rest
            if (stretch > worstStretch) worstStretch = stretch
        }
    }

    private fun solveConstraints(h: Float, block: Int) {
        val alpha = p.softCompliance / (h * h)
        // Здесь исходный линейный порядок: ядро может переставлять независимые
        // связи, а PhysicsCheck сверяет, что порядок поправок каждой частицы тот же.
        val list = blocks.links
        val start = list.start[block]; val end = start + list.count[block]
        val order = if (sweepBackwards) end - 1 downTo start else start until end
        for (slot in order) {
            val c = list.items[slot]
            val i = topo.conA[c]; val j = topo.conB[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w == 0.0f) continue
            var dx = px[i] - px[j]
            var dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-9f) continue
            dx /= len; dy /= len
            val scale = muscleScale(topo.conMuscle[c])
            var over = len - conRest[c] * scale
            // Потолок тяги мышцы — как в решателе игры, см. MUSCLE_MAX_PULL.
            if (scale < 1.0f && over > MUSCLE_MAX_PULL * conRest[c]) over = MUSCLE_MAX_PULL * conRest[c]
            val dL = -over / (w + alpha)
            px[i] += dx * dL * wi; py[i] += dy * dL * wi
            px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
        }
    }

    /**
     * Изгиб границы: обычное ограничение расстояния между соседями через одну.
     * Стадия ставится ПОСЛЕ площадей и перед проекцией кости — там же, где живут
     * остальные позиционные ограничения.
     */
    private fun solveBend(h: Float, block: Int) {
        if (p.bendCompliance < 0.0f) return
        val alpha = p.bendCompliance / (h * h)
        val list = blocks.bends
        var cursor = list.head[block]
        while (cursor >= 0) {
            val c = cursor; cursor = list.next[cursor]
            val i = topo.bendA[c]; val j = topo.bendB[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w == 0.0f) continue
            var dx = px[i] - px[j]
            var dy = py[i] - py[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-12f) continue
            dx /= len; dy /= len
            val dL = -(len - topo.bendRest[c]) / (w + alpha)
            px[i] += dx * dL * wi; py[i] += dy * dL * wi
            px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
        }
    }

    private fun solveAreas(h: Float, block: Int) {
        val alpha = p.areaCompliance / (h * h)
        // -1 значит «не разделять»: тогда ветка ниже выбирает то же самое число, и
        // поведение совпадает с демо до бита.
        val alphaInv = if (p.areaComplianceInverted >= 0.0f) p.areaComplianceInverted / (h * h) else alpha
        val maxStep = p.areaMaxStep * topo.meanLinkLength
        val list = blocks.areas
        for (slot in list.start[block] until list.start[block] + list.count[block]) {
            val t = list.items[slot]
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
            if (denom < 1e-12f) continue

            val s = muscleScale(topo.triMuscle[t])
            val restArea2 = triRestArea2[t].toFloat() * s * s
            val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            // Плавный переход убирает ступеньку жёсткости ровно на нулевой площади.
            val a = if (p.areaSmoothRamp) {
                val t = (area2 / restArea2).coerceIn(0.0f, 1.0f)
                alphaInv + (alpha - alphaInv) * t * t
            } else if (area2 < 0.0f) alphaInv else alpha
            var excess = area2 - restArea2
            if (s < 1.0f) { val cap = 2.0f * MUSCLE_MAX_PULL * triRestArea2[t]; if (excess > cap) excess = cap }
            var dL = -excess / (denom + a)
            // Потолок на норму поправки: |dL| * sqrt(denom) и есть её длина.
            if (maxStep > 0.0f) {
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

        var cx = 0.0f; var cy = 0.0f; var wsum = 0.0f
        for (i in ids) {
            val w = matchWeight[i]
            cx += w * (px[i] - ox); cy += w * (py[i] - oy)
            wsum += w
        }
        cx /= wsum; cy /= wsum

        var s = 0.0f; var t = 0.0f
        for (k in ids.indices) {
            val i = ids[k]
            val w = matchWeight[i]
            val ppx = (px[i] - ox) - cx; val ppy = (py[i] - oy) - cy
            s += w * (q0x[k] * ppx + q0y[k] * ppy)
            t += w * (q0x[k] * ppy - q0y[k] * ppx)
        }
        val norm = sqrt(s * s + t * t)
        if (norm < 1e-9f) return
        val cos = s / norm; val sin = t / norm

        var sdx = 0.0f; var sdy = 0.0f
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
        var maxD2 = 0.0f
        for (k in ids.indices) {
            val dx = boneDx[k] - mdx
            val dy = boneDy[k] - mdy
            val d2 = dx * dx + dy * dy
            if (d2 > maxD2) maxD2 = d2
        }
        var scale = 1.0f
        val cap = p.boneMaxStep * topo.meanLinkLength
        if (cap > 0.0f && maxD2 > cap * cap) scale = cap / sqrt(maxD2)

        for (k in ids.indices) {
            val i = ids[k]
            px[i] += (boneDx[k] - mdx) * scale
            py[i] += (boneDy[k] - mdy) * scale
        }
    }

    private fun updateVelocities(h: Float) {
        for (i in 0 until n) {
            if (invMass[i] == 0.0f) { vx[i] = 0.0f; vy[i] = 0.0f; continue }
            vx[i] = (px[i] - prevX[i]) / h
            vy[i] = (py[i] - prevY[i]) / h
        }
    }

    private fun applyViscosity(h: Float) {
        var k = p.viscosity * h
        if (k > 0.5f) k = 0.5f
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w == 0.0f) continue
            var nx = px[j] - px[i]
            var ny = py[j] - py[i]
            val len = sqrt(nx * nx + ny * ny)
            if (len < 1e-9f) continue
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
    private fun applyNormalDrag(h: Float) {
        var kd = p.flowDecay * h
        if (kd > 1.0f) kd = 1.0f

        // Импульс, переданный телу за этот вызов. Нужен только общему запасу:
        // ровно он и уходит в среду с обратным знаком.
        var impX = 0.0f
        var impY = 0.0f

        val global = p.flowModel == FlowModel.GLOBAL_RESERVOIR
        val tracking = p.flowModel == FlowModel.GLOBAL_TRACKING
        val perEdge = p.flowModel == FlowModel.PER_EDGE
        var kf = p.flowEntrain * h
        if (kf > 1.0f) kf = 1.0f

        if (tracking) {
            // Запас догоняет скорость ЦЕНТРА МАСС СВОЕГО организма, а не всего мира.
            // Одно среднее на всех было прямой ошибкой: тела делили запас и толкали
            // друг друга на любом расстоянии. См. flowVX в DemoSession.
            for (o in 0 until topo.organismCount) { comAccX[o] = 0.0f; comAccY[o] = 0.0f }
            for (i in 0 until n) {
                val o = topo.organismOf[i]
                if (invMass[i] <= 0.0f) continue
                val mw = 1.0f / invMass[i]
                comAccX[o] += mw * vx[i]; comAccY[o] += mw * vy[i]
            }

            // Демпфер между телом и запасом: за шаг относительная скорость падает на
            // kf*(1 + flowMass). Выше единицы это переброс через ноль, то есть раскачка,
            // поэтому коэффициент здесь и ограничивается — устойчиво при любых массах.
            val limit = 0.9f / (1.0f + p.flowMass)
            val kfe = if (kf > limit) limit else kf

            for (o in 0 until topo.organismCount) {
                val mo = organismMass[o]
                if (mo <= 0.0f) continue
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
            if (len < 1e-9f) continue

            val nx = -ey / len
            val ny = ex / len

            val vmx = (vx[i] + vx[j]) * 0.5f
            val vmy = (vy[i] + vy[j]) * 0.5f
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
            if (k > 0.5f) k = 0.5f

            val dv = -vn * k
            vx[i] += dv * nx; vy[i] += dv * ny
            vx[j] += dv * nx; vy[j] += dv * ny
            impX += 2 * dv * nx
            impY += 2 * dv * ny

            // Трение ВДОЛЬ ребра. См. TANGENT_DRAG в DemoSession.
            if (p.tangentDrag > 0.0f) {
                val tx = ex / len; val ty = ey / len
                val vt = (vmx - flowVX[topo.organismOf[i]]) * tx + (vmy - flowVY[topo.organismOf[i]]) * ty
                var kt = p.tangentDrag * p.normalDrag * len * h
                if (kt > 0.5f) kt = 0.5f
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

    private fun applyMediumDrag(h: Float) {
        var keep = 1.0f - p.mediumDrag * h
        if (keep < 0.0f) keep = 0.0f
        for (i in 0 until n) { vx[i] *= keep; vy[i] *= keep }
    }

    // =================================================================
    //  КАДР
    // =================================================================

    /** [gait] — крутить автоматический гребок; иначе мышцы отпущены. */
    fun frame(dt: Float, substeps: Int, gait: Boolean) {
        muscleTarget.fill(0.0f)
        if (gait) {
            val duty = (p.gaitPeriod * p.gaitDuty).toInt().coerceAtLeast(1)
            if (gaitFrame % p.gaitPeriod < duty) muscleTarget.fill(1.0f)
            gaitFrame++
        }
        System.arraycopy(muscleActivation, 0, muscleActivationPrev, 0, muscleActivation.size)
        for (m in muscleActivation.indices) {
            val target = muscleTarget[m]
            val rate = if (target > muscleActivation[m]) p.muscleRateContract else p.muscleRateRelax
            var k = rate * dt
            if (k > 1.0f) k = 1.0f
            muscleActivation[m] += (target - muscleActivation[m]) * k
        }

        if (DemoConst.CANCEL_INTERNAL_SPIN) tickMomentum.begin(px, py, vx, vy, invMass, topo.organismOf)
        val h = dt / substeps
        beginTickMotion()
        for (s in 0 until substeps) {
            muscleFrac = (s + 1).toFloat() / substeps
            integrate(h)
            // Фаза чередования — как в демо, см. SWEEP_FLIP.
            if (DemoConst.SWEEP_FLIP == 1) sweepBackwards = !sweepBackwards

            // Цикл по всем ограничениям сразу, включая контакт — зеркало демо.
            for (iter in 0 until DemoConst.SOLVER_ITERS) {
                if (DemoConst.SWEEP_FLIP == 2) sweepBackwards = !sweepBackwards
                solveBlockedIteration(h)

            }
            // Предел длины ещё раз, уже после контактов — зеркало демо: проекция
            // кости способна растянуть связь, а от него зависит непроницаемость.
            solveLinkMaxLength()
            trackStress()

            if (p.contactsOn) {
                contacts.prepare(px, py, prevX, prevY, vx, vy, invMass, continuing = false)
                if (DemoConst.CANCEL_INTERNAL_SPIN) tickMomentum.noteContacts(contacts, invMass)
                contacts.updateBones(px, py, invMass, topo.boneOf, topo.rigidBones)
                solveContactsAndBoundary(h)
            }
            updateVelocities(h)
            // Отскок — зеркало демо, см. solveRestitution.
            if (DemoConst.CANCEL_INTERNAL_SPIN) tickMomentum.beforeExternal(vx, vy)
            if (p.contactsOn) contacts.solveRestitution(px, py, vx, vy, invMass)
            if (s == substeps - 1) applyViscosity(h * substeps)
            // Зеркало ядра: сопротивление видит среднюю скорость за тик. См. TickMotion.
            if (s == substeps - 1) { removeTickJitter(dt); applyNormalDrag(h * substeps); restoreTickJitter() }
            if (s == substeps - 1) applyMediumDrag(h * substeps)
            // Потолок — после учёта внешнего импульса, как в ядре (FusedPasses.velocityAfterRange).
            if (DemoConst.CANCEL_INTERNAL_SPIN) tickMomentum.afterExternal(px, py, vx, vy, invMass)
            clampSpeed(dt)
            if (DemoConst.CANCEL_INTERNAL_SPIN && s == substeps - 1) {
                tickMomentum.cancel(px, py, vx, vy, invMass)
                clampSpeed(dt)
            }
        }
        applyPlasticity(dt)
    }

    /**
     * ПЛАСТИЧЕСКОЕ ТЕЧЕНИЕ ТКАНИ. Копия стадии из демо, см. DemoSession.applyPlasticity.
     * Зовётся раз в тик, после всех подшагов.
     */
    private val conHold = ByteArray(topo.conCount)
    private val triHold = ByteArray(topo.triCount)

    private fun applyPlasticity(dt: Float) {
        if (p.plasticRate <= 0.0f) return
        var k = p.plasticRate * dt
        if (k > 1.0f) k = 1.0f
        for (c in 0 until topo.conCount) {
            if (topo.conMuscle[c] >= 0) continue
            val target = conRest[c]
            if (target < 1e-12f) continue
            val dx = px[topo.conA[c]] - px[topo.conB[c]]
            val dy = py[topo.conA[c]] - py[topo.conB[c]]
            val len = sqrt(dx * dx + dy * dy)
            val strain = len / target - 1.0f
            val over = when {
                strain > p.plasticYield -> strain - p.plasticYield
                strain < -p.plasticYield -> strain + p.plasticYield
                else -> 0.0f
            }
            if (over == 0.0f) { conHold[c] = 0; continue }
            if (conHold[c] < p.plasticHold) { conHold[c]++; continue }
            conRest[c] *= 1.0f + over * k
            conMaxLen[c] = p.linkMaxStretch * conRest[c]
        }
        for (t in 0 until topo.triCount) {
            if (topo.triMuscle[t] >= 0) continue
            val rest2 = triRestArea2[t].toFloat()
            if (kotlin.math.abs(rest2) < 1e-18f) continue
            val i0 = topo.triA[t]; val i1 = topo.triB[t]; val i2 = topo.triC[t]
            val area2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) -
                (py[i1] - py[i0]) * (px[i2] - px[i0])
            // ВЫВЕРНУТЫМ ТЕЧЬ НЕЛЬЗЯ: подогнав площадь покоя под сложенное состояние,
            // пластика УЗАКОНИТ складку, и та не разойдётся уже никогда. Проверка
            // ловила это прямо: складка 50% и 75% переставали расходиться за 8 секунд.
            if (area2 <= 0.0f) continue
            val strain = area2 / rest2 - 1.0f
            val over = when {
                strain > p.plasticYield -> strain - p.plasticYield
                strain < -p.plasticYield -> strain + p.plasticYield
                else -> 0.0f
            }
            if (over == 0.0f) { triHold[t] = 0; continue }
            if (triHold[t] < p.plasticHold) { triHold[t]++; continue }
            triRestArea2[t] = (triRestArea2[t] * (1.0f + over * k)).toFloat()
        }
    }

    /** Держать мышцу 0 сокращённой — режим сверки с демо и ручных замеров. */
    fun frameHoldMuscle0(dt: Float, substeps: Int, hold: Boolean) {
        muscleTarget.fill(0.0f)
        if (hold && muscleTarget.isNotEmpty()) muscleTarget[0] = 1.0f
        System.arraycopy(muscleActivation, 0, muscleActivationPrev, 0, muscleActivation.size)
        for (m in muscleActivation.indices) {
            val target = muscleTarget[m]
            val rate = if (target > muscleActivation[m]) p.muscleRateContract else p.muscleRateRelax
            var k = rate * dt
            if (k > 1.0f) k = 1.0f
            muscleActivation[m] += (target - muscleActivation[m]) * k
        }
        if (DemoConst.CANCEL_INTERNAL_SPIN) tickMomentum.begin(px, py, vx, vy, invMass, topo.organismOf)
        val h = dt / substeps
        beginTickMotion()
        for (s in 0 until substeps) {
            muscleFrac = (s + 1).toFloat() / substeps
            integrate(h)
            // Фаза чередования — как в демо, см. SWEEP_FLIP.
            if (DemoConst.SWEEP_FLIP == 1) sweepBackwards = !sweepBackwards

            // Цикл по всем ограничениям сразу, включая контакт — зеркало демо.
            for (iter in 0 until DemoConst.SOLVER_ITERS) {
                if (DemoConst.SWEEP_FLIP == 2) sweepBackwards = !sweepBackwards
                solveBlockedIteration(h)

            }
            // Предел длины ещё раз, уже после контактов — зеркало демо: проекция
            // кости способна растянуть связь, а от него зависит непроницаемость.
            solveLinkMaxLength()
            trackStress()

            if (p.contactsOn) {
                contacts.prepare(px, py, prevX, prevY, vx, vy, invMass, continuing = false)
                if (DemoConst.CANCEL_INTERNAL_SPIN) tickMomentum.noteContacts(contacts, invMass)
                contacts.updateBones(px, py, invMass, topo.boneOf, topo.rigidBones)
                solveContactsAndBoundary(h)
            }
            updateVelocities(h)
            // Отскок — зеркало демо, см. solveRestitution.
            if (DemoConst.CANCEL_INTERNAL_SPIN) tickMomentum.beforeExternal(vx, vy)
            if (p.contactsOn) contacts.solveRestitution(px, py, vx, vy, invMass)
            if (s == substeps - 1) applyViscosity(h * substeps)
            // Зеркало ядра: сопротивление видит среднюю скорость за тик. См. TickMotion.
            if (s == substeps - 1) { removeTickJitter(dt); applyNormalDrag(h * substeps); restoreTickJitter() }
            if (s == substeps - 1) applyMediumDrag(h * substeps)
            // Потолок — после учёта внешнего импульса, как в ядре (FusedPasses.velocityAfterRange).
            if (DemoConst.CANCEL_INTERNAL_SPIN) tickMomentum.afterExternal(px, py, vx, vy, invMass)
            clampSpeed(dt)
            if (DemoConst.CANCEL_INTERNAL_SPIN && s == substeps - 1) {
                tickMomentum.cancel(px, py, vx, vy, invMass)
                clampSpeed(dt)
            }
        }
        applyPlasticity(dt)
    }

    /** Жёстко поставить тело в повёрнутую позу покоя с твердотельной скоростью. */
    fun setRigidRotation(angle: Float, omega: Float, cx: Float, cy: Float) {
        val cs = kotlin.math.cos(angle); val sn = kotlin.math.sin(angle)
        for (i in 0 until n) {
            val qx = topo.restX[i].toFloat() - cx; val qy = topo.restY[i].toFloat() - cy
            px[i] = cx + cs * qx - sn * qy
            py[i] = cy + sn * qx + cs * qy
            vx[i] = -omega * (py[i] - cy)
            vy[i] = omega * (px[i] - cx)
        }
    }

    fun dragOnly(h: Float) = applyNormalDrag(h)

    private fun solveContactsAndBoundary(h: Float) {
        contacts.solveContacts(px, py, vx, vy, invMass, h)
        // Клетки кости ориентация не двигает — как стадия ядра (projectBoundaryOrientation).
        solveBoundaryOrientation(topo.boundCount, topo.boundA, topo.boundB, topo.boundOpp,
            topo.boundaryOrientation, px, py, invMass, topo.boneOf)
    }

    /** Активация первой мышцы. Нужна замеру, чтобы отличить гребок от зажатой позы. */
    fun activation0(): Float = if (muscleActivation.isEmpty()) 0.0f else muscleActivation[0].toFloat()

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
    fun comX(): Float { var s = 0.0f; for (i in 0 until n) s += px[i]; return s / n }
    fun comY(): Float { var s = 0.0f; for (i in 0 until n) s += py[i]; return s / n }
    fun momX(): Float { var s = 0.0f; for (i in 0 until n) s += vx[i]; return s }
    fun momY(): Float { var s = 0.0f; for (i in 0 until n) s += vy[i]; return s }
    /**
     * НЕВЯЗКА СВЯЗЕЙ: корень из среднего квадрата (len - rest), в долях средней связи.
     *
     * Прямая мера СХОДИМОСТИ. Податливость задаёт целевую жёсткость материала, но
     * добирается решатель до неё только за несколько подшагов. Мало подшагов — ткань
     * получается мягче, чем задано, и невязка большая. Именно этим и надо выбирать
     * число подшагов, а не туннелированием: его закрывает CCD.
     */
    fun linkResidual(): Float {
        var s = 0.0f
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d = sqrt(dx * dx + dy * dy) - conRest[c] * muscleScale(topo.conMuscle[c])
            s += d * d
        }
        return sqrt(s / topo.conCount) / topo.meanLinkLength
    }

    fun kinetic(): Float {
        var e = 0.0f
        for (i in 0 until n) e += 0.5f * (vx[i] * vx[i] + vy[i] * vy[i])
        return e
    }

    fun momentum() = sqrt(momX() * momX() + momY() * momY())

    /** Момент импульса относительно центра масс, за вычетом поступательного движения. */
    fun angularMomentum(): Float {
        val cx = comX(); val cy = comY()
        val vxm = momX() / n; val vym = momY() / n
        var l = 0.0f
        for (i in 0 until n) {
            l += (px[i] - cx) * (vy[i] - vym) - (py[i] - cy) * (vx[i] - vxm)
        }
        return l
    }

    fun inertia(): Float {
        val cx = comX(); val cy = comY()
        var j = 0.0f
        for (i in 0 until n) {
            val rx = px[i] - cx; val ry = py[i] - cy
            j += rx * rx + ry * ry
        }
        return j
    }

    fun maxSpeed(): Float {
        var mx = 0.0f
        for (i in 0 until n) {
            val s = vx[i].toFloat() * vx[i] + vy[i].toFloat() * vy[i]
            if (s > mx) mx = s
        }
        return sqrt(mx)
    }

    /** Расходимость: NaN или заведомо нефизичная скорость. Кандидат такой отвергается. */
    fun checkDiverged(limit: Float = 50.0f): Boolean {
        for (i in 0 until n) {
            if (px[i].isNaN() || py[i].isNaN() || vx[i].isNaN() || vy[i].isNaN()) { diverged = true; return true }
        }
        if (maxSpeed() > limit) { diverged = true; return true }
        return false
    }
}
