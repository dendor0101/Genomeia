package io.github.some_example_name.physics.lab.stands

import io.github.some_example_name.physics.BodyDefinition
import io.github.some_example_name.physics.lab.io.BodyFile
import io.github.some_example_name.physics.withTempLinks
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ФИГУРА СТЕНДА: клетки на шестиугольной решётке редактора генома, в единицах игры
 * относительно своего центра, — или тело настоящего генома ([genome]).
 *
 * Решётка та же, что у редактора (шаг 0.6, ряды через 0.52, радиус клетки 0.5), и не для
 * красоты: соседи по решётке перекрываются и связаны, а следующие за ними стоят дальше
 * диаметра. Значит, выполняется инвариант редактора «перекрытие = связь», на котором
 * держится исключение ложных контактов внутри тела. Фигура с другой решёткой проверяла бы
 * не ту физику.
 *
 * ДВА НАБОРА КООРДИНАТ. [lx], [ly] — узлы ровной решётки: по ним раскладываются кости и мышцы и
 * выбираются клетки, и у всех вариаций они одни. [x], [y], [r] — поза покоя и радиусы клеток в
 * вариации, которая сейчас действует на потоке (Variation.current): в ней тело заводится в мире
 * и по ней сцена расставляет тела. У ровной решётки это одни и те же числа.
 *
 * Кости и мышцы — пометки клеток. Кость уходит в описание тела (связные куски костных клеток,
 * как у моста игры), мышца — только номер группы: в игре мышца у каждой клетки своя
 * (PhysicsWorld.setCellScale), а группа нужна сценарию, чтобы командовать сразу куском.
 */
class ShapeSpec internal constructor(
    /** Короткое имя латиницей — для номера сценария и имени снимка. */
    val id: String,
    /** Имя для человека. */
    val name: String,
    val lx: DoubleArray,
    val ly: DoubleArray,
    val linkA: IntArray,
    val linkB: IntArray,
    val bone: BooleanArray,
    /** Номер мышечной группы клетки, -1 — не мышца. */
    val muscle: IntArray,
    private val geometries: GeometryCache,
    /** Данные настоящего генома: свои радиусы, треугольники и порядок рождения. null — фигура на решётке. */
    val genome: GenomeData?,
) {
    constructor(id: String, name: String, x: DoubleArray, y: DoubleArray, linkA: IntArray, linkB: IntArray, bone: BooleanArray, muscle: IntArray)
        : this(id, name, x, y, linkA, linkB, bone, muscle, GeometryCache(), null)

    val count: Int get() = lx.size
    val linkCount: Int get() = linkA.size

    /** Есть ли у фигуры вариации: тело генома приходит со своими длинами связей и радиусами. */
    val varies: Boolean get() = genome == null

    /** Соседи клетки по связям: adj[adjStart[k]] .. adj[adjStart[k + 1] - 1]. */
    val adjStart = IntArray(count + 1)
    val adj: IntArray

    init {
        for (k in linkA.indices) { adjStart[linkA[k] + 1]++; adjStart[linkB[k] + 1]++ }
        for (i in 0 until count) adjStart[i + 1] += adjStart[i]
        val fill = adjStart.copyOf()
        adj = IntArray(adjStart[count])
        for (k in linkA.indices) { adj[fill[linkA[k]]++] = linkB[k]; adj[fill[linkB[k]]++] = linkA[k] }
    }

    // --- поза покоя действующей вариации ---

    private val latticeSeed: Long = run {
        var h = count.toLong()
        for (k in 0 until count) h = h * 31 + Math.round(lx[k] * 1000) * 7 + Math.round(ly[k] * 1000)
        h
    }

    private fun geometry(): Geometry {
        val g = genome
        if (g != null) return geometries.get(Variation.BASE) { Geometry(lx, ly, g.radius) }
        val v = Variation.current
        return geometries.get(v) { v.geometry(lx, ly, linkA, linkB, latticeSeed) }
    }

    val x: DoubleArray get() = geometry().x
    val y: DoubleArray get() = geometry().y
    /** Радиусы клеток игры. */
    val r: DoubleArray get() = geometry().r
    val halfWidth: Double get() = geometry().halfWidth
    val halfHeight: Double get() = geometry().halfHeight
    /** Радиус круга вокруг центра фигуры, в который она помещается вместе с радиусами клеток. */
    val reach: Double get() = geometry().reach

    /**
     * Радиус касания клетки в единицах игры — как его считает решатель (BoundaryContacts): у
     * клетки контура это половина её самой длинной связи по контуру, у клетки внутри ткани —
     * ноль (её закрывают соседи, в касаниях она не участвует), у клетки без связей — её радиус на
     * CONTACT_SCALE. Без запаса решателя на сшивание мембраны (CT_SEAL, 5%): так у ровной
     * решётки сумма двух радиусов касания — ровно длина связи. Сверено с решателем в VariationTest.
     */
    /** Средний радиус касания клеток контура — для клеток, которые окажутся на краю лишь на время (росток). */
    val typicalContact: Double get() {
        var sum = 0.0; var count = 0
        for (v in contact) if (v > 0.0) { sum += v; count++ }
        return if (count > 0) sum / count else GAME_LINK / 2
    }

    val contact: DoubleArray get() {
        val g = geometry()
        g.contact?.let { return it }
        val c = DoubleArray(count)
        val faces = linkFaces
        for (e in linkA.indices) {
            if (faces[e] >= 2) continue
            val a = linkA[e]; val b = linkB[e]
            val half = 0.5 * sqrt((g.x[a] - g.x[b]) * (g.x[a] - g.x[b]) + (g.y[a] - g.y[b]) * (g.y[a] - g.y[b]))
            if (half > c[a]) c[a] = half
            if (half > c[b]) c[b] = half
        }
        for (k in 0 until count) if (degree(k) == 0) c[k] = g.r[k] * LONE_CONTACT_SCALE
        g.contact = c
        return c
    }

    /** Клетка на контуре тела: у неё есть радиус касания. */
    fun isOutline(k: Int): Boolean = contact[k] > 0.0

    /**
     * Есть ли у фигуры ткань — хоть один треугольник из трёх взаимно связанных клеток. У нити
     * её нет: среда тормозит такую фигуру втрое слабее (только изотропная часть), и сценам с
     * ней нужно больше времени, чтобы остановиться.
     */
    val hasTissue: Boolean = linkA.indices.any { k ->
        val a = linkA[k]; val b = linkB[k]
        (adjStart[a] until adjStart[a + 1]).any { s -> val c = adj[s]; c != b && (adjStart[b] until adjStart[b + 1]).any { adj[it] == c } }
    }

    /**
     * Треугольники ткани подряд по три клетки (a < b < c): тройки взаимно связанных клеток —
     * так же их собирает описание тела. У тела генома — те, что нашла раскладка игры.
     */
    val triangles: IntArray by lazy {
        genome?.let { return@lazy it.triangles }
        val out = ArrayList<Int>()
        for (k in linkA.indices) {
            val a = minOf(linkA[k], linkB[k]); val b = maxOf(linkA[k], linkB[k])
            for (s in adjStart[b] until adjStart[b + 1]) {
                val c = adj[s]
                if (c > b && linked(a, c)) { out.add(a); out.add(b); out.add(c) }
            }
        }
        out.toIntArray()
    }

    /** Сколько треугольников сходится на связи: меньше двух — связь лежит на контуре тела. */
    private val linkFaces: IntArray by lazy {
        val index = HashMap<Long, Int>(linkCount * 2)
        for (e in linkA.indices) index[pair(linkA[e], linkB[e])] = e
        val faces = IntArray(linkCount)
        val tri = triangles
        var t = 0
        while (t < tri.size) {
            for (s in 0..2) index[pair(tri[t + s], tri[t + (s + 1) % 3])]?.let { faces[it]++ }
            t += 3
        }
        faces
    }

    private fun pair(a: Int, b: Int): Long = minOf(a, b).toLong() * count + maxOf(a, b)

    fun linked(a: Int, b: Int): Boolean {
        for (s in adjStart[a] until adjStart[a + 1]) if (adj[s] == b) return true
        return false
    }

    /**
     * Жёсткая ли фигура по построению: каждая клетка входит в треугольник, а все треугольники
     * сцеплены общими сторонами в один кусок. Такую фигуру нельзя согнуть, не растянув связей,
     * и у неё есть «поза покоя», к которой она обязана вернуться. Нить, фигура с перемычкой в
     * одну клетку или с двумя кусками ткани на общей вершине — шарнир: согнуть её можно даром,
     * и требовать от неё прежнюю позу нельзя (длины связей при этом всё равно проверяются).
     */
    val rigid: Boolean by lazy {
        val tri = triangles
        val tc = tri.size / 3
        if (tc == 0) return@lazy false
        val inTriangle = BooleanArray(count)
        for (v in tri) inTriangle[v] = true
        if (inTriangle.any { !it }) return@lazy false
        val root = IntArray(tc) { it }
        fun find(t: Int): Int { var r = t; while (root[r] != r) { root[r] = root[root[r]]; r = root[r] }; return r }
        val byEdge = HashMap<Long, Int>(tc * 4)
        for (t in 0 until tc) for (e in 0..2) {
            val p = tri[3 * t + e]; val q = tri[3 * t + (e + 1) % 3]
            val key = minOf(p, q).toLong() * count + maxOf(p, q)
            val other = byEdge.putIfAbsent(key, t)
            if (other != null) root[find(t)] = find(other)
        }
        val first = find(0)
        (1 until tc).all { find(it) == first }
    }

    /** Рисунок фигуры буквами — для сообщений проверок: o ткань, # кость, цифра — мышечная группа. */
    fun ascii(): String {
        if (count == 0 || genome != null) return ""
        val minX = lx.min(); val maxY = ly.max()
        val cols = ((lx.max() - minX) / (GAME_LINK / 2)).let { Math.round(it).toInt() } + 1
        val rows = Math.round((maxY - ly.min()) / GAME_ROW).toInt() + 1
        val grid = Array(rows) { CharArray(cols) { ' ' } }
        for (k in 0 until count) {
            val c = Math.round((lx[k] - minX) / (GAME_LINK / 2)).toInt(); val r = Math.round((maxY - ly[k]) / GAME_ROW).toInt()
            grid[r][c] = when { bone[k] -> '#'; muscle[k] >= 0 -> ('0' + muscle[k] % 10); else -> 'o' }
        }
        return grid.joinToString("\n") { String(it).trimEnd() }
    }

    val muscleGroups: Int = (muscle.maxOrNull() ?: -1) + 1
    val boneCount: Int get() = bone.count { it }
    val muscleCount: Int get() = muscle.count { it >= 0 }

    fun degree(k: Int): Int = adjStart[k + 1] - adjStart[k]

    /** Клетки мышечной группы [g]. */
    fun group(g: Int): IntArray = (0 until count).filter { muscle[it] == g }.toIntArray()

    /** Клетка, ближайшая к точке ([px], [py]) фигуры. */
    fun nearest(px: Double, py: Double): Int {
        val x = x; val y = y
        var best = 0; var bestD = Double.MAX_VALUE
        for (k in 0 until count) {
            val d = (x[k] - px) * (x[k] - px) + (y[k] - py) * (y[k] - py)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** Клетка, самая дальняя в направлении ([dx], [dy]): край фигуры с этой стороны. */
    fun extreme(dx: Double, dy: Double): Int {
        val x = x; val y = y
        var best = 0; var bestP = -Double.MAX_VALUE
        for (k in 0 until count) {
            val p = x[k] * dx + y[k] * dy
            if (p > bestP) { bestP = p; best = k }
        }
        return best
    }

    /** Связные куски костных клеток — как их соберёт описание тела (BodyFile.build). */
    fun boneClusters(): List<IntArray> = components { bone[it] }

    private fun components(flag: (Int) -> Boolean): List<IntArray> {
        val seen = BooleanArray(count)
        val result = ArrayList<IntArray>()
        val queue = IntArray(count)
        for (start in 0 until count) {
            if (!flag(start) || seen[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; seen[start] = true
            while (head < tail) {
                val v = queue[head++]
                for (s in adjStart[v] until adjStart[v + 1]) {
                    val u = adj[s]
                    if (flag(u) && !seen[u]) { seen[u] = true; queue[tail++] = u }
                }
            }
            result.add(queue.copyOf(tail))
        }
        return result
    }

    private fun derived(newId: String, newName: String, bones: BooleanArray, groups: IntArray, data: GenomeData? = genome) =
        ShapeSpec(newId, newName, lx, ly, linkA, linkB, bones, groups, geometries, data)

    /**
     * Та же фигура с костями там, где [pick] вернул true. Куски меньше трёх клеток снимаются:
     * решатель такие кости отбрасывает (кластер shape matching — от трёх клеток), и пометка
     * без кости только путала бы рисунок. [pick] видит узлы ровной решётки, какая бы вариация
     * ни действовала: раскладка костей у всех вариаций одна.
     */
    fun withBones(tagId: String, tagName: String, pick: (x: Double, y: Double, k: Int) -> Boolean): ShapeSpec {
        val mask = Variation.use(Variation.BASE) { BooleanArray(count) { pick(lx[it], ly[it], it) } }
        val probe = derived(id, name, mask, muscle)
        for (cluster in probe.boneClusters()) if (cluster.size < 3) for (k in cluster) mask[k] = false
        val groups = IntArray(count) { if (mask[it]) -1 else muscle[it] }
        return derived("$id-$tagId", "$name, $tagName", mask, groups)
    }

    /** Та же фигура с мышечными группами: [group] возвращает номер группы или -1. Кость мышцей не бывает. */
    fun withMuscles(tagId: String, tagName: String, group: (x: Double, y: Double, k: Int) -> Int): ShapeSpec {
        val groups = Variation.use(Variation.BASE) { IntArray(count) { if (bone[it]) -1 else group(lx[it], ly[it], it) } }
        return derived("$id-$tagId", "$name, $tagName", bone, groups)
    }

    fun renamed(newId: String, newName: String) = derived(newId, newName, bone, muscle)

    /** То же тело, где кости заменены обычной тканью: видно, виноваты ли в поломке кости. */
    fun withoutBones(): ShapeSpec =
        derived("$id-nobone", "$name, без костей", BooleanArray(count), muscle, genome?.withoutBones())

    /**
     * Порядок рождения от клетки [seed]: обход связей в ширину. order[0] = seed; parent[k] —
     * клетка, от которой рождается k (у seed — -1); depth[k] — слой обхода. Так растёт и тело
     * игры: новая клетка появляется делением уже живой соседки. У тела генома от его зиготы —
     * порядок самих стадий генома: кто от кого и на какой стадии родился и когда стал костью.
     */
    fun growthFrom(seed: Int): GrowthOrder {
        val g = genome
        if (g != null && seed == g.zygote) return g.growthOrder()
        val order = IntArray(count); val parent = IntArray(count) { -1 }; val depth = IntArray(count) { -1 }
        var head = 0; var tail = 0
        order[tail++] = seed; depth[seed] = 0
        while (head < tail) {
            val v = order[head++]
            for (s in adjStart[v] until adjStart[v + 1]) {
                val u = adj[s]
                if (depth[u] < 0) { depth[u] = depth[v] + 1; parent[u] = v; order[tail++] = u }
            }
        }
        check(tail == count) { "фигура $id не связна: рост от клетки $seed достал $tail из $count" }
        return GrowthOrder(order, parent, depth, null)
    }

    /**
     * Описание тела библиотеки: фигура повёрнута на [angle] и поставлена центром в ([cx], [cy])
     * игры, в позе действующей вариации. Фигура на решётке собирается тем же кодом, что тела
     * стенда из файла (BodyFile.build): треугольники — тройки взаимно связанных клеток, кости —
     * связные куски. Тело генома — так же, как его собирает мост игры (LayoutBodies в :core):
     * треугольники раскладки, кости — куски по всем связям, включая связи поперёк граней.
     * Мышц-кластеров нет, как у игры.
     *
     * [frame] — система отсчёта сцены (Frame.kt): каждая клетка ставится в мир через неё. При
     * отражении треугольники сами выходят в нужной ориентации — она берётся по положениям клеток.
     */
    fun definition(cx: Double, cy: Double, angle: Double = 0.0, frame: Frame = Frame.SCENE): BodyDefinition {
        val k = PHYSICS_PER_GAME
        val g = geometry()
        val cs = cos(angle); val sn = sin(angle)
        val px: FloatArray; val py: FloatArray
        if (frame.still) {
            px = FloatArray(count) { ((cx + g.x[it] * cs - g.y[it] * sn) * k).toFloat() }
            py = FloatArray(count) { ((cy + g.x[it] * sn + g.y[it] * cs) * k).toFloat() }
        } else {
            px = FloatArray(count) { (frame.x(cx + g.x[it] * cs - g.y[it] * sn, cy + g.x[it] * sn + g.y[it] * cs) * k).toFloat() }
            py = FloatArray(count) { (frame.y(cx + g.x[it] * cs - g.y[it] * sn, cy + g.x[it] * sn + g.y[it] * cs) * k).toFloat() }
        }
        val r = FloatArray(count) { (g.r[it] * k).toFloat() }
        val data = genome ?: return BodyFile.build(px, py, r, bone.copyOf(), BooleanArray(count), linkA.toList(), linkB.toList(), k.toFloat())
        val tri = data.triangles
        val ta = IntArray(tri.size / 3); val tb = IntArray(tri.size / 3); val tc = IntArray(tri.size / 3); val area = FloatArray(tri.size / 3)
        var t = 0
        for (i in 0 until tri.size / 3) {
            val a = tri[3 * i]; var b = tri[3 * i + 1]; var c = tri[3 * i + 2]
            var a2 = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
            if (a2 < 0f) { val s = b; b = c; c = s; a2 = -a2 }
            // Тройка, выродившаяся в ноль при округлении, пропускается — так же, как у моста игры.
            if (a2 < 1e-12f) continue
            ta[t] = a; tb[t] = b; tc[t] = c; area[t] = a2; t++
        }
        // Связи поперёк граней (`X` файла тела) — временными, как их отдаёт физике мост игры (LayoutBodies, находка 32).
        val own = HashSet<Long>(linkCount * 2)
        for (s in 0 until linkCount) own.add(pairKey(linkA[s], linkB[s]))
        val xa = ArrayList<Int>(); val xb = ArrayList<Int>()
        for (s in data.allLinkA.indices) if (pairKey(data.allLinkA[s], data.allLinkB[s]) !in own) { xa.add(data.allLinkA[s]); xb.add(data.allLinkB[s]) }
        return BodyDefinition(
            x = px, y = py, radius = r, isBone = bone.copyOf(), isMuscle = BooleanArray(count),
            linkA = linkA.copyOf(), linkB = linkB.copyOf(),
            triA = ta.copyOf(t), triB = tb.copyOf(t), triC = tc.copyOf(t), triRestArea2 = area.copyOf(t),
            boneClusters = data.boneClusters(bone), muscleClusters = emptyArray(), scale = k.toFloat(),
        ).withTempLinks(xa.toIntArray(), xb.toIntArray())
    }

    private fun pairKey(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

    companion object {
        /** Радиус касания клетки без связей — доля её радиуса, как CONTACT_SCALE решателя. */
        const val LONE_CONTACT_SCALE = 0.6
    }
}

/**
 * Порядок рождения клеток тела. [boneStage] — слой, с которого клетка становится костью (-1 — не
 * кость), или null: кость включается сразу, как клетка родилась.
 */
class GrowthOrder(val order: IntArray, val parent: IntArray, val depth: IntArray, val boneStage: IntArray?)

/** Сборка фигур на решётке. */
object Lattice {
    private fun key(c: Int, r: Int): Long = (c.toLong() shl 32) xor (r.toLong() and 0xffffffffL)

    // Соседи клетки (столбец, ряд): нечётный ряд сдвинут на полшага вправо.
    private val EVEN = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(-1, 1), intArrayOf(0, 1))
    private val ODD = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(1, -1), intArrayOf(0, 1), intArrayOf(1, 1))
    private fun around(r: Int) = if (r and 1 == 0) EVEN else ODD

    fun cellX(c: Int, r: Int): Double = c * GAME_LINK + if (r and 1 != 0) GAME_LINK / 2 else 0.0
    fun cellY(r: Int): Double = r * GAME_ROW

    /**
     * Фигура из клеток решётки, для которых [inside] вернул true; [reach] — на сколько единиц
     * игры от начала решётки смотреть. Остаётся наибольший связный кусок, и он ставится
     * серединой своей рамки в ноль: у полумесяца или подковы начало решётки лежит не в середине
     * фигуры, и полуразмеры, посчитанные от него, врали бы (15.11: тела на «лёгком касании»
     * не долетали друг до друга). [prune] убирает клетки с
     * одной связью: висящая на нитке клетка не ткань, и в «квадрате» или «круге» её быть не должно.
     */
    fun shape(id: String, name: String, reach: Double, prune: Boolean = true, inside: (x: Double, y: Double) -> Boolean): ShapeSpec {
        val rows = ceil(reach / GAME_ROW).toInt() + 1
        val cols = ceil(reach / GAME_LINK).toInt() + 1
        val cells = ArrayList<IntArray>()
        for (r in -rows..rows) for (c in -cols..cols) if (inside(cellX(c, r), cellY(r))) cells.add(intArrayOf(c, r))
        return fromCells(id, name, cells, prune, recenter = true)
    }

    /** Прямоугольник [cols] × [rows] клеток: ряды решётки подряд. Центр — середина рамки. */
    fun grid(id: String, name: String, cols: Int, rows: Int): ShapeSpec {
        val cells = ArrayList<IntArray>(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) cells.add(intArrayOf(c, r))
        return fromCells(id, name, cells, prune = false, recenter = true)
    }

    fun fromCells(id: String, name: String, cells: List<IntArray>, prune: Boolean, recenter: Boolean): ShapeSpec {
        var alive = HashMap<Long, IntArray>(cells.size * 2)
        for (cell in cells) alive[key(cell[0], cell[1])] = cell
        fun degree(cell: IntArray): Int = around(cell[1]).count { alive.containsKey(key(cell[0] + it[0], cell[1] + it[1])) }
        if (prune) {
            while (true) {
                val weak = alive.values.filter { degree(it) < 2 }
                if (weak.isEmpty()) break
                for (cell in weak) alive.remove(key(cell[0], cell[1]))
            }
        }
        // Наибольший связный кусок: обрезанная предикатом фигура может распасться.
        val seen = HashSet<Long>()
        var best: List<IntArray> = emptyList()
        for (start in alive.values.sortedWith(compareBy({ it[1] }, { it[0] }))) {
            if (!seen.add(key(start[0], start[1]))) continue
            val piece = ArrayList<IntArray>()
            val queue = ArrayDeque<IntArray>()
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                piece.add(cell)
                for (d in around(cell[1])) {
                    val k = key(cell[0] + d[0], cell[1] + d[1])
                    val next = alive[k] ?: continue
                    if (seen.add(k)) queue.addLast(next)
                }
            }
            if (piece.size > best.size) best = piece
        }
        // Порядок клеток — по рядам: соседние клетки рядом и по номеру, лента связей узкая.
        val sorted = best.sortedWith(compareBy({ it[1] }, { it[0] }))
        val index = HashMap<Long, Int>(sorted.size * 2)
        sorted.forEachIndexed { i, cell -> index[key(cell[0], cell[1])] = i }
        val x = DoubleArray(sorted.size) { cellX(sorted[it][0], sorted[it][1]) }
        val y = DoubleArray(sorted.size) { cellY(sorted[it][1]) }
        if (recenter && sorted.isNotEmpty()) {
            val cx = (x.min() + x.max()) / 2; val cy = (y.min() + y.max()) / 2
            for (i in x.indices) { x[i] -= cx; y[i] -= cy }
        }
        val la = ArrayList<Int>(); val lb = ArrayList<Int>()
        sorted.forEachIndexed { i, cell ->
            for (d in around(cell[1])) {
                val j = index[key(cell[0] + d[0], cell[1] + d[1])] ?: continue
                if (j > i) { la.add(i); lb.add(j) }
            }
        }
        return ShapeSpec(id, name, x, y, la.toIntArray(), lb.toIntArray(), BooleanArray(sorted.size), IntArray(sorted.size) { -1 })
    }
}
