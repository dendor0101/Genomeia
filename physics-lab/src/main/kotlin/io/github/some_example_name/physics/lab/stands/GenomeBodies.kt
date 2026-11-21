package io.github.some_example_name.physics.lab.stands

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.sqrt

/**
 * ДАННЫЕ ТЕЛА НАСТОЯЩЕГО ГЕНОМА сверх того, что есть у любой фигуры: свои радиусы клеток,
 * треугольники раскладки игры, все связи (включая связи поперёк граней: физика их не получает,
 * но кость они скрепляют) и порядок рождения по стадиям генома.
 *
 * Берётся из файла, который выгружает игра (StandGenomeExport в :lwjgl3): стенд не выводит
 * структуру тела сам, а получает ровно то, из чего мост игры заводит это тело в физике.
 */
class GenomeData(
    /** Имя файла тела без расширения — латиницей, идёт в номер сцены. */
    val slug: String,
    /** Имя генома, как его назвал игрок. */
    val title: String,
    val source: String,
    val radius: DoubleArray,
    /** Тип взрослой клетки по ключу игры: muscle, bone, suction_cup, fin… */
    val types: Array<String>,
    val triangles: IntArray,
    val allLinkA: IntArray,
    val allLinkB: IntArray,
    /** Стадия генома, на которой клетка родилась (0 — зигота), и от кого. */
    val stageOf: IntArray,
    val parentOf: IntArray,
    /** Стадия, на которой клетка стала костью; -1 — не кость. */
    val boneStage: IntArray,
    /** Клетки в порядке рождения; первая — зигота. */
    val birthOrder: IntArray,
) {
    val zygote: Int get() = birthOrder[0]
    val stages: Int get() = stageOf.max()

    fun growthOrder() = GrowthOrder(birthOrder, parentOf, stageOf, boneStage)

    fun withoutBones() = GenomeData(slug, title, source, radius, types, triangles, allLinkA, allLinkB, stageOf, parentOf, IntArray(stageOf.size) { -1 }, birthOrder)

    /** Связные куски костных клеток по всем связям раскладки — как LayoutBodies.boneClusters моста игры. */
    fun boneClusters(bone: BooleanArray): Array<IntArray> {
        val n = bone.size
        if (bone.none { it }) return emptyArray()
        val start = IntArray(n + 1)
        for (s in allLinkA.indices) if (bone[allLinkA[s]] && bone[allLinkB[s]]) { start[allLinkA[s] + 1]++; start[allLinkB[s] + 1]++ }
        for (v in 0 until n) start[v + 1] += start[v]
        val other = IntArray(start[n]); val fill = start.copyOf()
        for (s in allLinkA.indices) {
            val a = allLinkA[s]; val b = allLinkB[s]
            if (!bone[a] || !bone[b]) continue
            other[fill[a]++] = b; other[fill[b]++] = a
        }
        val seen = BooleanArray(n); val queue = IntArray(n)
        val result = ArrayList<IntArray>()
        for (from in 0 until n) {
            if (!bone[from] || seen[from]) continue
            var head = 0; var tail = 0
            queue[tail++] = from; seen[from] = true
            while (head < tail) {
                val v = queue[head++]
                for (e in start[v] until start[v + 1]) { val u = other[e]; if (!seen[u]) { seen[u] = true; queue[tail++] = u } }
            }
            result.add(queue.copyOf(tail))
        }
        return result.toTypedArray()
    }
}

/** Запись списка тел: что это за геном и насколько он велик — по ней семейства выбирают, какие сцены ему ставить. */
class GenomeEntry(val slug: String, val title: String, val cells: Int, val links: Int, val bones: Int, val muscles: Int, val cups: Int, val stages: Int, val source: String) {
    /** Размер тела словом — для названий сцен. */
    val size: String get() = when { cells < 60 -> "малое"; cells < 400 -> "среднее"; cells < 1500 -> "большое"; else -> "огромное" }
}

/**
 * БИБЛИОТЕКА ТЕЛ ГЕНОМОВ: файлы *.body из ресурсов stand-genomes и их список index.tsv.
 * Список пишет StandGenomeIndex (ниже) после выгрузки; тело читается при первом обращении.
 */
object GenomeBodies {
    private const val DIR = "stand-genomes"

    val entries: List<GenomeEntry> by lazy {
        val stream = GenomeBodies::class.java.classLoader.getResourceAsStream("$DIR/index.tsv") ?: return@lazy emptyList()
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            r.readLines().drop(1).mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 9) null
                else GenomeEntry(f[0], f[1], f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7].toInt(), f[8])
            }
        }
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, ShapeSpec>()

    fun entry(slug: String): GenomeEntry? = entries.firstOrNull { it.slug == slug }

    /** Тело генома фигурой стенда: мышцы — одна группа, кости — как у взрослого тела в игре. */
    fun shape(slug: String): ShapeSpec = cache.getOrPut(slug) {
        val stream = GenomeBodies::class.java.classLoader.getResourceAsStream("$DIR/$slug.body") ?: error("нет тела генома $slug")
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { parse(slug, it.readLines()) }
    }

    /** Разбор файла тела (формат — StandGenomeExport). Отдельно от ресурсов: тесты читают текст напрямую. */
    fun parse(slug: String, lines: List<String>): ShapeSpec {
        var title = slug; var source = ""
        val px = ArrayList<Double>(); val py = ArrayList<Double>(); val pr = ArrayList<Double>()
        val types = ArrayList<String>(); val bone = ArrayList<Boolean>()
        val la = ArrayList<Int>(); val lb = ArrayList<Int>(); val xa = ArrayList<Int>(); val xb = ArrayList<Int>()
        val tri = ArrayList<Int>()
        val births = ArrayList<IntArray>(); val bones = ArrayList<IntArray>()
        for (raw in lines) {
            if (raw.isEmpty() || raw[0] == '#') continue
            if (raw.startsWith("name\t")) { title = raw.substring(5); continue }
            if (raw.startsWith("source\t")) { source = raw.substring(7); continue }
            val f = raw.split(' ')
            when (f[0]) {
                "P" -> { px.add(f[2].toDouble()); py.add(f[3].toDouble()); pr.add(f[4].toDouble()); types.add(f[5]); bone.add(f[6] == "1") }
                "L" -> { la.add(f[1].toInt()); lb.add(f[2].toInt()) }
                "X" -> { xa.add(f[1].toInt()); xb.add(f[2].toInt()) }
                "T" -> { tri.add(f[1].toInt()); tri.add(f[2].toInt()); tri.add(f[3].toInt()) }
                "G" -> births.add(intArrayOf(f[1].toInt(), f[2].toInt(), f[3].toInt()))
                "O" -> bones.add(intArrayOf(f[1].toInt(), f[2].toInt()))
            }
        }
        val n = px.size
        require(n > 0) { "в теле $slug нет клеток" }
        // Середина рамки тела — в ноль, как у фигур на решётке: полуразмеры тогда настоящие.
        val cx = (px.min() + px.max()) / 2; val cy = (py.min() + py.max()) / 2
        val x = DoubleArray(n) { px[it] - cx }; val y = DoubleArray(n) { py[it] - cy }

        // Порядок рождения: зигота — клетка, которую никто не рожал; дальше — по стадиям, как в файле.
        val stageOf = IntArray(n) { -1 }; val parentOf = IntArray(n) { -1 }
        val order = ArrayList<Int>(n)
        val bornLater = BooleanArray(n)
        for (b in births) bornLater[b[1]] = true
        val zygote = (0 until n).firstOrNull { !bornLater[it] } ?: 0
        stageOf[zygote] = 0; order.add(zygote)
        for (b in births) {
            val cell = b[1]
            if (stageOf[cell] >= 0) continue
            stageOf[cell] = b[0]; parentOf[cell] = b[2]; order.add(cell)
        }
        // Клетка без записи о рождении (в геноме её рожает действие, которого выгрузка не нашла):
        // рождается после всех, от первой соседки, — чтобы тело всё же выросло целиком.
        var last = stageOf.max()
        var orphans = (0 until n).filter { stageOf[it] < 0 }
        while (orphans.isNotEmpty()) {
            last++
            var progressed = false
            for (cell in orphans) {
                var parent = -1
                for (s in la.indices) {
                    val other = if (la[s] == cell) lb[s] else if (lb[s] == cell) la[s] else -1
                    if (other >= 0 && stageOf[other] in 0 until last) { parent = other; break }
                }
                if (parent < 0) continue
                stageOf[cell] = last; parentOf[cell] = parent; order.add(cell); progressed = true
            }
            if (!progressed) { for (cell in orphans) if (stageOf[cell] < 0) { stageOf[cell] = last; order.add(cell) } }
            orphans = (0 until n).filter { stageOf[it] < 0 }
        }
        val boneStage = IntArray(n) { -1 }
        for (b in bones) if (bone[b[1]] && boneStage[b[1]] < 0) boneStage[b[1]] = maxOf(b[0], stageOf[b[1]])
        // Костная клетка без записи о мутации — кость с рождения.
        for (k in 0 until n) if (bone[k] && boneStage[k] < 0) boneStage[k] = stageOf[k]

        val data = GenomeData(slug, title, source, pr.toDoubleArray(), types.toTypedArray(), tri.toIntArray(),
            (la + xa).toIntArray(), (lb + xb).toIntArray(), stageOf, parentOf, boneStage, order.toIntArray())
        val muscle = IntArray(n) { if (types[it] == "muscle" && !bone[it]) 0 else -1 }
        return ShapeSpec(slug, title, x, y, la.toIntArray(), lb.toIntArray(), bone.toBooleanArray(), muscle, GeometryCache(), data)
    }
}

/** Клетки-присоски тела генома (SuctionCup игры): в сценах с опорой они держатся за дно. */
fun ShapeSpec.cups(): IntArray = genome?.let { g -> (0 until count).filter { g.types[it] == "suction_cup" }.toIntArray() } ?: IntArray(0)

/**
 * Мышцы тела, разложенные на две группы по сторонам от его длинной оси, — чтобы тело генома можно
 * было гнуть, как гнёт себя организм: одна сторона тянет, другая отпускает. Ось — главная ось
 * разброса клеток.
 */
fun ShapeSpec.muscleSides(): ShapeSpec {
    val axis = longAxis()
    return withMuscleGroups("sides", "мышцы по сторонам от оси") { k -> if (lx[k] * -axis[1] + ly[k] * axis[0] >= 0) 0 else 1 }
}

/** Мышцы тела, разложенные на [n] групп вдоль его длинной оси, — для бегущей волны. */
fun ShapeSpec.muscleAlong(n: Int): ShapeSpec {
    val axis = longAxis()
    var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
    for (k in 0 until count) { val p = lx[k] * axis[0] + ly[k] * axis[1]; if (p < lo) lo = p; if (p > hi) hi = p }
    return withMuscleGroups("along$n", "$n мышечных групп вдоль оси") { k ->
        (((lx[k] * axis[0] + ly[k] * axis[1] - lo) / (hi - lo + 1e-9)) * n).toInt().coerceIn(0, n - 1)
    }
}

private fun ShapeSpec.withMuscleGroups(tag: String, tagName: String, group: (Int) -> Int): ShapeSpec {
    val was = muscle
    return withMuscles(tag, tagName) { _, _, k -> if (was[k] >= 0) group(k) else -1 }
}

/** Единичный вектор длинной оси тела: направление наибольшего разброса клеток. */
fun ShapeSpec.longAxis(): DoubleArray {
    var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var mx = 0.0; var my = 0.0
    for (k in 0 until count) { mx += lx[k]; my += ly[k] }
    mx /= count; my /= count
    for (k in 0 until count) { val dx = lx[k] - mx; val dy = ly[k] - my; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
    // Собственный вектор матрицы разброса с большим собственным числом.
    val half = (sxx - syy) / 2
    val root = sqrt(half * half + sxy * sxy)
    var ax = sxy; var ay = root - half
    if (ax * ax + ay * ay < 1e-12) { ax = 1.0; ay = 0.0 }
    val len = sqrt(ax * ax + ay * ay)
    return doubleArrayOf(ax / len, ay / len)
}

/**
 * Тело генома на промежуточной стадии роста: клетки, родившиеся к тому времени, как выросла доля
 * [share] тела, со связями и треугольниками между ними. Ставится на сцену сразу таким, как
 * любое взрослое тело. Тип клетки — взрослый: кем клетка была на той стадии, выгрузка не хранит.
 * Если по связям физики рождённые клетки распались на куски, остаётся наибольший.
 */
fun ShapeSpec.grownTo(share: Double): ShapeSpec {
    val g = genome ?: return this
    val want = maxOf(3, (count * share).toInt())
    var stage = 0
    val sizes = IntArray(g.stages + 1)
    for (k in 0 until count) sizes[g.stageOf[k]]++
    var have = 0
    while (stage <= g.stages) { have += sizes[stage]; if (have >= want) break; stage++ }
    stage = minOf(stage, g.stages)
    val keep = BooleanArray(count) { g.stageOf[it] <= stage }
    // Наибольший связный кусок по связям физики.
    val comp = IntArray(count) { -1 }
    var best = -1; var bestSize = 0; var comps = 0
    val queue = IntArray(count)
    for (from in 0 until count) {
        if (!keep[from] || comp[from] >= 0) continue
        var head = 0; var tail = 0
        queue[tail++] = from; comp[from] = comps
        while (head < tail) {
            val v = queue[head++]
            for (s in adjStart[v] until adjStart[v + 1]) { val u = adj[s]; if (keep[u] && comp[u] < 0) { comp[u] = comps; queue[tail++] = u } }
        }
        if (tail > bestSize) { bestSize = tail; best = comps }
        comps++
    }
    val index = IntArray(count) { -1 }
    var m = 0
    for (k in 0 until count) if (keep[k] && comp[k] == best) index[k] = m++
    fun pick(a: IntArray): IntArray = IntArray(m).also { out -> for (k in 0 until count) if (index[k] >= 0) out[index[k]] = a[k] }
    fun pairs(a: IntArray, b: IntArray): Pair<IntArray, IntArray> {
        val pa = ArrayList<Int>(); val pb = ArrayList<Int>()
        for (s in a.indices) if (index[a[s]] >= 0 && index[b[s]] >= 0) { pa.add(index[a[s]]); pb.add(index[b[s]]) }
        return pa.toIntArray() to pb.toIntArray()
    }
    val (la, lb) = pairs(linkA, linkB)
    val (aa, ab) = pairs(g.allLinkA, g.allLinkB)
    val tri = ArrayList<Int>()
    var t = 0
    while (t < g.triangles.size) {
        val a = index[g.triangles[t]]; val b = index[g.triangles[t + 1]]; val c = index[g.triangles[t + 2]]
        if (a >= 0 && b >= 0 && c >= 0) { tri.add(a); tri.add(b); tri.add(c) }
        t += 3
    }
    val x = DoubleArray(m).also { out -> for (k in 0 until count) if (index[k] >= 0) out[index[k]] = lx[k] }
    val y = DoubleArray(m).also { out -> for (k in 0 until count) if (index[k] >= 0) out[index[k]] = ly[k] }
    val cx = (x.min() + x.max()) / 2; val cy = (y.min() + y.max()) / 2
    for (k in 0 until m) { x[k] -= cx; y[k] -= cy }
    val boneNow = BooleanArray(m).also { out -> for (k in 0 until count) if (index[k] >= 0) out[index[k]] = bone[k] && g.boneStage[k] in 0..stage }
    val order = g.birthOrder.filter { index[it] >= 0 }.map { index[it] }.toIntArray()
    val parent = IntArray(m) { -1 }.also { out -> for (k in 0 until count) if (index[k] >= 0 && g.parentOf[k] >= 0) out[index[k]] = index[g.parentOf[k]] }
    val percent = Math.round(100.0 * m / count).toInt()
    val data = GenomeData("${g.slug}-p$percent", "${g.title}, выросло $percent%", g.source,
        DoubleArray(m).also { out -> for (k in 0 until count) if (index[k] >= 0) out[index[k]] = g.radius[k] },
        Array(m) { "" }.also { out -> for (k in 0 until count) if (index[k] >= 0) out[index[k]] = g.types[k] },
        tri.toIntArray(), aa, ab, pick(g.stageOf), parent,
        IntArray(m).also { out -> for (k in 0 until count) if (index[k] >= 0) out[index[k]] = if (boneNow[index[k]]) g.boneStage[k] else -1 }, order)
    val groups = pick(muscle).also { for (k in 0 until m) if (boneNow[k]) it[k] = -1 }
    // Связь поперёк граней взрослого тела, на которую к этой стадии ещё не легла ни одна клетка, у растущего тела жива
    // (временная связь роста, находка 32) — у недорослого она обычная связь.
    val own = HashSet<Long>(la.size * 2)
    fun key(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
    for (s in la.indices) own.add(key(la[s], lb[s]))
    val cross = aa.indices.filter { key(aa[it], ab[it]) !in own }.toIntArray()
    val fx = FloatArray(m) { x[it].toFloat() }; val fy = FloatArray(m) { y[it].toFloat() }
    val blockers = io.github.some_example_name.physics.crossingBlockers(fx, fy, aa, ab, cross)
    val free = cross.indices.filter { blockers[it].isEmpty() }.map { cross[it] }
    val fa = la + IntArray(free.size) { aa[free[it]] }; val fb = lb + IntArray(free.size) { ab[free[it]] }
    return ShapeSpec(data.slug, data.title, x, y, fa, fb, boneNow, groups, GeometryCache(), data)
}
