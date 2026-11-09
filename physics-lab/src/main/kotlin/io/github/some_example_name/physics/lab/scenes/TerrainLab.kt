package io.github.some_example_name.physics.lab.scenes

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.PhysicsConfig
import io.github.some_example_name.physics.PhysicsWorkers
import io.github.some_example_name.physics.PhysicsWorld
import io.github.some_example_name.physics.SOFT_COMPLIANCE
import io.github.some_example_name.physics.WorldScale
import io.github.some_example_name.physics.lab.io.BodyFile
import java.util.Locale
import java.util.Random
import kotlin.math.hypot

/**
 * СТЕНД МЯГКОГО ТЕРРЕЙНА ИЗ ПЛИТОК (04.11). Террейн — частицы на клетках карты, как в игре
 * (WorldTerrainManager: 1.5 клетки карты на клетку мира, радиус 0.5). Весь он одним связным
 * телом не засыпал бы никогда: сон и пробуждение у мира — по организму. Поэтому карта режется на
 * квадраты [TILE]×[TILE] клеток карты, связные куски квадрата — отдельные тела, а соседние
 * частицы разных кусков держат швы (PhysicsWorld.addJoint с порогом пробуждения): спящий сосед
 * для проснувшейся плитки — опора, и будится он только силой через шов. Плитки одной группы
 * касаний (setContactGroup) друг с другом не сталкиваются — шов держат связи. Каждая частица —
 * на мягкой пружине к своему месту (setHome) и с хваткой о дно (setGrip): удар гаснет в ткани,
 * а не уходит волной по швам.
 *
 * Опыты: покой (засыпает ли всё и за сколько), обстрел пулями (сколько плиток проснулось, прошла
 * ли пуля в толщу, порвались ли швы), таран кирпичом (то же для тела). Цена — медиана тика.
 *
 * Запуск: gradlew :physics-lab:terrainLab [-Pgrip=0,0.3,0.6] [-Pworld=64] [-Pworkers=7]
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val grips = args.getOrElse(0) { "0,0.3,0.6" }.split(',').map { it.trim().toFloat() }
    val worldCells = args.getOrElse(1) { "64" }.toInt()
    val workers = args.getOrElse(2) { "0" }.toInt()
    val map = caveMap(worldCells * 3 / 2, worldCells * 3 / 2, seed = 7)
    for (grip in grips) run(map, worldCells, grip, workers)
}

/** Клеток карты в стороне плитки: 8 клеток мира. */
private const val TILE = 12
private const val PHYS = 0.048            // единиц физики на клетку мира игры (PHYSICS_PER_GAME)
private const val R = 0.5 * PHYS          // радиус частицы террейна
private const val CELL = PHYS / 1.5       // шаг клетки карты
/** Мягкость ткани террейна: множитель податливости связей и площадей, как у жира. */
private const val SOFTNESS = 2.5f
/** Податливость пружины к месту: мягче связи — плитка проминается, но держит форму. */
private const val HOME_COMPLIANCE = 4 * SOFT_COMPLIANCE
/** Порог пробуждения соседа через шов: доля длины покоя. */
private const val WAKE_STRAIN = 0.02
/** Разброс частицы по клетке карты, в клетках карты: в игре ±0.375 клетки мира = 1.125 клетки карты в размахе. */
private val JITTER = System.getProperty("terrain.jitter")?.toDouble() ?: 1.125

/** Пещера: клеточный автомат на сиде, рамка карты сплошная, середина вычищена под опыты. */
internal fun caveMap(w: Int, h: Int, seed: Long): Array<BooleanArray> {
    val rnd = Random(seed)
    var m = Array(h) { y -> BooleanArray(w) { x -> x == 0 || y == 0 || x == w - 1 || y == h - 1 || rnd.nextDouble() < 0.47 } }
    repeat(5) {
        val next = Array(h) { BooleanArray(w) }
        for (y in 0 until h) for (x in 0 until w) {
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx !in 0 until w || yy !in 0 until h || m[yy][xx]) c++
            }
            next[y][x] = if (m[y][x]) c >= 4 else c >= 5
        }
        m = next
    }
    // Чистая середина: туда ставятся пули и кирпич.
    val cx = w / 2; val cy = h / 2; val rr = w / 5
    for (y in 0 until h) for (x in 0 until w) if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < rr * rr) m[y][x] = false
    return m
}

/** Соседи клетки карты для связей и швов: вправо, вверх и одна диагональ — сетка из треугольников. */
private val NEIGHBORS = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1))

private class Terrain(val world: PhysicsWorld, val tiles: IntArray, val particles: Int, val joints: IntArray)

private fun buildTerrain(world: PhysicsWorld, map: Array<BooleanArray>, grip: Float): Terrain {
    val h = map.size; val w = map[0].size
    val rnd = Random(11)
    val px = Array(h) { DoubleArray(w) }; val py = Array(h) { DoubleArray(w) }
    for (y in 0 until h) for (x in 0 until w) {
        px[y][x] = (x + 0.5 + (rnd.nextDouble() - 0.5) * JITTER) * CELL
        py[y][x] = (y + 0.5 + (rnd.nextDouble() - 0.5) * JITTER) * CELL
    }
    // Связные куски по плиткам: метка куска и номер частицы в нём.
    val piece = Array(h) { IntArray(w) { -1 } }
    val slot = Array(h) { IntArray(w) { -1 } }
    val pieces = ArrayList<ArrayList<IntArray>>()
    for (y in 0 until h) for (x in 0 until w) {
        if (!map[y][x] || piece[y][x] >= 0) continue
        val tx = x / TILE; val ty = y / TILE
        val id = pieces.size
        val cells = ArrayList<IntArray>()
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(x, y)); piece[y][x] = id
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            slot[c[1]][c[0]] = cells.size; cells.add(c)
            for (d in NEIGHBORS) for (s in intArrayOf(1, -1)) {
                val xx = c[0] + d[0] * s; val yy = c[1] + d[1] * s
                if (xx !in 0 until w || yy !in 0 until h || !map[yy][xx] || piece[yy][xx] >= 0) continue
                if (xx / TILE != tx || yy / TILE != ty) continue
                piece[yy][xx] = id; stack.addLast(intArrayOf(xx, yy))
            }
        }
        pieces.add(cells)
    }
    val firstOf = IntArray(pieces.size)
    var particles = 0
    for ((id, cells) in pieces.withIndex()) {
        val n = cells.size
        val x = FloatArray(n) { px[cells[it][1]][cells[it][0]].toFloat() }
        val y = FloatArray(n) { py[cells[it][1]][cells[it][0]].toFloat() }
        val la = ArrayList<Int>(); val lb = ArrayList<Int>()
        for ((k, c) in cells.withIndex()) for (d in NEIGHBORS) {
            val xx = c[0] + d[0]; val yy = c[1] + d[1]
            if (xx in 0 until w && yy in 0 until h && piece[yy][xx] == id) { la.add(k); lb.add(slot[yy][xx]) }
        }
        val def: BodyDefinition = BodyFile.build(x, y, FloatArray(n) { R.toFloat() }, BooleanArray(n), BooleanArray(n), la, lb, 1f)
        val first = world.bodyFirst(world.addBody(def))
        firstOf[id] = first
        for ((k, c) in cells.withIndex()) {
            val i = first + k
            world.setContactGroup(i, 1)
            world.setHome(i, px[c[1]][c[0]], py[c[1]][c[0]], HOME_COMPLIANCE)
            world.setGrip(i, grip)
            world.setSoftness(i, SOFTNESS)
        }
        particles += n
    }
    // Швы: соседи по той же сетке, но в разных кусках.
    val joints = ArrayList<Int>()
    for (y in 0 until h) for (x in 0 until w) {
        if (!map[y][x]) continue
        for (d in NEIGHBORS) {
            val xx = x + d[0]; val yy = y + d[1]
            if (xx !in 0 until w || yy !in 0 until h || !map[yy][xx] || piece[yy][xx] == piece[y][x]) continue
            val a = firstOf[piece[y][x]] + slot[y][x]; val b = firstOf[piece[yy][xx]] + slot[yy][xx]
            val rest = hypot(px[y][x] - px[yy][xx], py[y][x] - py[yy][xx])
            joints.add(world.addJoint(a, b, rest, SOFT_COMPLIANCE * SOFTNESS, WAKE_STRAIN))
        }
    }
    return Terrain(world, firstOf, particles, joints.toIntArray())
}

private fun awakeTiles(t: Terrain): Int = t.tiles.count { !t.world.isAsleep(it) }

/** Частица в толще: её клетка карты и все восемь соседних — террейн. */
private fun inBulk(map: Array<BooleanArray>, x: Double, y: Double): Boolean {
    val cx = (x / CELL).toInt(); val cy = (y / CELL).toInt()
    for (dy in -1..1) for (dx in -1..1) {
        val xx = cx + dx; val yy = cy + dy
        if (yy !in map.indices || xx !in map[0].indices || !map[yy][xx]) return false
    }
    return true
}

private fun median(a: DoubleArray): Double { val s = a.sorted(); return if (s.isEmpty()) 0.0 else s[s.size / 2] }

private fun run(map: Array<BooleanArray>, worldCells: Int, grip: Float, workers: Int) {
    val world = PhysicsWorld(PhysicsConfig(), workers = if (workers > 0) PhysicsWorkers.shared(workers) else null,
        scale = WorldScale(meanLinkLength = (0.6 * PHYS).toFloat(), meanCellArea = R * R)).apply { state.quiet = true }
    world.sleepOn = true
    val size = map[0].size * CELL
    world.setBounds(0.0, 0.0, size, map.size * CELL)
    val t0 = System.nanoTime()
    val t = buildTerrain(world, map, grip)
    val buildMs = (System.nanoTime() - t0) / 1e6
    println("\n== хватка $grip: мир ${worldCells}×$worldCells, частиц террейна ${t.particles}, плиток ${t.tiles.size}, " +
        "швов ${t.joints.size}, сборка %.0f мс".format(buildMs))

    // 1. Покой.
    var allAsleep = -1
    val rest = DoubleArray(300)
    for (tick in 0 until 300) {
        val s = System.nanoTime(); world.step(); rest[tick] = (System.nanoTime() - s) / 1e6
        if (allAsleep < 0 && awakeTiles(t) == 0) allAsleep = tick + 1
    }
    val restTail = rest.copyOfRange(200, 300)
    println("  покой: всё уснуло к тику $allAsleep; тик во сне %.3f мс (медиана тиков 200–300)".format(median(restTail)))

    // 2. Обстрел: пули из середины во все стороны, по одной в 30 тиков.
    val cx = size / 2; val cy = map.size * CELL / 2
    var maxAwake = 0; var penetrated = 0; var bulletTicks = 0
    val shots = 16
    val times = ArrayList<Double>()
    val bullets = IntArray(shots)
    for (k in 0 until shots) {
        val a = 2 * Math.PI * k / shots
        val speed = 6.0 * 0.6 * PHYS * 30        // 6 связей за тик — почти потолок скорости
        bullets[k] = world.freeParticle(world.addFree(cx, cy, R, kotlin.math.cos(a) * speed, kotlin.math.sin(a) * speed))
        repeat(30) {
            val s = System.nanoTime(); world.step(); times.add((System.nanoTime() - s) / 1e6)
            maxAwake = maxOf(maxAwake, awakeTiles(t)); bulletTicks++
        }
    }
    for (b in bullets) if (!world.state.cellDead[b] && inBulk(map, world.x[b], world.y[b])) penetrated++
    val tornJoints = t.joints.count { !world.isJointLive(it) }
    println("  обстрел $shots пулями: проснулось плиток до $maxAwake из ${t.tiles.size}; пуль в толще $penetrated; " +
        "швов порвано $tornJoints; тик %.3f мс (медиана)".format(median(times.toDoubleArray())))
    var sleepAgain = -1
    for (tick in 0 until 300) { world.step(); if (sleepAgain < 0 && awakeTiles(t) == 0) sleepAgain = tick + 1 }
    println("  после обстрела всё снова уснуло через $sleepAgain тиков")

    // 3. Таран кирпичом: тело из выгрузки (клетки игры → единицы физики), разгон к стенке.
    val raw = BodyFile.parse("body-export-кирпич.txt")
    val bx = raw.x.average(); val by = raw.y.average()
    val brick = BodyFile.build(FloatArray(raw.n) { ((raw.x[it] - bx) * PHYS + cx).toFloat() },
        FloatArray(raw.n) { ((raw.y[it] - by) * PHYS + cy).toFloat() }, FloatArray(raw.n) { (raw.r[it] * PHYS).toFloat() },
        BooleanArray(raw.n), BooleanArray(raw.n), raw.linkA.toList(), raw.linkB.toList(), 1f)
    val body = world.addBody(brick)
    val first = world.bodyFirst(body)
    val count = world.bodyCount(body)
    maxAwake = 0
    times.clear()
    val tornBefore = world.state.tornTotal
    for (tick in 0 until 240) {
        if (tick < 60) for (i in first until first + count) world.setVelocity(i, 3.0 * 0.6 * PHYS * 30, 0.0)
        val s = System.nanoTime(); world.step(); times.add((System.nanoTime() - s) / 1e6)
        maxAwake = maxOf(maxAwake, awakeTiles(t))
    }
    var bulk = 0
    for (i in first until first + count) if (!world.state.cellDead[i] && inBulk(map, world.x[i], world.y[i])) bulk++
    val tornAll = t.joints.count { !world.isJointLive(it) }
    println("  таран кирпичом ($count клеток, 3 связи/тик 60 тиков): проснулось плиток до $maxAwake; клеток кирпича в толще " +
        "$bulk; швов порвано всего $tornAll; разрывов в мире ${world.state.tornTotal - tornBefore}; тик %.3f мс (медиана)"
            .format(median(times.toDoubleArray())))
    var hash = 1125899906842597L
    for (i in 0 until world.particleCount) {
        hash = (hash xor world.x[i].toRawBits()) * -7046029254386353131L
        hash = (hash xor world.y[i].toRawBits()) * -7046029254386353131L
    }
    println("  хэш мира %016x".format(hash))
}

