package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.PlayerLog
import io.github.some_example_name.physics.lab.io.SceneFile
import io.github.some_example_name.physics.lab.session.DemoSettings
import java.util.Locale

/**
 * РОСТ ТЕЛА ПО КЛЕТКЕ (этап A2.4). Две части.
 *
 * 1. Оракул: тело, добавленное с born = «все рождены», — побитово то же, что тело без
 *    born (позиции на каждом тике и скрытое состояние). Так проверяется, что путь
 *    роста ничего не меняет, пока нерождённых нет.
 *
 * 2. Рост: в мир с телами стенда добавляется тело, у которого рождена одна клетка, и
 *    клетки рождаются по обходу связей там, где им место (PhysicsWorld.activate), по
 *    нескольку за тик, под гребок. Второе растущее тело удаляется недоросшим. Один и
 *    тот же сценарий в одном потоке и на N воркерах обязан дать побитово один мир на
 *    каждом тике; полноту пар с новорождёнными проверяет CT_BPCHECK=1. Выросшее тело
 *    сравнивается по целости с целой копией того же тела.
 *
 * Запуск: GrowthCheck <сцена.scene> [воркеров]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val bodies = SceneFile.loadBodies(args.getOrElse(0) { "docs/physics/scenes/append-small.scene" })
    val workers = args.getOrNull(1)?.toIntOrNull() ?: 7
    require(bodies.size >= 6) { "нужно хотя бы 6 тел в сцене" }
    val scale = WorldScale.of(bodies[0])
    val base = DemoSettings.fromEnvironment().snapshot().toMutableMap()
    base["RB_PARALLEL_MIN"] = "0"
    val config = PhysicsConfig(base)
    val ok = bornOracle(config, scale, bodies) && growth(config, scale, bodies, workers)
    println(if (ok) "GROWTH OK" else "GROWTH FAILED")
    if (!ok) kotlin.system.exitProcess(1)
}

private fun bornOracle(config: PhysicsConfig, scale: WorldScale, bodies: List<BodyDefinition>): Boolean {
    val plain = PhysicsWorld(config, workers = null, scale = scale)
    val allBorn = PhysicsWorld(config, workers = null, scale = scale)
    for (w in listOf(plain, allBorn)) { w.state.quiet = true; w.sleepOn = true; w.addBody(bodies[0]) }
    for (k in 1 until bodies.size) {
        plain.addBody(bodies[k])
        allBorn.addBody(bodies[k], BooleanArray(bodies[k].count) { true })
    }
    for (tick in 1..120) {
        val on = if (tick % 40 < 20) 1.0 else 0.0
        for (w in listOf(plain, allBorn)) { w.setAllMuscleTargets(on); w.step() }
        val a = PlayerLog.stateHash(plain.particleCount, plain.x, plain.y, plain.vx, plain.vy)
        val b = PlayerLog.stateHash(allBorn.particleCount, allBorn.x, allBorn.y, allBorn.vx, allBorn.vy)
        if (a != b) { println("GROWTH oracle: born=все разошёлся с телом без born на тике $tick"); return false }
        if (tick % 40 == 0 && !sameHidden(plain, allBorn, "oracle tick $tick")) return false
    }
    println("GROWTH oracle: born=все ≡ без born, 120 тиков побитово")
    return true
}

/** Клетки тела в порядке рождения: обход связей от клетки 0; несвязные куски — следом. */
internal fun growthOrder(def: BodyDefinition): IntArray {
    val n = def.count
    val start = IntArray(n + 1)
    for (k in 0 until def.linkCount) { start[def.linkA[k] + 1]++; start[def.linkB[k] + 1]++ }
    for (i in 0 until n) start[i + 1] += start[i]
    val fill = start.copyOf(); val adj = IntArray(start[n])
    for (k in 0 until def.linkCount) { adj[fill[def.linkA[k]]++] = def.linkB[k]; adj[fill[def.linkB[k]]++] = def.linkA[k] }
    val order = IntArray(n); val seen = BooleanArray(n)
    var head = 0; var tail = 0
    for (root in 0 until n) {
        if (seen[root]) continue
        seen[root] = true; order[tail++] = root
        while (head < tail) {
            val v = order[head++]
            for (s in start[v] until start[v + 1]) { val u = adj[s]; if (!seen[u]) { seen[u] = true; order[tail++] = u } }
        }
    }
    return order
}

/** Растущее тело: первая клетка рождена при добавлении, остальные — по одной в порядке growthOrder. */
private class Grower(var first: Int, val def: BodyDefinition) {
    var id = -1
    val order = growthOrder(def)
    var next = 1
    val done get() = next >= order.size
    val born: BooleanArray get() = BooleanArray(def.count).also { it[order[0]] = true }

    /** Родить следующую клетку во всех мирах одинаково — там, где ей место (PhysicsWorld.activate). */
    fun birth(worlds: List<PhysicsWorld>) {
        val c = order[next++]
        for (w in worlds) w.activate(first + c)
    }
}

private fun growth(config: PhysicsConfig, scale: WorldScale, bodies: List<BodyDefinition>, workers: Int): Boolean {
    val serial = PhysicsWorld(config, workers = null, scale = scale)
    val parallel = PhysicsWorld(config, workers = PhysicsWorkers.shared(workers), scale = scale)
    val worlds = listOf(serial, parallel)
    for (w in worlds) { w.state.quiet = true; w.sleepOn = true }
    // Растут тело с наибольшим числом костей (поза кости по рождённой части) и следующее.
    val growing = (1 until bodies.size).sortedByDescending { bodies[it].boneClusters.size }.take(2)
    val placed = ArrayList<IntArray>()
    for (k in bodies.indices) if (k !in growing) {
        var id = -1
        for (w in worlds) id = w.addBody(bodies[k])
        placed.add(intArrayOf(k, id))
    }
    var tick = 0
    var births = 0; var birthNs = 0L
    fun add(k: Int): Grower {
        val g = Grower(serial.particleCount, bodies[k])
        for (w in worlds) g.id = w.addBody(bodies[k], g.born)
        return g
    }
    fun run(count: Int, vararg grow: Grower): Boolean {
        repeat(count) {
            for (g in grow) if (!g.done && tick % 2 == 0) repeat(3) {
                if (!g.done) {
                    val t0 = System.nanoTime(); g.birth(worlds); birthNs += System.nanoTime() - t0; births++
                }
            }
            val on = if (tick % 40 < 20) 1.0 else 0.0
            for (w in worlds) { w.setAllMuscleTargets(on); w.step() }
            tick++
            val h = worlds.map { PlayerLog.stateHash(it.particleCount, it.x, it.y, it.vx, it.vy) }
            if (h[0] != h[1]) { println("GROWTH diverged at tick $tick (рождено $births)"); return false }
            for (i in 0 until serial.particleCount) if (!serial.x[i].isFinite() || !serial.y[i].isFinite()) {
                println("GROWTH: не число у частицы $i на тике $tick"); return false
            }
        }
        return true
    }
    val a = add(growing[0])
    if (!run(30, a)) return false
    val b = add(growing[1])
    if (!run(60, a, b)) return false
    // Недоросшее тело удаляется: его нерождённые рёбра уходят из рабочих списков.
    val bLeft = b.order.size - b.next
    for (w in worlds) w.removeBody(b.id)
    // Уплотнение могло сдвинуть растущее тело: его частицы — от нынешнего начала тела.
    a.first = serial.bodyFirst(a.id)
    while (!a.done) if (!run(10, a)) return false
    if (!run(120)) return false
    val unborn = (0 until serial.particleCount).count { serial.isUnborn(it) }
    if (unborn != 0) { println("GROWTH: остались нерождённые ($unborn)"); return false }
    if (!sameHidden(serial, parallel, "end", scratch)) return false
    println("GROWTH: тело ${a.def.count} клеток выросло из одной, второе удалено недоросшим (не родилось $bLeft), " +
        "$tick тиков, порвано ${serial.state.tornTotal}, один поток = $workers воркеров побитово")
    println("GROWTH birth cost: %.1f мкс на клетку (оба мира), %d рождений".format(birthNs / 1e3 / births, births))

    // ЦЕЛОСТЬ: выросшее под гребком тело против целой копии того же тела в той же сцене.
    // Остаток потерь у растущего — рождения на место, которое под сокращением заняла
    // сползшая в пустоту ткань: новорождённая ложится поверх соседа и гибнет от глубокого
    // контакта (план, раздел 2.1). Выросшее 25.10: 2483 из 2617 без дорастания, 26.10 с
    // дорастанием 2532, с поиском свободного места 2520 (один прогон в сцене со
    // столкновениями — в пределах разброса; рост в одиночку дал вдвое меньше потерь), целое 2617. Порог 95% от целого ловит поломку вроде вынутых из
    // решателя нерождённых связей (было 231) и возврат рывка при рождении (2483).
    val twin = placed.firstOrNull { bodies[it[0]].count == a.def.count && it[0] != growing[0] } ?: return true
    val grown = liveEdges(serial, a.first, a.def.count); val whole = liveEdges(serial, serial.bodyFirst(twin[1]), a.def.count)
    println("GROWTH integrity: живых рёбер выросшего ${grown.first} из ${grown.second}, целой копии ${whole.first} из ${whole.second}")
    if (grown.first < 0.95 * whole.first) { println("GROWTH: выросшее тело разрушено"); return false }
    return true
}

/** Живые рёбра тела [first, first + count) и все его рёбра (кроме ещё нерождённых). */
internal fun liveEdges(w: PhysicsWorld, first: Int, count: Int): Pair<Int, Int> {
    val b = w.state.baked
    var all = 0; var alive = 0
    for (e in 0 until b.edgeCount) {
        val u = b.edgeU(e); if (u < first || u >= first + count) continue
        if (b.edgeUnborn.size > e && b.edgeUnborn[e]) continue
        all++; if (!b.edgeDead[e]) alive++
    }
    return alive to all
}

/**
 * Рабочие буферы проходов: метки обхода, очереди пределов, ключи сортировки широкой
 * фазы, клетки растеризованного отрезка (dda). У одного потока и у воркеров они свои по
 * устройству (у воркеров — свои черновики), каждый проход пишет их до чтения, в будущие
 * тики они не входят.
 */
private val scratch = setOf("contacts.keys.lowKeys", "contacts.lowKeys", "contacts.mark", "contacts.stamp",
    "contacts.ddaX", "contacts.ddaY",
    "localBoundary.bend.blockLayout.limitQueue", "localBoundary.blockLayout.limitQueue",
    "localBoundary.flap.blockLayout.limitQueue")

private fun sameHidden(a: PhysicsWorld, b: PhysicsWorld, label: String, skip: Set<String> = emptySet()): Boolean {
    val sa = stateSnapshot(a.state); val sb = stateSnapshot(b.state)
    val diff = (sa.keys + sb.keys).filter { it !in skip && sa[it] != sb[it] }.sorted()
    if (diff.isNotEmpty()) { println("GROWTH hidden mismatch at $label: $diff"); return false }
    return true
}
