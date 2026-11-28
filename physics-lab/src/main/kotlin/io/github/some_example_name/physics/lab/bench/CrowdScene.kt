package io.github.some_example_name.physics.lab.bench

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.WorldScale
import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt

/**
 * СЦЕНА ТОЛПЫ — только данные, без мира: её строят crowdBench (мир при любом числе воркеров) и lawCostBench
 * (несколько миров с разными настройками шаг в шаг, в том числе на телефоне). Тела без мышц, костей и
 * нейросвязей: треть клеток в телах по 300, треть по 1000, треть по 3000, расставлены рядами в квадратном
 * мире. Каждое тело толкает своя сила, направление которой медленно поворачивается, поэтому тела всё время
 * двигаются и бьются друг о друга.
 *
 * Масштаб и единицы — как в игре (PhysicsUnits): клетка радиуса 0.5, связь 0.6 единиц игры.
 * Случайные числа берутся в прежнем порядке CrowdBench: та же сцена даёт ту же CROWD_HASH.
 *
 * [minWorld] — наименьшая сторона мира в единицах игры. 256 — мир игры: при 5 тысячах клеток тела в нём
 * почти не встречаются (решатель касаний — 0.1% тика). Меньшая сторона ставит тела вплотную — давка:
 * касания и удары всё время, так мерится цена того, что работает только в касаниях (клин).
 *
 * Тот же файл (в double, без [minWorld]) есть в ветке alpha-0.2.4-rust-physics: при слиянии — свести.
 */
internal class CrowdScene(cells: Int, minWorld: Double = 256.0) {
    /** Сторона квадратного мира: границы 0..[side] по обеим осям (не меньше [minWorld] единиц игры). */
    val side: Double

    /** Тела на своих местах в порядке addBody: номер тела в мире = номер в списке. */
    val bodies: List<BodyDefinition>

    private val phase: DoubleArray
    private val omega: DoubleArray

    init {
        val random = Random(SEED)
        val sizes = ArrayList<Int>()
        for (size in SIZES) repeat(max(1, cells / SIZES.size / size)) { sizes.add(size) }
        sizes.shuffle(random)
        val templates = HashMap<Int, BodyDefinition>()
        for (size in SIZES) templates[size] = hexDisc(size)
        val radii = sizes.map { bodyRadius(templates.getValue(it)) + 2 * RADIUS }
        val (side, rows) = shelves(radii, minWorld * PHYSICS_PER_GAME)
        this.side = side
        val placed = ArrayList<BodyDefinition>(sizes.size)
        var y = 0.0
        val freeY = side - rows.sumOf { row -> row.maxOf { 2 * radii[it] } }
        for (row in rows) {
            val height = row.maxOf { 2 * radii[it] }
            y += freeY / (rows.size + 1)
            var x = 0.0
            val freeX = side - row.sumOf { 2 * radii[it] }
            for (k in row) {
                x += freeX / (row.size + 1) + radii[k]
                placed.add(moved(templates.getValue(sizes[k]), x, y + height / 2))
                x += radii[k]
            }
            y += height
        }
        bodies = placed
        // Толчок — после раскладки, как в прежнем CrowdBench: раскладка случайных чисел не берёт.
        phase = DoubleArray(sizes.size) { random.nextDouble() * 2 * Math.PI }
        omega = DoubleArray(sizes.size) { (0.01 + random.nextDouble() * 0.03) * if (random.nextBoolean()) 1 else -1 }
    }

    /** Прибавка к скорости каждой частицы тела [b] в тик [tick] по x; [push] — разгон за тик. */
    fun pushX(b: Int, tick: Int, push: Double): Double = push * StrictMath.cos(phase[b] + omega[b] * tick)

    /** То же по y. */
    fun pushY(b: Int, tick: Int, push: Double): Double = push * StrictMath.sin(phase[b] + omega[b] * tick)

    companion object {
        const val PHYSICS_PER_GAME = 0.048
        const val LINK = 0.6 * PHYSICS_PER_GAME
        const val RADIUS = 0.5 * PHYSICS_PER_GAME
        private const val SEED = 20261109L
        /** Разгон за тик при -Ppush=1, в единицах мира в секунду. Подобран так, чтобы тела не рвались. */
        const val PUSH = 0.012
        private val SIZES = intArrayOf(300, 1000, 3000)

        /** Масштаб мира толпы (WorldScale): связь и площадь клетки игры. */
        val SCALE = WorldScale(LINK.toFloat(), (RADIUS * RADIUS).toFloat())

        /**
         * РЕЖИМ «КАК В ИГРЕ» (23.11.2026). Толпа без мышц идёт путём ядра связей без множителей клеток
         * (solveBlockLinks), которого у организмов в игре нет: там мышца — множитель клетки, и работают
         * solveBlockLinksCells, мягкость и волокно. Чтобы замер (особенно на телефоне) мерил то, что получит
         * игрок, каждой седьмой клетке даётся постоянная команда мышцы 0.9 с волокном вдоль x, каждой пятой —
         * мягкость 1.5. Тела от этого чуть сжимаются и живут иначе: сумма мира своя, не CROWD_HASH.
         * [offset] — номер первой частицы этого мира в общем мире толпы: у мира одного тела (islandBench) клетки
         * получают те же команды, что в общем мире.
         * [patches] — мышцы и мягкая ткань не вперемешку (каждая седьмая и каждая пятая клетка), а сплошными участками
         * по 64 клетки подряд (каждый седьмой и каждый пятый участок): так лежат мышцы настоящих организмов. Разница
         * важна ядру связей: у мышцы свой путь, и когда мышечные связи идут вперемешку с обычными, процессор то и
         * дело ошибается в предсказании перехода (находка 39).
         */
        fun gameLike(world: io.github.some_example_name.physics.PhysicsWorld, offset: Int = 0, patches: Boolean = false) {
            for (i in 0 until world.state.n) {
                val g = if (patches) (offset + i) / 64 else offset + i
                if (g % 7 == 0) { world.setCellScale(i, 0.9f); world.setCellFiber(i, 1f, 0f) }
                if (g % 5 == 1) world.setSoftness(i, 1.5f)
            }
        }

        /** Начало контрольной суммы мира CROWD_HASH. */
        const val HASH_SEED = -3750763034362895579L

        /** Контрольная сумма CROWD_HASH: сырые биты массивов частиц [arrays] до [n]. */
        fun hash(seed: Long, n: Int, vararg arrays: FloatArray): Long {
            var h = seed
            for (a in arrays) for (i in 0 until n) { h = (h xor a[i].toRawBits().toLong()) * 1099511628211L; h = h xor (h ushr 29) }
            return h
        }

        private fun bodyRadius(body: BodyDefinition): Double {
            var r2 = 0f
            for (i in 0 until body.count) r2 = maxOf(r2, body.x[i] * body.x[i] + body.y[i] * body.y[i])
            return sqrt(r2.toDouble())
        }

        /** Ряды тел: сторона мира (не меньше [minSide]) и номера тел по рядам. */
        private fun shelves(radii: List<Double>, minSide: Double): Pair<Double, List<List<Int>>> {
            var side = minSide
            while (true) {
                val rows = ArrayList<List<Int>>()
                var row = ArrayList<Int>()
                var used = 0.0
                for (k in radii.indices) {
                    if (used + 2 * radii[k] > side) { rows.add(row); row = ArrayList(); used = 0.0 }
                    row.add(k); used += 2 * radii[k]
                }
                if (row.isNotEmpty()) rows.add(row)
                if (rows.sumOf { r -> r.maxOf { 2 * radii[it] } } <= side) return side to rows
                side *= 1.05
            }
        }

        private fun moved(body: BodyDefinition, x: Double, y: Double) = BodyDefinition(
            FloatArray(body.count) { body.x[it] + x.toFloat() }, FloatArray(body.count) { body.y[it] + y.toFloat() },
            body.radius, body.isBone, body.isMuscle, body.linkA, body.linkB, body.triA, body.triB, body.triC, body.triRestArea2,
            body.boneClusters, body.muscleClusters, body.scale)

        /** Круг из треугольной решётки около [cells] клеток; порядок частиц — по рядам, лента ~ стороне. */
        private fun hexDisc(cells: Int): BodyDefinition {
            val h = sqrt(3.0) / 2
            val reach = sqrt(cells * h / Math.PI)
            val span = reach.toInt() + 2
            val size = 4 * span + 1
            val slot = IntArray(size * size) { -1 }
            fun key(q: Int, r: Int) = (r + 2 * span) * size + q + 2 * span
            fun inside(q: Int, r: Int): Boolean {
                val px = q + r * 0.5; val py = r * h
                return px * px + py * py <= reach * reach
            }
            val tris = ArrayList<IntArray>()
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            fun cell(q: Int, r: Int): Int {
                if (slot[key(q, r)] < 0) {
                    slot[key(q, r)] = xs.size
                    xs.add(((q + r * 0.5) * LINK).toFloat()); ys.add((r * h * LINK).toFloat())
                }
                return slot[key(q, r)]
            }
            for (r in -span..span) for (q in -2 * span..2 * span) {
                if (r + 1 > span || q + 1 > 2 * span) continue
                if (inside(q, r) && inside(q + 1, r) && inside(q, r + 1)) tris.add(intArrayOf(cell(q, r), cell(q + 1, r), cell(q, r + 1)))
                if (inside(q, r + 1) && inside(q + 1, r) && inside(q + 1, r + 1)) tris.add(intArrayOf(cell(q, r + 1), cell(q + 1, r), cell(q + 1, r + 1)))
            }
            val n = xs.size
            val edges = sortedSetOf<Long>()
            for (t in tris) for (k in 0 until 3) {
                val a = t[k]; val b = t[(k + 1) % 3]
                edges.add(minOf(a, b).toLong() * n + maxOf(a, b))
            }
            val x = FloatArray(n) { xs[it] }; val y = FloatArray(n) { ys[it] }
            val area = FloatArray(tris.size) { k ->
                val t = tris[k]
                (x[t[1]] - x[t[0]]) * (y[t[2]] - y[t[0]]) - (y[t[1]] - y[t[0]]) * (x[t[2]] - x[t[0]])
            }
            return BodyDefinition(x, y, FloatArray(n) { RADIUS.toFloat() }, BooleanArray(n), BooleanArray(n),
                edges.map { (it / n).toInt() }.toIntArray(), edges.map { (it % n).toInt() }.toIntArray(),
                IntArray(tris.size) { tris[it][0] }, IntArray(tris.size) { tris[it][1] }, IntArray(tris.size) { tris[it][2] },
                area, emptyArray(), emptyArray(), PHYSICS_PER_GAME.toFloat())
        }
    }
}
