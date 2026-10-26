package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.io.SceneFile
import io.github.some_example_name.physics.lab.session.DemoSettings
import java.util.Locale

/**
 * ОРАКУЛ УПЛОТНЕНИЯ МИРА (этап A2.3b). Мир X — тела сцены, мир Y — те же тела без тела B.
 * Оба идут под одним гребком; на тике T мир X удаляет B и уплотняется, мир Y уплотняется
 * в тот же тик, хотя удалять ему нечего. После этого X и Y обязаны быть побитово одним
 * миром — позиции на каждом тике и скрытое состояние. Так проверяется, что уплотнение
 * перенесло всё состояние оставшихся тел (разрывы, пластику, сон, мышцы, организмы) и
 * ничего не взяло у удалённого.
 *
 * Условие оракула — B не касается остальных до удаления: до тика T частицы остальных тел в
 * X сравниваются с Y побитово, и если B их задело, вариант не годится (печатается).
 *
 * Варианты: B удаляется до первого шага; B неподвижное (без мышц); B плавающее (мышцы,
 * разрывы, деление организмов). Запуск: CompactCheck <сцена.scene> [T] [тиков после]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val bodies = SceneFile.loadBodies(args.getOrElse(0) { "docs/physics/scenes/append-small.scene" })
    val before = args.getOrNull(1)?.toIntOrNull() ?: 120
    val after = args.getOrNull(2)?.toIntOrNull() ?: 240
    val config = PhysicsConfig(DemoSettings.fromEnvironment().snapshot())
    val scale = WorldScale.of(bodies[0])
    val still = bodies.indices.drop(1).first { bodies[it].muscleClusters.isEmpty() && bodies[it].count > 50 }
    val swimmer = bodies.indices.drop(1).first { bodies[it].muscleClusters.isNotEmpty() }
    var ok = true
    ok = variant("удалено до первого шага", bodies, still, 0, after, config, scale) == OK && ok
    ok = variant("неподвижное", bodies, still, before, after, config, scale) == OK && ok
    ok = variant("плавающее", bodies, swimmer, before, after, config, scale) == OK && ok
    // Таран может задеть B: тогда берётся следующее тело — выбор зависит только от сцены.
    var rich = TOUCHED
    for (b in bodies.indices.drop(1)) {
        rich = variant("разрывы, рост, сон", bodies, b, before, after, config, scale, rich = true)
        if (rich != TOUCHED) break
    }
    ok = rich == OK && ok
    println(if (ok) "COMPACT OK" else "COMPACT FAILED")
    if (!ok) kotlin.system.exitProcess(1)
}

/**
 * [rich] — до удаления два других тела таранят друг друга (разрывы, деление организмов,
 * пластика), а последнее тело сцены растёт по клетке до и после уплотнения (нерождённые
 * связи, дорастание); номера частиц растущего — от bodyFirst, в X и Y они разные.
 */
private const val OK = 0
private const val FAILED = 1
private const val TOUCHED = 2

private fun variant(label: String, bodies: List<BodyDefinition>, b: Int, before: Int, after: Int,
                    config: PhysicsConfig, scale: WorldScale, rich: Boolean = false): Int {
    fun world() = PhysicsWorld(config, workers = null, scale = scale).apply { state.quiet = true; sleepOn = true }
    val x = world(); val y = world()
    val idsX = IntArray(bodies.size) { -1 }; val idsY = IntArray(bodies.size) { -1 }
    val grow = if (rich) bodies.indices.last { it != b && bodies[it].count > 50 } else -1
    val order = if (grow >= 0) growthOrder(bodies[grow]) else IntArray(0)
    for (k in bodies.indices) {
        val born = if (k == grow) BooleanArray(bodies[k].count).also { it[order[0]] = true } else null
        idsX[k] = x.addBody(bodies[k], born)
        if (k != b) idsY[k] = y.addBody(bodies[k], born)
    }
    val bFirst = x.bodyFirst(idsX[b]); val bCount = bodies[b].count
    var tick = 0
    var next = 1
    // Таран: первое тело и следующее с мышцами (не B и не растущее) — навстречу друг другу.
    val ram = bodies.indices.firstOrNull { it != 0 && it != b && it != grow && bodies[it].muscleClusters.isNotEmpty() } ?: -1
    fun slam(w: PhysicsWorld, ids: IntArray) {
        fun centre(k: Int): DoubleArray {
            var cx = 0.0; var cy = 0.0; val f = w.bodyFirst(ids[k]); val c = w.bodyCount(ids[k])
            for (i in f until f + c) { cx += w.x[i]; cy += w.y[i] }
            return doubleArrayOf(cx / c, cy / c)
        }
        val p = centre(0); val q = centre(ram)
        var dx = q[0] - p[0]; var dy = q[1] - p[1]
        val d = Math.sqrt(dx * dx + dy * dy); dx /= d; dy /= d
        val speed = 0.8 * io.github.some_example_name.physics.MAX_SPEED_CELLS_PER_TICK * w.meanLinkLength / io.github.some_example_name.physics.DT
        for ((k, sgn) in listOf(0 to 1.0, ram to -1.0)) {
            val f = w.bodyFirst(ids[k])
            for (i in f until f + w.bodyCount(ids[k])) w.setVelocity(i, sgn * dx * speed, sgn * dy * speed)
            w.wake(f)
        }
    }
    fun gait(vararg ws: PhysicsWorld) {
        if (rich && tick == 10 && ram >= 0) { slam(x, idsX); slam(y, idsY) }
        if (grow >= 0 && tick % 2 == 0 && next < order.size) {
            x.activate(x.bodyFirst(idsX[grow]) + order[next]); y.activate(y.bodyFirst(idsY[grow]) + order[next]); next++
        }
        val on = if (tick % 40 < 20) 1.0 else 0.0
        for (w in ws) { w.setAllMuscleTargets(on); w.step() }
        tick++
    }
    repeat(before) {
        gait(x, y)
        if (hashExcept(x, bFirst, bCount) != hashExcept(y, 0, 0)) {
            println("COMPACT [$label]: тело B (${bodies[b].count} клеток) задело остальных на тике $tick — не годится как оракул")
            return TOUCHED
        }
    }
    val torn = x.state.tornTotal
    val nBefore = x.particleCount
    val t0 = System.nanoTime()
    x.removeBody(idsX[b])
    if (x.removedParticles > 0) x.compact()
    val compactMs = (System.nanoTime() - t0) / 1e6
    y.compact()
    if (!filtersMatchRebuild(x, label) || !filtersMatchRebuild(y, label)) return FAILED
    if (x.particleCount != y.particleCount) { println("COMPACT [$label]: частиц ${x.particleCount} против ${y.particleCount}"); return FAILED }
    for (k in 0 until after) {
        gait(x, y)
        val hx = PlayerLog.stateHash(x.particleCount, x.x, x.y, x.vx, x.vy)
        val hy = PlayerLog.stateHash(y.particleCount, y.x, y.y, y.vx, y.vy)
        if (hx != hy) {
            println("COMPACT [$label]: разошлись на тике $tick (${k + 1}-м после уплотнения)")
            sameHidden(x, y, label, tick)
            return FAILED
        }
        if ((k == 0 || (k + 1) % 40 == 0) && !sameHidden(x, y, label, tick)) return FAILED
    }
    val unborn = (0 until x.particleCount).count { x.isUnborn(it) }
    println("COMPACT [$label]: B = ${bCount} клеток удалено на тике $before ($nBefore -> ${x.particleCount} частиц, " +
        "порвано у X до удаления $torn, организмов ${x.organismCount}, нерождённых в конце $unborn), " +
        "уплотнение %.1f мс; дальше $after тиков X ≡ Y побитово".format(compactMs))
    return OK
}

/** Хэш позиций и скоростей всех частиц, кроме [skipFirst, skipFirst + skipCount). */
private fun hashExcept(w: PhysicsWorld, skipFirst: Int, skipCount: Int): Long {
    var h = 1125899906842597L
    fun mix(v: Long) { h = (h xor v) * -7046029254386353131L }
    for (i in 0 until w.particleCount) {
        if (i >= skipFirst && i < skipFirst + skipCount) continue
        mix(w.x[i].toRawBits()); mix(w.y[i].toRawBits()); mix(w.vx[i].toRawBits()); mix(w.vy[i].toRawBits())
    }
    return h
}

/** Счётчики за всю историю мира: у X в них и удалённое тело. */
private val history = setOf("tornTotal")

/**
 * Рабочие буферы контактов на подшаг: кандидаты и пары широкой фазы, контакты подшага с
 * множителями, сетка статики, центры костей, ключи сортировки, метки обхода. Живая длина у
 * них — в счётчике, за ней лежат записи прошлых подшагов; у X до удаления пар было больше
 * (с телом B), поэтому хвосты разные. Каждый подшаг пишет их до чтения, состоянием мира они
 * не являются; физику проверяют позиции и скорости — побитово на каждом тике.
 */
private val contactScratch = setOf("boneCx", "boneCy", "keptKeys", "mark", "moved", "movers", "nearA", "nearB",
    "pairA", "pairB", "pairNear", "stamp", "bpMoved", "cAx", "cAy", "cI", "cJ", "cLam", "cLamTot", "cNx", "cNy",
    "cRR", "cVn", "ddaX", "ddaY", "frcList", "lowKeys", "newKeys", "nearRestSlot",
    // Пробои подшага; метки «сдвинутых» сверяются со штампом, а штамп — счётчик за историю
    // мира, как и счётчик пересборок широкой фазы.
    "pierceA", "pierceB", "movedMark", "movedStamp", "bpBuilds")

/** Диапазоны бодрствующих частиц сна: живая часть — spanCount, за ней остатки прошлых тиков. */
private val sleepScratch = setOf("sleep.spanStart", "sleep.spanEnd")

private fun scratch(key: String): Boolean {
    if (key in sleepScratch) return true
    if (!key.startsWith("contacts.")) return false
    val rest = key.removePrefix("contacts.")
    return rest.startsWith("staticGrid.") || rest.startsWith("keys.") || rest in contactScratch
}

private fun sameHidden(x: PhysicsWorld, y: PhysicsWorld, label: String, tick: Int): Boolean {
    val a = stateSnapshot(x.state); val b = stateSnapshot(y.state)
    val diff = (a.keys + b.keys).filter { it !in history && !scratch(it) && a[it] != b[it] }.sorted()
    if (diff.isNotEmpty()) { println("COMPACT [$label]: скрытое состояние разошлось на тике $tick: $diff"); return false }
    return true
}

/**
 * Топология и поверхность при уплотнении не строятся заново, а фильтруются
 * (BakedTopology.compactFrom, LocalSurface.compactFrom). Здесь — сверка с честной
 * постройкой по уплотнённому описанию: структура топологии и вся поверхность обязаны
 * совпасть поэлементно.
 */
private fun filtersMatchRebuild(w: PhysicsWorld, label: String): Boolean {
    val st = w.state
    fun field(o: Any, name: String): Any {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) {}
            c = c.superclass
        }
        error("нет поля $name")
    }
    fun same(a: Any, b: Any): Boolean = when (a) {
        is IntArray -> a.contentEquals(b as IntArray)
        is LongArray -> a.contentEquals(b as LongArray)
        is DoubleArray -> a.contentEquals(b as DoubleArray)
        is BooleanArray -> a.contentEquals(b as BooleanArray)
        else -> a == b
    }
    val fresh = io.github.some_example_name.physics.BakedTopology(st.body)
    val bad = ArrayList<String>()
    for (f in listOf("edgeCount", "linkEdge", "edgeKeys", "triEdge", "edgeTriStart", "edgeTris", "useInit",
            "vertStart", "vertOther", "vertEdge", "vertexTriangles", "vertexTriStart", "restAngles"))
        if (!same(field(st.baked, f), field(fresh, f))) bad.add("baked.$f")
    val free = st.isFree.copyOf(); val probe = st.probe.copyOf()
    val surface = io.github.some_example_name.physics.LocalSurface(st.body, st.baked, st.boneOf, st.cellDead, st.radius,
        io.github.some_example_name.physics.CONTACT_SCALE, st.config.contacts.CONTACT_SEAL,
        io.github.some_example_name.physics.PROBE_ANGLE_EPS, free, probe, st.probeOn)
    for (v in 0 until st.n) surface.update(v)
    for (f in listOf("adj", "adjEnd", "boundary", "opposite", "boundaryEnd", "radius", "active"))
        if (!same(field(st.localSurface, f), field(surface, f))) bad.add("surface.$f")
    if (!free.contentEquals(st.isFree)) bad.add("surface.free")
    if (!probe.contentEquals(st.probe)) bad.add("surface.probe")
    if (bad.isNotEmpty()) { println("COMPACT [$label]: фильтр разошёлся с постройкой: $bad"); return false }
    return true
}
