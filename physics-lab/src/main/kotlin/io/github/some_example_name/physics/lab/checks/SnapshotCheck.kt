package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.BodyState
import io.github.some_example_name.physics.DT
import io.github.some_example_name.physics.MAX_SPEED_CELLS_PER_TICK
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.SceneFile
import io.github.some_example_name.physics.lab.session.DemoSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.Locale

/**
 * ПОЛНЫЙ СНИМОК МИРА (этап A4): мир, загруженный из снимка, продолжает побитово так же, как
 * мир, который его записал.
 *
 * Сценарий нагружает всё, что несёт историю: таран (разрывы, деление организмов,
 * пластика), рост тела по клетке (нерождённые связи, дорастание, позы костей), поток
 * свободных частиц, удаление тела с уплотнением, захват клетки мышью, сон и мышцы (у половины клеток — с волокном). На тике
 * T мир W пишет снимок; из него читаются L (один поток) и Lp (N воркеров). Дальше:
 *  1. все поля состояния W и L совпадают — обход отражением по всему графу объектов
 *     решателя, а не по списку: забытое в снимке поле с содержимым всплывёт;
 *  2. W, L и Lp с одними входами побитово одни на каждом тике;
 *  3. второе поколение: L пишет снимок, из него читается L2 — тоже побитово.
 *
 * Запуск: SnapshotCheck <сцена.scene> [T] [тиков после] [воркеров]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val all = SceneFile.loadBodies(args.getOrElse(0) { "docs/physics/scenes/append-small.scene" })
    val snapAt = args.getOrNull(1)?.toIntOrNull() ?: 120
    val after = args.getOrNull(2)?.toIntOrNull() ?: 300
    val workers = args.getOrNull(3)?.toIntOrNull() ?: 7
    val bodies = all.filter { it.linkCount > 0 }
    val config = PhysicsConfig(DemoSettings.fromEnvironment().snapshot().toMutableMap().also { it["RB_PARALLEL_MIN"] = "0" })
    val scale = WorldScale.of(bodies[0])
    val sc = Scenario(bodies)
    val w = PhysicsWorld(config, workers = null, scale = scale).apply { state.quiet = true; sleepOn = true }
    sc.build(w)
    repeat(snapAt) { sc.tick(listOf(w)) }
    val bytes = write(w)
    val l = read(bytes, config, null)
    val lp = read(bytes, config, PhysicsWorkers.shared(workers))
    var ok = sameFields(w, l, "после чтения")
    var l2: PhysicsWorld? = null
    var worlds = listOf(w, l, lp)
    for (k in 1..after) {
        if (!ok) break
        if (k == after / 2) {
            // Второе поколение: пишут все (запись каноническая и в пишущем), L2 — из снимка L.
            write(w); write(lp)
            val again = read(write(l), config, null)
            ok = sameFields(l, again, "второе поколение")
            l2 = again; worlds = listOf(w, l, lp, again)
        }
        sc.tick(worlds)
        val h = worlds.map { hash(it) }
        if (h.any { it != h[0] }) {
            println("SNAPSHOT: разошлись на тике ${sc.tick} (${k}-м после снимка): " + h.mapIndexed { i, v -> "${"W L Lp L2".split(' ')[i]}=${v == h[0]}" })
            ok = false
        }
    }
    val st = w.state
    println("SNAPSHOT: снимок ${bytes.size / 1024} КБ на тике $snapAt, ${w.particleCount} частиц, организмов ${w.organismCount}, " +
        "порвано ${st.tornTotal}, свободных ${w.freeCount}, нерождённых ${(0 until w.particleCount).count { w.isUnborn(it) }}, " +
        "дальше $after тиков W = L = Lp${if (l2 != null) " = L2" else ""} побитово: $ok")
    println(if (ok) "SNAPSHOT OK" else "SNAPSHOT FAILED")
    if (!ok) kotlin.system.exitProcess(1)
}

private fun write(w: PhysicsWorld): ByteArray = ByteArrayOutputStream().also { w.write(DataOutputStream(it)) }.toByteArray()

private fun read(bytes: ByteArray, config: PhysicsConfig, workers: PhysicsWorkers?): PhysicsWorld =
    PhysicsWorld(config, workers = workers, scale = null).apply { state.quiet = true; read(DataInputStream(ByteArrayInputStream(bytes))) }

private fun hash(w: PhysicsWorld): Long {
    var h = 1125899906842597L
    fun mix(v: Long) { h = (h xor v) * -7046029254386353131L }
    for (i in 0 until w.particleCount) { mix(w.x[i].toRawBits()); mix(w.y[i].toRawBits()); mix(w.vx[i].toRawBits()); mix(w.vy[i].toRawBits()) }
    return h
}

/** Сценарий: решения по зерну и номерам тел — одинаковые для всех миров. */
private class Scenario(val bodies: List<BodyDefinition>) {
    var tick = 0
    val ids = IntArray(bodies.size)
    val grow = bodies.indices.first { bodies[it].muscleClusters.isNotEmpty() && bodies[it].count in 100..600 }
    val order = growthOrder(bodies[grow])
    var next = 1
    val rnd = java.util.Random(4242L)
    val free = ArrayList<Int>()
    val ram = bodies.indices.first { it != 0 && it != grow && bodies[it].muscleClusters.isNotEmpty() }
    val gone = bodies.indices.last { it != 0 && it != grow && it != ram }
    var minX = 0.0; var maxX = 0.0; var minY = 0.0; var maxY = 0.0

    fun build(w: PhysicsWorld) {
        for (k in bodies.indices) {
            val born = if (k == grow) BooleanArray(bodies[k].count).also { it[order[0]] = true } else null
            ids[k] = w.addBody(bodies[k], born)
        }
        minX = (0 until w.particleCount).minOf { w.x[it] }; maxX = (0 until w.particleCount).maxOf { w.x[it] }
        minY = (0 until w.particleCount).minOf { w.y[it] }; maxY = (0 until w.particleCount).maxOf { w.y[it] }
        // Входы клиента-игры: рамка мира, масса и сопротивление клеток первого тела.
        val pad = 0.2 * (maxX - minX)
        w.setBounds(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val f = w.bodyFirst(ids[0])
        for (k in 0 until 20) { w.setMass(f + k, 2.0 / w.state.restInvMass[f + k]); w.setDrag(f + k, 1.5) }
        // Волокно мышцы у половины клеток растущего тела (MuscleFiber.kt): в снимке — волокна, после чтения — производное заново.
        val g = w.bodyFirst(ids[grow])
        val fl = Math.sqrt(1.09) // волокно — единичный вектор (PhysicsWorld.setCellFiber)
        for (j in 0 until bodies[grow].count) if (j % 2 == 0) w.setCellFiber(g + j, (1.0 / fl).toFloat(), (0.3 / fl).toFloat())
    }

    fun tick(worlds: List<PhysicsWorld>) {
        val w0 = worlds[0]
        if (tick == 20) for (w in worlds) slam(w)
        if (tick % 2 == 0 && next < order.size) { for (w in worlds) w.activate(w.bodyFirst(ids[grow]) + order[next]); next++ }
        // Поток свободных частиц.
        repeat(rnd.nextInt(4)) {
            val x = minX + rnd.nextDouble() * (maxX - minX); val y = minY + rnd.nextDouble() * (maxY - minY)
            val r = 0.5 * w0.meanLinkLength * (0.6 + 0.8 * rnd.nextDouble())
            var id = -1
            val drag = if (rnd.nextBoolean()) 1.0 else 0.4
            for (w in worlds) id = w.addFree(x, y, r, 0.0, 0.0, drag = drag)
            free.add(id)
        }
        var k = 0
        while (k < free.size) { if (rnd.nextInt(60) == 0) { for (w in worlds) w.removeFree(free[k]); free.removeAt(k) } else k++ }
        if (tick == 60) for (w in worlds) { w.removeBody(ids[gone]); if (w.removedParticles > 0) w.compact() }
        // Мышь: схватить клетку первого тела и вести её по кругу.
        if (tick == 90) for (w in worlds) w.grab(w.bodyFirst(ids[0]) + 5)
        if (tick in 90..200) for (w in worlds) {
            val c = w.bodyFirst(ids[0]) + 5
            w.dragTo(w.x[c] + 0.02 * Math.cos(tick * 0.1), w.y[c] + 0.02 * Math.sin(tick * 0.1))
        }
        if (tick == 200) for (w in worlds) w.release()
        // Мышцы клеток растущего тела — бегущая волна; гибель клетки первого тела раз в 50 тиков.
        for (w in worlds) {
            val f = w.bodyFirst(ids[grow])
            for (j in 0 until bodies[grow].count) w.setCellScale(f + j, (1.0 + 0.3 * Math.sin(0.2 * tick + 0.05 * j)).toFloat())
            if (tick % 50 == 25) w.kill(w.bodyFirst(ids[0]) + 30 + tick / 50)
        }
        // Свободные частицы: половина — со своим сопротивлением.
        val on = if (tick % 40 < 20) 1.0 else 0.0
        for (w in worlds) { w.setAllMuscleTargets(on); w.step() }
        tick++
    }

    private fun slam(w: PhysicsWorld) {
        fun centre(b: Int): DoubleArray {
            val f = w.bodyFirst(ids[b]); val c = w.bodyCount(ids[b]); var cx = 0.0; var cy = 0.0
            for (i in f until f + c) { cx += w.x[i]; cy += w.y[i] }
            return doubleArrayOf(cx / c, cy / c)
        }
        val p = centre(0); val q = centre(ram)
        var dx = q[0] - p[0]; var dy = q[1] - p[1]
        val d = Math.sqrt(dx * dx + dy * dy); dx /= d; dy /= d
        val speed = 0.8 * MAX_SPEED_CELLS_PER_TICK * w.meanLinkLength / DT
        for ((b, sgn) in listOf(0 to 1.0, ram to -1.0)) {
            val f = w.bodyFirst(ids[b])
            for (i in f until f + w.bodyCount(ids[b])) w.setVelocity(i, sgn * dx * speed, sgn * dy * speed)
            w.wake(f)
        }
    }
}

/**
 * Все поля состояния двух миров: обход отражением по графу объектов решателя (BodyState и
 * вложенные объекты пакета физики), массивы — по живой длине (хвост нулей не в счёт).
 * Пропуск — только то, что не влияет на будущие тики: разбор, замеры, счётчики,
 * исполнители и рабочие буферы проходов (список ниже, с объяснением).
 */
private fun sameFields(a: PhysicsWorld, b: PhysicsWorld, label: String): Boolean {
    val da = digestAll(a.state); val db = digestAll(b.state)
    val diff = (da.keys + db.keys).filter { !scratchKey(it) && da[it] != db[it] }.sorted()
    if (diff.isNotEmpty()) { println("SNAPSHOT [$label]: поля разошлись: $diff"); return false }
    return true
}

private val skipNames = setOf("workers", "observer", "config", "dbgSubstepHook", "dbgTearLog", "structureVersion",
    "version", "rebuilding", "quiet",
    // Счётчики разбора: на физику не влияют.
    "linkCapHits", "speedCapHits", "boneCapHits", "peakSpeed2", "piercedCount", "modCount", "bpBuilds", "bpCalls",
    "restDirtyTotal", "restFullRebuilds",
    // Черновики построителей сброса (buildBend, buildOrganisms) и одной клетки поверхности.
    "bendDeg", "bendNb0", "bendNb1", "orgQueue", "orgSeed", "orgPrev", "surfaceOne", "boneDx", "boneDy")

/**
 * Рабочие буферы, которые проход пишет до чтения: учёт импульса за тик (begin в начале
 * тика), диапазоны сна (spansDirty — пересобираются на шаге), контакты подшага, кандидаты
 * и ключи широкой фазы, сетка статики (staticValid = false — строится заново), кости
 * контактов (updateBones каждый подшаг) и их накопители (rigidBatch.begin обнуляет в
 * начале прохода), метки и штампы обходов.
 */
private val scratchPrefixes = listOf("state.tickMomentumState.", "state.sleep.span", "state.contacts.staticGrid.",
    "state.contacts.keys.", "state.contacts.rigidBatch.",
    // Журналы событий для клиента (этап B2): чистятся в начале шага, при уплотнении и записи
    // снимка (счётчики edgeDeathN/cellDeathN сверяются и равны нулю); за счётчиком — остатки
    // прошлых шагов и разная ёмкость, на следующие тики они не влияют.
    "state.cellDeathLog", "state.edgeDeathLog",
    // Черновик activateCell: ожившие треугольники до старта дорастания, за пределами вызова не читается.
    "state.bornTriScratch",
    // Клин (ImpactWedge): клинья, область распорок и метки обходов — буферы подшага; список клеток с памятью
    // удара собирается из неё заново в начале тика (bindImpact). Сама память — state.impactSpeed — сверяется.
    "state.contacts.wedge.")
private val contactScratch = setOf("boneCx", "boneCy", "boneI", "boneIds", "boneM", "boneOf", "bpMoved", "cAx", "cAy",
    "cI", "cJ", "cLam", "cLamTot", "cNx", "cNy", "cRR", "cVn", "ddaX", "ddaY", "dirtyBuf", "frcList", "frcN", "keptKeys",
    "lowKeys", "moved", "movedCount", "movedStamp", "moverCount", "movers", "nearA", "nearB", "nearN", "nearRestSlot",
    "newKeys", "pairA", "pairB", "pairNear", "pass", "pierceA", "pierceB", "stamp", "staticCount", "velH",
    // Вид контакта и список упоров в неподвижное (cFixed): пишутся в каждом подшаге до чтения.
    "cFixed", "fixedList",
    // Клин: положения начала подшага (ставит prepare) и признак «гибель уже осуждена» (ставит проход касаний).
    "wedgeQx", "wedgeQy", "wedgeKillsJudged")

private fun skip(name: String): Boolean =
    name in skipNames || name.startsWith("dbg") || name.startsWith("parallel") || name.startsWith("profile") ||
        name.startsWith("time") || name.endsWith("Ns") || name == "shards"

private fun scratchKey(key: String): Boolean =
    scratchPrefixes.any { key.startsWith(it) } || (key.startsWith("state.contacts.") && key.removePrefix("state.contacts.") in contactScratch)

private fun digestAll(root: BodyState): Map<String, Long> {
    val out = sortedMapOf<String, Long>()
    val seen = IdentityHashMap<Any, String>()
    fun visit(path: String, o: Any, depth: Int) {
        if (depth > 6 || seen.put(o, path) != null) return
        var c: Class<*>? = o.javaClass
        while (c != null && c.packageName.startsWith("io.github.some_example_name.physics")) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || skip(f.name)) continue
                f.isAccessible = true
                val v = f.get(o) ?: continue
                val key = "$path.${f.name}"
                when {
                    // Список пределов контура: живая часть — count, хвост за ней reset не чистит.
                    key == "state.boundaryLinkLimits.ids" -> out[key] = digest((v as IntArray).copyOf(f.declaringClass
                        .getDeclaredField("count").apply { isAccessible = true }.getInt(o)))
                    v is Number || v is Boolean -> out[key] = digest(v)
                    v.javaClass.isArray -> out[key] = digest(v)
                    v.javaClass.packageName.startsWith("io.github.some_example_name.physics") -> visit(key, v, depth + 1)
                }
            }
            c = c.superclass
        }
    }
    visit("state", root, 0)
    return out
}

private fun digest(value: Any?): Long {
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
            is Array<*> -> { mix(v.size.toLong()); for (x in v) if (x == null || x.javaClass.isArray || x is Number || x is Boolean) visit(x) }
            else -> mix(0)
        }
    }
    visit(value)
    return hash
}
