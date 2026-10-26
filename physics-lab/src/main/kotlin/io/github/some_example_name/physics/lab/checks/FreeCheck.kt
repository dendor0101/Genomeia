package io.github.some_example_name.physics.lab.checks

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.SceneFile
import io.github.some_example_name.physics.lab.session.DemoSettings
import java.util.Locale

/**
 * СВОБОДНЫЕ ЧАСТИЦЫ (этап A3). Три части.
 *
 * 1. Оракул: свободные клетки сцены (последнее тело — клетки без связей), заданные в
 *    описании, и те же клетки через addFree — те же места, радиусы и массы — побитово один
 *    мир под гребком.
 * 2. Поток субстанций: частицы появляются и исчезают каждый тик (зерно фиксировано), пул
 *    растёт дальше первого блока, посреди прогона удаляется организм и мир уплотняется.
 *    Один поток = N воркеров побитово на каждом тике; полноту пар — CT_BPCHECK=1. Живая
 *    частица обязана иметь контакт, убранная — нет.
 * 3. Сброс: после потока все слоты снова нерождённые, живых свободных нет.
 *
 * Запуск: FreeCheck <сцена.scene> [воркеров]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val bodies = SceneFile.loadBodies(args.getOrElse(0) { "docs/physics/scenes/append-small.scene" })
    val workers = args.getOrNull(1)?.toIntOrNull() ?: 7
    val free = bodies.last()
    require(free.linkCount == 0 && free.count > 1) { "последнее тело сцены — свободные клетки" }
    val config = PhysicsConfig(DemoSettings.fromEnvironment().snapshot().toMutableMap().also { it["RB_PARALLEL_MIN"] = "0" })
    val scale = WorldScale.of(bodies[0])
    val ok = oracle(bodies, config, scale) && stream(bodies.dropLast(1), config, scale, workers)
    println(if (ok) "FREE OK" else "FREE FAILED")
    if (!ok) kotlin.system.exitProcess(1)
}

private fun oracle(bodies: List<BodyDefinition>, config: PhysicsConfig, scale: WorldScale): Boolean {
    fun world() = PhysicsWorld(config, workers = null, scale = scale).apply { state.quiet = true; sleepOn = true }
    val inDef = world(); val viaApi = world()
    for (b in bodies) inDef.addBody(b)
    for (b in bodies.dropLast(1)) viaApi.addBody(b)
    val free = bodies.last()
    val ids = IntArray(free.count) { viaApi.addFree(free.x[it].toDouble(), free.y[it].toDouble(), free.radius[it].toDouble()) }
    val common = inDef.particleCount
    if (viaApi.freeParticle(ids[0]) != common - free.count) { println("FREE oracle: слоты пула не на месте свободных клеток"); return false }
    for (tick in 1..300) {
        val on = if (tick % 40 < 20) 1.0 else 0.0
        for (w in listOf(inDef, viaApi)) { w.setAllMuscleTargets(on); w.step() }
        if (hashOf(inDef, common) != hashOf(viaApi, common)) { println("FREE oracle: разошлись на тике $tick"); return false }
    }
    println("FREE oracle: ${free.count} свободных клеток через addFree ≡ клетки в описании, 300 тиков побитово")
    return true
}

private fun hashOf(w: PhysicsWorld, count: Int): Long {
    var h = 1125899906842597L
    fun mix(v: Long) { h = (h xor v) * -7046029254386353131L }
    for (i in 0 until count) { mix(w.x[i].toRawBits()); mix(w.y[i].toRawBits()); mix(w.vx[i].toRawBits()); mix(w.vy[i].toRawBits()) }
    return h
}

private fun stream(bodies: List<BodyDefinition>, config: PhysicsConfig, scale: WorldScale, workers: Int): Boolean {
    val serial = PhysicsWorld(config, workers = null, scale = scale)
    val parallel = PhysicsWorld(config, workers = PhysicsWorkers.shared(workers), scale = scale)
    val worlds = listOf(serial, parallel)
    for (w in worlds) { w.state.quiet = true; w.sleepOn = true }
    val ids = IntArray(bodies.size)
    for (k in bodies.indices) for (w in worlds) ids[k] = w.addBody(bodies[k])
    var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (i in 0 until serial.particleCount) {
        minX = minOf(minX, serial.x[i]); maxX = maxOf(maxX, serial.x[i]); minY = minOf(minY, serial.y[i]); maxY = maxOf(maxY, serial.y[i])
    }
    val r0 = 0.5 * serial.meanLinkLength
    val rnd = java.util.Random(20261026L)
    val alive = ArrayList<Int>()
    var added = 0; var removed = 0; var peak = 0; var compacted = false
    var killedNow = 0; var killedPeak = 0
    for (tick in 1..600) {
        // Поток: до 6 новых частиц за тик, пока занятых меньше 700; каждая исчезает с
        // вероятностью 0.5% за тик — равновесие выше первого блока пула, пул растёт.
        // Решения — по зерну, одинаково для обоих миров.
        val spawn = if (alive.size < 700) rnd.nextInt(7) else 0
        repeat(spawn) {
            val x = minX + rnd.nextDouble() * (maxX - minX); val y = minY + rnd.nextDouble() * (maxY - minY)
            val r = r0 * (0.6 + 0.8 * rnd.nextDouble())
            val vx = (rnd.nextDouble() - 0.5) * 0.2; val vy = (rnd.nextDouble() - 0.5) * 0.2
            var id = -1
            for (w in worlds) id = w.addFree(x, y, r, vx, vy)
            alive.add(id); added++
        }
        var k = 0
        while (k < alive.size) {
            if (rnd.nextInt(200) == 0) { for (w in worlds) w.removeFree(alive[k]); alive.removeAt(k); removed++ } else k++
        }
        peak = maxOf(peak, alive.size)
        if (tick == 300) {
            // Удаление организма посреди потока: при четверти удалённых мир уплотняется сам.
            val big = bodies.indices.maxBy { bodies[it].count }
            for (w in worlds) { w.removeBody(ids[big]); if (w.removedParticles > 0) w.compact() }
            compacted = true
        }
        val on = if (tick % 40 < 20) 1.0 else 0.0
        for (w in worlds) { w.setAllMuscleTargets(on); w.step() }
        if (hashOf(serial, serial.particleCount) != hashOf(parallel, parallel.particleCount)) {
            println("FREE stream: разошлись на тике $tick (живых ${alive.size})"); return false
        }
        val ct = serial.state.contacts!!
        killedNow = 0
        for (id in alive) {
            val alivePhys = serial.isFreeAlive(id)
            if (!alivePhys) { killedNow++; continue }
            if (ct.contactRadiusOf(serial.freeParticle(id)) <= 0.0) {
                println("FREE stream: у живой частицы $id нет контакта на тике $tick"); return false
            }
        }
        killedPeak = maxOf(killedPeak, killedNow)
        if (serial.freeCount != alive.size) { println("FREE stream: freeCount ${serial.freeCount} против ${alive.size}"); return false }
    }
    var deadWithContact = 0
    val ct = serial.state.contacts!!
    for (i in 0 until serial.particleCount) if (serial.isUnborn(i) && ct.contactRadiusOf(i) > 0.0) deadWithContact++
    if (deadWithContact > 0) { println("FREE stream: $deadWithContact убранных слотов с контактом"); return false }
    println("FREE stream: добавлено $added, убрано $removed, занятых в пике $peak, частиц мира ${serial.particleCount}, " +
        "уплотнение=${compacted}, один поток = $workers воркеров побитово, порвано ${serial.state.tornTotal}, " +
        "убитых физикой среди занятых до $killedPeak (рождены на месте клетки)")
    serial.reset()
    val bornSlots = (0 until serial.particleCount).count { !serial.isUnborn(it) }
    val bodyCells = bodies.indices.filter { serial.isBodyAlive(ids[it]) }.sumOf { serial.bodyCount(ids[it]) }
    if (serial.freeCount != 0 || bornSlots != bodyCells) {
        println("FREE reset: живых свободных ${serial.freeCount}, рождённых частиц $bornSlots при клетках тел $bodyCells"); return false
    }
    println("FREE reset: слоты пула снова нерождённые")
    return true
}
