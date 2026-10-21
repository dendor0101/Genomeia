package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.comX
import io.github.some_example_name.physics.comY
import io.github.some_example_name.physics.lab.inspection.Probe
import io.github.some_example_name.physics.lab.io.PlayerLog
import java.io.File
import kotlin.math.sqrt

/** Дополнение к PhysicsCheck: контактные проверки не замечают изменение материала.
 * Например, 16×1 прошёл все 31 проверки, но дал 53 инверсии при гребке вместо нуля.
 * Здесь нет подгоняемого порога: показатели сравниваются с исходной сборкой на
 * том же теле. Время тика не измеряем — обход невязки и конечности вне решателя.
 */
fun main(args: Array<String>) {
    require(args.size == 1 || (args.size == 2 && args[1] == "--tearing")) {
        "MaterialQualityCheck <body> [--tearing]"
    }
    val tearing = args.size == 2
    val bodyFile = File(args[0])
    val p = Probe
    p.boot(bodyFile.path, 2, 0)
    val state = p.demo.state
    require(state.conCount > 0) { "Для диагностики материала нужно тело с мягкими связями" }
    val dt = p.const("DT")
    val substeps = p.constInt("SUBSTEPS")
    println("MATERIAL_CONFIG body=${args[0]} bodyCrc=${PlayerLog.crc(bodyFile)} particles=${state.n} dt=$dt " +
        "substeps=$substeps iterations=${p.constInt("SOLVER_ITERS")} ticks=600 warm=120 tearing=$tearing")
    for (mode in arrayOf("rest", "hold", "gait")) {
        p.resetState()
        p.setTearing(tearing)
        val invertedRun = IntArray(state.body.triCount)
        var maxInvertedRun = 0
        val cx = state.comX(); val cy = state.comY()
        var residualSum = 0.0; var peakResidual = 0.0
        var peakInverted = 0; var firstInversion = -1; var firstDeath = -1
        for (tick in 0 until 600) {
            if (mode == "gait") p.frameGait(dt, tick)
            else p.frame(dt, substeps, contract = mode == "hold")
            for (i in 0 until state.n) {
                check(state.px[i].isFinite() && state.py[i].isFinite() &&
                    state.vx[i].isFinite() && state.vy[i].isFinite()) {
                    "Non-finite state: mode=$mode tick=${tick + 1} particle=$i"
                }
            }
            var residual2 = 0.0
            // В конце тика рабочие списки уже уплотнены. Мышечную цель берём
            // действующую, иначе сокращение записалось бы в ошибку материала.
            check(state.conCount > 0) { "No surviving material: mode=$mode tick=${tick + 1}" }
            for (c in 0 until state.conCount) {
                val i = state.conA[c]; val j = state.conB[c]
                val dx = state.px[i] - state.px[j]; val dy = state.py[i] - state.py[j]
                val m = state.conMuscle[c]
                val rest = state.conRest[c] * if (m < 0) 1.0 else state.muscleScaleOf[m]
                val error = sqrt(dx * dx + dy * dy) - rest
                residual2 += error * error
            }
            val residual = sqrt(residual2 / state.conCount) / state.body.meanLinkLength
            check(residual.isFinite()) { "Non-finite residual: mode=$mode tick=${tick + 1}" }
            if (tick >= 120) residualSum += residual
            peakResidual = maxOf(peakResidual, residual)
            val inverted = p.countInverted()
            peakInverted = maxOf(peakInverted, inverted)
            if (inverted > 0 && firstInversion < 0) firstInversion = tick + 1
            if (state.deadCount > 0 && firstDeath < 0) firstDeath = tick + 1
            // Независимая проверка срока по геометрии, а не чтение счётчика движка.
            for (t in 0 until state.triCount) {
                val a = state.triA[t]; val b = state.triB[t]; val c = state.triC[t]
                val id = state.triId[t]
                val area2 = (state.px[b] - state.px[a]) * (state.py[c] - state.py[a]) -
                    (state.py[b] - state.py[a]) * (state.px[c] - state.px[a])
                invertedRun[id] = if (area2 < 0.0) invertedRun[id] + 1 else 0
                maxInvertedRun = maxOf(maxInvertedRun, invertedRun[id])
                if (tearing) check(invertedRun[id] <= 10) {
                    "Persistent live inversion: mode=$mode tick=${tick + 1} triangle=$id age=${invertedRun[id]}"
                }
            }
        }
        val dx = state.comX() - cx; val dy = state.comY() - cy
        println("MATERIAL mode=$mode meanResidual=${residualSum / 480} " +
            "peakResidual=$peakResidual peakInverted=$peakInverted firstInversion=$firstInversion " +
            "dead=${state.deadCount} firstDeath=$firstDeath " +
            "comTravel=${sqrt(dx * dx + dy * dy) / state.body.meanLinkLength} " +
            "maxInvertedRun=$maxInvertedRun removedTriangles=${state.body.triCount - state.triCount} " +
            "tornEdges=${state.tornTotal}")
    }
    println("MATERIAL_DONE: метрики собраны; это не проверка совпадения с другой версией.")
}
