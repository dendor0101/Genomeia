package io.github.some_example_name.lwjgl3.demo

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ВОСПРОИЗВЕДЕНИЕ ЗАПИСАННЫХ ДЕЙСТВИЙ МЫШЬЮ.
 *
 *      gradlew :lwjgl3:dragReplay -Plog=drag-log.txt
 *
 * ЗАЧЕМ. Часть неустойчивости видна только руками: человек тащит конкретный кластер,
 * отпускает, держит — и тело начинает дёргаться. Словами это не передать достаточно
 * точно, чтобы собрать тест: результат зависит от того, ЗА КАКУЮ клетку взялись и по
 * какой траектории вели. Записанный лог снимает разрыв полностью — симуляция
 * детерминирована, поэтому воспроизведение побитово повторяет наблюдавшееся.
 *
 * ЧТО ПЕЧАТАЕТСЯ. Потиковая полоса показателей, но не подряд, а по СОБЫТИЯМ: строка
 * выводится, когда что-то заметно изменилось или сработала аварийная стадия. Иначе
 * тридцать строк в секунду смысла не несут, а нужное в них тонет.
 *
 * ЧТО ИСКАТЬ В ВЫВОДЕ:
 *   пик        — скорость внутри подшага. Выше MAX_SPEED_CELLS_PER_TICK нельзя.
 *   потолок    — сработал clampSpeed. Он правит частицы ПО ОДНОЙ и импульс не
 *                сохраняет, поэтому любое ненулевое число здесь означает движение
 *                из ниоткуда.
 *   |v|, omega — скорость центра масс и вращение ТОГО организма, за который тянут.
 *   контакты   — сколько пар решается. Скачок означает, что тело сложилось в себя.
 *   порвано    — связи, которые обязаны были оборваться.
 */
private class Cmd(val tick: Int, val kind: Char, val id: Int, val x: Double, val y: Double)

fun main(args: Array<String>) {
    val logPath = if (args.isNotEmpty()) args[0] else "drag-log.txt"
    val f = File(logPath)
    require(f.isFile) { "нет файла лога: $logPath" }

    var body = "body-export.txt"
    val cmds = ArrayList<Cmd>()
    val muscleCmds = ArrayList<Pair<Int, String>>()
    var endTick = 0
    for (raw in f.readLines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) {
            val k = line.indexOf("body=")
            if (k >= 0) body = line.substring(k + 5).trim().substringBefore(' ')
            println("  $line")
            continue
        }
        val p = line.split(' ')
        when (p[0]) {
            "D" -> cmds.add(Cmd(p[1].toInt(), 'D', p[2].toInt(), p[3].toDouble(), p[4].toDouble()))
            "U" -> cmds.add(Cmd(p[1].toInt(), 'U', -1, 0.0, 0.0))
            "M" -> muscleCmds.add(Pair(p[1].toInt(), p[2]))
            "E" -> endTick = p[1].toInt()
        }
    }
    if (endTick == 0 && cmds.isNotEmpty()) endTick = cmds.last().tick + 60
    println()

    val P = Probe
    P.boot(body)
    val dt = P.const("DT")
    val sub = P.constInt("SUBSTEPS")
    val speedCap = P.const("MAX_SPEED_CELLS_PER_TICK")
    val topo = Topology.load(body)
    P.resetState()

    println("=== ВОСПРОИЗВЕДЕНИЕ ${cmds.size} команд мыши, ${muscleCmds.size} команд мышц, " +
        "$endTick тиков, тело ${P.n} частиц ===")
    if (muscleCmds.isEmpty()) println("    (в логе нет строк M — мышцы молчали или лог снят старой версией)")
    println()

    var ci = 0
    var mi = 0
    var muscles = DoubleArray(0)
    var heldId = -1
    var heldX = 0.0
    var heldY = 0.0
    var worstGap = 0.0
    var worstGapTick = 0
    var worstCom = 0.0
    var worstComTick = 0
    var lastCom = 0.0
    var prevCaps = 0
    var prevTorn = 0
    var lastPrint = -99
    var worstPeak = 0.0
    var worstOmega = 0.0
    var worstV = 0.0
    var totalCaps = 0

    val ct = P.contactsObj()
    var gapFinal = 0.0
    for (tick in 0..endTick) {
        while (ci < cmds.size && cmds[ci].tick <= tick) {
            val c = cmds[ci]
            if (c.kind == 'D') { P.dragTo(c.id, c.x, c.y); heldId = c.id; heldX = c.x; heldY = c.y }
            else { P.dragRelease(); heldId = -1 }
            ci++
        }
        while (mi < muscleCmds.size && muscleCmds[mi].first <= tick) {
            val spec = muscleCmds[mi].second
            muscles = DoubleArray(P.muscleTarget.size)
            if (spec != "-") for (part in spec.split(',')) {
                val kv = part.split(':')
                val idx = kv[0].toInt()
                if (idx < muscles.size) muscles[idx] = kv[1].toDouble()
            }
            mi++
        }
        if (muscles.isEmpty()) P.frame(dt, sub, contract = false)
        else P.frameWithMuscles(dt, muscles)

        val o = if (heldId >= 0) P.organismOf[heldId] else 0
        val v = P.comSpeed()
        val om = abs(P.angVelOf(o))
        val peak = P.peakSpeedCellsPerTick()
        val caps = P.speedCapHits()
        val torn = P.tornCount()
        val cn = P.liveContacts()
        // РАССТОЯНИЕ ДО КУРСОРА — главный показатель того, работает ли тяга.
        // Скорость может затухнуть, а клетка при этом остаться далеко от курсора:
        // именно так выглядит «тело улетело и не вернулось». Меряется в связях.
        val gap = if (heldId >= 0) {
            val gx = P.px[heldId] - heldX; val gy = P.py[heldId] - heldY
            sqrt(gx * gx + gy * gy) / topo.meanLinkLength
        } else 0.0
        if (gap > worstGap) { worstGap = gap; worstGapTick = tick }

        // РАССТОЯНИЕ ОТ ЦЕНТРА МАСС ОРГАНИЗМА ДО КУРСОРА — это и есть «как далеко
        // улетело ТЕЛО». Схваченная клетка может честно стоять на курсоре, пока
        // остальное тело унесло в сторону: одно измерение другое не заменяет.
        var comGap = 0.0
        if (heldId >= 0) {
            var mm = 0.0; var mx2 = 0.0; var my2 = 0.0
            for (q in 0 until P.n) {
                if (P.organismOf[q] != o || P.invMass[q] <= 0.0) continue
                val w = 1.0 / P.invMass[q]
                mm += w; mx2 += w * P.px[q]; my2 += w * P.py[q]
            }
            if (mm > 0.0) {
                val gx = mx2 / mm - heldX; val gy = my2 / mm - heldY
                comGap = sqrt(gx * gx + gy * gy) / topo.meanLinkLength
            }
        }
        lastCom = comGap
        if (comGap > worstCom) { worstCom = comGap; worstComTick = tick }

        if (peak > worstPeak) worstPeak = peak
        if (om > worstOmega) worstOmega = om
        if (v > worstV) worstV = v
        totalCaps = caps

        // Печатаем по событию, а не каждый тик.
        val newCaps = caps > prevCaps
        val newTorn = torn > prevTorn
        val loud = peak > speedCap * 0.5 || om > 0.5 || v > 2.0 || gap > 2.0
        // Печатаем ещё и регулярно: разрыв до курсора может держаться ровно, не
        // давая всплесков, и по одним событиям его не увидеть.
        val periodic = heldId >= 0 && tick % 30 == 0
        if (newCaps || newTorn || periodic || (loud && tick - lastPrint >= 3)) {
            println(String.format(Locale.ROOT,
                "  тик %4d  клетка %5d  клетка->курсор %7.2f  тело->курсор %7.2f  пик %5.2f  потолок %3d  |v| %7.3f  omega %6.3f  порвано %4d%s",
                tick, heldId, gap, comGap, peak, caps, v, om, torn,
                if (newCaps) "   <-- ПОТОЛОК СКОРОСТИ" else ""))
            lastPrint = tick
        }
        prevCaps = caps; prevTorn = torn
        if (heldId >= 0) gapFinal = gap
    }

    println()
    println(String.format(Locale.ROOT,
        "ИТОГ: худший пик %.2f клеток/тик (потолок %.1f), потолок скорости сработал %d раз,",
        worstPeak, speedCap, totalCaps))
    println(String.format(Locale.ROOT,
        "      наибольшая скорость центра масс %.3f связей/с, наибольшее вращение %.3f рад/с,",
        worstV, worstOmega))
    println(String.format(Locale.ROOT,
        "      порвано связей %d из %d", P.tornCount(), topo.conCount))
    println(String.format(Locale.ROOT,
        "      клетка отставала от курсора до %.2f связи (тик %d), в конце %.2f",
        worstGap, worstGapTick, gapFinal))
    println(String.format(Locale.ROOT,
        "      ЦЕНТР МАСС ТЕЛА уходил от курсора до %.2f связи (тик %d), в конце %.2f",
        worstCom, worstComTick, lastCom))
    println()
    if (totalCaps > 0) {
        println("ПОТОЛОК СКОРОСТИ СРАБАТЫВАЛ. Он правит частицы по одной и импульс не")
        println("сохраняет — значит часть движения взялась из ниоткуда, а не от руки.")
    } else {
        println("Потолок скорости молчал: всё движение пришло от руки, из ниоткуда ничего")
        println("не взялось. Если на глаз выглядит хаосом — вопрос в величине DRAG_ACCEL.")
    }
}
