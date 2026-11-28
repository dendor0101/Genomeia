package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.lab.inspection.Probe
import io.github.some_example_name.physics.lab.session.replayStateHash
import io.github.some_example_name.physics.lab.scenes.slamOrganisms
import java.lang.management.ManagementFactory
import java.security.MessageDigest

/** Протокол для последовательного A/B в отдельных JVM. Ожидание команды происходит
 * вне симуляции: оба процесса могут жить одновременно, но физику считает только
 * один. Так две версии не делят JIT-профили, загрузчики классов и одну кучу JVM.
 * Контроллер сохраняет исходные параметры, чередует RUN и сравнивает каждый хэш.
 */
internal object IsolatedBenchWorker {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size >= 3) { "<scale|replay> <body|log> <copies|ticks> [rest|gait|slam] [slam-tick] [jit-body]" }
        println("PHYSICS_RUNTIME ${PairedReplayWorker.physicsLocation()}")
        if (args[0] == "replay") {
            replay(args[1], args[2].toInt())
            return
        }
        require(args[0] == "scale")
        val p = Probe
        p.boot(args.getOrNull(5) ?: args[1], 2, 0)
        p.demo.state.quiet = true
        p.resetState()
        val dt = p.const("DT"); val substeps = p.constInt("SUBSTEPS")
        repeat(200) { p.frameGait(dt, it) }
        p.boot(args[1], args[2].toInt(), 0)
        p.demo.state.quiet = true
        p.resetState()
        val mode = args.getOrNull(3) ?: "rest"
        require(mode in arrayOf("rest", "gait", "slam"))
        val slamTick = args.getOrNull(4)?.toInt() ?: 64
        val cpu = ManagementFactory.getThreadMXBean()
        check(cpu.isCurrentThreadCpuTimeSupported)
        cpu.isThreadCpuTimeEnabled = true
        val snapshot = Class.forName("io.github.some_example_name.physics.lab.checks.RefactorCheckKt")
            .getDeclaredMethod("snapshots").apply { isAccessible = true }
        var tick = 0
        println("ISOLATED_READY scale n=${p.n}")
        System.out.flush()
        while (true) {
            val command = readlnOrNull() ?: break
            if (command == "QUIT") break
            val count = command.removePrefix("RUN ").toInt()
            require(count > 0)
            val hashes = LongArray(count)
            var ns = 0L
            val start = tick
            val cpuStart = cpu.currentThreadCpuTime
            repeat(count) { k ->
                if (mode == "slam" && tick == slamTick) p.demo.slamOrganisms()
                val t0 = System.nanoTime()
                if (mode == "gait") p.frameGait(dt, tick) else p.frame(dt, substeps, contract = false)
                ns += System.nanoTime() - t0
                hashes[k] = p.demo.replayStateHash()
                tick++
            }
            val used = cpu.currentThreadCpuTime - cpuStart
            // Полный набор прежних скрытых полей, вне обоих таймеров.
            val fields = snapshot.invoke(null) as Map<*, *>
            val digest = MessageDigest.getInstance("SHA-256")
            for ((name, value) in fields.entries.sortedBy { it.key.toString() }) {
                digest.update("$name=$value\n".toByteArray(Charsets.UTF_8))
            }
            val hidden = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            result(start, count, ns, used, hidden, hashes)
        }
    }

    private fun replay(path: String, ticks: Int) {
        println("ISOLATED_READY replay")
        System.out.flush()
        while (true) {
            val command = readlnOrNull() ?: break
            if (command == "QUIT") break
            require(command == "RUN 1")
            val sample = PairedReplayWorker.run(path, ticks)
            result(0, sample[0].toInt(), sample[1], sample[2], "checked-separately", sample.copyOfRange(3, sample.size))
        }
    }

    private fun result(start: Int, count: Int, ns: Long, cpuNs: Long, hidden: String, hashes: LongArray) {
        println("ISOLATED_RESULT\t$start\t$count\t$ns\t$cpuNs\t$hidden\t" +
            hashes.joinToString(",") { java.lang.Long.toHexString(it) })
        System.out.flush()
    }
}
