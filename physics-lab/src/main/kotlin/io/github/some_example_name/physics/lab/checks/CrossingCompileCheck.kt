package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.crossingBlockers
import io.github.some_example_name.physics.lab.stands.GenomeBodies
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.system.exitProcess

/**
 * ВРЕМЕННЫЕ СВЯЗИ РОСТА: «что ложится на связь» (crossingBlockers, physics/topology/TempLinks.kt) — ответ и компиляция.
 *
 * Повод — 20.11 21:24: полный прогон стендов упал, когда у машины кончилась память, и последним выделял её компилятор C2
 * HotSpot на crossingBlockers. Подозрение, что раздулся он, проверено здесь и НЕ подтвердилось: сам процесс прогона
 * занимал 1.5 ГБ (hs_err), а прежняя версия функции (с замыканиями) и при обычной компиляции (23 700 вызовов, C2 на
 * четвёртом уровне), и при принудительной (-Xcomp) не поднялась выше 1.1 ГБ. Память ел кто-то другой. Проверка осталась
 * сверкой ответа и сторожем: ограничить память компилятора JDK 21 не умеет (CompileCommand MemLimit — с JDK 22), поэтому
 * сторож — свой поток: память процесса больше [LIMIT_MB] — немедленная остановка с кодом 3.
 *
 * Что проверяется:
 *  1. ответ — на всех телах геномов стендов связи поперёк граней (`X` файла тела) против простого перебора всех клеток
 *     и связей (те же правила);
 *  2. компиляция — многократные вызовы на всех телах; запуск с `-XX:-TieredCompilation -Xcomp
 *     -XX:CompileCommand=compileonly,io.github.some_example_name.physics.*::*` отдаёт функцию C2 с первого вызова.
 *
 * Запуск (из корня рабочей копии, classpath — :physics-lab:writeBenchmarkClasspath):
 *   java [флаги выше] -cp <classpath> io.github.some_example_name.physics.lab.checks.CrossingCompileCheckKt [кругов]
 * Итог: PASS — ответ совпал и память не выросла; код 1 — ответ разошёлся; код 3 — сторож остановил процесс.
 */
private const val LIMIT_MB = 3072L

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val rounds = args.getOrNull(0)?.toIntOrNull() ?: 20
    val peak = startWatchdog()
    val bodies = GenomeBodies.entries.map { e ->
        val s = GenomeBodies.shape(e.slug)
        val g = s.genome!!
        val own = HashSet<Long>()
        for (k in 0 until s.linkCount) own.add(key(s.linkA[k], s.linkB[k]))
        val temp = g.allLinkA.indices.filter { key(g.allLinkA[it], g.allLinkB[it]) !in own }.toIntArray()
        Body(e.slug, FloatArray(s.count) { s.lx[it].toFloat() }, FloatArray(s.count) { s.ly[it].toFloat() }, g.allLinkA, g.allLinkB, temp)
    }
    println("тел ${bodies.size}, связей поперёк граней ${bodies.sumOf { it.temp.size }}")
    var wrong = 0
    for (b in bodies) {
        val fast = crossingBlockers(b.x, b.y, b.la, b.lb, b.temp)
        for (q in b.temp.indices) if (!fast[q].contentEquals(brute(b, b.temp[q]))) {
            if (wrong++ < 5) println("РАСХОЖДЕНИЕ: ${b.slug}, связь ${b.temp[q]}: ${fast[q].toList()} против ${brute(b, b.temp[q]).toList()}")
        }
    }
    println("ответ: расхождений $wrong")
    val t0 = System.nanoTime()
    var pairs = 0L
    repeat(rounds) { for (b in bodies) for (r in crossingBlockers(b.x, b.y, b.la, b.lb, b.temp)) pairs += r.size / 2 }
    println("вызовов ${rounds * bodies.size} за %.1f с, пар %d, наибольшая память процесса %d МБ".format((System.nanoTime() - t0) / 1e9, pairs, peak[0] / (1 shl 20)))
    if (wrong > 0) { println("FAIL: ответ разошёлся с перебором"); exitProcess(1) }
    println("PASS")
    exitProcess(0)
}

private class Body(val slug: String, val x: FloatArray, val y: FloatArray, val la: IntArray, val lb: IntArray, val temp: IntArray)

private fun key(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

/** Сторож памяти: свой поток раз в 50 мс; больше LIMIT_MB — остановка с кодом 3. Возвращает наибольшее увиденное. */
private fun startWatchdog(): LongArray {
    val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    val peak = LongArray(1)
    val t = Thread {
        while (true) {
            val used = os.committedVirtualMemorySize
            if (used > peak[0]) peak[0] = used
            if (used > LIMIT_MB shl 20) {
                System.err.println("СТОРОЖ: память процесса ${used shr 20} МБ больше $LIMIT_MB МБ — остановка")
                Runtime.getRuntime().halt(3)
            }
            Thread.sleep(50)
        }
    }
    t.isDaemon = true
    t.priority = Thread.MAX_PRIORITY
    t.start()
    return peak
}

/** Перебор всех клеток и связей против связи [k] — те же правила, что у crossingBlockers. */
private fun brute(b: Body, k: Int): IntArray {
    val x = b.x; val y = b.y; val la = b.la; val lb = b.lb
    val a = la[k]; val c = lb[k]
    val ax = x[a].toDouble(); val ay = y[a].toDouble()
    val dx = x[c] - ax; val dy = y[c] - ay
    val len2 = dx * dx + dy * dy
    if (len2 < 1e-24) return IntArray(0)
    val n = x.size
    val keys = sortedSetOf<Long>()
    for (o in 0 until n) {
        if (o == a || o == c) continue
        val ox = x[o] - ax; val oy = y[o] - ay
        val u = (ox * dx + oy * dy) / len2
        if (u < 0.1 || u > 0.9) continue
        val px = ox - u * dx; val py = oy - u * dy
        if (px * px + py * py < len2 / 16.0) keys.add(o.toLong() * (n + 1))
    }
    for (j in la.indices) {
        if (j == k) continue
        val u = la[j]; val v = lb[j]
        if (u == a || u == c || v == a || v == c) continue
        val o1 = orient(x, y, a, c, u); val o2 = orient(x, y, a, c, v); val o3 = orient(x, y, u, v, a); val o4 = orient(x, y, u, v, c)
        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0)))
            keys.add(minOf(u, v).toLong() * (n + 1) + maxOf(u, v) + 1)
    }
    val out = IntArray(2 * keys.size); var s = 0
    for (key in keys) { out[s++] = (key / (n + 1)).toInt(); out[s++] = (key % (n + 1)).toInt() - 1 }
    return out
}

private fun orient(x: FloatArray, y: FloatArray, p: Int, q: Int, r: Int): Double =
    (x[q] - x[p]).toDouble() * (y[r] - y[p]).toDouble() - (y[q] - y[p]).toDouble() * (x[r] - x[p]).toDouble()
