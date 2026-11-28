package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.BodySimulation
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.DemoSettings
import io.github.some_example_name.physics.lab.session.replayBoot
import io.github.some_example_name.physics.lab.session.replayRun
import java.io.File
import java.lang.management.ManagementFactory

/** Маленький адаптер загружается с ОБЕИМИ версиями библиотеки. Ничего не меняет
 * в ядре: одинаково применяет записанные команды и собирает хэши каждого тика.
 * Своя копия адаптера нужна старому classpath, где этого нового стенда ещё нет.
 */
internal object PairedReplayWorker {
    fun physicsLocation(): String = BodySimulation::class.java.protectionDomain.codeSource.location.toString()

    fun run(path: String, requestedTicks: Int): LongArray {
        val log = PlayerLog.parse(File(path))
        val header = log.header
        val body = File(header.getValue("body"))
        check(PlayerLog.crc(body) == header["bodyCrc"]) { "Body CRC mismatch" }
        val ticks = if (requestedTicks <= 0) log.lines.last().tick else minOf(requestedTicks, log.lines.last().tick)
        val lines = log.lines.filter { it.tick < ticks || it.tick == ticks && it.op == "H" } +
            PlayerLog.Line(ticks, "END", emptyList(), "$ticks END")
        val demo = DemoSession(body.path,
            copies = header.getValue("copies").toInt(), freeParticles = header.getValue("free").toInt(),
            labKillFraction = header.getValue("killFraction").toDouble(),
            labKillSeed = header.getValue("killSeed").toLong(), physicsConfig = DemoSettings.forReplay(header))
        demo.state.quiet = true
        demo.replayBoot()
        // Первые три значения — число тиков, время физики, CPU всего повтора.
        // Остальное — хэши. Сбор хэшей и загрузка не входят в dbgTickNs.
        val result = LongArray(ticks + 3)
        val cpu = ManagementFactory.getThreadMXBean()
        check(cpu.isCurrentThreadCpuTimeSupported)
        cpu.isThreadCpuTimeEnabled = true
        val cpuStart = cpu.currentThreadCpuTime
        var count = 0
        demo.replayRun(lines, {}, {
            check(count < ticks)
            result[1] += demo.dbgTickNs
            val s = demo.state
            result[3 + count] = PlayerLog.stateHash(s.n, s.px, s.py, s.vx, s.vy)
            count++
        })
        result[0] = count.toLong()
        result[2] = cpu.currentThreadCpuTime - cpuStart
        check(count == ticks)
        check(demo.replayHashBad == 0 && demo.replayHashOk == lines.count { it.op == "H" }) {
            "Исходная запись не совпала: ${demo.replayHashOk} совпало, ${demo.replayHashBad} разошлось"
        }
        return result
    }
}
