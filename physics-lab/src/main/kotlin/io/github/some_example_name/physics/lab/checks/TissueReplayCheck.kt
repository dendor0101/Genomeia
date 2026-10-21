package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.lab.inspection.Probe
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.DemoSettings
import io.github.some_example_name.physics.lab.session.replayBoot
import io.github.some_example_name.physics.lab.session.replayRun
import io.github.some_example_name.physics.lab.session.replayStateHash
import java.io.File

/** Полный перебор частиц против чужой живой ткани — только проверка, не горячий
 * путь решателя. Старые H не переписывает: отдельно показывает их совпадение
 * и отдельно проверяет детерминизм новой версии после изменения физики.
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "tissueReplayCheck <player-log>" }
    val log = PlayerLog.parse(File(args[0]))
    val h = log.header
    val body = File(h.getValue("body"))
    check(PlayerLog.crc(body) == h["bodyCrc"]) { "Body CRC mismatch" }
    val poses = LongArray(log.lines.last().tick)
    val hidden = linkedMapOf<String, Long>()
    var checkedFields = 0
    var penetrationTicks = 0
    var firstPenetration = -1
    var lastPenetration = -1
    var maximumInside = 0
    repeat(2) { pass ->
        val demo = DemoSession(body.path, copies = h.getValue("copies").toInt(),
            freeParticles = h.getValue("free").toInt(),
            labKillFraction = h.getValue("killFraction").toDouble(),
            labKillSeed = h.getValue("killSeed").toLong(), physicsConfig = DemoSettings.forReplay(h))
        demo.state.quiet = true
        demo.replayBoot(); Probe.attach(demo)
        val connectivity = ConnectivityAudit(demo.state)
        var tick = 0
        val seen = hashSetOf<String>()
        demo.replayRun(log.lines, {}, {
            val s = demo.state
            for (i in 0 until s.n) check(s.px[i].isFinite() && s.py[i].isFinite() &&
                s.vx[i].isFinite() && s.vy[i].isFinite()) { "Non-finite state tick=${tick + 1} particle=$i" }
            if (pass == 0) {
                connectivity.verify(tick + 1)
                val inside = foreignTissueCount(s.n, s.px, s.py, s.organismOf, s.cellDead,
                    s.triA, s.triB, s.triC, s.triCount)
                if (inside > 0) {
                    if (firstPenetration < 0) firstPenetration = tick + 1
                    lastPenetration = tick + 1
                    penetrationTicks++; maximumInside = maxOf(maximumInside, inside)
                }
            }
            val hash = demo.replayStateHash()
            if (pass == 0) poses[tick] = hash
            else check(poses[tick] == hash) { "Pose mismatch tick=${tick + 1}" }
            tick++
            if (tick % 30 == 0 || tick == poses.size) {
                for ((name, value) in snapshots()) {
                    val key = "$tick:$name"
                    seen.add(key)
                    if (pass == 0) hidden[key] = value
                    else {
                        check(hidden[key] == value) { "Hidden state mismatch $key" }
                        checkedFields++
                    }
                }
            }
            if (tick % 3000 == 0) println("PROGRESS pass=$pass tick=$tick")
        })
        check(tick == poses.size)
        if (pass == 1) check(seen == hidden.keys)
        println("ORIGINAL_SUMS pass=$pass ok=${demo.replayHashOk} bad=${demo.replayHashBad}")
    }
    println("Новый повтор: контрольные суммы: все ${poses.size} сошлись; скрытых полей $checkedFields")
    println("TISSUE_AUDIT ticks=$penetrationTicks maximum=$maximumInside first=$firstPenetration last=$lastPenetration")
    println("CONNECTIVITY_AUDIT ticks=${poses.size} passed")
    check(penetrationTicks == 0) { "Foreign tissue audit failed: $penetrationTicks ticks" }
}
