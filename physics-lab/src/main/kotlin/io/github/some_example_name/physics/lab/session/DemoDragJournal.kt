package io.github.some_example_name.physics.lab.session

import io.github.some_example_name.physics.DRAG_ACCEL
import io.github.some_example_name.physics.DRAG_SPEED_LIMIT
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.SUBSTEPS
import kotlin.math.abs
import kotlin.math.sqrt

internal fun DemoSession.toggleDragLog() { with(state) {
    val log = dragLog
    if (log == null) {
        // Сброс уже сделан снаружи через act('R'), чтобы попасть в журнал игрока.
        logTick = 0
        logLastId = -2
        val sb = StringBuilder()
        sb.append("# GENOMEIA DRAG LOG v1\n")
        sb.append("# body=").append(bodyPath).append('\n')
        sb.append("# ups=").append(Math.round(1.0 / DT))
            .append(" substeps=").append(SUBSTEPS)
            .append(" organisms=").append(ORGANISMS)
            .append(" free=").append(FREE_PARTICLES).append('\n')
        logMuscles = ""
        sb.append("# dragAccel=").append(DRAG_ACCEL)
            .append(" dragSpeedLimit=").append(DRAG_SPEED_LIMIT)
            .append(" bones=").append(if (bonesRigid) 1 else 0)
            .append(" contacts=").append(if (contactsOn) 1 else 0)
            .append(" bend=").append(bendLevel)
            .append(" gait=").append(if (gait) 1 else 0).append('\n')
        dragLog = sb
        println("[DemoSession] ЗАПИСЬ НАЧАТА, тело сброшено. Нажми L снова, чтобы остановить.")
    } else {
        log.append("E ").append(logTick).append('\n')
        val out = java.io.File("drag-log.txt")
        out.writeText(log.toString())
        dragLog = null
        println("[DemoSession] ЗАПИСЬ ОСТАНОВЛЕНА, строк " + log.count { it == '\n' } +
            ", файл " + out.absolutePath)
        println("----- НАЧАЛО ЛОГА -----")
        print(log)
        println("----- КОНЕЦ ЛОГА -----")
    }
} }

internal fun DemoSession.recordMuscles() { with(state) {
    val log = dragLog ?: return
    val sb = StringBuilder()
    for (m in muscleTarget.indices) {
        if (muscleTarget[m] == 0.0) continue
        if (sb.isNotEmpty()) sb.append(',')
        sb.append(m).append(':').append(muscleTarget[m])
    }
    val now = sb.toString()
    if (now == logMuscles) return
    logMuscles = now
    log.append("M ").append(logTick).append(' ').append(if (now.isEmpty()) "-" else now).append('\n')
} }

internal fun DemoSession.recordDrag() { with(state) {
    val log = dragLog ?: return
    val id = dragId
    if (id != logLastId) {
        if (id < 0) log.append("U ").append(logTick).append('\n')
        else log.append("D ").append(logTick).append(' ').append(id)
            .append(' ').append(mouseX).append(' ').append(mouseY).append('\n')
        logLastId = id; logLastX = mouseX; logLastY = mouseY
        return
    }
    if (id < 0) return
    // Порог в сотую долю связи: дрожание руки в лог не пишем, оно и в игре
    // ничего не решает, а строк добавило бы сотни.
    val eps = body.meanLinkLength * 0.01
    if (abs(mouseX - logLastX) > eps || abs(mouseY - logLastY) > eps) {
        log.append("D ").append(logTick).append(' ').append(id)
            .append(' ').append(mouseX).append(' ').append(mouseY).append('\n')
        logLastX = mouseX; logLastY = mouseY
    }
} }
