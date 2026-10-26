package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.SceneFile
import io.github.some_example_name.physics.lab.session.DemoSettings
import java.util.Locale

/**
 * НЕДОРОСШЕЕ ТЕЛО (этап A2.4). Две проверки на части тела — первых K клетках по обходу
 * связей (growthOrder).
 *
 * 1. Маска против вырезанного: часть, заданная маской рождения (addBody с born), и та же
 *    часть отдельным телом, вырезанным из этих клеток. Под гребком обе обязаны остаться
 *    целыми: ни одного порванного ребра, ни одной погибшей клетки. Не побитово — у
 *    вырезанного тела кольца мышц и кости считаются по его собственным треугольникам.
 *
 * 2. Оракул рождения: часть K0, в которой клетки до K1 рождены на месте покоя до первого
 *    шага (activate), обязана совпасть со скрытым состоянием части K1, заданной маской, —
 *    кроме порядка в списках, которые ведутся по месту (orderOnly), и дорастания
 *    (growing), — и под гребком
 *    остаться целой. Так поймана связь, вынутая из блоков решателя до рождения
 *    (updateBoundaryLinkLimit 25.10: 464 разрыва против нуля).
 *
 * Запуск: PartialBodyCheck <сцена.scene> [номер тела] [K через запятую] [K0-K1] [тиков]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val bodies = SceneFile.loadBodies(args.getOrElse(0) { "docs/physics/scenes/append-small.scene" })
    val target = bodies[args.getOrNull(1)?.toIntOrNull() ?: 1]
    val ks = (args.getOrNull(2) ?: "200,361,600,947").split(',').map { it.toInt() }
    val (k0, k1) = (args.getOrNull(3) ?: "361-761").split('-').map { it.toInt() }
    val ticks = args.getOrNull(4)?.toIntOrNull() ?: 300
    val scale = WorldScale.of(bodies[0])
    val config = PhysicsConfig(DemoSettings.fromEnvironment().snapshot())
    // Первое тело мира загружается целиком; растущее дописывается следом.
    val seed = bodies.first { it.count <= 8 }
    val order = growthOrder(target)
    fun mask(k: Int) = BooleanArray(target.count).also { for (j in 0 until minOf(k, target.count)) it[order[j]] = true }
    fun world() = PhysicsWorld(config, workers = null, scale = scale).apply { state.quiet = true; sleepOn = false; addBody(seed) }
    fun gait(vararg worlds: PhysicsWorld) = repeat(ticks) { tick ->
        val on = if (tick % 40 < 20) 1.0 else 0.0
        for (w in worlds) { w.setAllMuscleTargets(on); w.step() }
    }
    var ok = true
    fun intact(label: String, w: PhysicsWorld, first: Int, count: Int): String {
        val (alive, all) = liveEdges(w, first, count)
        var dead = 0
        for (i in first until first + count) if (w.state.cellDead[i] && !w.state.cellUnborn[i]) dead++
        if (alive != all || dead != 0) { ok = false; println("PARTIAL $label разрушено") }
        return "рёбер живо $alive из $all, погибло клеток $dead"
    }

    for (k in ks) {
        val masked = world(); val cropped = world()
        val mFirst = masked.bodyFirst(masked.addBody(target, mask(k)))
        val sub = crop(target, mask(k))
        val cFirst = cropped.bodyFirst(cropped.addBody(sub))
        gait(masked, cropped)
        println("PARTIAL K=$k: маской ${intact("маска K=$k", masked, mFirst, target.count)}, " +
            "вырезанным ${intact("вырезанное K=$k", cropped, cFirst, sub.count)}")
    }

    val static = world(); val grown = world()
    val first = static.bodyFirst(static.addBody(target, mask(k1))); grown.addBody(target, mask(k0))
    for (j in k0 until k1) { val c = order[j]; grown.activate(first + c, target.x[c].toDouble(), target.y[c].toDouble()) }
    val a = stateSnapshot(static.state); val b = stateSnapshot(grown.state)
    val diff = (a.keys + b.keys).filter { a[it] != b[it] && it !in growing && orderOnly.none { r -> r.containsMatchIn(it) } }.sorted()
    if (diff.isNotEmpty()) { ok = false; println("PARTIAL рождение на месте покоя разошлось с маской: $diff") }
    gait(static, grown)
    println("PARTIAL рождено $k0 -> $k1 на месте покоя: ${intact("рождённое", grown, first, target.count)}; " +
        "маской ${intact("маска K=$k1", static, first, target.count)}")
    println(if (ok) "PARTIAL OK" else "PARTIAL FAILED")
    if (!ok) kotlin.system.exitProcess(1)
}

/**
 * Поля, где законно различается только ПОРЯДОК: списки, которые ведутся по месту
 * (пределы, рёбра и изгибы контура в блоках, кольцо контура, вершины контактов), черновик
 * грязных вершин и prev, который интегрирование ставит до первого чтения. Связи и
 * треугольники блоков (links, areas) сюда не входят: их состав обязан совпасть.
 */
private val orderOnly = listOf(
    Regex("""(^|\.)(limits|edges|bends|flaps|blockIndex)\.(head|next|prev|tail|owner)$"""),
    Regex("""^localBoundary(\.(bend|flap))?\.(a|b|rest|opposite|orientation|slotOf|edgeAt|head|owner)$"""),
    Regex("""^(bendA|bendB|bendRest|boundA|boundB|boundOpp|prevX|prevY)$"""),
    Regex("""^contacts\.(verts|vertSlot|bndOpp|bndOther)$"""),
    Regex("""(^|\.)dirtyVertices$"""),
)

/**
 * Дорастание: у рождённой ткани длина и площадь покоя начинаются с фактических и идут к
 * цели (BodyState.conGrowTarget). На месте покоя фактическое отличается от цели только
 * округлением (float-разность в позе покоя против double) и закрывается первым же тиком.
 */
private val growing = setOf("conRest", "triRestArea2", "conGrowTarget", "triGrowTarget",
    "growEdges", "growEdgeN", "growTris", "growTriN")

/** Тело из клеток [keep]: номера по порядку, связи и треугольники — только целиком внутри. */
private fun crop(def: BodyDefinition, keep: BooleanArray): BodyDefinition {
    val map = IntArray(def.count) { -1 }
    var m = 0
    for (i in 0 until def.count) if (keep[i]) map[i] = m++
    val idx = (0 until def.count).filter { keep[it] }
    val links = (0 until def.linkCount).filter { keep[def.linkA[it]] && keep[def.linkB[it]] }
    val tris = (0 until def.triCount).filter { keep[def.triA[it]] && keep[def.triB[it]] && keep[def.triC[it]] }
    fun clusters(c: Array<IntArray>) = c.map { cl -> cl.filter { keep[it] }.map { map[it] }.toIntArray() }
        .filter { it.isNotEmpty() }.toTypedArray()
    return BodyDefinition(
        x = FloatArray(m) { def.x[idx[it]] }, y = FloatArray(m) { def.y[idx[it]] },
        radius = FloatArray(m) { def.radius[idx[it]] },
        isBone = BooleanArray(m) { def.isBone[idx[it]] }, isMuscle = BooleanArray(m) { def.isMuscle[idx[it]] },
        linkA = IntArray(links.size) { map[def.linkA[links[it]]] }, linkB = IntArray(links.size) { map[def.linkB[links[it]]] },
        triA = IntArray(tris.size) { map[def.triA[tris[it]]] }, triB = IntArray(tris.size) { map[def.triB[tris[it]]] },
        triC = IntArray(tris.size) { map[def.triC[tris[it]]] },
        triRestArea2 = FloatArray(tris.size) { def.triRestArea2[tris[it]] },
        boneClusters = clusters(def.boneClusters), muscleClusters = clusters(def.muscleClusters),
        scale = def.scale,
    )
}
