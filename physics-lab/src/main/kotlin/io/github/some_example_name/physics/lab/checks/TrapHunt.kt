package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.MAX_SPEED_CELLS_PER_TICK
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.inspection.Probe
import io.github.some_example_name.physics.lab.scenes.RADIUS
import io.github.some_example_name.physics.lab.scenes.STEP
import io.github.some_example_name.physics.lab.scenes.slamOrganisms
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ОХОТА НА ПРОНИКНОВЕНИЕ. Сталкивает тела и падает на каждом случае, когда центр клетки
 * оказался внутри чужой ткани.
 *
 * ЗАЧЕМ ОТДЕЛЬНО ОТ collisionBench. Тот стенд МЕРИТ: гоняет девять вариаций и печатает
 * медиану, потому что числа вроде дребезга хаотичны. Здесь задача обратная — не измерить
 * средний уровень, а НАЙТИ КАЖДЫЙ ОТДЕЛЬНЫЙ СЛУЧАЙ и воспроизвести его. Поэтому:
 *
 *   - отлов застрявших включён как УТВЕРЖДЕНИЕ В КОДЕ (DemoSession.trapCrash): первая
 *     же клетка внутри чужого треугольника останавливает прогон с полным описанием;
 *   - сценарии перебираются по сетке углов, сдвигов, скоростей и вращений;
 *   - тела берутся и готовые, и СИНТЕТИЧЕСКИЕ: квадраты, прямоугольники, тонкие пластины
 *     со случайными кластерами костей. Готовое тело одно, а геометрия у прорыва разная:
 *     тонкая пластина, угол квадрата и костяной кластер ломаются по-разному;
 *   - каждый случай печатается со СВОИМ номером и всеми параметрами, чтобы повторить его
 *     одной командой.
 *
 * ЗАПУСК
 *   gradlew :lwjgl3:trapHunt
 *   gradlew :lwjgl3:trapHunt -Pcase=57
 *   gradlew :lwjgl3:trapHunt -Pplan=quick
 */

/** Один сценарий столкновения. Полностью задаётся числами, поэтому воспроизводим. */
class HuntCase(
    val id: Int,
    val bodyName: String,
    val bodyPath: String,
    /** Свободных частиц в сцене: одиночку не держит ничто, кроме контура. */
    val freeParticles: Int,
    val angleDeg: Double,
    /** Поперечный сдвиг второго тела, в средних длинах связи. */
    val offLinks: Double,
    /** Скорость сближения в долях потолка скорости. */
    val speedFrac: Double,
    /** Вращение второго тела, в долях той же скорости по касательной. */
    val spinFrac: Double,
    val tearing: Boolean,
    val ticks: Int,
    /**
     * Сколько копий тела создать из файла. Для РАЗНОРОДНОЙ пары файл уже содержит два
     * тела, и копия нужна одна — иначе в сцене оказалось бы четыре.
     */
    val copies: Int = 2,
) {
    fun describe(): String = String.format(
        Locale.ROOT,
        "#%d %s своб=%d угол=%.0f сдвиг=%.1f скорость=%.2f вращение=%.2f разрыв=%s",
        id, bodyName, freeParticles, angleDeg, offLinks, speedFrac, spinFrac,
        if (tearing) "да" else "нет"
    )
}

/**
 * СИНТЕТИЧЕСКИЕ ТЕЛА на той же шестиугольной решётке, что даёт редактор: шаг 0.6 по
 * горизонтали, 0.5196 по вертикали, радиус 0.5. Решётка та же не для красоты — от
 * соотношения радиуса к шагу зависит сшивание контура, и тело с другим соотношением
 * проверяло бы не ту физику.
 */
object BodyGen {

    private const val STEP = 0.6
    private const val ROW = 0.5196152422706632      // STEP * sin(60)
    private const val RADIUS = 0.5
    private val NL: String = "\n"

    /**
     * НЕОДНОРОДНАЯ РЕШЁТКА: часть полос растянута. Проверка предсказания.
     *
     * Все однородные тела дают на таране РОВНО НОЛЬ проникновения, медуза — 651 клетку.
     * Единственное, что у неё иное после отвергнутых объяснений (радиус, толщина) — это
     * разброс длин связей 0.20..1.36 против ровных 1.00. Длинная связь означает БОЛЬШОЙ
     * треугольник, а внутри большого треугольника чужая клетка оказывается дальше
     * контактного радиуса от любой клетки контура — достать её нечем. Если предсказание
     * верно, такое тело обязано проникать, а однородное того же размера — нет.
     *
     * [stretch] — во сколько раз растянута каждая [every]-я полоса.
     */
    fun uneven(path: String, cols: Int, rows: Int, stretch: Double, every: Int): String {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        var y = 0.0
        for (r in 0 until rows) {
            val wide = (r % every == 0)
            val step = if (wide) STEP * stretch else STEP
            for (c in 0 until cols) xs.add(c * step + if (r % 2 == 1) step * 0.5 else 0.0)
            repeat(cols) { ys.add(y) }
            y += if (wide) ROW * stretch else ROW
        }
        val n = xs.size
        val links = ArrayList<IntArray>()
        // Связь по близости: порог берётся с запасом на растянутые полосы.
        val lim = STEP * stretch * 1.15
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = xs[i] - xs[j]; val dy = ys[i] - ys[j]
            if (dx * dx + dy * dy <= lim * lim) links.add(intArrayOf(i, j))
        }
        val sb = StringBuilder()
        sb.append("# genomeia body export (uneven lattice)").append(NL)
        sb.append("# cells=").append(n).append(" links=").append(links.size).append(NL)
        sb.append("# P <genomeId> <x> <y> <radius> <cellType> <bone> <muscle>").append(NL)
        for (i in 0 until n) sb.append(String.format(Locale.ROOT,
            "P %d %.4f %.4f %.4f 0 0 0", i, xs[i], ys[i], RADIUS)).append(NL)
        for (l in links) sb.append("L ").append(l[0]).append(" ").append(l[1]).append(NL)
        File(path).writeText(sb.toString())
        return path
    }

    /** Одна фигура: клетки, связи, кости. Нужна, чтобы собирать РАЗНОРОДНЫЕ пары. */
    class Shape(val xs: DoubleArray, val ys: DoubleArray,
                val links: List<IntArray>, val bone: BooleanArray)

    /**
     * Фигура на решётке. Отдельно от записи файла, потому что в одном файле нужно уметь
     * собрать ДВА РАЗНЫХ тела: до сих пор стенд сталкивал тело с его же копией, а
     * столкновение маленького с большим — это другой режим (одна сторона уступает
     * целиком), и ловиться в нём может другое.
     */
    fun shape(cols: Int, rows: Int, boneSeeds: Int = 0, boneSize: Int = 0,
              seed: Long = 1L): Shape {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (r in 0 until rows) for (c in 0 until cols) {
            xs.add(c * STEP + if (r % 2 == 1) STEP * 0.5 else 0.0)
            ys.add(r * ROW)
        }
        val n = xs.size
        val links = ArrayList<IntArray>()
        val nb = Array(n) { ArrayList<Int>() }
        val lim = STEP * 1.05
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = xs[i] - xs[j]; val dy = ys[i] - ys[j]
            if (dx * dx + dy * dy <= lim * lim) {
                links.add(intArrayOf(i, j)); nb[i].add(j); nb[j].add(i)
            }
        }
        val bone = BooleanArray(n)
        val rnd = java.util.Random(seed)
        repeat(boneSeeds) {
            var cur = rnd.nextInt(n)
            var grown = 0
            while (grown < boneSize) {
                if (!bone[cur]) { bone[cur] = true; grown++ }
                val cand = nb[cur]
                if (cand.isEmpty()) break
                cur = cand[rnd.nextInt(cand.size)]
            }
        }
        return Shape(xs.toDoubleArray(), ys.toDoubleArray(), links, bone)
    }

    /**
     * Записать несколько фигур в ОДИН файл как отдельные организмы: связей между ними
     * нет, поэтому связные компоненты расходятся сами. Сдвиг по X берётся с запасом,
     * чтобы фигуры не касались в позе покоя.
     */
    fun writePair(path: String, a: Shape, b: Shape): String {
        val sb = StringBuilder()
        var nn = 0; var ln = 0
        for (sh in listOf(a, b)) { nn += sh.xs.size; ln += sh.links.size }
        sb.append("# genomeia body export (synthetic hunt pair)").append(NL)
        sb.append("# cells=").append(nn).append(" links=").append(ln).append(NL)
        sb.append("# P <genomeId> <x> <y> <radius> <cellType> <bone> <muscle>").append(NL)
        var base = 0
        var shiftX = 0.0
        for (sh in listOf(a, b)) {
            for (i in sh.xs.indices) sb.append(String.format(Locale.ROOT,
                "P %d %.4f %.4f %.4f %d %d 0", base + i, sh.xs[i] + shiftX, sh.ys[i], RADIUS,
                if (sh.bone[i]) 2 else 0, if (sh.bone[i]) 1 else 0)).append(NL)
            for (l in sh.links) sb.append("L ").append(base + l[0]).append(" ")
                .append(base + l[1]).append(NL)
            base += sh.xs.size
            shiftX += (sh.xs.maxOrNull() ?: 0.0) + STEP * 6.0
        }
        File(path).writeText(sb.toString())
        return path
    }

    /**
     * Прямоугольник из cols x rows клеток. Связи — все пары на расстоянии шага решётки,
     * то есть шесть соседей внутри. Треугольники стенд выводит сам из топологии.
     *
     * [boneSeeds] кластеров костей, выращенных случайным обходом по [boneSize] клеток.
     * Кость бессмертна и жёстко проецируется, и это отдельный источник проникновения:
     * проекция кластера разбавляет поправку контакта в каждой отдельной клетке.
     */
    fun rect(path: String, cols: Int, rows: Int, boneSeeds: Int = 0, boneSize: Int = 0,
             seed: Long = 1L): String {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (r in 0 until rows) for (c in 0 until cols) {
            xs.add(c * STEP + if (r % 2 == 1) STEP * 0.5 else 0.0)
            ys.add(r * ROW)
        }
        val n = xs.size
        val links = ArrayList<IntArray>()
        val nb = Array(n) { ArrayList<Int>() }
        val lim = STEP * 1.05
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = xs[i] - xs[j]; val dy = ys[i] - ys[j]
            if (dx * dx + dy * dy <= lim * lim) {
                links.add(intArrayOf(i, j)); nb[i].add(j); nb[j].add(i)
            }
        }
        // Кости: случайный посев и рост по соседям. Кластер обязан быть СВЯЗНЫМ —
        // несвязный распался бы на отдельные кластеры, и проверялось бы не то.
        val bone = BooleanArray(n)
        val rnd = java.util.Random(seed)
        repeat(boneSeeds) {
            var cur = rnd.nextInt(n)
            var grown = 0
            while (grown < boneSize) {
                if (!bone[cur]) { bone[cur] = true; grown++ }
                val cand = nb[cur]
                if (cand.isEmpty()) break
                cur = cand[rnd.nextInt(cand.size)]
            }
        }
        val sb = StringBuilder()
        sb.append("# genomeia body export (synthetic hunt body)").append(NL)
        sb.append("# cells=").append(n).append(" links=").append(links.size)
            .append(" bone=").append(bone.count { it }).append(NL)
        sb.append("# P <genomeId> <x> <y> <radius> <cellType> <bone> <muscle>").append(NL)
        for (i in 0 until n) sb.append(String.format(Locale.ROOT,
            "P %d %.4f %.4f %.4f %d %d 0", i, xs[i], ys[i], RADIUS,
            if (bone[i]) 2 else 0, if (bone[i]) 1 else 0)).append(NL)
        for (l in links) sb.append("L ").append(l[0]).append(" ").append(l[1]).append(NL)
        File(path).writeText(sb.toString())
        return path
    }
}

object TrapHunt {

    /** Центр масс по геометрии: сценарию достаточно среднего по клеткам. */
    private fun com(o: Int): DoubleArray {
        val P = Probe
        var x = 0.0; var y = 0.0; var k = 0
        for (i in 0 until P.n) if (P.organismOf[i] == o) { x += P.px[i]; y += P.py[i]; k++ }
        return if (k == 0) doubleArrayOf(0.0, 0.0) else doubleArrayOf(x / k, y / k)
    }

    private fun radiusOf(o: Int, cx: Double, cy: Double): Double {
        val P = Probe
        var r = 0.0
        for (i in 0 until P.n) if (P.organismOf[i] == o) {
            val d = sqrt((P.px[i] - cx) * (P.px[i] - cx) + (P.py[i] - cy) * (P.py[i] - cy))
            if (d > r) r = d
        }
        return r
    }

    /** Два самых крупных тела — на них и ставится столкновение. */
    private fun twoLargest(): IntArray {
        val P = Probe
        val order = (0 until P.organismCount).sortedByDescending { P.organismSize(it) }
        return intArrayOf(order[0], if (order.size > 1) order[1] else order[0])
    }

    /**
     * Расстановка: два тела навстречу по оси X, второе повёрнуто и сдвинуто поперёк.
     * Импульс сцены стартует с нуля — скорости встречные и равные, — поэтому любое
     * движение центра масс сцены потом сделано решателем, а не постановкой.
     */
    private fun place(c: HuntCase, ml: Double, dt: Double, vmax: Double) {
        val P = Probe
        val two = twoLargest()
        val oa = two[0]; val ob = two[1]
        val ca = com(oa); val cb = com(ob)
        val ra = radiusOf(oa, ca[0], ca[1]); val rb = radiusOf(ob, cb[0], cb[1])
        val gap = ml * 3.0
        val ax = -(ra + gap * 0.5); val bx = rb + gap * 0.5
        val by = c.offLinks * ml
        val a = Math.toRadians(c.angleDeg); val cs = cos(a); val sn = sin(a)
        for (i in 0 until P.n) {
            val o = P.organismOf[i]
            if (o == oa) {
                P.px[i] = P.px[i] - ca[0] + ax
                P.py[i] = P.py[i] - ca[1]
            } else if (o == ob) {
                val x = P.px[i] - cb[0]; val y = P.py[i] - cb[1]
                P.px[i] = x * cs - y * sn + bx
                P.py[i] = x * sn + y * cs + by
            }
        }
        val v = c.speedFrac * vmax * ml / dt
        for (i in 0 until P.n) {
            val o = P.organismOf[i]
            if (o == oa) { P.vx[i] = v * 0.5; P.vy[i] = 0.0 }
            else if (o == ob) { P.vx[i] = -v * 0.5; P.vy[i] = 0.0 }
            else { P.vx[i] = 0.0; P.vy[i] = 0.0 }
        }
        // Вращение второго тела: по касательной той же величины, что и сближение.
        if (c.spinFrac != 0.0 && rb > 1e-9) {
            val om = c.spinFrac * v / rb
            for (i in 0 until P.n) {
                if (P.organismOf[i] != ob) continue
                val dx = P.px[i] - bx; val dy = P.py[i] - by
                P.vx[i] += -om * dy; P.vy[i] += om * dx
            }
        }
        // СВОБОДНЫЕ ЧАСТИЦЫ СТАВЯТСЯ В ЗАЗОР, а не туда, куда их положил загрузчик:
        // смысл их в том, чтобы попасть под удар. Раскладываются поперёк оси.
        var k = 0
        for (i in 0 until P.n) {
            if (!P.isFree(i)) continue
            P.px[i] = 0.0
            P.py[i] = (k - (c.freeParticles - 1) * 0.5) * ml * 1.3
            P.vx[i] = 0.0; P.vy[i] = 0.0
            k++
        }
        for (i in 0 until P.n) { P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i] }
    }

    /**
     * ДИАГНОСТИКА ПРОГОНА. Без неё «прорывов нет» ничего не значит: сцена, в которой тела
     * не встретились, тоже даёт ноль. Поэтому каждый прогон сообщает, сошлись ли тела
     * вообще, насколько глубоко перекрылись границы и сколько связей порвалось.
     */
    var lastMet = 0.0          // наименьшее расстояние центров, в долях суммы радиусов
    var lastPen = 0.0          // наибольшее перекрытие границ, в долях порога
    var lastTorn = 0           // порвано связей
    var lastTick = 0           // на каком тике остановились

    /** Прогон одного сценария. Возвращает падение или null, если прорыва не случилось. */
    fun run(c: HuntCase, crashMode: Int = 1, contacts: Boolean = true): TrappedCrash? {
        val P = Probe
        P.boot(c.bodyPath, copies = c.copies, freeParticles = c.freeParticles)
        val dt = P.const("DT")
        val sub = P.constInt("SUBSTEPS")
        val ml = P.body.meanLinkLength.toDouble()
        val vmax = P.const("MAX_SPEED_CELLS_PER_TICK")
        P.setContacts(contacts)
        P.setTearing(c.tearing)
        P.resetState()
        place(c, ml, dt, vmax)
        P.setField("trapCrash", crashMode)
        val two = twoLargest()
        val oa = two[0]; val ob = two[1]
        val ca0 = com(oa); val cb0 = com(ob)
        val sumR = radiusOf(oa, ca0[0], ca0[1]) + radiusOf(ob, cb0[0], cb0[1])
        lastMet = Double.MAX_VALUE; lastPen = 0.0; lastTorn = 0; lastTick = 0
        val ct = P.contactsObj()
        try {
            for (t in 1..c.ticks) {
                P.frame(dt, sub, contract = false)
                lastTick = t
                val ca = com(oa); val cb = com(ob)
                val d = sqrt((ca[0] - cb[0]) * (ca[0] - cb[0]) + (ca[1] - cb[1]) * (ca[1] - cb[1]))
                if (sumR > 1e-9 && d / sumR < lastMet) lastMet = d / sumR
                if (ct != null) {
                    val pen = ct.maxPenetration(P.px, P.py)
                    if (pen > lastPen) lastPen = pen
                }
            }
            lastTorn = P.tornCount()
            return null
        } catch (e: Throwable) {
            // РЕШАТЕЛЬ ВЫЗЫВАЕТСЯ ЧЕРЕЗ reflection, и падение приходит завёрнутым в
            // InvocationTargetException. Без разворачивания охота ловила бы обёртку и
            // объявляла ошибкой стенда собственную находку.
            val tc = unwrap(e) ?: throw e
            lastTorn = P.tornCount()
            lastTick++          // тик, на котором бросило, не успел досчитаться
            return tc
        }
    }

    /**
     * В КАКОМ ЧУЖОМ ТРЕУГОЛЬНИКЕ СИДИТ КЛЕТКА, или -1. Тот же признак, что у решателя, но
     * считается здесь и полным перебором: разбору нужна не скорость, а независимость от
     * внутренних сеток решателя.
     */
    fun insideForeign(i: Int): Int {
        val P = Probe
        val oi = P.organismOf[i]
        val x = P.px[i]; val y = P.py[i]
        for (t in 0 until P.triCount) {
            val a = P.triA[t]; val b = P.triB[t]; val c = P.triC[t]
            if (a == i || b == i || c == i) continue
            if (P.organismOf[a] == oi) continue
            val ax = P.px[a]; val ay = P.py[a]
            val bx = P.px[b]; val by = P.py[b]
            val cx = P.px[c]; val cy = P.py[c]
            val d1 = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val d2 = (cx - bx) * (y - by) - (cy - by) * (x - bx)
            val d3 = (ax - cx) * (y - cy) - (ay - cy) * (x - cx)
            if ((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0)) continue
            return t
        }
        return -1
    }

    /** Глубина внутрь треугольника, в связях: наименьшее расстояние до его сторон. */
    fun depthIn(i: Int, t: Int, ml: Double): Double {
        val P = Probe
        val v = intArrayOf(P.triA[t], P.triB[t], P.triC[t])
        var best = Double.MAX_VALUE
        for (k in 0 until 3) {
            val p0 = v[k]; val p1 = v[(k + 1) % 3]
            var ex = P.px[p1] - P.px[p0]; var ey = P.py[p1] - P.py[p0]
            val el = sqrt(ex * ex + ey * ey)
            if (el < 1e-12) continue
            ex /= el; ey /= el
            val d = Math.abs((P.px[i] - P.px[p0]) * ey - (P.py[i] - P.py[p0]) * ex)
            if (d < best) best = d
        }
        return if (best == Double.MAX_VALUE) 0.0 else best / ml
    }

    /** Результат разбора одного случая: живучесть и исход. */
    class Triage(
        val entryTick: Int,
        val ticksInside: Int,
        val maxDepth: Double,
        val exited: Boolean,
        val killed: Boolean,
        val tornAfter: Int,
    ) {
        fun describe(): String = String.format(
            Locale.ROOT,
            "вход на тике %d, внутри %d тиков, глубина до %.3f связи, исход: %s, порвано после %d",
            entryTick, ticksInside, maxDepth,
            if (killed) "клетка лопнула" else if (exited) "вышла сама" else "ОСТАЛАСЬ ВНУТРИ",
            tornAfter
        )
    }

    /**
     * РАЗБОР СЛУЧАЯ НА ЖИВУЧЕСТЬ. Мгновенное перекрытие на ударе и застрявшая навсегда
     * клетка — разные болезни, и лечатся они разным. Отличить их можно только продолжив
     * прогон: сценарий воспроизводится тем же посевом, падение выключается, и дальше
     * просто смотрится, где клетка.
     */
    fun triage(c: HuntCase, cell: Int, crashTick: Int, after: Int = 60): Triage {
        val P = Probe
        P.boot(c.bodyPath, copies = c.copies, freeParticles = c.freeParticles)
        val dt = P.const("DT")
        val sub = P.constInt("SUBSTEPS")
        val ml = P.body.meanLinkLength.toDouble()
        val vmax = P.const("MAX_SPEED_CELLS_PER_TICK")
        P.setContacts(true)
        P.setTearing(c.tearing)
        P.resetState()
        place(c, ml, dt, vmax)
        P.setField("trapCrash", 0)
        val dead = P.get<BooleanArray>("cellDead")
        var inside = 0; var maxDepth = 0.0; var exited = false; var killed = false
        var entry = -1
        for (t in 1..(crashTick + after)) {
            P.frame(dt, sub, contract = false)
            if (t < crashTick) continue
            if (dead[cell]) { killed = true; break }
            val tri = insideForeign(cell)
            if (tri >= 0) {
                if (entry < 0) entry = t
                inside++
                val d = depthIn(cell, tri, ml)
                if (d > maxDepth) maxDepth = d
            } else if (entry >= 0) {
                exited = true; break
            }
        }
        return Triage(if (entry < 0) crashTick else entry, inside, maxDepth, exited, killed,
            P.tornCount())
    }

    /**
     * ПОШАГОВЫЙ СЛЕД КЛЕТКИ вокруг места прорыва. Тик — слишком крупная единица: внутрь
     * клетка заходит за один-два подшага из шестнадцати, и на уровне тика видно только
     * «уже внутри». Печатается то же, что в разборе журнала игрока: где клетка, в чьей
     * ткани, ближайшая чужая клетка, ближайшее чужое граничное ребро с его щелью,
     * сколько контактов у клетки и насколько глубоко сжата ближайшая пара.
     */
    fun trace(c: HuntCase, cell: Int, fromTick: Int, toTick: Int) {
        val P = Probe
        P.boot(c.bodyPath, copies = c.copies, freeParticles = c.freeParticles)
        val dt = P.const("DT")
        val sub = P.constInt("SUBSTEPS")
        val ml = P.body.meanLinkLength.toDouble()
        val vmax = P.const("MAX_SPEED_CELLS_PER_TICK")
        P.setContacts(true)
        P.setTearing(c.tearing)
        P.resetState()
        place(c, ml, dt, vmax)
        P.setField("trapCrash", 0)
        val org = P.organismOf
        val dead = P.get<BooleanArray>("cellDead")
        var tick = 0
        var prevX = P.px[cell]; var prevY = P.py[cell]
        // ХОД ВСЕХ КЛЕТОК ЗА ПОДШАГ. Потолок поправки контакта ограничивает только
        // контакт; жёсткие стадии связей, площадей и изгиба двигают клетки позиционно и
        // этим потолком не связаны вовсе. Если на ударе ткань уезжает за подшаг дальше,
        // чем контакт способен отжать, прорыв не в скорости и не в CCD.
        val wasX = DoubleArray(P.n); val wasY = DoubleArray(P.n)
        for (q in 0 until P.n) { wasX[q] = P.px[q]; wasY[q] = P.py[q] }
        P.demo.state.dbgSubstepHook = { step ->
            if (tick in fromTick..toTick) {
                val ct = P.contactsObj()
                val x = P.px[cell]; val y = P.py[cell]
                val tri = insideForeign(cell)
                var nj = -1; var nd = Double.MAX_VALUE
                for (j in 0 until P.n) {
                    if (j == cell || dead[j] || org[j] == org[cell]) continue
                    val dx = P.px[j] - x; val dy = P.py[j] - y
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < nd) { nd = d; nj = j }
                }
                var be = -1; var bd = Double.MAX_VALUE
                for (e in 0 until P.boundCount) {
                    val a = P.boundA[e]; val b = P.boundB[e]
                    if (org[a] == org[cell] || dead[a] || dead[b]) continue
                    val ax = P.px[a]; val ay = P.py[a]
                    val ex = P.px[b] - ax; val ey = P.py[b] - ay
                    val l2 = ex * ex + ey * ey
                    var t = if (l2 < 1e-18) 0.0 else ((x - ax) * ex + (y - ay) * ey) / l2
                    if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
                    val qx = ax + ex * t - x; val qy = ay + ey * t - y
                    val d = sqrt(qx * qx + qy * qy)
                    if (d < bd) { bd = d; be = e }
                }
                val edge = if (be < 0 || ct == null) "нет" else {
                    val a = P.boundA[be]; val b = P.boundB[be]
                    val ex = P.px[b] - P.px[a]; val ey = P.py[b] - P.py[a]
                    val len = sqrt(ex * ex + ey * ey)
                    val seal = ct.contactRadiusOf(a) + ct.contactRadiusOf(b)
                    String.format(Locale.ROOT,
                        "#%d-#%d орг %d: до ребра %.3f, длина %.3f, щель %+.3f",
                        a, b, org[a], bd / ml, len / ml, (len - seal) / ml)
                }
                var cc = 0; var cmin = 9.0
                if (ct != null) for (k in 0 until ct.contactCount) {
                    val a = ct.contactI(k); val b = ct.contactJ(k)
                    if (a != cell && b != cell) continue
                    val j = if (a == cell) b else a
                    val dx = P.px[j] - x; val dy = P.py[j] - y
                    val q = sqrt(dx * dx + dy * dy) / ct.contactDistanceOf(cell, j)
                    cc++; if (q < cmin) cmin = q
                }
                val moved = sqrt((x - prevX) * (x - prevX) + (y - prevY) * (y - prevY)) / ml
                // Наибольший ход за подшаг по всей сцене и у ближайшей чужой клетки.
                var maxMove = 0.0; var maxWho = -1
                for (q in 0 until P.n) {
                    if (dead[q]) continue
                    val d = sqrt((P.px[q] - wasX[q]) * (P.px[q] - wasX[q]) +
                        (P.py[q] - wasY[q]) * (P.py[q] - wasY[q])) / ml
                    if (d > maxMove) { maxMove = d; maxWho = q }
                }
                val njMove = if (nj >= 0) sqrt((P.px[nj] - wasX[nj]) * (P.px[nj] - wasX[nj]) +
                    (P.py[nj] - wasY[nj]) * (P.py[nj] - wasY[nj])) / ml else 0.0
                println(String.format(Locale.ROOT,
                    "   %d.%02d %s ход %.3f св/подшаг v=%.2f кл/тик | ближайшая чужая #%d на %.3f (r=%.2f) | ребро %s | контактов %d%s",
                    tick, step, if (tri >= 0) "В ТКАНИ" else "снаружи ", moved,
                    sqrt(P.vx[cell] * P.vx[cell] + P.vy[cell] * P.vy[cell]) * dt / ml,
                    nj, nd / ml, if (nj >= 0 && ct != null) ct.contactRadiusOf(nj) / ml else 0.0,
                    edge, cc,
                    if (cc > 0) String.format(Locale.ROOT, " (мин d/упор %.3f)", cmin) else "") +
                    String.format(Locale.ROOT,
                        " | ход чужой %.3f, наибольший в сцене %.3f (#%d)", njMove, maxMove, maxWho))
                prevX = x; prevY = y
                for (q in 0 until P.n) { wasX[q] = P.px[q]; wasY[q] = P.py[q] }
            }
        }
        for (t in 1..toTick) { tick = t; P.frame(dt, sub, contract = false) }
        P.demo.state.dbgSubstepHook = null
    }

    /** Достать проникновение из цепочки причин. */
    fun unwrap(t: Throwable?): TrappedCrash? {
        var cur = t
        var guard = 0
        while (cur != null && guard++ < 16) {
            if (cur is TrappedCrash) return cur
            cur = cur.cause
        }
        return null
    }

    /** Полная развёртка. Один список на всё, чтобы номер случая не плавал. */
    fun plan(quick: Boolean): List<HuntCase> {
        val dir = File(System.getProperty("java.io.tmpdir"), "traphunt").apply { mkdirs() }
        // Тело, путь, сколько копий. НОВЫЕ ТЕЛА ДОБАВЛЯЮТСЯ В КОНЕЦ: номер случая — это
        // способ повторить его одной командой, и сдвигать номера нельзя.
        val bodies = ArrayList<Triple<String, String, Int>>()
        bodies.add(Triple("медуза", "body-export.txt", 2))
        val brick = "body-export-" + "кирпич" + ".txt"
        if (File(brick).isFile) bodies.add(Triple("кирпич", brick, 2))
        bodies.add(Triple("квадрат 10x10", BodyGen.rect(File(dir, "sq10.txt").path, 10, 10), 2))
        bodies.add(Triple("прямоуг 16x5", BodyGen.rect(File(dir, "rc16x5.txt").path, 16, 5), 2))
        bodies.add(Triple("пластина 20x2", BodyGen.rect(File(dir, "pl20x2.txt").path, 20, 2), 2))
        bodies.add(Triple("квадрат+кости", BodyGen.rect(File(dir, "sq10b.txt").path, 10, 10, 2, 12, 7L), 2))
        bodies.add(Triple("прямоуг+кости", BodyGen.rect(File(dir, "rc16x5b.txt").path, 16, 5, 3, 8, 11L), 2))
        // РАЗНОРОДНЫЕ ПАРЫ: до сих пор сталкивались только одинаковые близнецы, а
        // маленькое в большое — другой режим: уступает целиком одна сторона.
        bodies.add(Triple("мелкий в крупный", BodyGen.writePair(File(dir, "pair_sb.txt").path,
            BodyGen.shape(14, 14), BodyGen.shape(4, 4)), 1))
        bodies.add(Triple("пластина в квадрат", BodyGen.writePair(File(dir, "pair_pl.txt").path,
            BodyGen.shape(10, 10), BodyGen.shape(18, 2)), 1))
        bodies.add(Triple("угол в бок", BodyGen.writePair(File(dir, "pair_cor.txt").path,
            BodyGen.shape(16, 4), BodyGen.shape(6, 6, 1, 8, 5L)), 1))
        bodies.add(Triple("кости в мягкое", BodyGen.writePair(File(dir, "pair_bone.txt").path,
            BodyGen.shape(12, 8), BodyGen.shape(8, 8, 3, 14, 23L)), 1))

        val angles = if (quick) doubleArrayOf(0.0, 37.0) else doubleArrayOf(0.0, 17.0, 37.0, 61.0, 90.0)
        val offs = if (quick) doubleArrayOf(0.0) else doubleArrayOf(0.0, 0.7, 1.7)
        val speeds = if (quick) doubleArrayOf(1.0) else doubleArrayOf(0.25, 0.5, 1.0)
        val spins = if (quick) doubleArrayOf(0.0) else doubleArrayOf(0.0, 0.5)
        val tears = booleanArrayOf(true, false)
        val out = ArrayList<HuntCase>()
        var id = 0
        for (b in bodies) for (ang in angles) for (off in offs) for (sp in speeds)
            for (spin in spins) for (tear in tears) {
                out.add(HuntCase(id++, b.first, b.second, 4, ang, off, sp, spin, tear, 90, b.third))
            }
        return out
    }
}

fun main(args: Array<String>) {
    // СПОСОБ ИГРОКА: клавиша O. См. SlamTest — он бьёт втрое сильнее любого моего
    // сценария (сближение 12.8 клеток/тик против 8), и мерить надо прежде всего им.
    if (args.isNotEmpty() && args[0] == "slam") {
        val dir = File(System.getProperty("java.io.tmpdir"), "traphunt").apply { mkdirs() }
        val bodies = ArrayList<Triple<String, String, Int>>()
        bodies.add(Triple("медуза", "body-export.txt", 2))
        val brick = "body-export-" + "кирпич" + ".txt"
        if (File(brick).isFile) bodies.add(Triple("кирпич", brick, 2))
        bodies.add(Triple("квадрат 10x10", BodyGen.rect(File(dir, "sq10.txt").path, 10, 10), 2))
        bodies.add(Triple("пластина 20x2", BodyGen.rect(File(dir, "pl20x2.txt").path, 20, 2), 2))
        bodies.add(Triple("квадрат+кости", BodyGen.rect(File(dir, "sq10b.txt").path, 10, 10, 2, 12, 7L), 2))
        // Проверка предсказания про разброс длин связей, см. BodyGen.uneven.
        bodies.add(Triple("неоднородный x2.5", BodyGen.uneven(File(dir, "un25.txt").path, 12, 12, 2.5, 3), 2))
        bodies.add(Triple("неоднородный x4", BodyGen.uneven(File(dir, "un40.txt").path, 12, 12, 4.0, 3), 2))
        bodies.add(Triple("однородный 12x12", BodyGen.rect(File(dir, "sq12.txt").path, 12, 12), 2))
        println("ТАРАН КЛАВИШЕЙ O (slamOrganisms), 90 тиков")
        for (b in bodies) {
            val r = try { SlamTest.run(b.second, b.third, 4, 90) } catch (e: Throwable) { "ошибка: " + e }
            println("  " + b.first + ": " + r)
        }
        return
    }
    val quick = args.isNotEmpty() && args[0] == "quick"
    val only = if (args.size > 1 && args[1].isNotEmpty()) args[1].toIntOrNull() else null
    val traceOn = args.size > 2 && args[2].isNotEmpty() && args[2] != "0"
    val cases = TrapHunt.plan(quick)

    println("ОХОТА НА ПРОНИКНОВЕНИЕ: сценариев " + cases.size)

    // САМОПРОВЕРКА ДЕТЕКТОРА. «Прорывов нет» имеет смысл только если детектор вообще
    // умеет срабатывать. Тот же сценарий с ВЫКЛЮЧЕННЫМИ контактами обязан дать прорыв:
    // без контактов тела проходят друг сквозь друга по определению. Если и здесь тихо —
    // сломан стенд, а не физика исправна.
    run {
        val c = cases.first { it.speedFrac >= 0.9 }
        val e = TrapHunt.run(c, crashMode = 1, contacts = false)
        println(String.format(Locale.ROOT,
            "самопроверка детектора (контакты выключены): %s, сошлись до %.2f суммы радиусов",
            if (e == null) "ПРОВАЛ — детектор молчит" else "ловит на тике " + TrapHunt.lastTick,
            TrapHunt.lastMet))
        if (e != null) println("    " + e.message)
    }

    if (only != null) {
        val c = cases.firstOrNull { it.id == only }
        if (c == null) { println("нет случая " + only); return }
        println(c.describe())
        val crash = TrapHunt.run(c)
        println(String.format(Locale.ROOT,
            "  сошлись до %.2f суммы радиусов, перекрытие границ %.3f, порвано %d, тиков %d",
            TrapHunt.lastMet, TrapHunt.lastPen, TrapHunt.lastTorn, TrapHunt.lastTick))
        if (crash == null) { println("  прорыва нет"); return }
        println("  ПРОРЫВ: " + crash.message)
        val tick = TrapHunt.lastTick
        val tr = TrapHunt.triage(c, crash.cell, tick)
        println("  разбор: " + tr.describe())
        if (traceOn) {
            println("  --- след по подшагам, тики " + maxOf(1, tick - 2) + ".." + (tick + 4))
            TrapHunt.trace(c, crash.cell, maxOf(1, tick - 2), tick + 4)
        }
        return
    }

    // Тик падения хранится рядом со случаем: без него разбор не знает, с какого места
    // продолжать прогон.
    // КАТАЛОГ ПО ИСХОДУ, А НЕ ПО ФАКТУ ПЕРЕКРЫТИЯ. Разбор показал, что случаи разные по
    // сути: одни клетки выходят сами за несколько тиков (решатель работает, перекрытие
    // на ударе — его нормальная работа), другие остаются внутри навсегда. Чинить надо
    // вторые, и смешивать их в одну кучу значит не видеть цели.
    class Found(val c: HuntCase, val e: TrappedCrash, val tick: Int, val tr: TrapHunt.Triage?)

    // ОТЧЁТ ПИШЕТСЯ ПО ХОДУ, а не в конце: кампания длинная, и смотреть на неё нужно не
    // дожидаясь конца. Плюс вывод gradle в перенаправленный поток буферизуется, и до
    // самого конца в нём пусто.
    val report = File("traphunt-report.txt")
    val log = java.io.PrintWriter(java.io.OutputStreamWriter(
        java.io.FileOutputStream(report), "UTF-8"), true)
    fun say(sx: String) { println(sx); log.println(sx) }

    val stuck = ArrayList<Found>()
    val transient = ArrayList<Found>()
    val unclear = ArrayList<Found>()
    var done = 0; var crashed = 0
    var metWorst = Double.MAX_VALUE; var metBest = 0.0; var penWorst = 0.0; var tornAll = 0
    val t0 = System.currentTimeMillis()
    for (c in cases) {
        val crash = try { TrapHunt.run(c) } catch (e: Throwable) {
            say("  ОШИБКА СТЕНДА на " + c.describe() + ": " + e)
            null
        }
        done++
        if (TrapHunt.lastMet < metWorst) metWorst = TrapHunt.lastMet
        if (TrapHunt.lastMet > metBest) metBest = TrapHunt.lastMet
        if (TrapHunt.lastPen > penWorst) penWorst = TrapHunt.lastPen
        tornAll += TrapHunt.lastTorn
        if (crash != null) {
            crashed++
            val tick = TrapHunt.lastTick
            val tr = try { TrapHunt.triage(c, crash.cell, tick) } catch (x: Throwable) { null }
            val f = Found(c, crash, tick, tr)
            log.println("НАЙДЕНО " + c.describe())
            log.println("   " + crash.message)
            log.println("   " + (tr?.describe() ?: "разбор не удался"))
            when {
                tr == null -> unclear.add(f)
                tr.killed -> transient.add(f)
                tr.exited -> transient.add(f)
                else -> stuck.add(f)
            }
        }
        if (done % 50 == 0) say("  ... " + done + " из " + cases.size +
            ", перекрытий " + crashed + ", застряло " + stuck.size)
    }
    val secs = (System.currentTimeMillis() - t0) / 1000.0

    say("")
    say(String.format(Locale.ROOT,
        "ИТОГ за %.0f с: сценариев %d, перекрытий %d, ЗАСТРЯЛО НАВСЕГДА %d, вышли сами %d, неясно %d",
        secs, cases.size, crashed, stuck.size, transient.size, unclear.size))
    say(String.format(Locale.ROOT,
        "сцены сталкивались: сближение от %.2f до %.2f суммы радиусов, перекрытие границ до %.3f, порвано всего %d",
        metWorst, metBest, penWorst, tornAll))

    say("")
    say("=========== ЗАСТРЯВШИЕ — это и есть ошибки ===========")
    val byKind = LinkedHashMap<String, ArrayList<Found>>()
    for (f in stuck) byKind.getOrPut(f.e.kind + " | " + f.c.bodyName) { ArrayList() }.add(f)
    for (e in byKind.entries.sortedByDescending { it.value.size }) {
        say("=== " + e.key + " — " + e.value.size)
        for (f in e.value.take(2)) {
            say("    " + f.c.describe())
            say("      " + f.e.message)
            say("      " + (f.tr?.describe() ?: "разбор не удался"))
        }
        if (e.value.size > 2) say("    ... ещё " + (e.value.size - 2))
    }
    if (stuck.isEmpty()) say("застрявших нет")

    say("")
    say("--- вышли сами (перекрытие на ударе, решатель справился): " + transient.size)
    val tk = LinkedHashMap<String, Int>()
    for (f in transient) tk[f.e.kind] = (tk[f.e.kind] ?: 0) + 1
    for (e in tk.entries.sortedByDescending { it.value }) say("    " + e.key + " — " + e.value)
    log.close()
}
