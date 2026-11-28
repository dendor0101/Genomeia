package io.github.some_example_name.physics.lab.stands

import java.util.Locale
import kotlin.math.sqrt

/*
 * ВРЕМЕННО, замер 19.11.2026 (три варианта мышц): насколько мышца меняет тело. Убрать после замеров.
 * squeeze — за сжатие (тики 21..150) к позе на тике 19: радиус инерции живых клеток (наименьший), размер вдоль
 *   длинной оси тела (наименьший) и поперёк неё (наибольший). Меньше радиус и размер вдоль — сильнее мышца.
 * wave — смещение центра масс тела от тика 19 до конца сцены, в связях покоя (больше — мышца лучше двигает тело).
 * Настройки мира — как у стендов: -Dstands.config=КЛЮЧ=… (ключи опыта мышц MX_* убраны 25.11, находка 42).
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val scenes = Catalog.all().filter { it.variation == Variation.BASE && Regex("^gen-muscle/.*/(squeeze|wave)$").matches(it.id) }
    var sqN = 0; var gySum = 0.0; var alSum = 0.0; var acSum = 0.0
    var wvN = 0; var wvSum = 0.0
    for (s in scenes) {
        val run = StandRun(s)
        val st = run.w.world.state
        val body = run.w.bodies[0]
        val squeeze = s.id.endsWith("squeeze")
        fun live(): IntArray = (0 until body.count).map { body.particle(it) }.filter { !st.cellDead[it] }.toIntArray()
        fun centre(ids: IntArray): DoubleArray {
            var x = 0.0; var y = 0.0
            for (i in ids) { x += st.px[i]; y += st.py[i] }
            return doubleArrayOf(x / ids.size, y / ids.size)
        }
        fun spread(ids: IntArray, ux: Double, uy: Double): Double {
            val c = centre(ids); var r = 0.0
            for (i in ids) { val d = (st.px[i] - c[0]) * ux + (st.py[i] - c[1]) * uy; r += d * d }
            return sqrt(r / ids.size)
        }
        val link = run.w.world.meanLinkLength
        var g0 = 0.0; var gMin = Double.MAX_VALUE
        var ax = 1.0; var ay = 0.0; var a0 = 0.0; var b0 = 0.0; var aMin = Double.MAX_VALUE; var bMax = 0.0
        var c0: DoubleArray? = null; var cEnd: DoubleArray? = null
        while (run.w.tick < s.ticks) {
            run.inVariation { run.w.step() }
            val ids = live(); if (ids.isEmpty()) break
            val t = run.w.tick
            if (t == 19) {
                val c = centre(ids); c0 = c
                var sxx = 0.0; var syy = 0.0; var sxy = 0.0
                for (i in ids) { val dx = st.px[i] - c[0]; val dy = st.py[i] - c[1]; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
                val th = 0.5 * Math.atan2(2.0 * sxy, sxx - syy)
                ax = Math.cos(th); ay = Math.sin(th)
                g0 = sqrt((sxx + syy) / ids.size)
                a0 = spread(ids, ax, ay); b0 = spread(ids, -ay, ax)
            }
            if (squeeze && t in 21..150) {
                val a = spread(ids, ax, ay); val b = spread(ids, -ay, ax)
                gMin = minOf(gMin, sqrt(a * a + b * b)); aMin = minOf(aMin, a); bMax = maxOf(bMax, b)
            }
            cEnd = centre(ids)
        }
        if (squeeze && g0 > 0.0 && gMin < Double.MAX_VALUE) {
            sqN++; gySum += gMin / g0; alSum += aMin / a0; acSum += bMax / b0
            println(String.format("%-44s радиус %.3f  вдоль %.3f  поперёк %.3f", s.id, gMin / g0, aMin / a0, bMax / b0))
        }
        if (!squeeze && c0 != null && cEnd != null) {
            val d = sqrt((cEnd[0] - c0[0]) * (cEnd[0] - c0[0]) + (cEnd[1] - c0[1]) * (cEnd[1] - c0[1])) / link
            wvN++; wvSum += d
            println(String.format("%-44s смещение %.2f связи", s.id, d))
        }
    }
    println(String.format("ИТОГ: сжатие — радиус %.4f, вдоль оси %.4f, поперёк %.4f (сцен %d); смещение за волну %.3f связи (сцен %d)",
        gySum / sqN, alSum / sqN, acSum / sqN, sqN, wvSum / wvN, wvN))
}
