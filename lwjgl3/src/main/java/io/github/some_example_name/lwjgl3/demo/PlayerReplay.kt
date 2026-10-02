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
    // Стадии на НАСТОЯЩЕЙ сессии: PR_STAGES=1. В perfBench тела не сталкиваются, поэтому
    // доля контактов там заниженная; здесь она такая, какая была у игрока.
    if (System.getenv("PR_STAGES") != null) demo.dbgTimeOn = true
    // Проходимость мембраны: PR_SEAL=1. См. dbgSeal.
    if (System.getenv("PR_SEAL") != null) demo.dbgSealOn = true
    // Журнал разрывов: PR_TEARS=1 — кто, когда и почему порвался.
    if (System.getenv("PR_TEARS") != null) demo.dbgTearLog = StringBuilder()
    // Прогрев кода разрушения, как в окне: PR_WARM=тиков. См. startWarmUp.
    System.getenv("PR_WARM")?.toIntOrNull()?.let { demo.warmUpBlocking(it) }
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
        val conDead: BooleanArray = P.get("conDead")
        val c = demo.conIndexOf(i, j).takeIf { it >= 0 }
        return when {
            c != null && c < conDead.size && !conDead[c] -> "жива"
            c != null -> "порвана"
            P.get<Set<Long>?>("everLinked")?.contains(minOf(i, j).toLong() * 1000003L + maxOf(i, j).toLong()) == true -> "порвана"
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
        // ИЗ КАКОГО ИСХОДНОГО ТЕЛА клетка и её «хозяин». Копии тела лежат в массивах
        // подряд, свободные частицы — в конце; организм после разрыва перенумеровывается,
        // а номер копии остаётся честным ответом, своя это ткань или чужая.
        if (ins >= 0 && !insideOwn) {
            val copies = hdr["copies"]?.toInt() ?: 2
            val free = hdr["free"]?.toInt() ?: 6
            val per = (P.n - free) / copies
            fun copyOf(c: Int) = if (c >= per * copies) "свободная частица" else "тело ${c / per}"
            val hostCells = (0 until P.n).filter { org[it] == ins }
            val hostCopies = hostCells.groupingBy { copyOf(it) }.eachCount()
            t.append("  исходно: клетка — ${copyOf(i)}, хозяин — $hostCopies\n")
        }

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
        val ctp = P.contactsObj()
        val pen = ctp?.maxPenetration(P.px, P.py) ?: 0.0
        // КТО ИМЕННО УПИРАЕТСЯ. Без пары «перекрытие 0.011» ничего не говорит: в сцене
        // восемь организмов и свободные частицы, и упираться могут любые двое.
        val penWho = if (ctp == null || ctp.worstI < 0) "" else
            " (#%d орг %d — #%d орг %d)".format(ctp.worstI, org[ctp.worstI], ctp.worstJ, org[ctp.worstJ])
        println("  %6d | %5d | %4d | %5d (%4d) | %6.3f | %5.3f | %5.3f%s | %5.2f | тик %.1f мс | пересборок %d за %.1f мс | лопнуло давл %d разд %d | мембрана %d | ловушка %d (глуб %d, кость %d, складок %d) убито %d, %.0f мкс | h=%x".format(
            tk, P.killedLinks(), P.get<Int>("organismCount"), inside, deep,
            demo.dbgMaxOverStrain, minRatio, pen, penWho, vmax, demo.dbgTickNs / 1e6, demo.dbgRebuildN, demo.dbgRebuildNs / 1e6, demo.pressureBurstCount, demo.crushBurstCount, demo.membraneTearCount,
            demo.dbgTrapNow, demo.dbgTrapDeep, demo.dbgTrapBone, demo.dbgTrapFold, demo.trapKillCount, demo.dbgTrapNs / 1e3,
            PlayerLog.stateHash(P.n, P.px, P.py, P.vx, P.vy)))
        if (demo.dbgRebuildN > 0) println("         части пересборки, мс: " +
            demo.dbgRebuildSec.joinToString(" ") { "%.1f".format(it / 1e6) })
        demo.dbgRebuildSec.fill(0L)
        demo.dbgRebuildN = 0; demo.dbgRebuildNs = 0L; demo.dbgTrapNs = 0L
    }

    // ------------------------------------------------------------------
    //  РАСКРУТКА КУСКА: PR_SPIN=клетка,тикС,тикПо,окно
    //
    //  Кусок — организм, в котором клетка сейчас. Каждый тик замер по стадиям идёт
    //  только по его клеткам (dbgOnly), и печатается, какая стадия сколько угловой
    //  скорости ему добавила за окно, рядом с тем, сколько добавилось на самом деле.
    //  Внутренние стадии обязаны давать ноль: парная сила вдоль линии центров и
    //  градиент площади момента не создают. Законно крутить кусок могут только
    //  контакты с чужими телами и среда.
    // ------------------------------------------------------------------
    val spinSpec = System.getenv("PR_SPIN")?.split(',')?.map { it.trim().toInt() }
    val stageNames = arrayOf("связи", "площади", "изгиб", "кость", "предел", "контакты", "CCD",
        "отскок", "мышь+вязк", "лоскуты", "среда аниз", "среда изо", "дрейф")
    var spinMask: BooleanArray? = null
    var spinL0 = 0.0
    val spinAcc = DoubleArray(stageNames.size)
    var spinActual = 0.0
    var spinHeader = false
    val spinWork = DoubleArray(stageNames.size)
    val spinPx = DoubleArray(stageNames.size)
    val spinPy = DoubleArray(stageNames.size)
    var spinP0x = 0.0; var spinP0y = 0.0
    var spinActPx = 0.0; var spinActPy = 0.0

    /** Импульс и масса клеток маски. */
    fun maskP(mask: BooleanArray): DoubleArray {
        var m0 = 0.0; var sx = 0.0; var sy = 0.0
        for (i in 0 until P.n) {
            if (!mask[i] || P.invMass[i] <= 0.0) continue
            val m = 1.0 / P.invMass[i]
            m0 += m; sx += m * P.vx[i]; sy += m * P.vy[i]
        }
        return doubleArrayOf(sx, sy, m0)
    }
    var spinTicks = 0

    /** Что внутри куска держит напряжение: перерастянутые связи, свои контакты, кости. */
    fun fragmentReport(mask: BooleanArray) {
        val conCount: Int = P.get("conCount")
        val conA: IntArray = P.get("conA"); val conB: IntArray = P.get("conB")
        val conRest: DoubleArray = P.get("conRest")
        val maxStretch = P.const("LINK_MAX_STRETCH")
        val conDead: BooleanArray = P.get("conDead")
        val conMuscle: IntArray = P.get("conMuscle")
        val boneOf: IntArray = P.get("boneOf")
        var links = 0; var atMax = 0; var minR = 9.0; var maxR = 0.0
        val top = ArrayList<Pair<Int, Double>>()
        for (c in 0 until conCount) {
            if (!mask[conA[c]] || c < conDead.size && conDead[c]) continue
            links++
            val dx = P.px[conA[c]] - P.px[conB[c]]; val dy = P.py[conA[c]] - P.py[conB[c]]
            val len = sqrt(dx * dx + dy * dy)
            val r = len / conRest[c]
            if (len >= maxStretch * conRest[c] * 0.999) atMax++
            if (r < minR) minR = r
            if (r > maxR) maxR = r
            top.add(Pair(c, r))
        }
        top.sortByDescending { it.second }
        println("       КУСОК: связей $links, на пределе длины $atMax, длина/покой от %.3f до %.3f".format(minR, maxR))
        println("       самые растянутые: " + top.take(8).joinToString("  ") { (c, r) ->
            "#%d-#%d %.3f%s%s".format(conA[c], conB[c], r,
                if (conMuscle[c] >= 0) " мышца" else "",
                if (boneOf[conA[c]] >= 0 || boneOf[conB[c]] >= 0) " у кости" else "")
        })
        val bones = HashSet<Int>()
        for (i in 0 until P.n) if (mask[i] && boneOf[i] >= 0) bones.add(boneOf[i])
        val ct = P.contactsObj()
        var own = 0; var ownDeep = 0.0
        val lines = ArrayList<String>()
        if (ct != null) {
            val ids = (0 until P.n).filter { mask[it] && ct.contactRadiusOf(it) > 0.0 }
            for (a in ids.indices) for (b in a + 1 until ids.size) {
                val i = ids[a]; val j = ids[b]
                if (ct.isBonded(i, j)) continue
                val dx = P.px[i] - P.px[j]; val dy = P.py[i] - P.py[j]
                val rc = ct.contactDistanceOf(i, j)
                val d = sqrt(dx * dx + dy * dy)
                if (d >= rc) continue
                own++
                if (1 - d / rc > ownDeep) ownDeep = 1 - d / rc
                val rr = ct.contactRadiusOf(i) + ct.contactRadiusOf(j)
                val rdx = (P.body.x[i] - P.body.x[j]).toDouble(); val rdy = (P.body.y[i] - P.body.y[j]).toDouble()
                val restD = sqrt(rdx * rdx + rdy * rdy)
                val pk = minOf(i, j).toLong() * 1000003L + maxOf(i, j).toLong()
                val wasLinked = demo.conIndexOf(i, j) >= 0 || P.get<Set<Long>?>("everLinked")?.contains(pk) == true
                lines.add("         #%d-#%d  d/упор %.3f  упор %s  в позе покоя d/(ri+rj) %.3f  %s%s".format(
                    i, j, d / rc, if (rc < rr) "расстояние покоя" else "сумма радиусов", restD / rr,
                    if (wasLinked) "бывшие соседи" else "связаны не были",
                    if (boneOf[i] >= 0 || boneOf[j] >= 0) ", у кости" else ""))
            }
        }
        println("       костей в куске ${bones.size} ${bones.sorted()}, своих перекрытых пар $own (глубже всего %.3f от упора)".format(ownDeep))
        for (s in lines.take(30)) println(s)
    }

    /** Момент импульса клеток маски относительно их центра масс и момент инерции. */
    fun maskLJ(mask: BooleanArray): DoubleArray {
        var m0 = 0.0; var cx = 0.0; var cy = 0.0
        for (i in 0 until P.n) {
            if (!mask[i] || P.invMass[i] <= 0.0) continue
            val m = 1.0 / P.invMass[i]
            m0 += m; cx += m * P.px[i]; cy += m * P.py[i]
        }
        if (m0 <= 0.0) return doubleArrayOf(0.0, 0.0)
        cx /= m0; cy /= m0
        var l = 0.0; var j = 0.0
        for (i in 0 until P.n) {
            if (!mask[i] || P.invMass[i] <= 0.0) continue
            val m = 1.0 / P.invMass[i]
            val rx = P.px[i] - cx; val ry = P.py[i] - cy
            l += m * (rx * P.vy[i] - ry * P.vx[i])
            j += m * (rx * rx + ry * ry)
        }
        return doubleArrayOf(l, j)
    }

    fun spinTick(tk: Int) {
        val sp = spinSpec ?: return
        val cell = sp[0]; val from = sp[1]; val to = sp[2]; val every = sp.getOrElse(3) { 1 }
        val mask = spinMask
        if (mask != null && tk in from..to) {
            val lj = maskLJ(mask)
            spinActual += lj[0] - spinL0
            for (k in spinAcc.indices) spinAcc[k] += demo.dbgStageL[k]
            for (k in spinWork.indices) spinWork[k] += demo.dbgStageAbs[k]
            for (k in spinPx.indices) { spinPx[k] += demo.dbgStagePx[k]; spinPy[k] += demo.dbgStagePy[k] }
            val pNow = maskP(mask)
            spinActPx += pNow[0] - spinP0x; spinActPy += pNow[1] - spinP0y
            spinTicks++
            if ((tk - from) % every == every - 1 || tk == to) {
                if (!spinHeader) {
                    spinHeader = true
                    println("  РАСКРУТКА клетки #$cell: прирост угловой скорости куска за окно, рад/с (ΔL / J)")
                    println("     тик  орг  клеток  ω рад/с |  всего  | " + stageNames.joinToString(" | "))
                    fragmentReport(mask)
                }
                println("       работа стадий, связей смещения за тик на клетку: " + stageNames.indices.joinToString("  ") {
                    "%s %.4f".format(stageNames[it], spinWork[it] / spinTicks / meanLink / maxOf(1, mask.count { m -> m }))
                })
                spinWork.fill(0.0); spinTicks = 0
                val j = if (lj[1] > 0.0) lj[1] else 1.0
                val org: IntArray = P.get("organismOf")
                var size = 0
                for (i in 0 until P.n) if (mask[i]) size++
                println("   %5d %4d %6d %+8.3f | %+7.3f | %s".format(tk, org[cell], size, lj[0] / j, spinActual / j,
                    spinAcc.joinToString(" | ") { "%+7.3f".format(it / j) }))
                demo.internalMom?.let { im ->
                    val hh = dt / P.constInt("SUBSTEPS")
                    println("       снятие: остаток группы %+.3f рад/с".format(im.dbgLint / hh / j))
                    im.dbgLint = 0.0
                    im.dbgCell = cell
                }
                // ТЯГА: прирост скорости центра масс за окно, клеток/тик. Вдоль — по текущему
                // направлению движения куска, поперёк — то, что гнёт траекторию.
                val pm = maskP(mask)
                val mm = if (pm[2] > 0.0) pm[2] else 1.0
                val k2 = dt / meanLink / mm
                val vcx = pm[0] * k2; val vcy = pm[1] * k2
                val vl = sqrt(vcx * vcx + vcy * vcy)
                val ux = if (vl > 1e-12) vcx / vl else 1.0; val uy = if (vl > 1e-12) vcy / vl else 0.0
                fun along(x: Double, y: Double) = (x * ux + y * uy) * k2 * 1000.0
                fun across(x: Double, y: Double) = (-x * uy + y * ux) * k2 * 1000.0
                println("       ДВИЖЕНИЕ: скорость центра масс %.4f кл/тик; прирост за окно, 1e-3 кл/тик, вдоль/поперёк: всего %+.2f/%+.2f | %s".format(
                    vl, along(spinActPx, spinActPy), across(spinActPx, spinActPy),
                    stageNames.indices.filter { kk -> Math.abs(spinPx[kk]) + Math.abs(spinPy[kk]) > 0.0 }.joinToString(" | ") { kk ->
                        "%s %+.2f/%+.2f".format(stageNames[kk], along(spinPx[kk], spinPy[kk]), across(spinPx[kk], spinPy[kk]))
                    }))
                spinPx.fill(0.0); spinPy.fill(0.0); spinActPx = 0.0; spinActPy = 0.0
                spinAcc.fill(0.0); spinActual = 0.0
            }
        }
        if (tk >= from - 1 && tk < to) {
            val org: IntArray = P.get("organismOf")
            val o = org[cell]
            val m = BooleanArray(P.n) { org[it] == o }
            // Пятый параметр 1 — вся группа тел, соединённых контактами с этим куском.
            if (sp.getOrElse(4) { 0 } == 1) {
                val ct = P.contactsObj()
                val inG = HashSet<Int>(); inG.add(o)
                var grew = true
                while (grew && ct != null) {
                    grew = false
                    for (k in 0 until ct.contactCount) {
                        val a = org[ct.contactI(k)]; val b = org[ct.contactJ(k)]
                        if (a in inG && b !in inG) { inG.add(b); grew = true }
                        if (b in inG && a !in inG) { inG.add(a); grew = true }
                    }
                }
                for (i in 0 until P.n) m[i] = org[i] in inG
                if (tk == from - 1) println("       ГРУППА: " + inG.sorted().joinToString("  ") { g ->
                    val cells = (0 until P.n).filter { org[it] == g }
                    val first = cells.first()
                    val ins = insideTissue(first)
                    "орг $g: клеток ${cells.size}, клетка #$first" +
                        (if (ins >= 0 && !insideOwn) ", внутри ткани орг $ins на %.2f св".format(insideDepth / meanLink) else "")
                })
            }
            demo.dbgOnly = m; demo.dbgStages = true
            demo.dbgStageL.fill(0.0); demo.dbgStagePx.fill(0.0); demo.dbgStagePy.fill(0.0)
            demo.dbgStageAbs.fill(0.0)
            spinMask = m
            spinL0 = maskLJ(m)[0]
            val p0 = maskP(m); spinP0x = p0[0]; spinP0y = p0[1]
        } else {
            demo.dbgStages = false; demo.dbgOnly = null; spinMask = null
        }
    }

    // ------------------------------------------------------------------
    //  ПОШАГОВЫЙ СЛЕД КЛЕТКИ: PR_TRACE=клетка,тикС,тикПо
    //
    //  Каждый подшаг: где клетка, в чьей ткани, ближайшая чужая клетка и ближайшее
    //  чужое граничное ребро — расстояние до него, с какой стороны, и есть ли зазор в
    //  мембране (длина ребра против суммы контактных радиусов его концов). Этим видно,
    //  КАК клетка попала внутрь: протиснулась в щель, влетела в дыру разрыва или
    //  перескочила ребро за подшаг.
    // ------------------------------------------------------------------
    val traceSpec = System.getenv("PR_TRACE")?.split(',')?.map { it.trim().toInt() }
    var traceTick = 0
    fun traceSubstep(step: Int) {
        val sp = traceSpec ?: return
        val i = sp[0]
        val ct = P.contactsObj() ?: return
        val org: IntArray = P.get("organismOf")
        val dead: BooleanArray = P.get("cellDead")
        val isFree: BooleanArray = P.get("isFree")
        val ba: IntArray = P.get("boundA"); val bb: IntArray = P.get("boundB"); val bc: Int = P.get("boundCount")
        val x = P.px[i]; val y = P.py[i]
        val ri = ct.contactRadiusOf(i)
        val v = sqrt(P.vx[i] * P.vx[i] + P.vy[i] * P.vy[i]) * dt / meanLink
        val ins = insideTissue(i)
        // ближайшая чужая клетка
        var nj = -1; var nd = Double.MAX_VALUE
        for (j in 0 until P.n) {
            if (j == i || dead[j] || org[j] == org[i]) continue
            val dx = P.px[j] - x; val dy = P.py[j] - y
            val d = sqrt(dx * dx + dy * dy)
            if (d < nd) { nd = d; nj = j }
        }
        // ближайшее чужое граничное ребро
        var be = -1; var bd = Double.MAX_VALUE; var bs = 0.0
        for (e in 0 until bc) {
            val a = ba[e]; val b = bb[e]
            if (org[a] == org[i] || dead[a] || dead[b]) continue
            val ax = P.px[a]; val ay = P.py[a]
            val ex = P.px[b] - ax; val ey = P.py[b] - ay
            val len2 = ex * ex + ey * ey
            var s = if (len2 < 1e-18) 0.0 else ((x - ax) * ex + (y - ay) * ey) / len2
            if (s < 0.0) s = 0.0 else if (s > 1.0) s = 1.0
            val qx = ax + ex * s - x; val qy = ay + ey * s - y
            val d = sqrt(qx * qx + qy * qy)
            if (d < bd) { bd = d; be = e; bs = ex * (y - ay) - ey * (x - ax) }
        }
        val edgeStr = if (be < 0) "нет" else {
            val a = ba[be]; val b = bb[be]
            val ex = P.px[b] - P.px[a]; val ey = P.py[b] - P.py[a]
            val len = sqrt(ex * ex + ey * ey)
            val seal = ct.contactRadiusOf(a) + ct.contactRadiusOf(b)
            "#%d-#%d орг %d: до ребра %.3f св, сторона %s, длина %.3f св, щель %+.3f св".format(
                a, b, org[a], bd / meanLink, if (bs > 0) "+" else "-", len / meanLink, (len - seal) / meanLink)
        }
        var cc = 0; var cmin = 9.0
        for (k in 0 until ct.contactCount) {
            val a = ct.contactI(k); val b = ct.contactJ(k)
            if (a != i && b != i) continue
            val j = if (a == i) b else a
            val dx = P.px[j] - x; val dy = P.py[j] - y
            val q = sqrt(dx * dx + dy * dy) / ct.contactDistanceOf(i, j)
            cc++; if (q < cmin) cmin = q
        }
        println("   %d.%02d  орг %d%s r=%.2f  v=%.2f кл/тик  %s  | ближайшая чужая #%d (орг %d, r=%.2f) на %.3f св | ребро %s | контактов %d%s | пересборок %d".format(
            traceTick, step, org[i], if (isFree[i]) " своб" else "", ri / meanLink, v,
            if (ins >= 0 && !insideOwn) "В ТКАНИ орг $ins" else "снаружи",
            nj, if (nj >= 0) org[nj] else -1, if (nj >= 0) ct.contactRadiusOf(nj) / meanLink else 0.0, nd / meanLink,
            edgeStr, cc, if (cc > 0) " (мин d/упор %.3f)".format(cmin) else "", demo.dbgRebuildN))
    }

    val pressureThresholds = doubleArrayOf(4.0, 8.0, 12.0, 16.0, 20.0)
    val pressureOver = Array(pressureThresholds.size) { HashSet<Int>() }

    var markNo = 0
    val eventNames = mapOf(
        "R" to "сброс", "B" to "кости жёсткие", "G" to "гребок", "K" to "контакты", "X" to "разрыв",
        "E" to "изгиб", "N" to "выбрана сцена", "S" to "запуск сцены", "P" to "пуля", "O" to "таран",
        "D" to "схватил клетку", "U" to "отпустил", "UNMARK" to "метка снята", "END" to "окно закрыто")

    // Опыт: клетка лопается от давления контактов выше PR_BURST связей за тик.
    System.getenv("PR_BURST")?.toDoubleOrNull()?.let { demo.pressureBurst = it; println("  ОПЫТ: лопание от давления > $it связей/тик") }
    if (System.getenv("PR_CRUSH_OFF") != null) { demo.crushBurstOn = false; println("  ОПЫТ: раздавленная клетка НЕ лопается") }
    System.getenv("PR_MEMBRANE")?.toDoubleOrNull()?.let { demo.membraneTearStrain = it; println("  ОПЫТ: порог разрыва мембраны $it (меньше нуля — общий)") }
    // Старт с выключенным снятием момента — включить с тика через PR_OFF_AT=тик,+spincancel.
    if (System.getenv("PR_SPINCANCEL_OFF") != null) demo.cancelSpinOn = false
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
            // ДАВЛЕНИЕ КОНТАКТОВ: PR_PRESSURE=тикС,тикПо[,клетка...] — наибольшее за тик,
            // сколько клеток выше порогов и давление перечисленных клеток. В связях.
            System.getenv("PR_PRESSURE")?.split(',')?.map { it.trim().toInt() }?.let { ps ->
                val tk = demo.currentTick
                if (tk in ps[0]..ps[1]) {
                    val pr = demo.contactPressure
                    val boneOf: IntArray = P.get("boneOf")
                    var mx = 0.0; var mi = -1; var c1 = 0; var c2 = 0; var c4 = 0; var mxSoft = 0.0
                    for (i in 0 until P.n) {
                        val p = pr[i] / meanLink
                        if (p > mx) { mx = p; mi = i }
                        if (boneOf[i] < 0 && p > mxSoft) mxSoft = p
                        if (p > 1.0) c1++; if (p > 2.0) c2++; if (p > 4.0) c4++
                    }
                    for (i in 0 until P.n) {
                        if (boneOf[i] >= 0) continue
                        val p = pr[i] / meanLink
                        for ((k, th) in pressureThresholds.withIndex()) if (p > th) pressureOver[k].add(i)
                    }
                    if (System.getenv("PR_PRESSURE_QUIET") == null) println("  ДАВЛЕНИЕ тик %d: макс %.3f (клетка #%d%s), мягкие макс %.3f, >1: %d, >2: %d, >4: %d%s".format(
                        tk, mx, mi, if (mi >= 0 && boneOf[mi] >= 0) " кость" else "", mxSoft, c1, c2, c4,
                        ps.drop(2).joinToString("") { c -> "  #%d=%.3f".format(c, pr[c] / meanLink) }))
                }
            }
            traceSpec?.let { tr ->
                val tk = demo.currentTick
                if (tk >= tr[1] && tk < tr[2]) {
                    traceTick = tk
                    demo.dbgRebuildN = 0
                    demo.dbgSubstepHook = { s -> traceSubstep(s) }
                } else demo.dbgSubstepHook = null
            }
            // Эксперимент поверх журнала: PR_OFF_AT=тик,что — с тика выключить среду
            // (medium) или контакты (contacts). Контрольные суммы после этого, конечно,
            // расходятся — это уже не сессия игрока, а опыт на её состоянии.
            System.getenv("PR_OFF_AT")?.split(',')?.let { spec ->
                val at = spec[0].trim().toInt()
                if (demo.currentTick == at) for (what in spec.drop(1)) when (what.trim()) {
                    "medium" -> { P.setDragOff(true); demo.dbgIsoDragOff = true }
                    "aniso" -> P.setDragOff(true)
                    "bones" -> P.setBonesRigid(false)
                    "spincancel" -> demo.cancelSpinOn = false
                    // Направление обхода связей: см. SWEEP_FLIP. Переключается ПОСРЕДИ
                    // журнала, чтобы сравнивать на одной и той же расстановке тел.
                    "sweep0" -> demo.sweepFlip = 0
                    "sweep2" -> demo.sweepFlip = 2
                    // Мышца перестаёт укорачивать: глубина сокращения ровно 1.
                    "muscleoff" -> demo.muscleContraction = 1.0
                    "+spincancel" -> demo.cancelSpinOn = true
                    "contacts" -> P.setContacts(false)
                }
            }
            // Опыт: PR_REMOVE_AT=тик,клетка,клетка... — унести организмы этих клеток далеко и
            // остановить. Проверка «виноваты ли именно они».
            System.getenv("PR_REMOVE_AT")?.split(',')?.map { it.trim().toInt() }?.let { rm ->
                if (demo.currentTick == rm[0]) {
                    val org: IntArray = P.get("organismOf")
                    val orgs = rm.drop(1).map { org[it] }.toSet()
                    var k = 0
                    for (i in 0 until P.n) if (org[i] in orgs) {
                        P.px[i] += 500.0 + 3.0 * (k % 50); P.py[i] += 500.0 + 3.0 * (k / 50); k++
                        P.prevX[i] = P.px[i]; P.prevY[i] = P.py[i]; P.vx[i] = 0.0; P.vy[i] = 0.0
                    }
                    println("  ОПЫТ: унесено клеток $k из организмов $orgs")
                }
            }
            spinTick(demo.currentTick)
            // Хронология удара: PR_TIMELINE=тикС,тикПо
            System.getenv("PR_TIMELINE")?.split(',')?.map { it.trim().toInt() }?.let { tl ->
                val tk = demo.currentTick
                if (tk >= tl[0] && tk <= tl[1]) timelineRow(tk)
                demo.dbgMaxOverStrain = 0.0
            }
            // КАСАНИЯ МЕЖДУ РАЗНЫМИ ОРГАНИЗМАМИ ЗА ВСЮ СЕССИЮ: PR_CROSS=1[,клетка].
            //
            // Ищем не «где помечено», а ГДЕ ВООБЩЕ два тела упёрлись друг в друга: пара
            // контакта, у которой концы в разных организмах. Печатаем тик, самую глубокую
            // такую пару и скорости центров масс обоих тел — сразу видно, толкнуло ли.
            System.getenv("PR_CROSS")?.split(',')?.let { cs ->
                val ct = P.contactsObj()
                if (ct != null) {
                    val org: IntArray = P.get("organismOf")
                    val dead: BooleanArray = P.get("cellDead")
                    var wi = -1; var wj = -1; var worst = 9.0
                    for (k in 0 until ct.contactCount) {
                        val a = ct.contactI(k); val b = ct.contactJ(k)
                        if (dead[a] || dead[b] || org[a] == org[b]) continue
                        val q = Math.hypot(P.px[a] - P.px[b], P.py[a] - P.py[b]) / ct.contactDistanceOf(a, b)
                        if (q < worst) { worst = q; wi = a; wj = b }
                    }
                    if (wi >= 0 && worst < 1.0) {
                        fun com(o: Int): Double {
                            var m = 0.0; var vx = 0.0; var vy = 0.0
                            for (i in 0 until P.n) {
                                if (dead[i] || org[i] != o || P.invMass[i] <= 0.0) continue
                                val w = 1.0 / P.invMass[i]; m += w; vx += w * P.vx[i]; vy += w * P.vy[i]
                            }
                            return if (m <= 0.0) 0.0 else Math.hypot(vx / m, vy / m) * dt / meanLink
                        }
                        val watch = cs.getOrNull(1)?.trim()?.toIntOrNull()
                        println("  КАСАНИЕ тик %d: #%d орг %d — #%d орг %d, %.3f упора; центры масс %.4f и %.4f клетки/тик%s".format(
                            demo.currentTick, wi, org[wi], wj, org[wj], worst, com(org[wi]), com(org[wj]),
                            if (watch == null) "" else ", клетка #%d %.4f".format(
                                watch, Math.hypot(P.vx[watch], P.vy[watch]) * dt / meanLink)))
                    }
                }
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
    if (System.getenv("PR_PRESSURE") != null) {
        println("--- давление: сколько мягких клеток хоть раз превысили порог (связей за тик) ---")
        for ((k, th) in pressureThresholds.withIndex()) println("  > %.0f: %d клеток".format(th, pressureOver[k].size) +
            if (watched.isNotEmpty()) ", из помеченных " + watched.count { it in pressureOver[k] } + " из " + watched.size else "")
    }
    System.getenv("PR_TRAP_BENCH")?.let {
        for (k in 0 until 300) demo.scanTrapped()
        val reps = 2000
        val t0 = System.nanoTime()
        for (k in 0 until reps) demo.scanTrapped()
        val per = (System.nanoTime() - t0) / 1e3 / reps
        val tn: Int = P.get("triCount")
        println("--- обход застрявших отдельно: %.1f мкс за вызов, треугольников %d, клеток %d ---".format(per, tn, P.n))
    }
    // ПРОГРЕВ: тот же журнал прогоняется ещё раз на свежем демо, и печатается, во что
    // обошлись те же тики уже прогретому JIT. PR_TWICE=тик1,тик2,...
    System.getenv("PR_TWICE")?.let { spec ->
        val marks = spec.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        for (pass in 1..2) {
            val d2 = RealBodyDemo(bodyPath,
                copies = hdr["copies"]?.toInt() ?: 2,
                freeParticles = hdr["free"]?.toInt() ?: 6,
                labKillFraction = hdr["killFraction"]?.toDouble() ?: 0.0,
                labKillSeed = hdr["killSeed"]?.toLong() ?: 0L,
                killOnDeep = hdr["killOnDeep"] == "1")
            d2.replayBoot()
            var worst = 0.0; var worstTick = -1
            val shown = StringBuilder()
            var sum = 0.0
            d2.replayRun(log.lines, onEvent = {}, onTick = {
                val ms = d2.dbgTickNs / 1e6
                sum += ms
                if (ms > worst) { worst = ms; worstTick = d2.currentTick }
                if (d2.currentTick in marks) shown.append("  тик %d: %.1f мс".format(d2.currentTick, ms))
            })
            println("  проход %d: худший тик %d — %.1f мс, всего %.0f мс%s".format(pass, worstTick, worst, sum, shown))
        }
    }
    // КТО ЕЩЁ КРУТИТСЯ К КОНЦУ ЗАПИСИ: PR_SPINS=1. Считается по КАЖДОМУ организму, а не по
    // помеченной клетке, — с изменённой физикой журнал расходится с записью, и следить за
    // конкретным куском бессмысленно, а «остался ли в мире вечно крутящийся кусок» — нет.
    if (System.getenv("PR_SPINS") != null) {
        val org: IntArray = P.get("organismOf")
        val dead: BooleanArray = P.get("cellDead")
        val count: Int = P.get("organismCount")
        val cx = DoubleArray(count); val cy = DoubleArray(count); val mm = DoubleArray(count)
        val vxs = DoubleArray(count); val vys = DoubleArray(count); val cells = IntArray(count)
        for (i in 0 until P.n) {
            if (dead[i] || P.invMass[i] <= 0.0) continue
            val o = org[i]; val m = 1.0 / P.invMass[i]
            mm[o] += m; cx[o] += m * P.px[i]; cy[o] += m * P.py[i]
            vxs[o] += m * P.vx[i]; vys[o] += m * P.vy[i]; cells[o]++
        }
        for (o in 0 until count) if (mm[o] > 0.0) { cx[o] /= mm[o]; cy[o] /= mm[o]; vxs[o] /= mm[o]; vys[o] /= mm[o] }
        val lj = DoubleArray(count); val jj = DoubleArray(count)
        for (i in 0 until P.n) {
            if (dead[i] || P.invMass[i] <= 0.0) continue
            val o = org[i]; val m = 1.0 / P.invMass[i]
            val rx = P.px[i] - cx[o]; val ry = P.py[i] - cy[o]
            lj[o] += m * (rx * (P.vy[i] - vys[o]) - ry * (P.vx[i] - vxs[o]))
            jj[o] += m * (rx * rx + ry * ry)
        }
        var fast = 0
        val rows = ArrayList<String>()
        for (o in 0 until count) {
            if (cells[o] < 2 || jj[o] <= 1e-18) continue
            val w = lj[o] / jj[o]
            if (Math.abs(w) > 1.0) fast++
            rows.add("%9.3f|%d|%d".format(Math.abs(w), cells[o], o))
        }
        rows.sortDescending()
        println("--- вращение кусков в конце записи ---")
        println("  кусков с |ω| > 1 рад/с: $fast из ${rows.size}")
        for (r in rows.take(6)) {
            val p = r.split('|')
            println("    орг %s, клеток %s: |ω| %.3f рад/с".format(p[2], p[1], p[0].trim().toDouble()))
        }
        // Сколько проплыл самый крупный организм: столько же тяги, сколько и было?
        var big = 0
        for (o in 0 until count) if (cells[o] > cells[big]) big = o
        println("  самый крупный кусок: орг %d, клеток %d, центр масс (%.3f, %.3f), скорость %.4f кл/тик".format(
            big, cells[big], cx[big], cy[big],
            Math.hypot(vxs[big], vys[big]) * dt / meanLink))
    }
    demo.dbgTearLog?.let { if (it.isNotEmpty()) { println("--- разрывы ---"); print(it) } }
    if (demo.dbgTimeOn) {
        val ns = demo.dbgTimeNs
        val tot = ns.sum().toDouble()
        P.contactsObj()?.let { ct ->
            println("--- широкая фаза: перестроек %d на %d вызовов (%.2f на подшаг), пропущено пар %d ---"
                .format(ct.bpBuilds, ct.bpCalls, if (ct.bpCalls == 0L) 0.0 else ct.bpBuilds.toDouble() / ct.bpCalls, ct.bpMissed))
        }
        println("--- стадии за всю сессию, доля ---")
        for (k in ns.indices.sortedByDescending { ns[it] }) {
            if (ns[k] == 0L) continue
            println("    %-22s %5.1f%%  (%.0f мс)".format(demo.dbgTimeNames[k], ns[k] / tot * 100, ns[k] / 1e6))
        }
    }
    if (System.getenv("PR_REBUILD") != null) {
        val r = demo.dbgRebuildTot
        println("--- пересборка за сессию: всего %d штук, %.0f мс ---".format(demo.dbgRebuildCount, r.sum() / 1e6))
        val names = arrayOf("списки", "контур", "изгиб+лоскуты", "организмы", "контакты")
        for (k in names.indices) println("    %-16s %.0f мс".format(names[k], r[k] / 1e6))
    }
    if (demo.dbgSealOn) {
        println(("--- мембрана: наименьший радиус контакта %.4f связи, проверок %d, " +
            "коридор шире 2r у %d, худший %.3f от нужного (#%d-#%d) ---")
            .format(SealStats.minR, SealStats.checks, SealStats.over, SealStats.worst, SealStats.worstI, SealStats.worstJ))
    }
    // ЧТО С ЁМКОСТЬЮ В КОНЦЕ СЕССИИ: сколько засыпки живо и сколько осталось внутри
    // кольца. Пустой на вид круг должен отличаться от круга, из которого всё удрало или
    // в котором всё полопалось.
    if (BenchScenes.lastRadius > 0.0) {
        val dead: BooleanArray = P.get("cellDead")
        var alive = 0; var inside = 0
        for (k in BenchScenes.lastInside) {
            if (!dead[k]) alive++
            val dx = P.px[k] - BenchScenes.lastCx; val dy = P.py[k] - BenchScenes.lastCy
            if (!dead[k] && dx * dx + dy * dy <= BenchScenes.lastRadius * BenchScenes.lastRadius) inside++
        }
        println("--- ёмкость: засыпка %d, из них живы %d, внутри кольца %d ---"
            .format(BenchScenes.lastFill, alive, inside))
    }
    println("--- итог ---")
    println("  тиков воспроизведено ${demo.currentTick} (${sec(demo.currentTick)}) за %.1f с".format(secs))
    println("  в чужой ткани и вышли сами, по длительности (тиков): " + demo.dbgTrapEdges.indices.joinToString("  ") { b ->
        val lo = if (b == 0) 1 else demo.dbgTrapEdges[b - 1] + 1
        val hi = demo.dbgTrapEdges[b]
        (if (hi == Int.MAX_VALUE) "$lo+" else if (lo == hi) "$lo" else "$lo-$hi") + ": " + demo.dbgTrapExit[b]
    } + "  | убито застрявших ${demo.trapKillCount}")
    println("  складки своей ткани и вышли сами, по длительности (тиков): " + demo.dbgTrapEdges.indices.joinToString("  ") { b ->
        val lo = if (b == 0) 1 else demo.dbgTrapEdges[b - 1] + 1
        val hi = demo.dbgTrapEdges[b]
        (if (hi == Int.MAX_VALUE) "$lo+" else if (lo == hi) "$lo" else "$lo-$hi") + ": " + demo.dbgFoldExit[b]
    } + "  | убито складок ${demo.foldKillCount}")
    val ok = demo.replayHashOk; val bad = demo.replayHashBad
    if (ok + bad == 0) println("  контрольных сумм в журнале нет — совпадение с игрой не проверено")
    else if (bad == 0) println("  контрольные суммы: все $ok сошлись — воспроизведение побитово то же, что видел игрок")
    else println("  КОНТРОЛЬНЫЕ СУММЫ: не сошлось $bad из ${ok + bad}, первая на тике ${demo.replayFirstBadTick}. " +
        "Всё после этого тика — уже не та сессия.")
}
