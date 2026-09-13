package io.github.some_example_name.lwjgl3.demo

import java.io.File
import kotlin.math.sqrt

/**
 * БЕЗОКОННЫЙ ЗАМЕР ПО ТОЙ ЖЕ РЕШЁТКЕ, ЧТО В SoftGridLab.
 *
 * ЧЕМ ЭТОТ ЗАМЕР ОТЛИЧАЕТСЯ ОТ ВСЕХ ПРЕЖНИХ, и почему на него можно опереться.
 * Тело стартует в ПОЛНОМ ПОКОЕ и внешних сил не имеет: гравитации нет, второго тела
 * нет, мышц нет, гребка нет, руки нет. Значит суммарный импульс обязан остаться
 * нулевым НАВСЕГДА. Любое ненулевое число здесь — это тяга, созданная из ничего, и
 * спорить с ним нельзя: не с чем перепутать.
 *
 * Прежние стенды такой опоры не давали. Там тело стартовало живым, с уже набранным
 * импульсом, и отличить «создано» от «катится по инерции» удавалось только по росту.
 *
 *   gradlew :lwjgl3:softGridBench
 *   gradlew :lwjgl3:softGridBench -Pseed=58983
 */
fun main(args: Array<String>) {
    val seed = args.getOrNull(0)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 58983L
    val killFraction = args.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: 0.30
    val file = File(System.getProperty("java.io.tmpdir"), "soft-grid-bench.txt")
    writeGrid(file)

    val P = Probe
    P.boot(file.path, copies = 1, freeParticles = 0,
        killFraction = killFraction, killSeed = seed)
    val dt = P.const("DT")
    val sub = P.constInt("SUBSTEPS")
    val meanLink = P.body.meanLinkLength.toDouble()

    println("=== мягкая решётка ${GRID}x$GRID, сид $seed ===")

    /** Импульс, скорость и снос всего тела. В покое всё это обязано быть нулём. */
    fun momentum(): DoubleArray {
        var pxs = 0.0; var pys = 0.0; var vmax = 0.0; var m0 = 0.0
        var cx = 0.0; var cy = 0.0
        for (i in 0 until P.n) {
            if (P.invMass[i] <= 0.0) continue
            val m = 1.0 / P.invMass[i]
            pxs += m * P.vx[i]; pys += m * P.vy[i]; m0 += m
            cx += m * P.px[i]; cy += m * P.py[i]
            val s = sqrt(P.vx[i] * P.vx[i] + P.vy[i] * P.vy[i]) * dt / meanLink
            if (s > vmax) vmax = s
        }
        return doubleArrayOf(sqrt(pxs * pxs + pys * pys), vmax, cx / m0, cy / m0)
    }

    fun run(label: String, contacts: Boolean, drag: Boolean, stages: Boolean) {
        P.resetState()
        P.applyLabDamage()
        for (i in 0 until P.n) { P.vx[i] = 0.0; P.vy[i] = 0.0; P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i] }
        P.resetFlow(); P.resetCounters()
        P.setContacts(contacts); P.setDragOff(!drag)
        if (stages) {
            P.setStageProbe(true)
            java.util.Arrays.fill(P.stageL(), 0.0)
            java.util.Arrays.fill(P.stagePx(), 0.0)
            java.util.Arrays.fill(P.stagePy(), 0.0)
        }
        val a0 = momentum()
        val marks = intArrayOf(50, 150, 300, 600, 1200)
        val trail = StringBuilder()
        P.contactsObj()?.let { it.dbgCcdTotal = 0; it.dbgCcdWorst = 0.0 }
        var firstCcd = -1
        for (fr in 1..1200) {
            P.frame(dt, sub, contract = false)
            if (firstCcd < 0 && (P.contactsObj()?.dbgCcdTotal ?: 0L) > 0L) firstCcd = fr
            if (marks.contains(fr)) trail.append(" t").append(fr).append("=")
                .append(String.format("%.4f", momentum()[1]))
                .append("/ccd").append(P.contactsObj()?.dbgCcdTotal ?: 0L)
        }
        P.contactsObj()?.let {
            println("      CCDDBG first=%d total=%d worst=%.3f pair=%d-%d moveA=%.4f moveJ=%.4f meanLink=%.4f"
                .format(firstCcd, it.dbgCcdTotal, it.dbgCcdWorst, it.dbgCcdI, it.dbgCcdJ,
                    it.dbgCcdMoveA, it.dbgCcdMoveJ, meanLink))
        }
        val a1 = momentum()
        val moved = sqrt((a1[2] - a0[2]) * (a1[2] - a0[2]) + (a1[3] - a0[3]) * (a1[3] - a0[3]))
        // ПРОНИКНОВЕНИЕ ПЕЧАТАЕТСЯ РЯДОМ СО СКОРОСТЬЮ, и это не украшение.
        // Неподвижности легко добиться, ослабив контакт до бездействия: тело тогда
        // стоит смирно и складывается само в себя. Так у меня и вышло однажды —
        // покой вышел нулевым, а тела начали проходить насквозь на 9 связей. Пока
        // оба числа видны рядом, подмена цели видна сразу.
        val pen = P.contactsObj()?.maxPenetration(P.px, P.py) ?: 0.0
        val att = P.contactsObj()?.attractN ?: 0
        val ccd = P.toiClamps(); val cap = P.speedCapHits()
        println("  ROW %-24s | P %10.5f | V %8.4f | S %8.3f | PEN %6.3f | ATT %d | CCD %d | CAP %d |%s"
            .format(label, a1[0], a1[1], moved / meanLink, pen, att, ccd, cap, trail))
        if (stages) {
            P.setStageProbe(false)
            val names = arrayOf("svyazi", "ploshadi", "izgib", "kost", "predel_dliny", "kontakty", "prepare_ccd", "otskok_v", "sreda_v")
            val lp = P.stageL(); val sx = P.stagePx(); val sy = P.stagePy()
            for (k in names.indices) println("      STAGE%d %-13s | dP %12.5f | dL %12.5f"
                .format(k, names[k], sqrt(sx[k] * sx[k] + sy[k] * sy[k]), lp[k]))
        }
        P.setContacts(true); P.setDragOff(false)
    }

    println("  тело в покое, 20 секунд, внешних сил нет — |P| обязан остаться нулём")
    run("all", true, true, true)
    run("no_contacts", false, true, false)
    run("no_medium", true, false, true)
    run("no_both", false, false, false)
}
