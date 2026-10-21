package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.SUBSTEPS
import io.github.some_example_name.physics.lab.inspection.Probe
import io.github.some_example_name.physics.lab.io.PlayerLog
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URLClassLoader
import java.util.Locale

/** Старый и новый решатели в одном JVM: чередование порядка уменьшает влияние
 * меняющейся частоты/фоновой нагрузки. Загрузчики изолируют их статические поля.
 * Reflection используется один раз на целый тик, вне внутренних циклов физики.
 * Сравниваются также биты позиций/скоростей после КАЖДОГО тика между версиями.
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val baseline = File(args.getOrElse(0) { "" }).absoluteFile
    require(baseline.exists() && args.firstOrNull()?.isNotEmpty() == true) {
        "Укажите -Pbaseline: сохранённый classpath.txt или каталог исходных Kotlin classes"
    }
    val body = File(args.getOrElse(1) { "body-export.txt" }).absolutePath
    val copies = args.getOrNull(2)?.toIntOrNull() ?: 108
    val warm = args.getOrNull(3)?.toIntOrNull() ?: 32
    val ticks = args.getOrNull(4)?.toIntOrNull() ?: 48
    val order = args.getOrNull(5) ?: "old-first"
    require(order == "old-first" || order == "new-first")
    val newFirst = order == "new-first"
    val mode = args.getOrNull(6) ?: "rest"
    require(mode == "rest" || mode == "gait")
    require(warm > 0 && ticks > 0)
    val current = System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
    // Снимок полного classpath сохраняет и прежний physics.jar: нельзя случайно
    // сравнить старую оболочку с новым ядром после переноса классов между модулями.
    val previous = if (baseline.isFile) {
        baseline.readText().trim().split(File.pathSeparator).map(::File)
    } else {
        val currentTools = File(Probe::class.java.protectionDomain.codeSource.location.toURI())
        check(current.any { it.canonicalFile == currentTools.canonicalFile })
        current.map { if (it.canonicalFile == currentTools.canonicalFile) baseline else it }
    }
    val cpu = ManagementFactory.getThreadMXBean()
    check(cpu.isCurrentThreadCpuTimeSupported) { "JVM не поддерживает время CPU текущего потока" }
    cpu.isThreadCpuTimeEnabled = true

    class Variant(val label: String, val loader: URLClassLoader) {
        val type = try {
            loader.loadClass(Probe::class.java.name)
        } catch (_: ClassNotFoundException) {
            loader.loadClass("io.github.some_example_name.lwjgl3.demo.Probe")
        }
        val probe = type.getField("INSTANCE").get(null)
        val boot = type.getMethod("boot", String::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Double::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        val reset = type.getMethod("resetState")
        val frame = type.getMethod("frame", Double::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        val gait = type.getMethod("frameGait", Double::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        // Та же проверка скрытого состояния, что у повтора игрока. Вызывается вне
        // замеряемого тика; не добавляет обходов или диагностических полей в физику.
        val snapshotType = try {
            loader.loadClass("io.github.some_example_name.physics.lab.checks.RefactorCheckKt")
        } catch (_: ClassNotFoundException) {
            loader.loadClass("io.github.some_example_name.lwjgl3.demo.RefactorCheckKt")
        }
        val snapshot = snapshotType.getDeclaredMethod("snapshots").apply { isAccessible = true }
        val dt = type.getMethod("const", String::class.java).invoke(probe, "DT") as Double
        val substeps = type.getMethod("constInt", String::class.java).invoke(probe, "SUBSTEPS") as Int
        lateinit var x: DoubleArray; lateinit var y: DoubleArray
        lateinit var vx: DoubleArray; lateinit var vy: DoubleArray
        val wall = DoubleArray(ticks)
        val processor = DoubleArray(ticks)

        fun load(count: Int) {
            boot.invoke(probe, body, count, 0, 0.0, 0L)
            reset.invoke(probe)
            x = type.getMethod("getPx").invoke(probe) as DoubleArray
            y = type.getMethod("getPy").invoke(probe) as DoubleArray
            vx = type.getMethod("getVx").invoke(probe) as DoubleArray
            vy = type.getMethod("getVy").invoke(probe) as DoubleArray
        }
        fun step(tick: Int) {
            val p0 = cpu.currentThreadCpuTime
            val t0 = System.nanoTime()
            if (mode == "gait") gait.invoke(probe, dt, tick)
            else frame.invoke(probe, dt, substeps, false, true)
            val elapsed = System.nanoTime() - t0
            val used = cpu.currentThreadCpuTime - p0
            if (tick >= warm) {
                wall[tick - warm] = elapsed / 1e6
                processor[tick - warm] = used / 1e6
            }
        }
        fun hash() = PlayerLog.stateHash(x.size, x, y, vx, vy)
        fun hidden() = snapshot.invoke(null) as Map<*, *>
        fun report() {
            println("PAIRED $label n=${x.size} wall_ms=%.4f cpu_ms=%.4f".format(wall.average(), processor.average()))
        }
    }

    URLClassLoader(previous.map { it.toURI().toURL() }.toTypedArray(), null).use { oldLoader ->
        URLClassLoader(current.map { it.toURI().toURL() }.toTypedArray(), null).use { newLoader ->
            // Чередовать только тики недостаточно: порядок загрузки/прогрева тоже
            // влияет на JIT и размещение массивов. Сравнивать нужно оба порядка
            // отдельными запусками JVM, а не приписывать весь разброс рефакторингу.
            val first = Variant(if (newFirst) "new" else "old", if (newFirst) newLoader else oldLoader)
            val second = Variant(if (newFirst) "old" else "new", if (newFirst) oldLoader else newLoader)
            val old = if (newFirst) second else first
            val fresh = if (newFirst) first else second
            val oldSource = old.type.protectionDomain.codeSource.location
            val newSource = fresh.type.protectionDomain.codeSource.location
            check(oldSource != newSource) { "Обе версии загрузились из $oldSource" }
            println("PAIRED order=$order mode=$mode old=$oldSource new=$newSource")
            check(old.dt == fresh.dt && old.substeps == fresh.substeps)
            first.load(2); second.load(2)
            repeat(200) { tick ->
                first.gait.invoke(first.probe, first.dt, tick)
                second.gait.invoke(second.probe, second.dt, tick)
            }
            first.load(copies); second.load(copies)
            check(old.hash() == fresh.hash()) { "Разное исходное состояние" }
            check(old.hidden() == fresh.hidden()) { "Разное исходное скрытое состояние" }
            repeat(warm + ticks) { tick ->
                if (tick % 2 == 0) { first.step(tick); second.step(tick) }
                else { second.step(tick); first.step(tick) }
                check(old.hash() == fresh.hash()) { "Версии разошлись на тике ${tick + 1}" }
                if ((tick + 1) % 16 == 0 || tick + 1 == warm + ticks) {
                    val previousState = old.hidden()
                    val currentState = fresh.hidden()
                    check(previousState == currentState) {
                        "Скрытое состояние разошлось на тике ${tick + 1}: " +
                            previousState.keys.filter { previousState[it] != currentState[it] }
                    }
                }
                if ((tick + 1) % 16 == 0) println("PAIRED progress=${tick + 1}/${warm + ticks}")
            }
            old.report(); fresh.report()
            println("PAIRED контрольные суммы между версиями: все ${warm + ticks} сошлись")
        }
    }
}
