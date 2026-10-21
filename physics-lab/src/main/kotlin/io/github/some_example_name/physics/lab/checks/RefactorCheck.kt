package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.lab.inspection.Probe
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.session.DemoSession
import io.github.some_example_name.physics.lab.session.DemoSettings
import io.github.some_example_name.physics.lab.session.replayBoot
import io.github.some_example_name.physics.lab.session.replayRun
import java.io.File
import java.lang.reflect.Modifier
import java.util.Locale

/** Дополнительный эталон скрытого состояния. Старый формат PlayerLog не меняется.
 * Снимки снимаются за пределами замеряемого тика. Рефлексия здесь намеренна: проверка
 * читает и приватные буферы контактов, не добавляя диагностический обход в решатель.
 */
private val stateFields = ("n px py prevX prevY vx vy invMass restInvMass matchWeight " +
    "radius isFree probe probeAngle cellDead deadCount conCount conA conB conRest conMuscle " +
    "bendCount bendA bendB bendRest bendLevel boundCount boundOpp boundA boundB " +
    "organismOf organismCount organismSize organismMass flowVX flowVY " +
    "lnkA lnkB lnkCount triA triB triC triRestArea2 triCount lnkEdge triId conEdge conOfEdge " +
    "triMuscle rigidBones boneOf boneRestQx boneRestQy muscleOf muscleActivation muscleTarget " +
    "muscleScaleOf muscleActivationPrev conHold triHold linkTorn conDead tearsPending " +
    "tearQueue tearQueueN tornTotal sweepBackwards trapTicks trapOrg foldTicks " +
    "contactsOn bonesRigid tearingOn muscleContraction muscleRateContract muscleRateRelax " +
    "dragId mouseX mouseY gait gaitFrame").split(' ')

private fun digest(value: Any?): Long {
    var hash = 1125899906842597L
    fun mix(v: Long) { hash = (hash xor v) * -7046029254386353131L }
    fun visit(v: Any?) {
        when (v) {
            null -> mix(-1)
            is Double -> mix(v.toRawBits())
            is Float -> mix(v.toRawBits().toLong())
            is Number -> mix(v.toLong())
            is Boolean -> mix(if (v) 1 else 0)
            is DoubleArray -> { mix(v.size.toLong()); for (x in v) mix(x.toRawBits()) }
            is FloatArray -> { mix(v.size.toLong()); for (x in v) mix(x.toRawBits().toLong()) }
            is IntArray -> { mix(v.size.toLong()); for (x in v) mix(x.toLong()) }
            is LongArray -> { mix(v.size.toLong()); for (x in v) mix(x) }
            is ByteArray -> { mix(v.size.toLong()); for (x in v) mix(x.toLong()) }
            is BooleanArray -> { mix(v.size.toLong()); for (x in v) mix(if (x) 1 else 0) }
            is Array<*> -> { mix(v.size.toLong()); for (x in v) visit(x) }
            else -> error("Unsupported checkpoint value: ${v.javaClass.name}")
        }
    }
    visit(value)
    return hash
}

internal fun snapshots(): Map<String, Long> {
    val result = sortedMapOf<String, Long>()
    for (name in stateFields) result[name] = digest(Probe.get<Any?>(name))
    // Включая продолжение контакта и исходную/живую топологию. Время и счётчики
    // профилирования не определяют последующие тики и зависят от машины.
    fun nested(prefix: String, value: Any?, depth: Int) {
        if (value == null || depth > 3) return
        for (f in value.javaClass.declaredFields.sortedBy { it.name }) {
            // option* — неизменяемые параметры экземпляра, до выделения ядра они
            // были static и не входили в эталон. Их изоляцию/журнал проверяет DemoSettingsTest.
            if (Modifier.isStatic(f.modifiers) || f.name.startsWith("dbg") ||
                f.name.startsWith("option") || f.name.contains("Ns") || f.name.startsWith("time") || f.name.startsWith("profile")) continue
            f.isAccessible = true
            val v = f.get(value) ?: continue
            val key = "$prefix.${f.name}"
            if (key == "contacts.lam") {
                // Сравниваем логические множители, а не ёмкость/пустые слоты новой
                // таблицы. Этот же код загружается со старым HashMap для честного A/B.
                result[key] = digest(contactLambdaEntries(v))
            } else if (v is Number || v is Boolean || v.javaClass.isArray) {
                if (v is Array<*> && v.isNotEmpty() && v[0] !is DoubleArray && v[0] !is IntArray) continue
                result[key] = digest(v)
            } else if (v.javaClass.packageName.startsWith("io.github.some_example_name")) nested(key, v, depth + 1)
        }
    }
    // Здесь также история инверсий по постоянным ID и число живых треугольников:
    // одинаковые координаты при разном возрасте складки ещё не означают одинаковое будущее.
    nested("baked", Probe.get<Any>("baked"), 0)
    nested("contacts", Probe.get<Any?>("contacts"), 0)
    // Порядок контура влияет на следующие поправки. Проверяем и обратный индекс,
    // а не только координаты частиц; доступ через reflection остаётся в стенде.
    val limits = Probe.demo.state.javaClass.getDeclaredField("boundaryLinkLimits")
        .apply { isAccessible = true }.get(Probe.demo.state)
    nested("boundaryLinkLimits", limits, 0)
    val blocks = Probe.demo.state.javaClass.getDeclaredField("constraintBlocks")
        .apply { isAccessible = true }.get(Probe.demo.state)
    nested("constraintBlocks", blocks, 0)
    nested("localBoundary", Probe.demo.state.localBoundary, 0)
    return result
}

private fun contactLambdaEntries(value: Any): LongArray {
    val entries = sortedMapOf<Long, Long>()
    if (value is Map<*, *>) {
        for ((key, lambda) in value) entries[(key as Number).toLong()] = (lambda as Double).toRawBits()
    } else {
        fun field(name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
        val keys = field("keys") as LongArray
        val values = field("values") as DoubleArray
        val used = field("used") as BooleanArray
        for (i in keys.indices) if (used[i]) entries[keys[i]] = values[i].toRawBits()
    }
    val result = LongArray(entries.size * 2)
    var i = 0
    for ((key, lambda) in entries) { result[i++] = key; result[i++] = lambda }
    return result
}

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    require(args.size >= 3) { "refactorCheck <player-log> <baseline-file> <record|check> [perf-ticks]" }
    val log = PlayerLog.parse(File(args[0]))
    val header = log.header
    val body = File(header.getValue("body"))
    check(PlayerLog.crc(body) == header["bodyCrc"]) { "Body CRC mismatch" }
    val reference = File(args[1])
    val record = args[2] == "record"
    val perfOnly = args[2] == "perf"
    check(!record || !reference.exists()) { "Refusing to overwrite the baseline" }
    val expected = if (record || perfOnly) emptyMap() else reference.readLines().associate {
        val p = it.split(' '); p[0] to p[1]
    }
    val actual = linkedMapOf<String, String>()
    fun boot(): DemoSession = DemoSession(body.path,
        copies = header.getValue("copies").toInt(), freeParticles = header.getValue("free").toInt(),
        labKillFraction = header.getValue("killFraction").toDouble(),
        labKillSeed = header.getValue("killSeed").toLong(), physicsConfig = DemoSettings.forReplay(header)).also {
        it.state.quiet = true; it.replayBoot(); Probe.attach(it)
    }
    if (!perfOnly) {
    val demo = boot()
    demo.replayRun(log.lines, {}, {
        if (demo.currentTick % 30 == 0 || demo.currentTick == log.lines.last().tick) {
            for ((field, hash) in snapshots()) {
                val key = "${demo.currentTick}:$field"
                val hex = java.lang.Long.toHexString(hash)
                if (!record) check(expected[key] == hex) { "Hidden state mismatch $key: ${expected[key]} != $hex" }
                actual[key] = hex
            }
        }
    })
    check(demo.replayHashBad == 0 && demo.replayHashOk == log.lines.count { it.op == "H" })
    if (record) reference.writeText(actual.entries.joinToString("\n", postfix = "\n") { "${it.key} ${it.value}" })
    else check(expected.keys == actual.keys) { "Checkpoint field set changed" }
    println("контрольные суммы: все ${demo.replayHashOk} сошлись; скрытое состояние: ${actual.size} полей/снимков")
    }
    val ticks = args.getOrNull(3)?.toInt() ?: 1200
    val short = log.lines.filter { it.tick < ticks || it.tick == ticks && it.op == "H" } +
        PlayerLog.Line(ticks, "END", emptyList(), "$ticks END")
    if (perfOnly) boot().replayRun(short, {}, {})
    repeat(3) { pass ->
        val timed = boot()
        var ns = 0L
        var count = 0
        timed.replayRun(short, {}, { ns += timed.dbgTickNs; count++ })
        check(timed.replayHashBad == 0 && count == ticks)
        println("PERF pass=${pass + 1} ticks=$count mean_ms=%.6f".format(ns / 1e6 / count))
    }
}
