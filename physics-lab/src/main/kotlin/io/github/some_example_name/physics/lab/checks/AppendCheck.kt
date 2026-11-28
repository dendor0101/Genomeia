package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.BodyState
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.io.SceneFile
import io.github.some_example_name.physics.lab.session.DemoSettings
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * ОРАКУЛ ДОПИСЫВАНИЯ ТЕЛ (этап A2.2): мир, загруженный набором тел разом, и мир, где
 * первое тело загружено, а остальные дописаны по одному (PhysicsWorld.addBody), с теми
 * же постоянными мира, обязаны быть побитово одним миром — сразу и на каждом тике.
 *
 * Сравниваются хэш позиций и скоростей на каждом тике и скрытое состояние (поля
 * решателя, топология, контакты, контур, сон, блоки) — в начале и раз в 16 тиков.
 * Расхождение печатается именами полей: так видно, какая структура дописалась не так.
 *
 * Запуск: AppendCheck <сцена.scene> [тиков] [sleep]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val bodies = SceneFile.loadBodies(args.getOrElse(0) { "docs/physics/scenes/showcase-30k.scene" })
    val ticks = args.getOrNull(1)?.toIntOrNull() ?: 300
    val sleep = args.getOrNull(2) == "sleep"
    val combined = bodies.reduce { a, b -> a.concat(b) }
    val scale = WorldScale.of(combined)
    val config = DemoSettings.fromEnvironment()
    val whole = PhysicsWorld(config, scale = scale).apply { state.quiet = true; load(combined); reset() }
    var appendNs = 0L; var appendMax = 0L; var lastNs = 0L
    val parts = PhysicsWorld(config, scale = scale).apply {
        state.quiet = true
        for ((k, b) in bodies.withIndex()) {
            val t0 = System.nanoTime()
            addBody(b)
            val dt = System.nanoTime() - t0
            if (k > 0) { appendNs += dt; if (dt > appendMax) appendMax = dt; lastNs = dt }
        }
    }
    if (bodies.size > 1) println("APPEND cost: mean %.2f ms, max %.2f ms, last (largest world) %.2f ms".format(
        appendNs / 1e6 / (bodies.size - 1), appendMax / 1e6, lastNs / 1e6))
    whole.sleepOn = sleep; parts.sleepOn = sleep
    println("APPEND bodies=${bodies.size} particles=${combined.count} sleep=$sleep")
    fun compareHidden(label: String): Boolean {
        val a = stateSnapshot(whole.state); val b = stateSnapshot(parts.state)
        val diff = (a.keys + b.keys).filter { a[it] != b[it] }.sorted()
        if (diff.isNotEmpty()) { println("APPEND hidden mismatch at $label: $diff"); return false }
        return true
    }
    var ok = compareHidden("start")
    val period = 40; val duty = 20
    for (tick in 0 until ticks) {
        if (!ok) break
        val on = if (tick % period < duty) 1.0 else 0.0
        whole.setAllMuscleTargets(on.toFloat()); parts.setAllMuscleTargets(on.toFloat())
        whole.step(); parts.step()
        val ha = PlayerLog.stateHash(whole.particleCount, whole.x, whole.y, whole.vx, whole.vy)
        val hb = PlayerLog.stateHash(parts.particleCount, parts.x, parts.y, parts.vx, parts.vy)
        if (ha != hb) { println("APPEND positions diverged at tick ${tick + 1}"); ok = compareHidden("tick ${tick + 1}"); ok = false }
        else if ((tick + 1) % 16 == 0) ok = compareHidden("tick ${tick + 1}")
    }
    println(if (ok) "APPEND OK: $ticks тиков, состояние побитово одно" else "APPEND FAILED")
    if (!ok) kotlin.system.exitProcess(1)
}

/** Поля решателя, входящие в будущие тики (тот же список, что у RefactorCheck). */
private val fields = ("n px py prevX prevY vx vy invMass restInvMass matchWeight " +
    "radius isFree probe probeAngle cellDead deadCount conCount conA conB conRest conMuscle " +
    "bendCount bendA bendB bendRest bendLevel boundCount boundOpp boundA boundB " +
    "organismOf organismCount organismSize organismMass flowVX flowVY " +
    "lnkA lnkB lnkCount triA triB triC triRestArea2 triCount lnkEdge triId conEdge conOfEdge " +
    "triMuscle rigidBones boneOf boneRestQx boneRestQy muscleOf muscleActivation muscleTarget " +
    "muscleScaleOf muscleActivationPrev conHold triHold linkTorn conDead tearsPending " +
    "tearQueue tearQueueN tornTotal sweepBackwards trapTicks trapOrg foldTicks " +
    "contactsOn bonesRigid tearingOn sleepAnchorX sleepAnchorY " +
    "cellUnborn conGrowTarget triGrowTarget growEdges growEdgeN growTris growTriN").split(' ')

/**
 * Снимок состояния решателя по отражению: поля из списка (по живой длине, а не по
 * ёмкости — ёмкость массивов у двух миров законно разная) и вложенные объекты
 * топологии, контактов, контура, сна и блоков.
 */
internal fun stateSnapshot(state: BodyState): Map<String, Long> {
    val result = sortedMapOf<String, Long>()
    fun field(owner: Any, name: String): Any? {
        var c: Class<*>? = owner.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.get(owner) } catch (_: NoSuchFieldException) {}
            c = c.superclass
        }
        return Unit
    }
    for (name in fields) {
        val v = field(state, name)
        if (v === Unit) continue
        result[name] = digestLive(v, state)
    }
    fun nested(prefix: String, value: Any?, depth: Int) {
        if (value == null || depth > 3) return
        for (f in value.javaClass.declaredFields.sortedBy { it.name }) {
            if (Modifier.isStatic(f.modifiers) || f.name.startsWith("dbg") || f.name.startsWith("option") ||
                f.name.contains("Ns") || f.name.startsWith("time") || f.name.startsWith("profile") ||
                f.name.startsWith("parallel") || f.name == "shards" || f.name == "workers" ||
                // Счётчики разбора: у мира с дописанными телами законно другие.
                f.name.endsWith("Total") || f.name.contains("Rebuilds") ||
                // Черновик сборки и счётчик правок — не состояние мира.
                f.name == "cfgDeg" || f.name == "version") continue
            f.isAccessible = true
            val v = f.get(value) ?: continue
            val key = "$prefix.${f.name}"
            if (v is Number || v is Boolean || v.javaClass.isArray) {
                if (v is Array<*> && v.isNotEmpty() && v[0] !is DoubleArray && v[0] !is FloatArray && v[0] !is IntArray) continue
                result[key] = digestLive(v, state)
            } else if (v.javaClass.packageName.startsWith("io.github.some_example_name") && v !is BodyDefinition) {
                nested(key, v, depth + 1)
            }
        }
    }
    nested("baked", field(state, "baked"), 0)
    nested("contacts", field(state, "contacts"), 0)
    nested("localBoundary", state.localBoundary, 0)
    nested("sleep", state.sleep, 0)
    return result
}

/**
 * Хэш значения. Массивы — без длины: у мира с дописанными телами массивы ровно по
 * числу частиц, у загруженного разом — тоже, но рабочие списки (связи после разрывов)
 * могут иметь разную ёмкость при одном и том же содержимом. Хвост нулей не меняет хэш.
 */
private fun digestLive(value: Any?, state: BodyState): Long {
    var hash = 1125899906842597L
    fun mix(v: Long) { hash = (hash xor v) * -7046029254386353131L }
    fun trim(size: Int, isZero: (Int) -> Boolean): Int { var s = size; while (s > 0 && isZero(s - 1)) s--; return s }
    fun visit(v: Any?) {
        when (v) {
            null -> mix(-1)
            is Double -> mix(v.toRawBits())
            is Float -> mix(v.toRawBits().toLong())
            is Number -> mix(v.toLong())
            is Boolean -> mix(if (v) 1 else 0)
            is DoubleArray -> for (i in 0 until trim(v.size) { v[it] == 0.0 }) mix(v[i].toRawBits())
            is FloatArray -> for (i in 0 until trim(v.size) { v[it] == 0f }) mix(v[i].toRawBits().toLong())
            is IntArray -> for (i in 0 until trim(v.size) { v[it] == 0 || v[it] == -1 }) mix(v[i].toLong())
            is LongArray -> for (i in 0 until trim(v.size) { v[it] == 0L }) mix(v[i])
            is ByteArray -> for (i in 0 until trim(v.size) { v[it].toInt() == 0 }) mix(v[i].toLong())
            is BooleanArray -> for (i in 0 until trim(v.size) { !v[it] }) mix(if (v[i]) 1 else 0)
            is Array<*> -> { mix(v.size.toLong()); for (x in v) visit(x) }
            else -> mix(v.hashCode().toLong())
        }
    }
    visit(value)
    return hash
}
