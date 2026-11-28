package io.github.some_example_name.physics.lab.stands

import java.util.Locale
import kotlin.math.sqrt

/*
 * ВРЕМЕННО, замер 20.11.2026: как растёт тело генома из зиготы (сцены gen-grow). Убрать после разбора.
 * -Dprobe.scene=gen-grow/crayfish/open-p1 — сцена (вариация base); -Dprobe.until=120 — до какого тика печатать по тику.
 * Печатает по тикам: сколько клеток родилось, самую растянутую живую связь (длина / длина покоя-цели) и треугольник
 * (площадь / цель), а у новорождённых, родившихся растянутыми, — родителя и худшую связь при рождении.
 * -Dprobe.cells=78,83,86 -Dprobe.at=13 — на тике 13 взрослое и нынешнее положение этих клеток (в связях, от первой из
 * списка), стадия, родитель и тик рождения. Находка 30 (reference.md). Мир — как в стендах: -Dstands.config=КЛЮЧ=… .
 */
fun main() {
    Locale.setDefault(Locale.ROOT)
    val id = System.getProperty("probe.scene") ?: "gen-grow/crayfish/open-p1"
    val until = System.getProperty("probe.until")?.toIntOrNull() ?: 120
    val s = Catalog.all().first { it.id == id && it.variation == Variation.BASE }
    val run = StandRun(s)
    val st = run.w.world.state
    val b = run.w.bodies[0]
    val n = b.count
    val pOf = IntArray(n) { b.particle(it) }
    val kOf = HashMap<Int, Int>(); for (k in 0 until n) kOf[pOf[k]] = k
    val order = b.growth!!.order
    val bornAt = IntArray(n) { -1 }
    for (k in 0 until n) if (!st.cellUnborn[pOf[k]]) bornAt[k] = 0
    val dx0 = b.def.x; val dy0 = b.def.y
    fun design(k: Int, q: Int): Double { val x = (dx0[k] - dx0[q]).toDouble(); val y = (dy0[k] - dy0[q]).toDouble(); return sqrt(x * x + y * y) }
    fun dist(p: Int, q: Int): Double { val x = (st.px[p] - st.px[q]).toDouble(); val y = (st.py[p] - st.py[q]).toDouble(); return sqrt(x * x + y * y) }
    val link = run.w.world.meanLinkLength
    val adj = b.shape
    println("сцена $id: клеток $n, стадий ${order.depth.max()}, длина связи $link")
    println("створок при старте: " + st.flapCount + ", голых рёбер: " + st.baked.bareEdges)
    val was = BooleanArray(n)
    // Взрослая раскладка против растущего тела: выбранные клетки — стадия, родитель, точка во взрослом теле и сейчас.
    val watch = System.getProperty("probe.cells").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
    val watchAt = System.getProperty("probe.at")?.toIntOrNull() ?: -1
    fun dumpWatch(t: Int) {
        if (watch.isEmpty()) return
        println("   раскладка и рост на тике $t (в связях; взрослое — от первой клетки списка, сейчас — от неё же):")
        val k0 = watch[0]
        for (k in watch) {
            val ax = (dx0[k] - dx0[k0]) / link; val ay = (dy0[k] - dy0[k0]) / link
            val p = pOf[k]; val p0 = pOf[k0]
            val cx = (st.px[p] - st.px[p0]) / link; val cy = (st.py[p] - st.py[p0]) / link
            println(String.format("     %4d p=%4d стадия %2d родитель %4d рождена t%-3d взрослое (%6.2f, %6.2f) сейчас (%6.2f, %6.2f)%s",
                k, pOf[k], order.depth[k], order.parent[k], bornAt[k], ax, ay, cx, cy, if (st.cellUnborn[p]) " [не рождена]" else ""))
        }
    }
    while (run.w.tick < s.ticks) {
        if (run.w.tick == watchAt) dumpWatch(watchAt)
        for (k in 0 until n) was[k] = st.cellUnborn[pOf[k]]
        run.inVariation { run.w.step() }
        val t = run.w.tick
        val born = ArrayList<Int>()
        for (k in 0 until n) if (was[k] && !st.cellUnborn[pOf[k]]) { bornAt[k] = t; born.add(k) }
        val print = t <= until || t % 100 == 0 || t == s.ticks
        if (!print) continue
        // Связи: длина к длине покоя-цели (дорастающая — к цели).
        var worst = 0.0; var wc = -1; var over15 = 0; var over3 = 0; var live = 0
        for (c in 0 until st.conCount) {
            if (st.conDead[c]) continue
            live++
            val target = if (c < st.conGrowTarget.size && st.conGrowTarget[c] > 0.0) st.conGrowTarget[c] else st.conRest[c]
            if (target <= 0.0) continue
            val r = dist(st.conA[c], st.conB[c]) / target
            if (r > 1.5) over15++
            if (r > 3.0) over3++
            if (r > worst) { worst = r; wc = c }
        }
        // Треугольники: площадь к цели.
        var tw = 0.0; var tov3 = 0; var twW = -1
        for (w in 0 until st.triCount) {
            val tid = st.triId[w]
            if (!st.baked.triAlive[tid]) continue
            val i0 = st.triA[w]; val i1 = st.triB[w]; val i2 = st.triC[w]
            val a2 = (st.px[i1] - st.px[i0]) * (st.py[i2] - st.py[i0]) - (st.py[i1] - st.py[i0]) * (st.px[i2] - st.px[i0])
            val target = if (tid < st.triGrowTarget.size && st.triGrowTarget[tid] > 0f) st.triGrowTarget[tid].toDouble() else st.triRestArea2[w].toDouble()
            if (target <= 0.0) continue
            val r = a2 / target
            if (r > 3.0) tov3++
            if (r > tw) { tw = r; twW = w }
        }
        val bornN = (0 until n).count { !st.cellUnborn[pOf[it]] && !st.cellDead[pOf[it]] }
        val wDesc = if (wc >= 0) {
            val ka = kOf[st.conA[wc]] ?: -1; val kb = kOf[st.conB[wc]] ?: -1
            String.format("%.2f (%d-%d, рождены t%d/t%d)", worst, ka, kb, if (ka >= 0) bornAt[ka] else -9, if (kb >= 0) bornAt[kb] else -9)
        } else "—"
        println(String.format("t=%4d родилось +%2d (живых %3d) | связи %4d: худшая %s, >1.5: %d, >3: %d | треуг.: худший %.1f, >3: %d | створок %d, голых рёбер %d",
            t, born.size, bornN, live, wDesc, over15, over3, tw, tov3, st.flapCount, st.baked.bareEdges))
        if (twW >= 0 && tw > 10.0 && (t == 53 || t == 100 || t == s.ticks)) {
            val vv = intArrayOf(st.triA[twW], st.triB[twW], st.triC[twW]).map { kOf[it] ?: -1 }
            val tid = st.triId[twW]
            println("      худший треугольник " + vv + " рождены " + vv.map { if (it >= 0) bornAt[it] else -9 } + " цель-рост " + (if (tid < st.triGrowTarget.size) st.triGrowTarget[tid] else 0f) + " покой " + st.triRestArea2[twW])
            for ((a, q) in listOf(0 to 1, 1 to 2, 0 to 2)) { val ka = vv[a]; val kq = vv[q]; val lk = (adj.adjStart[ka] until adj.adjStart[ka + 1]).any { adj.adj[it] == kq }
                println(String.format("        ребро %d-%d: сейчас %.2f взрослое %.2f связи, связь в раскладке: %s", ka, kq, dist(pOf[ka], pOf[kq]) / link, design(ka, kq) / link, lk)) }
        }
        // Новорождённые, у которых худшая связь при рождении длиннее покоя в 1.5 раза.
        for (k in born) {
            val p = pOf[k]
            val par = order.parent[k]
            var bw = 0.0; var bq = -1
            for (a in adj.adjStart[k] until adj.adjStart[k + 1]) {
                val q = adj.adj[a]
                if (st.cellUnborn[pOf[q]] || st.cellDead[pOf[q]]) continue
                val r = dist(p, pOf[q]) / design(k, q)
                if (r > bw) { bw = r; bq = q }
            }
            if (bw > 1.5) {
                val dPar = if (par >= 0) dist(p, pOf[par]) / link else -1.0
                val linked = par >= 0 && (adj.adjStart[k] until adj.adjStart[k + 1]).any { adj.adj[it] == par }
                println(String.format("      клетка %d (стадия %d): родитель %d%s в %.2f связи; худшая связь %.2f с %d (рождена t%d, стадия %d)",
                    k, order.depth[k], par, if (linked) "" else " [не сосед по раскладке]", dPar, bw, bq, bornAt[bq], order.depth[bq]))
            }
        }
    }
}
