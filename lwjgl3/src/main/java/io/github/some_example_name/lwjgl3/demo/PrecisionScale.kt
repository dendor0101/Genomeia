package io.github.some_example_name.lwjgl3.demo

import java.util.Locale
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * ТОЧНОСТЬ ПРОТИВ МАСШТАБА МИРА: можно ли остаться во float, если аккуратно
 * нормализовать координаты.
 *
 * Проверяются четыре варианта хранения позиций на одном и том же конвейере
 * (ограничение расстояния + ограничение площади, ровно как в RealBodyDemo):
 *
 *   1. float, тело стоит в разных точках мира — от 0 до 1024;
 *   2. float в ЛОКАЛЬНЫХ координатах организма (мировое смещение хранится отдельно);
 *   3. double, тело в дальнем углу мира;
 *   4. int64 с фиксированной точкой, поправки считаются во float.
 *
 * МЕРИТСЯ утечка: суммарный сдвиг центра масс от ВНУТРЕННИХ стадий. Внутренние
 * ограничения обязаны сохранять центр масс точно, поэтому любое смещение — это
 * накопленное округление, и других источников у него нет.
 *
 * ЧТО ЭТОТ ЗАМЕР ДОЛЖЕН ОПРОВЕРГНУТЬ ИЛИ ПОДТВЕРДИТЬ. Есть соблазн «нормализовать»
 * мир — например, пересчитать координаты из 0..1024 в 0..1 — и ждать, что точность
 * вырастет. У float мантисса 24 бита НЕЗАВИСИМО от порядка, поэтому умножение всех
 * координат на степень двойки меняет только экспоненту, а все относительные ошибки
 * остаются те же до бита. Строка «float, масштаб /1024» в таблице проверяет ровно это.
 */
private const val FIXED_BITS = 30
private const val FIXED_SCALE = (1L shl FIXED_BITS).toDouble()

private class Rig(val topo: Topology, val offset: Double, val worldScale: Double = 1.0) {
    val n = topo.n

    val fpx = FloatArray(n); val fpy = FloatArray(n)
    val dpx = DoubleArray(n); val dpy = DoubleArray(n)
    val qpx = LongArray(n); val qpy = LongArray(n)

    val fRest = FloatArray(topo.conCount)
    val dRest = DoubleArray(topo.conCount)
    val fArea = FloatArray(topo.triCount)
    val dArea = DoubleArray(topo.triCount)

    /** Стартовый центр масс в double — опорная точка для замера утечки. */
    var c0x = 0.0
    var c0y = 0.0

    init {
        for (i in 0 until n) {
            val x = (topo.restX[i] + offset) * worldScale
            val y = (topo.restY[i] + offset) * worldScale
            fpx[i] = x.toFloat(); fpy[i] = y.toFloat()
            dpx[i] = x; dpy[i] = y
            qpx[i] = (x * FIXED_SCALE).roundToLong()
            qpy[i] = (y * FIXED_SCALE).roundToLong()
        }
        for (c in 0 until topo.conCount) {
            val r = topo.conRest[c] * worldScale
            fRest[c] = r.toFloat(); dRest[c] = r
        }
        for (t in 0 until topo.triCount) {
            val a = topo.triRestArea2[t] * worldScale * worldScale
            fArea[t] = a.toFloat(); dArea[t] = a
        }
        c0x = comXd(); c0y = comYd()
    }

    /**
     * НАСКОЛЬКО ВООБЩЕ РЕШЕНЫ ограничения расстояния: корень из среднего квадрата
     * (len - rest), делённый на среднюю длину связи.
     *
     * Без этой проверки таблица врёт, и я на этом чуть не попался. Малая утечка бывает
     * по двум противоположным причинам: либо счёт точный, либо поправки настолько мельче
     * шага float, что при записи обратно в позицию теряются целиком — тело просто
     * перестаёт двигаться, и «утечки» нет потому, что нет ничего. Невязка эти два случая
     * разделяет сразу: в первом она мала, во втором велика.
     */
    fun residualFloat(scale: Float): Double {
        var s = 0.0
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val dx = (fpx[i] - fpx[j]).toDouble()
            val dy = (fpy[i] - fpy[j]).toDouble()
            val d = sqrt(dx * dx + dy * dy) - fRest[c] * scale
            s += d * d
        }
        return sqrt(s / topo.conCount) / (meanLink * worldScale)
    }

    fun residualDouble(scale: Double): Double {
        var s = 0.0
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val dx = dpx[i] - dpx[j]
            val dy = dpy[i] - dpy[j]
            val d = sqrt(dx * dx + dy * dy) - dRest[c] * scale
            s += d * d
        }
        return sqrt(s / topo.conCount) / (meanLink * worldScale)
    }

    fun residualFixed(scale: Float): Double {
        var s = 0.0
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            val dx = (qpx[i] - qpx[j]) / FIXED_SCALE
            val dy = (qpy[i] - qpy[j]) / FIXED_SCALE
            val d = sqrt(dx * dx + dy * dy) - fRest[c] * scale
            s += d * d
        }
        return sqrt(s / topo.conCount) / (meanLink * worldScale)
    }

    // =================================================================
    //  СМЕШАННАЯ ТОЧНОСТЬ: позиции double, скорости float
    //
    //  Проверяется догадка, что утечка живёт в накоплении ПОЗИЦИЙ, а скорости к ней
    //  отношения не имеют. Основания для догадки: скорость лежит в -4..+4, то есть
    //  центрирована на нуле, сокращения разрядов там не бывает в принципе, и поправка
    //  к скорости сравнима с самой скоростью, а не на семь порядков меньше.
    //
    //  Но есть и довод ПРОТИВ, который и надо проверить: updateVelocities выводит
    //  скорость из позиций, и если позиции сохраняют импульс точно, то выведенная из
    //  них скорость — тоже. Округление до float эту связь рвёт, и импульс перестаёт
    //  сохраняться. Вопрос только в величине: рвёт на уровне 1e-7 или на уровне,
    //  который видно глазами.
    // =================================================================

    val dvx = DoubleArray(n); val dvy = DoubleArray(n)
    val fvx = FloatArray(n); val fvy = FloatArray(n)
    private val dprevX = DoubleArray(n); private val dprevY = DoubleArray(n)
    private val fprevX = FloatArray(n); private val fprevY = FloatArray(n)

    /** Полный внутренний шаг в double. Внешних сил нет, импульс обязан остаться нулём. */
    fun fullDouble(h: Double, aS: Double, aA: Double, scale: Double) {
        for (i in 0 until n) {
            dprevX[i] = dpx[i]; dprevY[i] = dpy[i]
            dpx[i] += dvx[i] * h; dpy[i] += dvy[i] * h
        }
        stepDouble(aS, aA, scale)
        for (i in 0 until n) {
            dvx[i] = (dpx[i] - dprevX[i]) / h
            dvy[i] = (dpy[i] - dprevY[i]) / h
        }
    }

    /** Позиции double, скорости float. */
    fun fullMixed(h: Double, aS: Double, aA: Double, scale: Double) {
        for (i in 0 until n) {
            dprevX[i] = dpx[i]; dprevY[i] = dpy[i]
            dpx[i] += fvx[i] * h; dpy[i] += fvy[i] * h
        }
        stepDouble(aS, aA, scale)
        for (i in 0 until n) {
            fvx[i] = ((dpx[i] - dprevX[i]) / h).toFloat()
            fvy[i] = ((dpy[i] - dprevY[i]) / h).toFloat()
        }
    }

    /** Всё во float. */
    fun fullFloat(h: Float, aS: Float, aA: Float, scale: Float) {
        for (i in 0 until n) {
            fprevX[i] = fpx[i]; fprevY[i] = fpy[i]
            fpx[i] += fvx[i] * h; fpy[i] += fvy[i] * h
        }
        stepFloat(aS, aA, scale)
        for (i in 0 until n) {
            fvx[i] = (fpx[i] - fprevX[i]) / h
            fvy[i] = (fpy[i] - fprevY[i]) / h
        }
    }

    // =================================================================
    //  FLOAT С КОМПЕНСАЦИЕЙ (суммирование Кэхэна)
    //
    //  Потолок обычного float в локальных координатах берётся не хранением, а
    //  НАКОПЛЕНИЕМ: в `px += d` младшие биты поправки теряются при округлении
    //  результата, и именно эти потери копятся в дрейф.
    //
    //  Кэхэн эти потери не выбрасывает, а запоминает в отдельном числе и возвращает
    //  в следующее сложение. Пара (значение, компенсация) ведёт себя примерно как
    //  48 бит мантиссы вместо 24 — почти double (53).
    //
    //  ВОПРОС, РАДИ КОТОРОГО ЭТО НАПИСАНО: даёт ли это точность double по цене float.
    //  Заранее видно, что памяти это НЕ экономит — два float на координату это те же
    //  8 байт, что и double. Значит остаётся только скорость, её и меряем.
    // =================================================================

    val kpx = FloatArray(n); val kpy = FloatArray(n)
    private val kcx = FloatArray(n); private val kcy = FloatArray(n)
    private val kprevX = FloatArray(n); private val kprevY = FloatArray(n)
    val kvx = FloatArray(n); val kvy = FloatArray(n)

    fun initKahan() {
        for (i in 0 until n) { kpx[i] = fpx[i]; kpy[i] = fpy[i] }
        kcx.fill(0f); kcy.fill(0f)
    }

    fun fullKahan(h: Float, aS: Float, aA: Float, scale: Float) {
        for (i in 0 until n) {
            kprevX[i] = kpx[i]; kprevY[i] = kpy[i]
            var y = kvx[i] * h - kcx[i]; var t = kpx[i] + y
            kcx[i] = (t - kpx[i]) - y; kpx[i] = t
            y = kvy[i] * h - kcy[i]; t = kpy[i] + y
            kcy[i] = (t - kpy[i]) - y; kpy[i] = t
        }
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            var dx = kpx[i] - kpx[j]
            var dy = kpy[i] - kpy[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-12f) continue
            dx /= len; dy /= len
            val dL = -(len - fRest[c] * scale) / (2f + aS)
            addK(kpx, kcx, i, dx * dL); addK(kpy, kcy, i, dy * dL)
            addK(kpx, kcx, j, -dx * dL); addK(kpy, kcy, j, -dy * dL)
        }
        for (t2 in 0 until topo.triCount) {
            val i0 = topo.triA[t2]; val i1 = topo.triB[t2]; val i2 = topo.triC[t2]
            val x0 = kpx[i0]; val y0 = kpy[i0]
            val x1 = kpx[i1]; val y1 = kpy[i1]
            val x2 = kpx[i2]; val y2 = kpy[i2]
            val g0x = y1 - y2; val g0y = x2 - x1
            val g1x = y2 - y0; val g1y = x0 - x2
            val g2x = y0 - y1; val g2y = x1 - x0
            val denom = g0x * g0x + g0y * g0y + g1x * g1x + g1y * g1y + g2x * g2x + g2y * g2y
            if (denom < 1e-20f) continue
            val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            val dL = -(area2 - fArea[t2] * scale * scale) / (denom + aA)
            addK(kpx, kcx, i0, dL * g0x); addK(kpy, kcy, i0, dL * g0y)
            addK(kpx, kcx, i1, dL * g1x); addK(kpy, kcy, i1, dL * g1y)
            addK(kpx, kcx, i2, dL * g2x); addK(kpy, kcy, i2, dL * g2y)
        }
        for (i in 0 until n) {
            kvx[i] = (kpx[i] - kprevX[i]) / h
            kvy[i] = (kpy[i] - kprevY[i]) / h
        }
    }

    /** Сложение с переносом потерянных младших бит в компенсацию. */
    private fun addK(p: FloatArray, comp: FloatArray, i: Int, d: Float) {
        val y = d - comp[i]
        val t = p[i] + y
        comp[i] = (t - p[i]) - y
        p[i] = t
    }

    fun momentumK(): Double {
        var sx = 0.0; var sy = 0.0
        for (i in 0 until n) { sx += kvx[i]; sy += kvy[i] }
        return sqrt(sx * sx + sy * sy)
    }

    fun comXk(): Double { var s = 0.0; for (i in 0 until n) s += kpx[i]; return s / n }
    fun comYk(): Double { var s = 0.0; for (i in 0 until n) s += kpy[i]; return s / n }

    fun momentumD(): Double {
        var sx = 0.0; var sy = 0.0
        for (i in 0 until n) { sx += dvx[i]; sy += dvy[i] }
        return sqrt(sx * sx + sy * sy)
    }

    fun momentumF(): Double {
        var sx = 0.0; var sy = 0.0
        for (i in 0 until n) { sx += fvx[i]; sy += fvy[i] }
        return sqrt(sx * sx + sy * sy)
    }

    private val meanLink: Double = run {
        var s = 0.0
        for (c in 0 until topo.conCount) s += topo.conRest[c]
        s / topo.conCount
    }

    fun comXd(): Double { var s = 0.0; for (i in 0 until n) s += dpx[i]; return s / n }
    fun comYd(): Double { var s = 0.0; for (i in 0 until n) s += dpy[i]; return s / n }
    fun comXf(): Double { var s = 0.0; for (i in 0 until n) s += fpx[i]; return s / n }
    fun comYf(): Double { var s = 0.0; for (i in 0 until n) s += fpy[i]; return s / n }
    fun comXq(): Double { var s = 0.0; for (i in 0 until n) s += qpx[i] / FIXED_SCALE; return s / n }
    fun comYq(): Double { var s = 0.0; for (i in 0 until n) s += qpy[i] / FIXED_SCALE; return s / n }

    // --- FLOAT ---
    fun stepFloat(alphaSoft: Float, alphaArea: Float, scale: Float) {
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            var dx = fpx[i] - fpx[j]
            var dy = fpy[i] - fpy[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-12f) continue
            dx /= len; dy /= len
            val dL = -(len - fRest[c] * scale) / (2f + alphaSoft)
            fpx[i] += dx * dL; fpy[i] += dy * dL
            fpx[j] -= dx * dL; fpy[j] -= dy * dL
        }
        for (t in 0 until topo.triCount) {
            val i0 = topo.triA[t]; val i1 = topo.triB[t]; val i2 = topo.triC[t]
            val x0 = fpx[i0]; val y0 = fpy[i0]
            val x1 = fpx[i1]; val y1 = fpy[i1]
            val x2 = fpx[i2]; val y2 = fpy[i2]
            val g0x = y1 - y2; val g0y = x2 - x1
            val g1x = y2 - y0; val g1y = x0 - x2
            val g2x = y0 - y1; val g2y = x1 - x0
            val denom = g0x * g0x + g0y * g0y + g1x * g1x + g1y * g1y + g2x * g2x + g2y * g2y
            if (denom < 1e-20f) continue
            val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            val dL = -(area2 - fArea[t] * scale * scale) / (denom + alphaArea)
            fpx[i0] += dL * g0x; fpy[i0] += dL * g0y
            fpx[i1] += dL * g1x; fpy[i1] += dL * g1y
            fpx[i2] += dL * g2x; fpy[i2] += dL * g2y
        }
    }

    // --- DOUBLE ---
    fun stepDouble(alphaSoft: Double, alphaArea: Double, scale: Double) {
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            var dx = dpx[i] - dpx[j]
            var dy = dpy[i] - dpy[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-12) continue
            dx /= len; dy /= len
            val dL = -(len - dRest[c] * scale) / (2.0 + alphaSoft)
            dpx[i] += dx * dL; dpy[i] += dy * dL
            dpx[j] -= dx * dL; dpy[j] -= dy * dL
        }
        for (t in 0 until topo.triCount) {
            val i0 = topo.triA[t]; val i1 = topo.triB[t]; val i2 = topo.triC[t]
            val x0 = dpx[i0]; val y0 = dpy[i0]
            val x1 = dpx[i1]; val y1 = dpy[i1]
            val x2 = dpx[i2]; val y2 = dpy[i2]
            val g0x = y1 - y2; val g0y = x2 - x1
            val g1x = y2 - y0; val g1y = x0 - x2
            val g2x = y0 - y1; val g2y = x1 - x0
            val denom = g0x * g0x + g0y * g0y + g1x * g1x + g1y * g1y + g2x * g2x + g2y * g2y
            if (denom < 1e-24) continue
            val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            val dL = -(area2 - dArea[t] * scale * scale) / (denom + alphaArea)
            dpx[i0] += dL * g0x; dpy[i0] += dL * g0y
            dpx[i1] += dL * g1x; dpy[i1] += dL * g1y
            dpx[i2] += dL * g2x; dpy[i2] += dL * g2y
        }
    }

    // --- ФИКСИРОВАННАЯ ТОЧКА ---
    //
    // Позиции — int64 с фиксированной точкой, ПОПРАВКИ считаются во float.
    //
    // Смысл именно в применении поправки. Разность позиций в целых числах точна всегда,
    // а поправка, округлённая до одного и того же целого d, прибавляется одной вершине
    // и вычитается у другой — сумма сохраняется ТОЧНО, не «почти». То есть утечка не
    // уменьшается, её не существует как явления.
    //
    // У площади три вершины и три разные поправки, их сумма сама по себе в ноль не
    // сходится. Поэтому остаток считается явно и раздаётся обратно с точностью до
    // единицы младшего разряда — тогда и здесь сумма ровно ноль.
    fun stepFixed(alphaSoft: Float, alphaArea: Float, scale: Float) {
        for (c in 0 until topo.conCount) {
            val i = topo.conA[c]; val j = topo.conB[c]
            var dx = ((qpx[i] - qpx[j]) / FIXED_SCALE).toFloat()
            var dy = ((qpy[i] - qpy[j]) / FIXED_SCALE).toFloat()
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-12f) continue
            dx /= len; dy /= len
            val dL = -(len - fRest[c] * scale) / (2f + alphaSoft)
            val ddx = (dx * dL * FIXED_SCALE).roundToLong()
            val ddy = (dy * dL * FIXED_SCALE).roundToLong()
            qpx[i] += ddx; qpy[i] += ddy
            qpx[j] -= ddx; qpy[j] -= ddy
        }
        for (t in 0 until topo.triCount) {
            val i0 = topo.triA[t]; val i1 = topo.triB[t]; val i2 = topo.triC[t]
            // Разности берутся относительно первой вершины: числа маленькие, порядка
            // размера треугольника, и на них float работает в полную силу.
            val x0 = 0f
            val y0 = 0f
            val x1 = ((qpx[i1] - qpx[i0]) / FIXED_SCALE).toFloat()
            val y1 = ((qpy[i1] - qpy[i0]) / FIXED_SCALE).toFloat()
            val x2 = ((qpx[i2] - qpx[i0]) / FIXED_SCALE).toFloat()
            val y2 = ((qpy[i2] - qpy[i0]) / FIXED_SCALE).toFloat()
            val g0x = y1 - y2; val g0y = x2 - x1
            val g1x = y2 - y0; val g1y = x0 - x2
            val g2x = y0 - y1; val g2y = x1 - x0
            val denom = g0x * g0x + g0y * g0y + g1x * g1x + g1y * g1y + g2x * g2x + g2y * g2y
            if (denom < 1e-20f) continue
            val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            val dL = -(area2 - fArea[t] * scale * scale) / (denom + alphaArea)

            var a0x = (dL * g0x * FIXED_SCALE).roundToLong()
            var a1x = (dL * g1x * FIXED_SCALE).roundToLong()
            var a2x = (dL * g2x * FIXED_SCALE).roundToLong()
            var a0y = (dL * g0y * FIXED_SCALE).roundToLong()
            var a1y = (dL * g1y * FIXED_SCALE).roundToLong()
            var a2y = (dL * g2y * FIXED_SCALE).roundToLong()

            // Остаток раздаётся обратно, чтобы сумма поправок была ровно нулём.
            val rx = a0x + a1x + a2x
            val ry = a0y + a1y + a2y
            a0x -= rx - rx / 3 * 2; a1x -= rx / 3; a2x -= rx / 3
            a0y -= ry - ry / 3 * 2; a1y -= ry / 3; a2y -= ry / 3

            qpx[i0] += a0x; qpy[i0] += a0y
            qpx[i1] += a1x; qpy[i1] += a1y
            qpx[i2] += a2x; qpy[i2] += a2y
        }
    }
}

private fun sci(v: Double) = String.format(Locale.ROOT, "%.3e", v)

fun main(args: Array<String>) {
    val path = if (args.isNotEmpty()) args[0] else "body-export.txt"
    val topo = Topology.load(path)

    val h = 1.0 / 144.0 / 4.0
    val alphaSoft = 1.0e-4 / (h * h)
    val alphaArea = 1.0e-6 / (h * h)
    val steps = 4000

    println()
    println("=== ТОЧНОСТЬ ПРОТИВ МАСШТАБА МИРА ===")
    println("тело: n=${topo.n}, средняя связь ${String.format(Locale.ROOT, "%.4f", topo.body())}")
    println("$steps подшагов, длина покоя связей колеблется (имитация работы мышцы)")
    println("утечка = сдвиг центра масс от ВНУТРЕННИХ стадий, обязан быть нулём")
    println()
    println("ULP — шаг float рядом с координатой: там, где он сравним с поправкой")
    println("ограничения, поправка просто теряется при записи обратно в позицию.")
    println()
    println(String.format(Locale.ROOT, "%-34s | %-11s | %-11s | %-9s | %s",
        "хранение позиций", "ULP коорд.", "утечка", "хуже базы", "невязка связей"))

    var base = 0.0
    // Последний использованный множитель длины покоя: цикл заканчивается на нечётном
    // шаге, невязку надо мерить относительно той же цели.
    val lastScale = 1.0 - 0.4 * ((steps - 1) and 1)

    fun runFloat(label: String, offset: Double, worldScale: Double = 1.0): Double {
        val r = Rig(topo, offset, worldScale)
        // ПОДАТЛИВОСТИ ТОЖЕ МАСШТАБИРУЮТСЯ, и это не мелочь: без этого пересчёт мира
        // меняет саму физику, и сравнивать точность было бы не с чем.
        //
        // У расстояния C = len - rest растёт как s, знаменатель (w + alpha) обязан
        // остаться прежним — значит alphaSoft инвариантна.
        // У площади C = area2 - rest растёт как s^2, градиент как s, знаменатель как
        // s^2 — значит alphaArea обязана расти как s^2, иначе при уменьшении мира
        // ограничение площади просто выключается, и «улучшение точности» окажется
        // всего лишь бездельем. Я на этом и попался в первом варианте стенда.
        val aSoft = alphaSoft.toFloat()
        val aArea = (alphaArea * worldScale * worldScale).toFloat()
        for (s in 0 until steps) {
            val sc = (1.0 - 0.4 * (s and 1)).toFloat()
            r.stepFloat(aSoft, aArea, sc)
        }
        val dx = r.comXf() - r.c0x
        val dy = r.comYf() - r.c0y
        val leak = sqrt(dx * dx + dy * dy) / worldScale
        val coord = (topo.restX[0] + offset) * worldScale
        val ulp = Math.ulp(coord.toFloat()).toDouble() / worldScale
        if (base == 0.0) base = leak
        println(String.format(Locale.ROOT, "%-34s | %11s | %11s | %9.1f | %.2f%%",
            label, sci(ulp), sci(leak), leak / base, r.residualFloat(lastScale.toFloat()) * 100.0))
        return leak
    }

    // 1. Тело в разных местах мира. Локальные координаты организма — это ровно
    //    строка offset = 0, потому что мировое смещение хранится отдельно и в
    //    решатель не попадает вовсе.
    runFloat("float, локальные координаты (0)", 0.0)
    runFloat("float, мир 64", 64.0)
    runFloat("float, мир 512", 512.0)
    runFloat("float, мир 1024", 1024.0)

    // 2. Та самая ловушка: пересчитать мир 1024 в 0..1 и ждать роста точности.
    runFloat("float, мир 1024, масштаб /1024", 1024.0, 1.0 / 1024.0)

    // 3. double в дальнем углу.
    run {
        val r = Rig(topo, 1024.0)
        for (s in 0 until steps) {
            val sc = 1.0 - 0.4 * (s and 1)
            r.stepDouble(alphaSoft, alphaArea, sc)
        }
        val dx = r.comXd() - r.c0x
        val dy = r.comYd() - r.c0y
        val leak = sqrt(dx * dx + dy * dy)
        println(String.format(Locale.ROOT, "%-34s | %11s | %11s | %9.4f | %.2f%%",
            "double, мир 1024", sci(Math.ulp(1024.0 + topo.restX[0])), sci(leak), leak / base,
            r.residualDouble(lastScale) * 100.0))
    }

    // 4. Фиксированная точка в дальнем углу.
    run {
        val r = Rig(topo, 1024.0)
        for (s in 0 until steps) {
            val sc = (1.0 - 0.4 * (s and 1)).toFloat()
            r.stepFixed(alphaSoft.toFloat(), alphaArea.toFloat(), sc)
        }
        val dx = r.comXq() - r.c0x
        val dy = r.comYq() - r.c0y
        val leak = sqrt(dx * dx + dy * dy)
        println(String.format(Locale.ROOT, "%-34s | %11s | %11s | %9.4f | %.2f%%",
            "int64 фикс. точка, мир 1024", sci(1.0 / FIXED_SCALE), sci(leak), leak / base,
            r.residualFixed(lastScale.toFloat()) * 100.0))
    }

    println()
    println("Фиксированная точка: $FIXED_BITS бит дробной части, шаг ${sci(1.0 / FIXED_SCALE)},")
    println("диапазон +-${String.format(Locale.ROOT, "%.1e", Long.MAX_VALUE / FIXED_SCALE)} мировых единиц.")

    // ------------------------------------------------------------------
    //  ЦЕНА В ТАКТАХ. Три варианта делают ОДНУ И ТУ ЖЕ работу — связи плюс площади,
    //  — поэтому времена сравнимы напрямую.
    // ------------------------------------------------------------------
    println()
    println("=== ЦЕНА В ПРОИЗВОДИТЕЛЬНОСТИ (связи + площади, один подшаг) ===")

    fun time(kind: Int): Double {
        val r = Rig(topo, 1024.0)
        val aS = alphaSoft.toFloat(); val aA = alphaArea.toFloat()
        fun run(iters: Int) {
            for (s in 0 until iters) {
                val scf = (1.0 - 0.4 * (s and 1)).toFloat()
                when (kind) {
                    0 -> r.stepFloat(aS, aA, scf)
                    1 -> r.stepDouble(alphaSoft, alphaArea, scf.toDouble())
                    else -> r.stepFixed(aS, aA, scf)
                }
            }
        }
        run(3000)
        var best = Double.MAX_VALUE
        for (a in 0 until 3) {
            val t0 = System.nanoTime()
            run(6000)
            val dt = (System.nanoTime() - t0) / 6000.0
            if (dt < best) best = dt
        }
        return best
    }

    val tf = time(0)
    val td = time(1)
    val tq = time(2)
    println(String.format(Locale.ROOT, "float          %8.2f мкс   x1.00", tf / 1000.0))
    println(String.format(Locale.ROOT, "double         %8.2f мкс   x%.2f", td / 1000.0, td / tf))
    println(String.format(Locale.ROOT, "int64 фикс.    %8.2f мкс   x%.2f", tq / 1000.0, tq / tf))
    println()
    println("У фиксированной точки лишняя работа — преобразования int<->float на каждую")
    println("связь и округление поправки обратно в целое. Корень и деление всё равно")
    println("считаются во float, так что эта часть не меняется.")

    // ------------------------------------------------------------------
    //  СМЕШАННАЯ ТОЧНОСТЬ: позиции double, скорости float.
    //
    //  Полный внутренний шаг, внешних сил НЕТ, старт из покоя. Тело только шевелит
    //  мышцей. Импульс обязан остаться ровно нулём — любой ненулевой это утечка,
    //  и её ни с чем не перепутать.
    // ------------------------------------------------------------------
    println()
    println("=== СМЕШАННАЯ ТОЧНОСТЬ: позиции double, скорости float ===")
    println("полный внутренний шаг, внешних сил нет, старт из покоя.")
    println("импульс обязан остаться НУЛЁМ — всё ненулевое это утечка.")
    println()
    println(String.format(Locale.ROOT, "%-38s | %-11s | %-11s | %s",
        "вариант", "|P|", "сдвиг ЦМ", "время, мкс"))

    fun full(label: String, mode: Int, offset: Double): Triple<Double, Double, Double> {
        val r = Rig(topo, offset)
        if (mode == 3) r.initKahan()
        val aSf = alphaSoft.toFloat(); val aAf = alphaArea.toFloat()
        fun run(iters: Int) {
            for (s in 0 until iters) {
                val sc = 1.0 - 0.4 * (s and 1)
                when (mode) {
                    0 -> r.fullDouble(h, alphaSoft, alphaArea, sc)
                    1 -> r.fullMixed(h, alphaSoft, alphaArea, sc)
                    2 -> r.fullFloat(h.toFloat(), aSf, aAf, sc.toFloat())
                    else -> r.fullKahan(h.toFloat(), aSf, aAf, sc.toFloat())
                }
            }
        }
        run(steps)
        val p = when (mode) { 0 -> r.momentumD(); 3 -> r.momentumK(); else -> r.momentumF() }
        val cx = when (mode) { 2 -> r.comXf(); 3 -> r.comXk(); else -> r.comXd() }
        val cy = when (mode) { 2 -> r.comYf(); 3 -> r.comYk(); else -> r.comYd() }
        val drift = sqrt((cx - r.c0x) * (cx - r.c0x) + (cy - r.c0y) * (cy - r.c0y))

        // Замер времени отдельным прогоном, с прогревом.
        val r2 = Rig(topo, offset)
        if (mode == 3) r2.initKahan()
        fun run2(iters: Int) {
            for (s in 0 until iters) {
                val sc = 1.0 - 0.4 * (s and 1)
                when (mode) {
                    0 -> r2.fullDouble(h, alphaSoft, alphaArea, sc)
                    1 -> r2.fullMixed(h, alphaSoft, alphaArea, sc)
                    2 -> r2.fullFloat(h.toFloat(), aSf, aAf, sc.toFloat())
                    else -> r2.fullKahan(h.toFloat(), aSf, aAf, sc.toFloat())
                }
            }
        }
        run2(3000)
        var best = Double.MAX_VALUE
        for (a in 0 until 3) {
            val t0 = System.nanoTime()
            run2(6000)
            val dt = (System.nanoTime() - t0) / 6000.0
            if (dt < best) best = dt
        }
        println(String.format(Locale.ROOT, "%-38s | %11s | %11s | %10.2f",
            label, sci(p), sci(drift), best / 1000.0))
        return Triple(p, drift, best)
    }

    val fFloat1024 = full("всё float, мир 1024", 2, 1024.0)
    full("всё float, локальные координаты", 2, 0.0)
    val fDouble1024 = full("всё double, мир 1024", 0, 1024.0)
    val fMixed1024 = full("double позиции + float скорости, 1024", 1, 1024.0)
    val fMixedLocal = full("double позиции + float скорости, локально", 1, 0.0)
    val fKahanLocal = full("float + компенсация Кэхэна, локально", 3, 0.0)
    full("float + компенсация Кэхэна, мир 1024", 3, 1024.0)

    println()
    println(String.format(Locale.ROOT,
        "цена относительно «всё float»: double %.2fx, смешанный %.2fx",
        fDouble1024.third / fFloat1024.third, fMixed1024.third / fFloat1024.third))
    println(String.format(Locale.ROOT,
        "утечка импульса: float %s, double %s, смешанный %s, смешанный+локально %s",
        sci(fFloat1024.first), sci(fDouble1024.first),
        sci(fMixed1024.first), sci(fMixedLocal.first)))
    println()
}

/** Средняя длина связи — для шапки отчёта. */
private fun Topology.body(): Double {
    var s = 0.0
    for (c in 0 until conCount) s += conRest[c]
    return s / conCount
}
