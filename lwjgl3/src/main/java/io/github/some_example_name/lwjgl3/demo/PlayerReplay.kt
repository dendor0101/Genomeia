package io.github.some_example_name.lwjgl3.demo

import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * РАЗБОР ЖУРНАЛА ИГРОКА: воспроизвести сессию и рассказать про помеченные клетки.
 *
 *   gradlew :lwjgl3:playerReplay
 *   gradlew :lwjgl3:playerReplay -Plog=player-log.txt -Phistory=90
 *
 * Демо собирается с теми же параметрами, что были в окне (тело, копии, сид разрушения),
 * и журнал исполняется через те же функции, через которые его писали — см. act() в
 * RealBodyDemo. На каждой метке средней кнопкой печатается состояние клетки ровно в тот
 * тик, когда игрок нажал кнопку, история до него и немного после.
 *
 * ЧЕСТНОСТЬ ПРОВЕРЯЕТСЯ, А НЕ ПРЕДПОЛАГАЕТСЯ. В журнале раз в секунду лежит контрольная
 * сумма позиций и скоростей. Разбор сверяет каждую; если хоть одна не сошлась, это
 * печатается, и всё, что после неё, относится уже не к тому, что видел игрок. Так
 * бывает, если между записью и разбором поменялась физика — журнал хранит действия, а
 * не результат.
 *
 * ЧТО ПЕЧАТАЕТСЯ ПО КЛЕТКЕ:
 *   d/(ri+rj)      расстояние до соседа в долях суммы контактных радиусов. Меньше
 *                  единицы — перекрыты; 0.6 значит перекрытие на 40%.
 *   центр внутри   центр одной клетки зашёл внутрь радиуса другой.
 *   сближение      нормальная скорость пары, клеток за тик; минус — сходятся.
 *   lam            накопленный множитель контакта; ноль — контакт не давит.
 *   в ткани       центр клетки внутри треугольника чужого организма (или своего,
 *                  но не касающегося её — это складка); рядом глубина до края ткани.
 */
private class Sample(
    val tick: Int, val speed: Double,
    val overlaps: Int, val centerIn: Int,
    val deep: Double, val deepWith: Int, val approach: Double,
    val insideOrg: Int, val insideDepth: Double, val insideOwn: Boolean,
)

private class MarkReport(val no: Int, val id: Int, val tick: Int) {
    val text = StringBuilder()
    val before = ArrayList<Sample>()
    /** Тики вокруг входа в чужую ткань, если клетка сидит в ней на момент метки. */
    val entry = ArrayList<Sample>()
    var entryTick = -1
    val after = ArrayList<Sample>()
}

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val logPath = args.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: PlayerLog.FILE
    val history = args.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 90
    val afterTicks = 30
    val rowEvery = 3

    val f = File(logPath)
    require(f.isFile) { "нет журнала: ${f.absolutePath}" }
    val log = PlayerLog.parse(f)
    val hdr = log.header
    println("=== РАЗБОР ЖУРНАЛА ${f.absolutePath} ===")
    for ((k, v) in hdr) println("  $k = $v")

    // --- тело: то же, что при записи ---
    var bodyPath = hdr["body"] ?: error("в журнале нет строки HDR body")
    val wantCrc = hdr["bodyCrc"]
    var bodyOk = wantCrc == null || (File(bodyPath).isFile && PlayerLog.crc(File(bodyPath)) == wantCrc)
    if (!bodyOk && File(bodyPath).name.startsWith("soft-grid")) {
        // Решётку стенд пишет во временную папку заново при каждом запуске, так что
        // файла могло уже не остаться. Она детерминирована — собираем её снова.
        val regen = File.createTempFile("soft-grid-replay", ".txt")
        regen.deleteOnExit()
        writeGrid(regen)
        if (PlayerLog.crc(regen) == wantCrc) { bodyPath = regen.path; bodyOk = true }
    }
    if (!bodyOk) {
        println()
        println("  ВНИМАНИЕ: файл тела не совпадает с тем, что был при записи (CRC).")
        println("  Воспроизведение пойдёт, но контрольные суммы почти наверняка разойдутся.")
    }

    val demo = RealBodyDemo(bodyPath,
        copies = hdr["copies"]?.toInt() ?: 2,
        freeParticles = hdr["free"]?.toInt() ?: 6,
        labKillFraction = hdr["killFraction"]?.toDouble() ?: 0.0,
        labKillSeed = hdr["killSeed"]?.toLong() ?: 0L,
        killOnDeep = hdr["killOnDeep"] == "1")
    demo.replayBoot()
    val P = Probe
    P.attach(demo)
    val dt = P.const("DT")
    val meanLink = P.body.meanLinkLength.toDouble()
    val ups = Math.round(1.0 / dt).toString()
    val sub = P.constInt("SUBSTEPS").toString()
    if (hdr["ups"] != null && hdr["ups"] != ups || hdr["substeps"] != null && hdr["substeps"] != sub) {
        println("  ВНИМАНИЕ: шаг физики другой — при записи ups=${hdr["ups"]} substeps=${hdr["substeps"]}, " +
            "сейчас ups=$ups substeps=$sub")
    }

    val watched = log.lines.filter { it.op == "MARK" }.mapNotNull { it.args.getOrNull(0)?.toIntOrNull() }.toSet()
    val traces = HashMap<Int, ArrayDeque<Sample>>()
    for (id in watched) traces[id] = ArrayDeque()
    val reports = ArrayList<MarkReport>()
    val open = ArrayList<MarkReport>()

    fun sec(t: Int) = "%.2f с".format(t * dt)

    /** Сколько до края ткани, в которую зашёл центр клетки, мировые единицы. См. insideTissue. */
    var insideDepth = 0.0
    /** Лежит ли найденный треугольник в СВОЁМ организме клетки — то есть это складка. */
    var insideOwn = false

    /**
     * В ЧЬЮ ТКАНЬ ЗАШЁЛ ЦЕНТР КЛЕТКИ: номер организма или -1.
     *
     * Проверка по ТРЕУГОЛЬНИКАМ, а не по контуру. Контур из граничных связей у
     * настоящего тела не замкнут: там, где на край выходит кость, связей нет, шов
     * держится контактным радиусом, и 200 граничных рёбер тела складываются в 10
     * отдельных цепочек. Чётность пересечений по такому контуру сначала врала на
     * десятки связей, а после отсева висящих концов тело выпало из проверки целиком.
     *
     * Треугольники же и есть ткань: центр внутри чужого треугольника — клетка зашла за
     * мембрану. Свои треугольники тоже смотрятся, кроме тех, где клетка сама вершина:
     * попасть в такой можно только складкой ткани на саму себя.
     *
     * Кость без треугольников сюда не попадает; заход в кость и так виден по
     * перекрытию с её клетками.
     */
    fun insideTissue(i: Int): Int {
        val org: IntArray = P.get("organismOf")
        val dead: BooleanArray = P.get("cellDead")
        val ta: IntArray = P.get("triA"); val tb: IntArray = P.get("triB"); val tc: IntArray = P.get("triC")
        val tn: Int = P.get("triCount")
        val x = P.px[i]; val y = P.py[i]
        var found = -1
        insideOwn = false
        for (t in 0 until tn) {
            val a = ta[t]; val b = tb[t]; val c = tc[t]
            if (a == i || b == i || c == i || dead[a] || dead[b] || dead[c]) continue
            val ax = P.px[a]; val ay = P.py[a]; val bx = P.px[b]; val by = P.py[b]
            val cx = P.px[c]; val cy = P.py[c]
            if (x < minOf(ax, bx, cx) || x > maxOf(ax, bx, cx) || y < minOf(ay, by, cy) || y > maxOf(ay, by, cy)) continue
            val d1 = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val d2 = (cx - bx) * (y - by) - (cy - by) * (x - bx)
            val d3 = (ax - cx) * (y - cy) - (ay - cy) * (x - cx)
            val neg = d1 < 0 || d2 < 0 || d3 < 0
            val pos = d1 > 0 || d2 > 0 || d3 > 0
            if (neg && pos) continue
            found = org[a]
            insideOwn = found == org[i]
            // Чужая ткань важнее своей складки: ищем дальше, только если нашли свою.
            if (!insideOwn) break
        }
        insideDepth = 0.0
        if (found < 0) return -1
        // Глубина — до ближайшего граничного ребра того же организма. Контур
        // разомкнут, но для расстояния это не важно.
        val ba: IntArray = P.get("boundA"); val bb: IntArray = P.get("boundB")
        val bc: Int = P.get("boundCount")
        var best = Double.MAX_VALUE
        for (e in 0 until bc) {
            val a = ba[e]; val b = bb[e]
            if (org[a] != found || org[b] != found) continue
            val ax = P.px[a]; val ay = P.py[a]
            val ex = P.px[b] - ax; val ey = P.py[b] - ay
            val len2 = ex * ex + ey * ey
            var s = if (len2 < 1e-18) 0.0 else ((x - ax) * ex + (y - ay) * ey) / len2
            if (s < 0.0) s = 0.0 else if (s > 1.0) s = 1.0
            val qx = ax + ex * s - x; val qy = ay + ey * s - y
            val dd = sqrt(qx * qx + qy * qy)
            if (dd < best) best = dd
        }
        if (best < Double.MAX_VALUE) insideDepth = best
        return found
    }

    fun approachOf(i: Int, j: Int): Double {
        val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
        val d = sqrt(dx * dx + dy * dy)
        if (d < 1e-12) return 0.0
        return ((P.vx[i] - P.vx[j]) * dx + (P.vy[i] - P.vy[j]) * dy) / d * dt / meanLink
    }

    fun sample(i: Int): Sample {
        val ct = P.contactsObj()
        val dead: BooleanArray = P.get("cellDead")
        val speed = sqrt(P.vx[i] * P.vx[i] + P.vy[i] * P.vy[i]) * dt / meanLink
        var overlaps = 0; var centerIn = 0
        var deep = Double.MAX_VALUE; var deepWith = -1; var appr = 0.0
        val ri = ct?.contactRadiusOf(i) ?: 0.0
        if (ct != null && ri > 0.0) for (j in 0 until P.n) {
            if (j == i || dead[j]) continue
            val rj = ct.contactRadiusOf(j)
            if (rj <= 0.0) continue
            val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
            val d2 = dx * dx + dy * dy
            val rr = ri + rj
            if (d2 >= rr * rr) continue
            if (ct.isBonded(i, j)) continue
            val d = sqrt(d2)
            overlaps++
            if (d < maxOf(ri, rj)) centerIn++
            if (d / rr < deep) { deep = d / rr; deepWith = j; appr = approachOf(i, j) }
        }
        val ins = insideTissue(i)
        return Sample(demo.currentTick, speed, overlaps, centerIn,
            if (deepWith < 0) Double.NaN else deep, deepWith, appr, ins, insideDepth / meanLink, insideOwn)
    }

    fun linkStatus(i: Int, j: Int): String {
        val ct = P.contactsObj()
        val lop: HashMap<Long, Int> = P.get("linkOfPair")
        val conDead: BooleanArray = P.get("conDead")
        val c = lop[minOf(i, j).toLong() * 1000003L + maxOf(i, j).toLong()]
        return when {
            c != null && c < conDead.size && !conDead[c] -> "жива"
            c != null -> "порвана"
            ct != null && ct.isBonded(i, j) -> "касание в покое, исключена"
            else -> "не было"
        }
    }

    /** Подробный снимок клетки в момент пометки. */
    fun describe(r: MarkReport, ln: PlayerLog.Line) {
        val i = r.id
        val t = r.text
        val ct = P.contactsObj()
        val org: IntArray = P.get("organismOf")
        val size: IntArray = P.get("organismSize")
        val orgCount: Int = P.get("organismCount")
        val boneOf: IntArray = P.get("boneOf")
        val isFree: BooleanArray = P.get("isFree")
        val dead: BooleanArray = P.get("cellDead")
        val conDead: BooleanArray = P.get("conDead")
        val conCount: Int = P.get("conCount")
        val conA: IntArray = P.get("conA"); val conB: IntArray = P.get("conB")
        var alive = 0; var total = 0
        for (c in 0 until conCount) {
            if (conA[c] != i && conB[c] != i) continue
            total++
            if (c < conDead.size && !conDead[c]) alive++
        }
        val opts = ln.args.drop(3).joinToString("  ")
        t.append("────────────────────────────────────────────────────────────────────\n")
        t.append("МЕТКА ${r.no}: клетка #$i, тик ${r.tick} (${sec(r.tick)})   [окно: $opts]\n")
        t.append("  организм ${org[i]} из $orgCount (клеток ${size[org[i]]}), " +
            "кость: ${if (boneOf[i] >= 0) "#${boneOf[i]}" else "нет"}, " +
            "свободная: ${if (isFree[i]) "да" else "нет"}, мёртвая: ${if (dead[i]) "ДА" else "нет"}, " +
            "связей живых $alive из $total\n")
        val ri = ct?.contactRadiusOf(i) ?: 0.0
        t.append("  контактный радиус %.3f связи%s\n".format(ri / meanLink,
            if (ri <= 0.0) " — ВНУТРЕННЯЯ клетка, в столкновениях не участвует вовсе" else ""))
        val dxs = (ln.args.getOrNull(1)?.toDoubleOrNull() ?: P.px[i]) - P.px[i]
        val dys = (ln.args.getOrNull(2)?.toDoubleOrNull() ?: P.py[i]) - P.py[i]
        t.append("  скорость %.3f клеток/тик; нарисована в %.2f связи от позиции физики\n".format(
            sqrt(P.vx[i] * P.vx[i] + P.vy[i] * P.vy[i]) * dt / meanLink, sqrt(dxs * dxs + dys * dys) / meanLink))
        val ins = insideTissue(i)
        t.append(when {
            ins < 0 -> "  центр в чужой ткани: нет\n"
            insideOwn -> "  ЦЕНТР В СВОЕЙ ЖЕ ТКАНИ (складка), до края %.2f связи\n".format(insideDepth / meanLink)
            else -> "  ЦЕНТР В ТКАНИ ЧУЖОГО ОРГАНИЗМА $ins, до её края %.2f связи\n".format(insideDepth / meanLink)
        })

        // Соседи в пределах 1.3 суммы радиусов — и связанные, и нет: прилипание часто
        // сидит как раз в паре, которую исключили из столкновений.
        if (ct != null && ri > 0.0) {
            val near = ArrayList<Pair<Int, Double>>()
            for (j in 0 until P.n) {
                if (j == i || dead[j]) continue
                val rj = ct.contactRadiusOf(j)
                if (rj <= 0.0) continue
                val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
                val q = sqrt(dx * dx + dy * dy) / (ri + rj)
                if (q < 1.3) near.add(Pair(j, q))
            }
            near.sortBy { it.second }
            t.append("  соседи ближе 1.3 суммы контактных радиусов: ${near.size}\n")
            if (near.isNotEmpty()) {
                t.append("     клетка   орг  кость  d/(ri+rj)  центр внутри  сближение  lam        связь\n")
                for ((j, q) in near.take(14)) {
                    val rj = ct.contactRadiusOf(j)
                    val d = q * (ri + rj)
                    t.append("     %-8s %-4d %-6s %9.3f  %-12s  %+9.3f  %-9.2e  %s\n".format(
                        "#$j", org[j], if (boneOf[j] >= 0) "#${boneOf[j]}" else "-", q,
                        if (d < maxOf(ri, rj)) "ДА" else "нет", approachOf(i, j),
                        ct.lambdaOf(i, j), linkStatus(i, j)))
                }
            }
        }
    }

    fun rows(t: StringBuilder, list: List<Sample>, every: Int = rowEvery) {
        t.append("     тик   скорость  перекр  центр_вн  глубже всего d/(ri+rj)  с клеткой  сближение  центр в ткани\n")
        var last: Sample? = null
        for ((k, s) in list.withIndex()) {
            val changed = last == null || s.overlaps != last.overlaps || s.centerIn != last.centerIn ||
                s.insideOrg != last.insideOrg || s.insideOwn != last.insideOwn
            if (!changed && k % every != 0 && k != list.size - 1) continue
            t.append("     %-5d %8.3f  %6d  %8d  %22s  %-9s  %+9.3f  %s\n".format(
                s.tick, s.speed, s.overlaps, s.centerIn,
                if (s.deepWith < 0) "-" else "%.3f".format(s.deep),
                if (s.deepWith < 0) "-" else "#${s.deepWith}",
                s.approach, if (s.insideOrg < 0) "-" else if (s.insideOwn) "СВОЯ складка, %.2f св".format(s.insideDepth) else "ОРГ %d, %.2f св".format(s.insideOrg, s.insideDepth)))
            last = s
        }
    }

    /**
     * Строка хронологии: сколько клеток зашло центром в чужую ткань, сколько связей
     * порвано, насколько связи хотели растянуться сверх предела и насколько сжаты.
     * Ткань ищется через сетку по треугольникам, иначе это n * треугольников на тик.
     */
    var timelineHeader = false
    fun timelineRow(tk: Int) {
        if (!timelineHeader) {
            timelineHeader = true
            println("  ХРОНОЛОГИЯ: тик | порвано | организмов | клеток в чужой ткани (глубже 1 связи) | " +
                "растяж. сверх предела, доли покоя | сжатие связей мин | перекрытие контактов макс | скорость макс")
        }
        val org: IntArray = P.get("organismOf")
        val dead: BooleanArray = P.get("cellDead")
        val ta: IntArray = P.get("triA"); val tb: IntArray = P.get("triB"); val tc: IntArray = P.get("triC")
        val tn: Int = P.get("triCount")
        val cs = 2.0 * meanLink
        val grid = HashMap<Long, MutableList<Int>>()
        fun gk(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
        for (t in 0 until tn) {
            val a = ta[t]; val b = tb[t]; val c = tc[t]
            val x0 = Math.floor(minOf(P.px[a], P.px[b], P.px[c]) / cs).toInt()
            val x1 = Math.floor(maxOf(P.px[a], P.px[b], P.px[c]) / cs).toInt()
            val y0 = Math.floor(minOf(P.py[a], P.py[b], P.py[c]) / cs).toInt()
            val y1 = Math.floor(maxOf(P.py[a], P.py[b], P.py[c]) / cs).toInt()
            if ((x1 - x0 + 1) * (y1 - y0 + 1) > 64) continue
            for (gx in x0..x1) for (gy in y0..y1) grid.getOrPut(gk(gx, gy)) { ArrayList(4) }.add(t)
        }
        var inside = 0; var deep = 0
        for (i in 0 until P.n) {
            if (dead[i]) continue
            val x = P.px[i]; val y = P.py[i]
            val list = grid[gk(Math.floor(x / cs).toInt(), Math.floor(y / cs).toInt())] ?: continue
            for (t in list) {
                val a = ta[t]; val b = tb[t]; val c = tc[t]
                if (a == i || b == i || c == i || org[a] == org[i]) continue
                val d1 = (P.px[b] - P.px[a]) * (y - P.py[a]) - (P.py[b] - P.py[a]) * (x - P.px[a])
                val d2 = (P.px[c] - P.px[b]) * (y - P.py[b]) - (P.py[c] - P.py[b]) * (x - P.px[b])
                val d3 = (P.px[a] - P.px[c]) * (y - P.py[c]) - (P.py[a] - P.py[c]) * (x - P.px[c])
                if ((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0)) continue
                inside++
                if (insideTissue(i) >= 0 && !insideOwn && insideDepth > meanLink) deep++
                break
            }
        }
        val conCount: Int = P.get("conCount")
        val conA: IntArray = P.get("conA"); val conB: IntArray = P.get("conB")
        val conRest: DoubleArray = P.get("conRest"); val conDead: BooleanArray = P.get("conDead")
        var minRatio = Double.MAX_VALUE
        for (c in 0 until conCount) {
            if (c < conDead.size && conDead[c]) continue
            val dx = P.px[conA[c]] - P.px[conB[c]]; val dy = P.py[conA[c]] - P.py[conB[c]]
            val r = sqrt(dx * dx + dy * dy) / conRest[c]
            if (r < minRatio) minRatio = r
        }
        var vmax = 0.0
        for (i in 0 until P.n) {
            val v = sqrt(P.vx[i] * P.vx[i] + P.vy[i] * P.vy[i]) * dt / meanLink
            if (v > vmax) vmax = v
        }
        val pen = P.contactsObj()?.maxPenetration(P.px, P.py) ?: 0.0
        println("  %6d | %5d | %4d | %5d (%4d) | %6.3f | %5.3f | %5.3f | %5.2f | пересборок %d за %.1f мс | h=%x".format(
            tk, P.killedLinks(), P.get<Int>("organismCount"), inside, deep,
            demo.dbgMaxOverStrain, minRatio, pen, vmax, demo.dbgRebuildN, demo.dbgRebuildNs / 1e6,
            PlayerLog.stateHash(P.n, P.px, P.py, P.vx, P.vy)))
        if (demo.dbgRebuildN > 0) println("         части пересборки, мс: " +
            demo.dbgRebuildSec.joinToString(" ") { "%.1f".format(it / 1e6) })
        demo.dbgRebuildSec.fill(0L)
        demo.dbgRebuildN = 0; demo.dbgRebuildNs = 0L
    }

    var markNo = 0
    val eventNames = mapOf(
        "R" to "сброс", "B" to "кости жёсткие", "G" to "гребок", "K" to "контакты", "X" to "разрыв",
        "E" to "изгиб", "N" to "выбрана сцена", "S" to "запуск сцены", "P" to "пуля", "O" to "таран",
        "D" to "схватил клетку", "U" to "отпустил", "UNMARK" to "метка снята", "END" to "окно закрыто")

    System.getenv("PR_AB")?.toIntOrNull()?.let { at ->
        BoundaryContacts.abLegacy = at > 0
        println("  A/B: до тика $at контакт старый, дальше нынешний")
    }
    println()
    println("--- действия игрока ---")
    val t0 = System.nanoTime()
    demo.replayRun(log.lines,
        onEvent = { ln ->
            if (ln.op == "MARK") {
                markNo++
                val id = ln.args[0].toInt()
                val r = MarkReport(markNo, id, ln.tick)
                val full = traces[id] ?: ArrayDeque()
                r.before.addAll(full.takeLast(history))
                // ВХОД В ЧУЖУЮ ТКАНЬ ищется по ВСЕЙ истории, а не по окну: клетка может
                // сидеть в мешке уже полминуты, и в последние 90 тиков момент входа не попадёт.
                var k = full.size
                while (k > 0 && full[k - 1].insideOrg >= 0 && !full[k - 1].insideOwn) k--
                if (k < full.size) {
                    r.entryTick = full[k].tick
                    r.entry.addAll(full.subList(maxOf(0, k - 6), minOf(full.size, k + 10)))
                }
                describe(r, ln)
                reports.add(r); open.add(r)
                println("  [тик %6d, %9s] МЕТКА %d на клетке #%d".format(ln.tick, sec(ln.tick), markNo, id))
            } else if (ln.op != "H") {
                val name = eventNames[ln.op] ?: ln.op
                println("  [тик %6d, %9s] %s %s".format(ln.tick, sec(ln.tick), name, ln.args.joinToString(" ")))
            }
        },
        onTick = {
            // Хронология удара: PR_TIMELINE=тикС,тикПо
            System.getenv("PR_TIMELINE")?.split(',')?.map { it.trim().toInt() }?.let { tl ->
                val tk = demo.currentTick
                if (tk >= tl[0] && tk <= tl[1]) timelineRow(tk)
                demo.dbgMaxOverStrain = 0.0
            }
            // A/B: PR_AB=тик — до этого тика контакт старый (BoundaryContacts.abLegacy).
            System.getenv("PR_AB")?.toIntOrNull()?.let { at ->
                BoundaryContacts.abLegacy = demo.currentTick < at
            }
            // Отладка одной пары: PR_PAIR=i,j,тикС,тикПо
            System.getenv("PR_PAIR")?.split(',')?.map { it.trim().toInt() }?.let { pp ->
                val ct = P.contactsObj()
                val tk = demo.currentTick
                if (ct != null && tk >= pp[2] && tk <= pp[3]) {
                    val i = pp[0]; val j = pp[1]
                    val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
                    val q = sqrt(dx * dx + dy * dy) / (ct.contactRadiusOf(i) + ct.contactRadiusOf(j))
                    println("PAIR t=%d d/rr=%.4f push=%.5f pull=%.5f (связи) lam=%.4f vsep=%+.3f кл/тик".format(tk, q,
                        ct.dbgPairPush / meanLink, ct.dbgPairPull / meanLink, ct.dbgPairLam, approachOf(i, j)))
                }
                ct?.dbgPair = ct?.pairKeyOf(pp[0], pp[1]) ?: -1L
                ct?.dbgPairPush = 0.0; ct?.dbgPairPull = 0.0
            }
            for (id in watched) {
                val s = sample(id)
                val q = traces[id]!!
                q.addLast(s)
                while (q.size > 200_000) q.removeFirst()
                val it2 = open.iterator()
                while (it2.hasNext()) {
                    val r = it2.next()
                    if (r.id != id) continue
                    r.after.add(s)
                    if (r.after.size >= afterTicks) it2.remove()
                }
            }
        })
    val secs = (System.nanoTime() - t0) / 1e9

    println()
    println("--- помеченные клетки ---")
    if (reports.isEmpty()) println("  меток в журнале нет — средней кнопкой по клетке, чтобы пометить")
    for (r in reports) {
        print(r.text)
        r.text.setLength(0)
        val sb = StringBuilder()
        if (r.entryTick >= 0) {
            sb.append("  ВОШЛА В ЧУЖУЮ ТКАНЬ на тике ${r.entryTick} (${sec(r.entryTick)}), " +
                "за ${r.tick - r.entryTick} тиков до метки:\n")
            rows(sb, r.entry, every = 1)
        }
        sb.append("  до метки (последние ${r.before.size} тиков):\n")
        rows(sb, r.before)
        if (r.after.isNotEmpty()) {
            sb.append("  после метки (${r.after.size} тиков):\n")
            rows(sb, r.after)
        }
        print(sb)
    }

    println()
    println("--- итог ---")
    println("  тиков воспроизведено ${demo.currentTick} (${sec(demo.currentTick)}) за %.1f с".format(secs))
    val ok = demo.replayHashOk; val bad = demo.replayHashBad
    if (ok + bad == 0) println("  контрольных сумм в журнале нет — совпадение с игрой не проверено")
    else if (bad == 0) println("  контрольные суммы: все $ok сошлись — воспроизведение побитово то же, что видел игрок")
    else println("  КОНТРОЛЬНЫЕ СУММЫ: не сошлось $bad из ${ok + bad}, первая на тике ${demo.replayFirstBadTick}. " +
        "Всё после этого тика — уже не та сессия.")
}
