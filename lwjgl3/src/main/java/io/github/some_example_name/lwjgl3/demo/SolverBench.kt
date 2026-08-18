package io.github.some_example_name.lwjgl3.demo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ЦЕНА DOUBLE В ПРОИЗВОДИТЕЛЬНОСТИ, замером а не рассуждением.
 *
 * Гоняет ОДИН И ТОТ ЖЕ горячий конвейер в двух вариантах — float32 и double — на
 * настоящей топологии тела, и меряет время. Арена набивается копиями организма, чтобы
 * увидеть главное: где рабочий набор перестаёт помещаться в кэш. Именно там разница и
 * появляется, а на одном организме её может не быть вовсе.
 *
 * ЧТО ИМЕННО СЧИТАЕТСЯ. Все стадии, стоимость которых линейна по числу связей,
 * треугольников и граничных рёбер: интегрирование, ограничение расстояния, ограничение
 * площади, восстановление скоростей, продольная вязкость, сопротивление среды. Проекция
 * кости не входит — её стоимость пропорциональна числу костных клеток, а их на порядок
 * меньше, и на общий счёт она не влияет.
 *
 * ПОЧЕМУ КОПИИ, А НЕ ОДНО БОЛЬШОЕ ТЕЛО. В движке арена — это набор организмов, каждый
 * со своей локальной нумерацией после RCM. Копии воспроизводят ровно это: внутри копии
 * связи короткие, между копиями связей нет. Так же ведёт себя и настоящая арена.
 */
private class Bench(topo: Topology, val copies: Int) {

    val n = topo.n * copies
    private val conCount = topo.conCount * copies
    private val triCount = topo.triCount * copies
    private val boundCount = topo.boundCount * copies

    // --- топология, размноженная по копиям ---
    private val conA = IntArray(conCount)
    private val conB = IntArray(conCount)
    private val triA = IntArray(triCount)
    private val triB = IntArray(triCount)
    private val triC = IntArray(triCount)
    private val boundA = IntArray(boundCount)
    private val boundB = IntArray(boundCount)

    // --- состояние: два комплекта ---
    private val fpx = FloatArray(n); private val fpy = FloatArray(n)
    private val fprevX = FloatArray(n); private val fprevY = FloatArray(n)
    private val fvx = FloatArray(n); private val fvy = FloatArray(n)
    private val fconRest = FloatArray(conCount)
    private val ftriRest = FloatArray(triCount)

    private val dpx = DoubleArray(n); private val dpy = DoubleArray(n)
    private val dprevX = DoubleArray(n); private val dprevY = DoubleArray(n)
    private val dvx = DoubleArray(n); private val dvy = DoubleArray(n)
    private val dconRest = DoubleArray(conCount)
    private val dtriRest = DoubleArray(triCount)

    /** Байт горячего состояния на вариант: то, что реально ходит через кэш. */
    fun bytes(double: Boolean): Long {
        val w = if (double) 8L else 4L
        val state = 6L * n * w                 // px, py, prevX, prevY, vx, vy
        val rest = (conCount + triCount) * w   // длины покоя и площади покоя
        val idx = (2L * conCount + 3L * triCount + 2L * boundCount) * 4L
        return state + rest + idx
    }

    init {
        for (c in 0 until copies) {
            val off = c * topo.n
            for (k in 0 until topo.conCount) {
                val q = c * topo.conCount + k
                conA[q] = topo.conA[k] + off
                conB[q] = topo.conB[k] + off
                dconRest[q] = topo.conRest[k]
                fconRest[q] = topo.conRest[k].toFloat()
            }
            for (k in 0 until topo.triCount) {
                val q = c * topo.triCount + k
                triA[q] = topo.triA[k] + off
                triB[q] = topo.triB[k] + off
                triC[q] = topo.triC[k] + off
                ftriRest[q] = topo.triRestArea2[k]
                dtriRest[q] = topo.triRestArea2[k].toDouble()
            }
            for (k in 0 until topo.boundCount) {
                val q = c * topo.boundCount + k
                boundA[q] = topo.boundA[k] + off
                boundB[q] = topo.boundB[k] + off
            }
            // Копии расставлены в сетку, чтобы не лежать одна в другой: на счёт это не
            // влияет, но так состояние ближе к настоящей арене.
            val gx = (c % 32) * 4.0
            val gy = (c / 32) * 4.0
            for (k in 0 until topo.n) {
                val i = off + k
                dpx[i] = topo.restX[k] + gx; dpy[i] = topo.restY[k] + gy
                fpx[i] = dpx[i].toFloat(); fpy[i] = dpy[i].toFloat()
                dprevX[i] = dpx[i]; dprevY[i] = dpy[i]
                fprevX[i] = fpx[i]; fprevY[i] = fpy[i]
            }
        }
    }

    // ------------------------------------------------------------------
    //  FLOAT
    // ------------------------------------------------------------------
    fun stepFloat(h: Float, alphaSoft: Float, alphaArea: Float, scale: Float) {
        for (i in 0 until n) {
            fprevX[i] = fpx[i]; fprevY[i] = fpy[i]
            fpx[i] += fvx[i] * h; fpy[i] += fvy[i] * h
        }
        for (c in 0 until conCount) {
            val i = conA[c]; val j = conB[c]
            var dx = fpx[i] - fpx[j]
            var dy = fpy[i] - fpy[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-9f) continue
            dx /= len; dy /= len
            val dL = -(len - fconRest[c] * scale) / (2f + alphaSoft)
            fpx[i] += dx * dL; fpy[i] += dy * dL
            fpx[j] -= dx * dL; fpy[j] -= dy * dL
        }
        for (t in 0 until triCount) {
            val i0 = triA[t]; val i1 = triB[t]; val i2 = triC[t]
            val x0 = fpx[i0]; val y0 = fpy[i0]
            val x1 = fpx[i1]; val y1 = fpy[i1]
            val x2 = fpx[i2]; val y2 = fpy[i2]
            val g0x = y1 - y2; val g0y = x2 - x1
            val g1x = y2 - y0; val g1y = x0 - x2
            val g2x = y0 - y1; val g2y = x1 - x0
            val denom = g0x * g0x + g0y * g0y + g1x * g1x + g1y * g1y + g2x * g2x + g2y * g2y
            if (denom < 1e-12f) continue
            val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            val dL = -(area2 - ftriRest[t] * scale * scale) / (denom + alphaArea)
            fpx[i0] += dL * g0x; fpy[i0] += dL * g0y
            fpx[i1] += dL * g1x; fpy[i1] += dL * g1y
            fpx[i2] += dL * g2x; fpy[i2] += dL * g2y
        }
        for (i in 0 until n) {
            fvx[i] = (fpx[i] - fprevX[i]) / h
            fvy[i] = (fpy[i] - fprevY[i]) / h
        }
        for (c in 0 until conCount) {
            val i = conA[c]; val j = conB[c]
            var nx = fpx[j] - fpx[i]; var ny = fpy[j] - fpy[i]
            val len = sqrt(nx * nx + ny * ny)
            if (len < 1e-9f) continue
            nx /= len; ny /= len
            val dv = (fvx[j] - fvx[i]) * nx + (fvy[j] - fvy[i]) * ny
            val s = 0.17f
            fvx[i] += dv * nx * s; fvy[i] += dv * ny * s
            fvx[j] -= dv * nx * s; fvy[j] -= dv * ny * s
        }
        for (e in 0 until boundCount) {
            val i = boundA[e]; val j = boundB[e]
            val ex = fpx[j] - fpx[i]; val ey = fpy[j] - fpy[i]
            val len = sqrt(ex * ex + ey * ey)
            if (len < 1e-9f) continue
            val nx = -ey / len; val ny = ex / len
            val vmx = (fvx[i] + fvx[j]) * 0.5f
            val vmy = (fvy[i] + fvy[j]) * 0.5f
            val vn = vmx * nx + vmy * ny
            var k = (43.33f + 71.8f * abs(vn)) * len * h
            if (k > 0.5f) k = 0.5f
            val dv = -vn * k
            fvx[i] += dv * nx; fvy[i] += dv * ny
            fvx[j] += dv * nx; fvy[j] += dv * ny
        }
    }

    // ------------------------------------------------------------------
    //  DOUBLE — тот же код, другой тип
    // ------------------------------------------------------------------
    fun stepDouble(h: Double, alphaSoft: Double, alphaArea: Double, scale: Double) {
        for (i in 0 until n) {
            dprevX[i] = dpx[i]; dprevY[i] = dpy[i]
            dpx[i] += dvx[i] * h; dpy[i] += dvy[i] * h
        }
        for (c in 0 until conCount) {
            val i = conA[c]; val j = conB[c]
            var dx = dpx[i] - dpx[j]
            var dy = dpy[i] - dpy[j]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-12) continue
            dx /= len; dy /= len
            val dL = -(len - dconRest[c] * scale) / (2.0 + alphaSoft)
            dpx[i] += dx * dL; dpy[i] += dy * dL
            dpx[j] -= dx * dL; dpy[j] -= dy * dL
        }
        for (t in 0 until triCount) {
            val i0 = triA[t]; val i1 = triB[t]; val i2 = triC[t]
            val x0 = dpx[i0]; val y0 = dpy[i0]
            val x1 = dpx[i1]; val y1 = dpy[i1]
            val x2 = dpx[i2]; val y2 = dpy[i2]
            val g0x = y1 - y2; val g0y = x2 - x1
            val g1x = y2 - y0; val g1y = x0 - x2
            val g2x = y0 - y1; val g2y = x1 - x0
            val denom = g0x * g0x + g0y * g0y + g1x * g1x + g1y * g1y + g2x * g2x + g2y * g2y
            if (denom < 1e-18) continue
            val area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            val dL = -(area2 - dtriRest[t] * scale * scale) / (denom + alphaArea)
            dpx[i0] += dL * g0x; dpy[i0] += dL * g0y
            dpx[i1] += dL * g1x; dpy[i1] += dL * g1y
            dpx[i2] += dL * g2x; dpy[i2] += dL * g2y
        }
        for (i in 0 until n) {
            dvx[i] = (dpx[i] - dprevX[i]) / h
            dvy[i] = (dpy[i] - dprevY[i]) / h
        }
        for (c in 0 until conCount) {
            val i = conA[c]; val j = conB[c]
            var nx = dpx[j] - dpx[i]; var ny = dpy[j] - dpy[i]
            val len = sqrt(nx * nx + ny * ny)
            if (len < 1e-12) continue
            nx /= len; ny /= len
            val dv = (dvx[j] - dvx[i]) * nx + (dvy[j] - dvy[i]) * ny
            val s = 0.17
            dvx[i] += dv * nx * s; dvy[i] += dv * ny * s
            dvx[j] -= dv * nx * s; dvy[j] -= dv * ny * s
        }
        for (e in 0 until boundCount) {
            val i = boundA[e]; val j = boundB[e]
            val ex = dpx[j] - dpx[i]; val ey = dpy[j] - dpy[i]
            val len = sqrt(ex * ex + ey * ey)
            if (len < 1e-12) continue
            val nx = -ey / len; val ny = ex / len
            val vmx = (dvx[i] + dvx[j]) * 0.5
            val vmy = (dvy[i] + dvy[j]) * 0.5
            val vn = vmx * nx + vmy * ny
            var k = (43.33 + 71.8 * abs(vn)) * len * h
            if (k > 0.5) k = 0.5
            val dv = -vn * k
            dvx[i] += dv * nx; dvy[i] += dv * ny
            dvx[j] += dv * nx; dvy[j] += dv * ny
        }
    }

    /** Чтобы JIT не выбросил вычисления как мёртвые. */
    fun checksum(double: Boolean): Double {
        var s = 0.0
        if (double) for (i in 0 until n step 17) s += dpx[i] + dvy[i]
        else for (i in 0 until n step 17) s += fpx[i] + fvy[i]
        return s
    }
}

private fun bench(b: Bench, double: Boolean, substeps: Int): Double {
    val h = 1.0 / 144.0 / substeps
    val alphaSoft = 1.0e-4 / (h * h)
    val alphaArea = 1.0e-6 / (h * h)

    fun run(iters: Int) {
        for (s in 0 until iters) {
            val scale = 1.0 - 0.5 * (s and 1)
            if (double) b.stepDouble(h, alphaSoft, alphaArea, scale)
            else b.stepFloat(h.toFloat(), alphaSoft.toFloat(), alphaArea.toFloat(), scale.toFloat())
        }
    }

    // Прогрев: без него меряется работа интерпретатора, а не скомпилированного кода.
    run(2000)
    var sink = b.checksum(double)

    // Три замера, берётся ЛУЧШИЙ: шум операционной системы только замедляет, поэтому
    // минимум ближе к правде, чем среднее.
    var best = Double.MAX_VALUE
    val iters = 4000
    for (attempt in 0 until 3) {
        val t0 = System.nanoTime()
        run(iters)
        val dt = (System.nanoTime() - t0).toDouble() / iters
        sink += b.checksum(double)
        if (dt < best) best = dt
    }
    if (sink == 123.456) println("недостижимо")
    return best
}

fun main(args: Array<String>) {
    val path = if (args.isNotEmpty()) args[0] else "body-export.txt"
    val topo = Topology.load(path)

    println()
    println("=== ЦЕНА DOUBLE В ПРОИЗВОДИТЕЛЬНОСТИ ===")
    println("CPU: ${System.getProperty("os.arch")}, ядер ${Runtime.getRuntime().availableProcessors()}")
    println("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    println("организм: n=${topo.n} связей=${topo.conCount} треугольников=${topo.triCount} " +
        "граничных рёбер=${topo.boundCount}")
    println()
    println("Один подшаг = интегрирование + связи + площади + скорости + вязкость + среда.")
    println("Проекция кости не входит: она пропорциональна числу костных клеток, их на")
    println("порядок меньше, и на общий счёт она не влияет.")
    println()
    println(String.format(Locale.ROOT, "%-8s | %-9s | %-11s | %-11s | %-11s | %-7s | %s",
        "копий", "частиц", "float, мкс", "double, мкс", "double/float", "КБ f", "КБ d"))

    for (copies in listOf(1, 4, 16, 64, 256, 1024)) {
        val b = Bench(topo, copies)
        val tf = bench(b, double = false, substeps = 4)
        val td = bench(b, double = true, substeps = 4)
        println(String.format(Locale.ROOT,
            "%-8d | %-9d | %11.2f | %11.2f | %11.3f | %7.0f | %.0f",
            copies, b.n, tf / 1000.0, td / 1000.0, td / tf,
            b.bytes(false) / 1024.0, b.bytes(true) / 1024.0))
    }

    println()
    println("Столбцы КБ — горячее состояние целиком. Ориентиры: L1d обычно 32..48 КБ на")
    println("ядро, L2 0.5..2 МБ, L3 общий на несколько МБ. Отношение double/float надо")
    println("читать вместе с ними: пока набор в кэше, разницы почти нет, а когда вываливается")
    println("в память, счёт идёт по БАЙТАМ, и double платит вдвое.")
    println()
}
