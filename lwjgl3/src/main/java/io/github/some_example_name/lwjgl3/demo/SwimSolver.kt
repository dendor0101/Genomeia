package io.github.some_example_name.lwjgl3.demo

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Параметризованная копия ЧИСЛЕННОГО конвейера RealBodyDemo для подбора констант.
 *
 * ПОЧЕМУ КОПИЯ, А НЕ САМ ДЕМО. Все константы там `private const val`, то есть
 * подставляются компилятором в место использования. Менять их в рантайме нельзя
 * никак — ни рефлексией, ни наследованием. Поэтому конвейер повторён здесь полями.
 *
 * ЧТОБЫ КОПИЯ НЕ ВРАЛА, она сверяется с настоящей ПОБИТОВО: при параметрах по
 * умолчанию и той же моделью среды она обязана дать те же позиции и скорости, что
 * RealBodyDemo, до последнего бита. Это проверяет SwimTuner первым делом и без этой
 * проверки не запускается — см. verifyAgainstDemo().
 *
 * ТОПОЛОГИЯ НЕ ДУБЛИРУЕТСЯ. Всё, что выводится из графа — связи, треугольники,
 * граница, кластеры костей и мышц, поза покоя костей, — берётся готовым из
 * RealBodyDemo через [Topology]. Здесь только арифметика шага.
 */

/** Топология тела, вынутая из настоящего RealBodyDemo. Только чтение, общая на все потоки. */
class Topology private constructor(
    val n: Int,
    val restX: FloatArray,
    val restY: FloatArray,
    val conA: IntArray,
    val conB: IntArray,
    val conRest: DoubleArray,
    val conMuscle: IntArray,
    val triA: IntArray,
    val triB: IntArray,
    val triC: IntArray,
    val triRestArea2: FloatArray,
    val triMuscle: IntArray,
    val boundA: IntArray,
    val boundB: IntArray,
    val rigidBones: Array<IntArray>,
    val boneRestQx: Array<DoubleArray>,
    val boneRestQy: Array<DoubleArray>,
    val muscleCount: Int,
    val meanLinkLength: Float,
) {
    val conCount: Int get() = conA.size
    val triCount: Int get() = triA.size
    val boundCount: Int get() = boundA.size
    val maxBoneSize: Int get() = rigidBones.maxOfOrNull { it.size } ?: 1

    companion object {
        fun load(path: String): Topology {
            val demo = RealBodyDemo(path)
            fun m(name: String) =
                RealBodyDemo::class.java.getDeclaredMethod(name).apply { isAccessible = true }
            m("buildFromFile").invoke(demo)
            fun <T> f(name: String): T {
                val fl = RealBodyDemo::class.java.getDeclaredField(name)
                fl.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return fl.get(demo) as T
            }
            val body: BodyFile = f("body")
            return Topology(
                n = f("n"),
                restX = body.x, restY = body.y,
                conA = f("conA"), conB = f("conB"),
                conRest = f("conRest"), conMuscle = f("conMuscle"),
                triA = body.triA, triB = body.triB, triC = body.triC,
                triRestArea2 = body.triRestArea2, triMuscle = f("triMuscle"),
                boundA = f("boundA"), boundB = f("boundB"),
                rigidBones = f("rigidBones"),
                boneRestQx = f("boneRestQx"), boneRestQy = f("boneRestQy"),
                muscleCount = body.muscleClusters.size,
                meanLinkLength = body.meanLinkLength,
            )
        }
    }
}

/** Как устроена память среды. */
enum class FlowModel {
    /** Выключено: чисто резистивная среда, память отсутствует. */
    NONE,

    /**
     * Как в RealBodyDemo сейчас: у КАЖДОГО граничного ребра свой запас, вектором
     * в мировых осях. Нужен только для побитовой сверки с демо.
     */
    PER_EDGE,

    /**
     * ОДИН запас на организм: вектор скорости увлечённой среды плюс её масса.
     *
     * ЧТО ЭТО ЛЕЧИТ. Запас, привязанный к ребру, ездит вместе с ребром. Тело
     * повернулось — и накопитель приехал в новое место вместе с ним, хотя жидкость
     * осталась там, где была. Отсюда толчок 308 на пустом повороте. У одного общего
     * вектора привязки к геометрии нет вообще, поэтому и артефакта нет.
     *
     * ЧТО ЭТО СОХРАНЯЕТ. Именно тот эффект, ради которого поток и вводился: разогнанное
     * тело какое-то время несёт разогнанная им же среда. Тело плывёт со скоростью V,
     * запас разгоняется к V, относительная скорость падает, сопротивление обнуляется —
     * длинный накат. А ТЯГУ это не портит, потому что тяга живёт на ЛОКАЛЬНЫХ скоростях
     * плавника (0.24..1.9), которые на два порядка больше скорости корпуса (около 0.01),
     * и вычитание общего вектора их почти не меняет.
     *
     * ПОЧЕМУ БЛУЖДАНИЯ БЫТЬ НЕ МОЖЕТ, по построению:
     *   - обмен строго симметричный: сколько импульса получило тело, ровно столько
     *     потерял запас. Значит тело не может получить импульс из ничего;
     *   - рассеяние только УНОСИТ импульс запаса в объём и никогда не создаёт;
     *   - запас — вектор в мировых осях, поворот тела его не переносит.
     * Итого: тело может получить назад только то, что само вложило, и только в том
     * направлении, в котором вкладывало.
     *
     * ЦЕНА ЧЕСТНОСТИ. Симметричный обмен означает, что за гребок запас набирает импульс,
     * ПРОТИВОПОЛОЖНЫЙ движению тела, и начинает его подтормаживать. Плавание работает
     * только потому, что этот импульс уходит в океан — то есть FLOW_DECAY здесь не
     * костыль, а физическая суть. Отсюда честный компромисс, которого раньше не было:
     * большое рассеяние — сильная тяга и короткий накат, малое — длинный накат и слабая
     * тяга. Вот эту ручку и крутит SwimTuner.
     *
     * ЧЕГО ЭТО НЕ ЛЕЧИТ, вопреки ожиданию. Поворотный толчок остаётся: 0.815 против
     * 0.833 у памяти по рёбрам, то есть почти не изменился. Причина в том, что запас
     * заряжается от импульсов на рёбрах, а они при жёстком вращении есть, и во время
     * зарядки у запаса появляется НЕвращающаяся составляющая — она и даёт разовый
     * толчок. Убирает это только [GLOBAL_TRACKING].
     */
    GLOBAL_RESERVOIR,

    /**
     * ТО ЖЕ САМОЕ, НО ЗАПАС ЗАРЯЖАЕТСЯ ТОЛЬКО ОТ ПОСТУПАТЕЛЬНОГО ДВИЖЕНИЯ.
     *
     * Это и есть ответ на «хочу накат, но не хочу блуждание».
     *
     * Наблюдение, из которого всё следует: эффект, который нужен, — это ПОСТУПАТЕЛЬНОЕ
     * увлечение. «Разогнанное тело какое-то время несёт поток, который он сам разогнал» —
     * речь о скорости корпуса, а не о том, чем машет плавник. Значит запас и надо
     * заряжать от скорости ЦЕНТРА МАСС, а не от суммы импульсов на рёбрах.
     *
     * Что из этого выходит само:
     *   - при чистом вращении скорость центра масс равна нулю, значит запас не
     *     заряжается ВООБЩЕ, и поворотного толчка нет по построению;
     *   - запас всегда направлен туда, куда тело уже едет, поэтому подтолкнуть тело
     *     он может только ВПЕРЁД по его же движению. Это накат, а не блуждание —
     *     «в случайную сторону» тут физически неоткуда взяться;
     *   - тяга не страдает: она живёт на локальных скоростях плавника (0.24..1.9),
     *     а вычитается общий вектор порядка скорости корпуса (около 0.01).
     *
     * Импульс по-прежнему честный: разгон запаса телу СТОИТ, ровно mFlow * dFlow,
     * и эта плата размазывается по всем частицам. Рассеяние только уносит.
     *
     * Сопротивление на рёбрах при этом обменивается импульсом с ОКЕАНОМ, а не с
     * запасом (в отличие от GLOBAL_RESERVOIR) — именно поэтому вращение в запас
     * ничего не кладёт.
     */
    GLOBAL_TRACKING,
}

/** Всё, что подбирается. Значения по умолчанию — ровно те, что стоят в RealBodyDemo. */
data class SwimParams(
    val normalDrag: Double = 43.33,
    val normalDragQuadratic: Double = 71.8,
    val mediumDrag: Double = 0.0021,
    val viscosity: Double = 850.5,

    /** Масса увлечённой среды в массах тела. Тело — n частиц массой 1. */
    val flowMass: Double = 0.088,
    val flowDecay: Double = 1.168,
    val flowEntrain: Double = 1.660,

    val muscleContraction: Double = 0.165,
    val muscleRateContract: Double = 24.71,
    val muscleRateRelax: Double = 35.313,

    val gaitPeriod: Int = 104,
    /** Доля периода на рабочую фазу. */
    val gaitDuty: Double = 0.402,

    val softCompliance: Double = 1.0e-4,
    val areaCompliance: Double = 1.0e-6,
    val flowModel: FlowModel = FlowModel.GLOBAL_TRACKING,
)

class SwimSolver(private val topo: Topology, var p: SwimParams) {

    private val n = topo.n
    val px = DoubleArray(n)
    val py = DoubleArray(n)
    private val prevX = DoubleArray(n)
    private val prevY = DoubleArray(n)
    val vx = DoubleArray(n)
    val vy = DoubleArray(n)
    private val invMass = DoubleArray(n) { 1.0 }
    private val matchWeight = DoubleArray(n) { 1.0 }

    private val muscleActivation = DoubleArray(topo.muscleCount)
    private val muscleTarget = DoubleArray(topo.muscleCount)

    /** PER_EDGE: запас у каждого ребра. */
    private val flowEx = DoubleArray(topo.boundCount)
    private val flowEy = DoubleArray(topo.boundCount)

    /** GLOBAL_RESERVOIR: один вектор скорости увлечённой среды на организм. */
    private var flowVX = 0.0
    private var flowVY = 0.0

    private val boneDx = DoubleArray(topo.maxBoneSize)
    private val boneDy = DoubleArray(topo.maxBoneSize)

    var diverged = false
        private set

    private var gaitFrame = 0

    fun reset() {
        for (i in 0 until n) {
            px[i] = topo.restX[i].toDouble(); py[i] = topo.restY[i].toDouble()
            prevX[i] = px[i]; prevY[i] = py[i]
            vx[i] = 0.0; vy[i] = 0.0
            invMass[i] = 1.0; matchWeight[i] = 1.0
        }
        flowEx.fill(0.0); flowEy.fill(0.0)
        flowVX = 0.0; flowVY = 0.0
        muscleActivation.fill(0.0); muscleTarget.fill(0.0)
        gaitFrame = 0
        diverged = false
    }

    // =================================================================
    //  СТАДИИ. Порядок и арифметика — как в RealBodyDemo.simulate().
    // =================================================================

    private fun muscleScale(m: Int) =
        if (m < 0) 1.0 else 1.0 - muscleActivation[m] * (1.0 - p.muscleContraction)

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
     * Направление обхода ЧЕРЕДУЕТСЯ — симметричный Гаусс-Зейдель, как в RealBodyDemo.
     * Без этого копия расходится с демо на ПЕРВОМ же кадре, потому что порядок обхода
     * задаёт знак углового дрейфа.
     */
    private var sweepBackwards = false

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
            val rest = topo.conRest[c] * muscleScale(topo.conMuscle[c])
            val dL = -(len - rest) / (w + alpha)
            px[i] += dx * dL * wi; py[i] += dy * dL * wi
            px[j] -= dx * dL * wj; py[j] -= dy * dL * wj
        }
    }

    private fun solveAreas(h: Double) {
        val alpha = p.areaCompliance / (h * h)
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
            val restArea2 = topo.triRestArea2[t].toDouble() * s * s
            val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            val dL = -(area2 - restArea2) / (denom + alpha)

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
        for (k in ids.indices) {
            val i = ids[k]
            px[i] += boneDx[k] - mdx
            py[i] += boneDy[k] - mdy
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
            // Запас догоняет скорость ЦЕНТРА МАСС. Вращение сюда не попадает вовсе.
            var comVX = 0.0; var comVY = 0.0
            for (i in 0 until n) { comVX += vx[i]; comVY += vy[i] }
            comVX /= n; comVY /= n

            // Демпфер между телом и запасом: за шаг относительная скорость падает на
            // kf*(1 + flowMass). Выше единицы это переброс через ноль, то есть раскачка,
            // поэтому коэффициент здесь и ограничивается — устойчиво при любых массах.
            val limit = 0.9 / (1.0 + p.flowMass)
            val kfe = if (kf > limit) limit else kf

            val dfx = (comVX - flowVX) * kfe
            val dfy = (comVY - flowVY) * kfe
            flowVX += dfx
            flowVY += dfy

            // Разгон запаса телу СТОИТ: импульс mFlow * dFlow, размазанный по n частицам
            // массой 1, то есть flowMass * dFlow на каждую.
            val pay = p.flowMass
            for (i in 0 until n) { vx[i] -= pay * dfx; vy[i] -= pay * dfy }

            // Рассеяние в объём: только сток.
            flowVX -= flowVX * kd
            flowVY -= flowVY * kd
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
                vn = (vmx - flowVX) * nx + (vmy - flowVY) * ny
            }

            var k = (p.normalDrag + p.normalDragQuadratic * abs(vn)) * len * h
            if (k > 0.5) k = 0.5

            val dv = -vn * k
            vx[i] += dv * nx; vy[i] += dv * ny
            vx[j] += dv * nx; vy[j] += dv * ny
            impX += 2 * dv * nx
            impY += 2 * dv * ny
        }

        if (global) {
            // Обмен симметричный: тело получило impX, среда потеряла ровно столько же.
            // Масса среды — flowMass масс тела, тело это n частиц массой 1.
            val mf = p.flowMass * n
            flowVX -= impX / mf
            flowVY -= impY / mf
            // Рассеяние в объём: только сток, никогда источник.
            flowVX -= flowVX * kd
            flowVY -= flowVY * kd
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
        for (m in muscleActivation.indices) {
            val target = muscleTarget[m]
            val rate = if (target > muscleActivation[m]) p.muscleRateContract else p.muscleRateRelax
            var k = rate * dt
            if (k > 1.0) k = 1.0
            muscleActivation[m] += (target - muscleActivation[m]) * k
        }

        val h = dt / substeps
        for (s in 0 until substeps) {
            integrate(h)
            // Фаза чередования идёт ровно по подшагам, как в демо.
            sweepBackwards = !sweepBackwards
            solveConstraints(h)
            solveAreas(h)
            for (b in topo.rigidBones.indices) projectBone(b)
            updateVelocities(h)
            applyViscosity(h)
            applyNormalDrag(h)
            applyMediumDrag(h)
        }
    }

    /** Держать мышцу 0 сокращённой — режим сверки с демо и ручных замеров. */
    fun frameHoldMuscle0(dt: Double, substeps: Int, hold: Boolean) {
        muscleTarget.fill(0.0)
        if (hold && muscleTarget.isNotEmpty()) muscleTarget[0] = 1.0
        for (m in muscleActivation.indices) {
            val target = muscleTarget[m]
            val rate = if (target > muscleActivation[m]) p.muscleRateContract else p.muscleRateRelax
            var k = rate * dt
            if (k > 1.0) k = 1.0
            muscleActivation[m] += (target - muscleActivation[m]) * k
        }
        val h = dt / substeps
        for (s in 0 until substeps) {
            integrate(h)
            // Фаза чередования идёт ровно по подшагам, как в демо.
            sweepBackwards = !sweepBackwards
            solveConstraints(h)
            solveAreas(h)
            for (b in topo.rigidBones.indices) projectBone(b)
            updateVelocities(h)
            applyViscosity(h)
            applyNormalDrag(h)
            applyMediumDrag(h)
        }
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
     * Знаковая площадь ушла в минус — ткань прошла сама через себя. Ограничение площади
     * такой треугольник назад не вывернет: оно тянет площадь к нужной ВЕЛИЧИНЕ, а знак
     * ему безразличен, и вывернутое состояние для него такое же законное равновесие.
     * Поэтому это не переходный эффект, а необратимая порча формы.
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
    fun momentum() = sqrt(momX() * momX() + momY() * momY())

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
