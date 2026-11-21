package io.github.some_example_name.physics

/*
 * ВРЕМЕННЫЕ СВЯЗИ РОСТА (находка 32, docs/physics/reference.md). Связь поперёк граней взрослой раскладки — диагональ
 * четырёхугольника, связь через клетку, «перекладина» — физике не отдаётся: голая связь поверх ткани для неё край тела
 * посреди него (контур, среда, касания; решение 03.11, LayoutBodies в :core). Но растущее тело ткани под ней ещё не
 * имеет: у Crayfish на стадии 5 горизонтальные пары двенадцати клеток связаны в геноме, а клетки, которые лягут на
 * эти связи, родятся только на стадии 8 — и до тех пор три цепочки клеток висели без связей между собой. У 49 геномов
 * из 158 есть даже дочери, связанные с родителем только такой связью (1592 рождения из 94 377): они рождались вовсе
 * без связи с родителем.
 *
 * Поэтому такая связь — временная: физика держит её, пока поверх неё не легла ткань, и снимает в тот миг, когда это
 * случается. Ложится ткань — по той же геометрии, по которой связь и признана связью поперёк граней
 * (LayoutTriangles.overTissue в :core), и в тот миг, когда это становится правдой у растущего тела:
 *  - рождается клетка, центр которой ближе четверти длины связи к отрезку между 10% и 90% его длины;
 *  - оживает связь, собственно пересекающая её (отрезки без общих концов), — то есть рождён второй её конец.
 *    Пересечение — со всеми связями, не только со сторонами граней: две пересекающиеся временные связи вместе не живут.
 * Первый вариант (20.11) снимал связь уже при рождении ПЕРВОГО конца пересекающей связи, когда той ещё нет: связи
 * жили мало, рост генома почти не выиграл (рождений не на месте 227 → 225 сцен), а гибель клеток в своей ткани выросла
 * (находка 32).
 *
 * ЗАПЕЧЕНО В ОПИСАНИЕ: что снимает связь, считается один раз по позе описания ([withTempLinks]) и лежит в
 * BodyDefinition.linkRetireStart/linkRetireCells парами «клетка, вторая клетка» (−1 — клетка-одиночка; в снимке мира —
 * с версии 7). Во время роста снятие пошаговое: рождение клетки проверяет только свои пары ([retireTempEdgesOf],
 * указатель по клетке строит [rebuildTempRetire]) — без пересчёта по миру. Тело, поставленное взрослым, временных
 * связей не получает вовсе (BodyDefinition.forBorn): оно побитово прежнее.
 *
 * СНЯТИЕ ТИХОЕ: связь гаснет, но не идёт ни в счёт разрывов (tornTotal), ни в журнал гибели рёбер, по которому игра
 * удаляет свою связь (WorldPhysics.reportDeaths): в игре связь остаётся, физика лишь перестаёт её держать. Кость она
 * не делит (BoneSplit): кусок кости — по описанию, где связь поперёк граней тоже скрепляет кость. Клетка, прошедшая
 * сквозь временную связь (пробой, applyPierce), тоже снимает её тихо: ткань там уже есть.
 *
 * МОСТ НЕ СНИМАЕТСЯ: если без связи её концы теряют путь друг к другу по живым связям, она ждёт обходного пути — правило
 * игры «связь поперёк лучше оторванной клетки» ([retireOrWait], [retirePendingTempEdges]).
 */

/**
 * Описание с временными связями роста: к связям описания дописываются [tempA]/[tempB] (связи поперёк граней взрослой
 * раскладки), каждая — с парами, рождение которых её снимает, по позе описания. Связь, на которую не ложится ничего,
 * не дописывается: снять её было бы нечем. Цена — сетка по клеткам и связям, раз на тело.
 */
fun BodyDefinition.withTempLinks(tempA: IntArray, tempB: IntArray): BodyDefinition {
    require(!hasTempLinks) { "временные связи у описания уже есть" }
    require(tempA.size == tempB.size)
    if (tempA.isEmpty()) return this
    val allA = linkA + tempA; val allB = linkB + tempB
    val blockers = crossingBlockers(x, y, allA, allB, IntArray(tempA.size) { linkCount + it })
    var kept = 0; var intsN = 0
    for (b in blockers) if (b.isNotEmpty()) { kept++; intsN += b.size }
    if (kept == 0) return this
    val la = linkA.copyOf(linkCount + kept); val lb = linkB.copyOf(linkCount + kept)
    val start = IntArray(linkCount + kept + 1)
    val pairs = IntArray(intsN)
    var m = linkCount; var c = 0
    for (q in tempA.indices) {
        val b = blockers[q]
        if (b.isEmpty()) continue
        la[m] = tempA[q]; lb[m] = tempB[q]
        for (v in b) pairs[c++] = v
        start[++m] = c / 2
    }
    return BodyDefinition(x, y, radius, isBone, isMuscle, la, lb, triA, triB, triC, triRestArea2, boneClusters, muscleClusters, scale,
        start, pairs)
}

/**
 * Что ложится на связи [temp] (номера в [linkA]/[linkB]), парами подряд: (клетка, −1) — клетка лежит на связи;
 * (u, v) — связь u–v собственно пересекает её (см. заголовок файла). Результат — по связи, пары по возрастанию.
 *
 * ПРОСТЫЕ ЦИКЛЫ, А НЕ ЗАМЫКАНИЯ. Первая версия (20.11) была одной функцией с локальными функциями и лямбдами, которые
 * захватывали изменяемые переменные; переписана классом с полями-массивами и короткими методами (ответ тот же —
 * сверка с перебором на всех телах геномов, CrossingCompileCheck). Повод был ложным: прогон стендов 20.11 в 21:24 упал,
 * когда у машины кончилась память, и последним выделял память компилятор C2 на этой функции. Но сам процесс занимал
 * 1.5 ГБ, а прежняя версия под C2 (23 700 вызовов и принудительная компиляция) не выросла выше 1.1 ГБ — память съел
 * не он (находка 32).
 */
fun crossingBlockers(x: FloatArray, y: FloatArray, linkA: IntArray, linkB: IntArray, temp: IntArray): Array<IntArray> {
    val result = arrayOfNulls<IntArray>(temp.size)
    if (x.isEmpty() || temp.isEmpty()) {
        for (q in temp.indices) result[q] = IntArray(0)
    } else {
        val grid = CrossingGrid(x, y, linkA, linkB)
        for (q in temp.indices) result[q] = grid.blockers(q, temp[q])
    }
    @Suppress("UNCHECKED_CAST")
    return result as Array<IntArray>
}

/** Сетка клеток и связей тела для [crossingBlockers]: только ускоряет поиск — что найдено, от её шага не зависит. */
private class CrossingGrid(private val x: FloatArray, private val y: FloatArray, private val linkA: IntArray, private val linkB: IntArray) {
    private val n = x.size
    private var minX = 0.0
    private var minY = 0.0
    private var h = 1.0
    private var cols = 1
    private var rows = 1
    private var pointStart = IntArray(0)
    private var points = IntArray(0)
    private var linkStart = IntArray(0)
    private var links = IntArray(0)
    private val seenCell = IntArray(n)
    private val seenLink = IntArray(linkA.size)
    private var found = IntArray(16)
    private var foundN = 0

    init {
        java.util.Arrays.fill(seenCell, -1)
        java.util.Arrays.fill(seenLink, -1)
        measure()
        binPoints()
        binLinks()
    }

    /** Рамка и шаг: по средней длине связи, у очень вытянутого тела крупнее — ячеек порядка клеток и связей. */
    private fun measure() {
        var sum = 0.0
        for (k in linkA.indices) {
            val dx = (x[linkA[k]] - x[linkB[k]]).toDouble(); val dy = (y[linkA[k]] - y[linkB[k]]).toDouble()
            sum += Math.sqrt(dx * dx + dy * dy)
        }
        minX = Double.MAX_VALUE; minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until n) {
            val px = x[i].toDouble(); val py = y[i].toDouble()
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        h = if (linkA.isEmpty()) 1.0 else Math.max(sum / linkA.size, 1e-9)
        val limit = 4.0 * (n + linkA.size) + 64
        while (((maxX - minX) / h + 1) * ((maxY - minY) / h + 1) > limit) h *= 2
        cols = ((maxX - minX) / h).toInt() + 1
        rows = ((maxY - minY) / h).toInt() + 1
    }

    private fun col(v: Double): Int {
        val c = ((v - minX) / h).toInt()
        return if (c < 0) 0 else if (c >= cols) cols - 1 else c
    }

    private fun row(v: Double): Int {
        val r = ((v - minY) / h).toInt()
        return if (r < 0) 0 else if (r >= rows) rows - 1 else r
    }

    private fun binPoints() {
        val cells = cols * rows
        pointStart = IntArray(cells + 1)
        for (i in 0 until n) pointStart[row(y[i].toDouble()) * cols + col(x[i].toDouble()) + 1]++
        for (g in 0 until cells) pointStart[g + 1] += pointStart[g]
        points = IntArray(n)
        val fill = pointStart.copyOf()
        for (i in 0 until n) points[fill[row(y[i].toDouble()) * cols + col(x[i].toDouble())]++] = i
    }

    /** Связь — во все ячейки своей рамки: два прохода, счёт и запись. */
    private fun binLinks() {
        val cells = cols * rows
        linkStart = IntArray(cells + 1)
        for (k in linkA.indices) countLink(k)
        for (g in 0 until cells) linkStart[g + 1] += linkStart[g]
        links = IntArray(linkStart[cells])
        val fill = linkStart.copyOf()
        for (k in linkA.indices) putLink(k, fill)
    }

    private fun countLink(k: Int) {
        val a = linkA[k]; val b = linkB[k]
        val c0 = col(Math.min(x[a], x[b]).toDouble()); val c1 = col(Math.max(x[a], x[b]).toDouble())
        val r0 = row(Math.min(y[a], y[b]).toDouble()); val r1 = row(Math.max(y[a], y[b]).toDouble())
        for (r in r0..r1) for (c in c0..c1) linkStart[r * cols + c + 1]++
    }

    private fun putLink(k: Int, fill: IntArray) {
        val a = linkA[k]; val b = linkB[k]
        val c0 = col(Math.min(x[a], x[b]).toDouble()); val c1 = col(Math.max(x[a], x[b]).toDouble())
        val r0 = row(Math.min(y[a], y[b]).toDouble()); val r1 = row(Math.max(y[a], y[b]).toDouble())
        for (r in r0..r1) for (c in c0..c1) links[fill[r * cols + c]++] = k
    }

    /** Пары, которые ложатся на связь [k]; [q] — номер запроса (метка «уже смотрели»). */
    fun blockers(q: Int, k: Int): IntArray {
        foundN = 0
        val a = linkA[k]; val b = linkB[k]
        val ax = x[a].toDouble(); val ay = y[a].toDouble()
        val dx = x[b] - ax; val dy = y[b] - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-24) return IntArray(0)
        val pad = Math.sqrt(len2 / 16.0)
        val c0 = col(Math.min(ax, ax + dx) - pad); val c1 = col(Math.max(ax, ax + dx) + pad)
        val r0 = row(Math.min(ay, ay + dy) - pad); val r1 = row(Math.max(ay, ay + dy) + pad)
        for (r in r0..r1) for (c in c0..c1) {
            scanPoints(r * cols + c, q, a, b, ax, ay, dx, dy, len2)
            scanLinks(r * cols + c, q, k, a, b)
        }
        return sortedPairs()
    }

    /** Клетки ячейки [g], лежащие на отрезке: проекция между 10% и 90% длины, до отрезка — меньше четверти длины. */
    private fun scanPoints(g: Int, q: Int, a: Int, b: Int, ax: Double, ay: Double, dx: Double, dy: Double, len2: Double) {
        val near2 = len2 / 16.0
        for (s in pointStart[g] until pointStart[g + 1]) {
            val o = points[s]
            if (o == a || o == b || seenCell[o] == q) continue
            seenCell[o] = q
            val ox = x[o] - ax; val oy = y[o] - ay
            val u = (ox * dx + oy * dy) / len2
            if (u < 0.1 || u > 0.9) continue
            val px = ox - u * dx; val py = oy - u * dy
            if (px * px + py * py < near2) add(o, -1)
        }
    }

    /** Связи ячейки [g], собственно пересекающие связь [k] (без общих концов). */
    private fun scanLinks(g: Int, q: Int, k: Int, a: Int, b: Int) {
        for (s in linkStart[g] until linkStart[g + 1]) {
            val j = links[s]
            if (j == k || seenLink[j] == q) continue
            seenLink[j] = q
            val u = linkA[j]; val v = linkB[j]
            if (u == a || u == b || v == a || v == b) continue
            if (properCross(a, b, u, v)) add(Math.min(u, v), Math.max(u, v))
        }
    }

    private fun orient(p: Int, q: Int, r: Int): Double =
        (x[q] - x[p]).toDouble() * (y[r] - y[p]).toDouble() - (y[q] - y[p]).toDouble() * (x[r] - x[p]).toDouble()

    private fun properCross(a: Int, b: Int, u: Int, v: Int): Boolean {
        val o1 = orient(a, b, u); val o2 = orient(a, b, v)
        if (!((o1 > 0.0 && o2 < 0.0) || (o1 < 0.0 && o2 > 0.0))) return false
        val o3 = orient(u, v, a); val o4 = orient(u, v, b)
        return (o3 > 0.0 && o4 < 0.0) || (o3 < 0.0 && o4 > 0.0)
    }

    private fun add(c: Int, p: Int) {
        if (foundN + 2 > found.size) found = found.copyOf(found.size * 2)
        found[foundN] = c; found[foundN + 1] = p
        foundN += 2
    }

    /** Найденные пары по возрастанию, без повторов: ключ — клетка, затем вторая клетка (−1 раньше всех). */
    private fun sortedPairs(): IntArray {
        val m = foundN / 2
        val keys = LongArray(m)
        for (s in 0 until m) keys[s] = found[2 * s].toLong() * (n + 1) + (found[2 * s + 1] + 1)
        keys.sort()
        var u = 0
        for (s in 0 until m) if (s == 0 || keys[s] != keys[s - 1]) keys[u++] = keys[s]
        val out = IntArray(2 * u)
        for (s in 0 until u) {
            out[2 * s] = (keys[s] / (n + 1)).toInt()
            out[2 * s + 1] = (keys[s] % (n + 1)).toInt() - 1
        }
        return out
    }
}

/*
 * КАСАНИЯ — КАК У ВЗРОСЛОГО. «Когда-либо связанные» пары и пары покоя (BoundaryContacts) запекаются по связям тела
 * один раз и от разрывов не зависят; временная связь в них не идёт: иначе после её снятия две клетки остались бы
 * «связанными когда-то» навсегда и касались бы иначе, чем во взрослом теле. Пока она жива, её концы не сталкиваются
 * так же, как концы любой живой связи (смежность касаний строится по живым связям решателя).
 */

/** Признак временного ребра по номеру ребра; null — временных связей в мире нет. */
internal fun BodyState.tempEdgeMask(): BooleanArray? {
    val d = body
    if (!d.hasTempLinks) return null
    val mask = BooleanArray(baked.edgeCount)
    for (l in 0 until d.linkCount) if (d.isTempLink(l)) mask[baked.linkEdge[l]] = true
    return mask
}

/** Пары связей решателя без временных — «когда-либо связанные» пары касаний (BoundaryContacts.linkedPairs). */
internal fun BodyState.permanentLinkedPairs(from: Int = 0, to: Int = conCount): LinkedPairs {
    val mask = tempEdgeMask() ?: return if (from == 0) BoundaryContacts.linkedPairs(conA, conB, to)
        else BoundaryContacts.linkedPairs(conA.copyOfRange(from, to), conB.copyOfRange(from, to), to - from)
    val a = IntArray(to - from); val b = IntArray(to - from); var m = 0
    for (c in from until to) if (!mask[conEdge[c]]) { a[m] = conA[c]; b[m] = conB[c]; m++ }
    return BoundaryContacts.linkedPairs(a, b, m)
}

/** Связи описания без временных: по ним считается радиус касания клеток (BoundaryContacts.maxContactRadius). */
internal fun BodyDefinition.permanentLinkA(): IntArray =
    if (!hasTempLinks) linkA else IntArray(linkCount - (0 until linkCount).count { isTempLink(it) }).also { out ->
        var m = 0; for (l in 0 until linkCount) if (!isTempLink(l)) out[m++] = linkA[l] }
internal fun BodyDefinition.permanentLinkB(): IntArray =
    if (!hasTempLinks) linkB else IntArray(linkCount - (0 until linkCount).count { isTempLink(it) }).also { out ->
        var m = 0; for (l in 0 until linkCount) if (!isTempLink(l)) out[m++] = linkB[l] }

/** Связь [e] ещё нужна: жива или ждёт рождения своих концов. */
private fun BodyState.tempEdgeLive(e: Int): Boolean = !baked.edgeDead[e] || baked.isUnborn(e)

/**
 * Указатель «клетка мира → временные рёбра, которые снимает её рождение» (CSR). Производное от описания (body) и
 * номеров рёбер: строится заново при загрузке, дописывании тела, уплотнении, чтении снимка и сбросе; в снимок не входит.
 * Снятые и порванные рёбра в указатель не идут.
 */
internal fun BodyState.rebuildTempRetire() {
    val d = body
    tempPendingN = 0
    if (!d.hasTempLinks) { tempRetireStart = IntArray(0); tempRetireEdge = IntArray(0); tempRetireOther = IntArray(0); tempEdge = BooleanArray(0); return }
    val rs = d.linkRetireStart; val rc = d.linkRetireCells
    // Пара (c, p) снимает связь: клетка-одиночка (p = −1) — рождением c; пересекающая связь — рождением второго из c, p.
    // Поэтому пара записана у обеих клеток: у каждой — вторая клетка пары.
    val start = IntArray(n + 1)
    val mask = BooleanArray(baked.edgeCount)
    for (l in 0 until d.linkCount) {
        if (rs[l + 1] == rs[l]) continue
        val e = baked.linkEdge[l]
        // Временная — свойство описания, а не состояния: снятая связь остаётся временной (divisionPlacement не сочтёт её
        // связью с родителем). В указатель идут только те, что ещё нужны.
        mask[e] = true
        if (!tempEdgeLive(e)) continue
        for (s in rs[l] until rs[l + 1]) {
            start[rc[2 * s] + 1]++
            if (rc[2 * s + 1] >= 0) start[rc[2 * s + 1] + 1]++
        }
    }
    for (i in 0 until n) start[i + 1] += start[i]
    val edges = IntArray(start[n]); val other = IntArray(start[n]); val fill = start.copyOf()
    for (l in 0 until d.linkCount) {
        if (rs[l + 1] == rs[l]) continue
        val e = baked.linkEdge[l]
        if (!tempEdgeLive(e)) continue
        var due = false
        for (s in rs[l] until rs[l + 1]) {
            val c = rc[2 * s]; val p = rc[2 * s + 1]
            edges[fill[c]] = e; other[fill[c]++] = p
            if (p >= 0) { edges[fill[p]] = e; other[fill[p]++] = c }
            if (bornAlive(c) && (p < 0 || bornAlive(p))) due = true
        }
        // Живая связь, на которую ткань уже легла, — значит, её держит правило моста: ждёт обходного пути.
        if (due && !baked.edgeDead[e]) addTempPending(e)
    }
    tempRetireStart = start; tempRetireEdge = edges; tempRetireOther = other; tempEdge = mask
}

private fun BodyState.bornAlive(c: Int): Boolean = !cellUnborn[c] && !cellDead[c]

/**
 * ПРАВИЛО МОСТА — как у игры: «связь поперёк лучше оторванной клетки» (LayoutTriangles.crossingLinks). Ткань легла на
 * временную связь, но её концы иначе как через неё друг с другом не связаны — снять её значит оторвать клетку от тела
 * (Armored Glist, 20.11: у клетки 556 к телу вели только временные связи, рождение её дочери 538 сняло обе, пара осталась
 * внутри тела сама по себе и погибла «в своей ткани», а 14 потомкам стало негде родиться). Такая связь ждёт: на каждом
 * рождении проверяется, не появился ли обходной путь ([retirePendingTempEdges]).
 *
 * Путь ищется обходом в ширину по живым рёбрам от одного конца к другому, не дальше [TEMP_BRIDGE_REACH] клеток: обходной
 * путь в ткани короткий (через саму клетку, легшую на связь, — две связи); не нашёлся за предел — связь ждёт дальше.
 */
private const val TEMP_BRIDGE_REACH = 1024

private fun BodyState.addTempPending(e: Int) {
    if (tempPendingN == tempPending.size) tempPending = tempPending.copyOf(maxOf(8, tempPendingN * 2))
    tempPending[tempPendingN++] = e
}

/** Связаны ли концы живого ребра [e] другим путём по живым рёбрам (не дальше [TEMP_BRIDGE_REACH] клеток обхода). */
private fun BodyState.connectedWithout(e: Int): Boolean {
    val u = baked.edgeU(e); val v = baked.edgeV(e)
    if (tempStamp.size < n) tempStamp = IntArray(n)
    if (tempQueue.size < TEMP_BRIDGE_REACH) tempQueue = IntArray(TEMP_BRIDGE_REACH)
    if (tempStampId == Int.MAX_VALUE) { tempStamp.fill(0); tempStampId = 0 }
    val id = ++tempStampId
    val vs = baked.vertStart; val ve = baked.vertEdge; val vo = baked.vertOther; val dead = baked.edgeDead
    var head = 0; var tail = 0
    tempQueue[tail++] = u; tempStamp[u] = id
    while (head < tail) {
        val x = tempQueue[head++]
        for (s in vs[x] until vs[x + 1]) {
            val f = ve[s]
            if (f == e || dead[f]) continue
            val y = vo[s]
            if (y == v) return true
            if (tempStamp[y] == id) continue
            if (tail == TEMP_BRIDGE_REACH) return false
            tempStamp[y] = id; tempQueue[tail++] = y
        }
    }
    return false
}

/** Снять ребро, на которое легла ткань: нерождённое — сразу; живое — если оно не мост, иначе ждать обходного пути. */
private fun BodyState.retireOrWait(e: Int) {
    if (baked.isUnborn(e)) { retireUnbornEdge(e, quiet = true); return }
    if (baked.edgeDead[e]) return
    if (connectedWithout(e)) killEdge(e, split = true, quiet = true) else addTempPending(e)
}

/** После рождения: ждущие временные связи, у концов которых появился обходной путь, сняты. Цена — по ждущим. */
internal fun BodyState.retirePendingTempEdges() {
    var m = 0
    for (k in 0 until tempPendingN) {
        val e = tempPending[k]
        if (baked.edgeDead[e]) continue
        if (connectedWithout(e)) killEdge(e, split = true, quiet = true) else tempPending[m++] = e
    }
    tempPendingN = m
}

/**
 * Клетка [i] рождается (activateCell): снять временные связи, на которые ложится ткань — она сама или ожившая с ней
 * пересекающая связь (её второй конец уже рождён и жив). Нерождённая связь (её конец ещё не родился) уже не родится;
 * живая гаснет тихо (см. заголовок файла). Цена — по парам клетки.
 */
internal fun BodyState.retireTempEdgesOf(i: Int) {
    val start = tempRetireStart
    if (start.isEmpty()) return
    for (s in start[i] until start[i + 1]) {
        val p = tempRetireOther[s]
        if (p >= 0 && (cellUnborn[p] || cellDead[p])) continue
        retireOrWait(tempRetireEdge[s])
    }
    // Ожившие связи новорождённой могли дать обходной путь связям, которые ждали.
    if (tempPendingN > 0) retirePendingTempEdges()
}

/** Снять временную связь [e] тихо: нерождённая уже не родится, живая гаснет без разрыва и без журнала для клиента. */
internal fun BodyState.retireTempEdge(e: Int) {
    if (baked.isUnborn(e)) retireUnbornEdge(e, quiet = true)
    else if (!baked.edgeDead[e]) killEdge(e, split = true, quiet = true)
}

/** Временная ли связь роста ребро [e] (снимается тихо, в том числе при пробое — applyPierce). */
internal fun BodyState.isTempEdge(e: Int): Boolean = e < tempEdge.size && tempEdge[e]

/** Сброс сделал целыми все тела — их клетки рождены, и временные связи роста сняты все. true — было что снять. */
internal fun BodyState.retireAllTempEdges(): Boolean {
    val d = body
    if (!d.hasTempLinks) return false
    var any = false
    for (l in 0 until d.linkCount) {
        if (!d.isTempLink(l)) continue
        val e = baked.linkEdge[l]
        if (!tempEdgeLive(e)) continue
        retireTempEdge(e); any = true
    }
    return any
}
