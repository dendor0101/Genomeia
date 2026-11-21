package io.github.some_example_name.physics

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * КЛИН: БЫСТРЫЙ УДАР ПРИНИМАЕТ НЕ ОДНА КЛЕТКА КОНТУРА, А КЛИН ТКАНИ ЗА НЕЙ (принят 18.11.2026).
 *
 * ЗАЧЕМ. Сталкиваются только клетки контура. Касание толкает одну клетку, а ткань за ней ещё летит
 * («паровозик»): за подшаг её вдавливают обратно свои же связи, центр клетки заходит в чужой радиус —
 * клетка гибнет или тела проникают друг в друга. Перескока частиц при этом нет (обрезка по времени
 * удара), и до 4 связей за тик клетку вдавливает именно ткань за ней, а не влёт за подшаг
 * (docs/physics/reference.md, находка 18). Жёсткие связи при шести подшагах это только усиливают
 * (находки 17, 19, 21), двенадцать подшагов лечат, но стоят +61…68% тика.
 *
 * ЗАКОН. У перекрытой пары клеток разных тел (и у упора в неподвижное) перед обычным проходом касаний
 * снимается то, на что пара сблизилась за этот подшаг (не больше перекрытия), — смещением не двух
 * клеток, а двух клиньев:
 *  - ПАМЯТЬ УДАРА клетки контура — наибольшая скорость сближения, которую видели её касания (связей
 *    за тик, по смещению пары за подшаг). Затухает (IMPACT_MEMORY_KEEP) и подпитывается, пока клинья
 *    двух тел как целые ещё сближаются. Касание медленнее IMPACT_FROM ударом не считается и решается
 *    как раньше. Память — состояние мира (BodyState.impactSpeed): она живёт несколько тиков;
 *  - ГЛУБИНА клина считается от удара: (удар − IMPACT_FROM) / IMPACT_PER_LINK связей, не больше
 *    IMPACT_MAX_DEPTH. Скачок скорости «у касания ткань стоит, в глубине летит» раскладывается так,
 *    чтобы на одну связь по глубине приходилось не больше IMPACT_PER_LINK связи сжатия за тик;
 *  - НЕ КЛАСТЕР, А КЛИН: клетка касания получает смещение целиком, клетки за ней — долю, линейно
 *    убывающую до нуля на глубине клина (обход по связям в ширину). Жёсткой границы у клина нет,
 *    форму ткани он не держит, импульс удара дальше в ткань жёстко не передаёт;
 *  - ТОЛЧОК делится по массам двух клиньев (масса клина — сумма масс клеток с их долями): импульс
 *    пары сохраняется точно. Остаток перекрытия решает обычное касание;
 *  - ЭНЕРГИЯ ТОЛЬКО УБЫВАЕТ: удар клиньев неупругий, толчок не больше того, что останавливает
 *    сближение клиньев как целых (см. solve). Чем глубже клин, тем больше массы участвует в
 *    неупругом ударе и тем больше энергии удара гасится сразу;
 *  - РАСПОРКИ: в передней части клиньев (доля не меньше IMPACT_STRUT_FROM_SHARE) пары клеток через одну
 *    связь не дают расстоянию стать меньше, чем оно было в начале тика (IMPACT_STRUT_SHARE укорочения за
 *    проход, умноженная на меньшую из долей двух клеток). Только на сжатие; импульс и момент пары
 *    сохраняются.
 * Правила разрушения не тронуты: гибель «центр в чужом радиусе» судится по тем же положениям и в том
 * же порядке, что и без клина, — до его толчков (BoundaryContacts.wedgeStage).
 *
 * КОГО КЛИН НЕ ТРОГАЕТ: растущие клетки (их касание — рост, а не удар), клетки костей (у кости свой
 * закон), касания клеток одного организма (замер: гибель о своё тело меньше, но клетко-тиков в своей
 * ткани на четверть больше, а цена в разы выше), одиночные частицы без связей.
 *
 * ЗАМЕР 18.11.2026 (каталог стендов, ровная решётка, 7993 сцены, шесть подшагов; было → стало;
 * docs/physics/reference.md, находки 19–22): сцен с разрушением 845 → 505, с проникновением 1079 → 811,
 * прошло все правила 6076 → 6380, погибло клеток 354 → 218 тысяч (прототип с распорками по всему клину:
 * 504, 809, 6384, 208 тысяч). Удар 2 связи за тик
 * ломает тело в 52 сценах из 906 вместо 186, таран 4 связи за тик — в 12 из 425 вместо 173. Все шесть
 * вариаций каталога (21 233 сцены): прошло 16 479 → 17 211, обе беды убывают в каждой.
 * Без распорок: 522 и 865. Распорки без клина вредят (разрушение 961): распорка толкает переднюю
 * клетку вперёд. Клин мельче (0.25 связи на связь, не глубже 8): 535 и 878.
 *
 * ОБОРОТНЫЕ СТОРОНЫ: тела после удара чаще остаются вплотную и не отскакивают; на 6–8 связях за тик
 * ткань разрушается вдвое-впятеро меньше прежнего; смятие тела в себя клин не лечит.
 *
 * ЦЕНА (standsPerf 18.11.2026, замеры подряд «без клина — с клином», медиана тика, один поток и восемь
 * воркеров): обычная игра +1% и +4%, толпа малых тел −2% и +7%; давка +13% и +55%, обломки +56% и +40% —
 * клин считается в одном потоке. Переключатель материала
 * CT_WEDGE=0 выключает клин для замеров «было — стало».
 *
 * ЭТАЛОН (ReferenceSolver) клина не знает и не должен: эталон — мелкий шаг, а клин — способ получить
 * на крупном шаге то, что мелкий шаг даёт сам.
 */
internal class ImpactWedge {
    /** Память удара клеток — массив хозяина (состояние мира). */
    private var hit = DoubleArray(0)
    /** Положения начала тика — массивы хозяина: длины распорок. */
    private var startX = DoubleArray(0)
    private var startY = DoubleArray(0)

    /** Клетки с ненулевой памятью удара. Порядок на итог не влияет: каждая затухает сама по себе. */
    private var hotMark = BooleanArray(0)
    private var hotList = IntArray(256)
    private var hotN = 0

    // Обход в ширину: пометка, расстояние в связях, очередь.
    private var stamp = IntArray(0)
    private var stampNow = 0
    private var dist = IntArray(0)
    private var queue = IntArray(1024)

    // Клинья этого прохода: клетка → номер клина; клетки и доли подряд.
    private var kOf = IntArray(0)
    private var kStamp = IntArray(0)
    private var kPass = 0
    private var kStart = IntArray(65)
    /** 1 / Σ m·g клина — его обратная масса. */
    private var kInvS = DoubleArray(64)
    /** Σ m·g² / (Σ m·g)² клина: вместе с обратной массой задаёт, сколько энергии отнимает толчок. См. solve. */
    private var kQ = DoubleArray(64)
    private var kN = 0
    private var kCell = IntArray(1024)
    private var kW = DoubleArray(1024)
    /** Масса клетки, умноженная на её долю в клине. */
    private var kMw = DoubleArray(1024)
    private var kLen = 0

    // Область распорок: клетки клиньев и наибольшая доля клетки.
    private var regStamp = IntArray(0)
    private var regPass = 0
    private var regW = DoubleArray(0)
    private var regList = IntArray(256)
    private var regN = 0

    // Растущие клетки (BoundaryContacts.immuneStamp): клин и распорки их не трогают.
    private var immStamp = IntArray(0)
    private var immNow = -1

    val bound: Boolean get() = hit.isNotEmpty()

    /**
     * Массивы хозяина на [n] частиц: память удара и положения начала тика. Зовётся раз в тик. Новый массив
     * памяти (загрузка, уплотнение, дописанное тело) — список горячих клеток собирается заново.
     */
    fun bind(memory: DoubleArray, tickStartX: DoubleArray, tickStartY: DoubleArray, n: Int) {
        startX = tickStartX; startY = tickStartY
        if (hotMark.size < n) {
            hotMark = hotMark.copyOf(n); stamp = stamp.copyOf(n); dist = dist.copyOf(n)
            kOf = kOf.copyOf(n); kStamp = kStamp.copyOf(n); regStamp = regStamp.copyOf(n); regW = regW.copyOf(n)
        }
        if (memory === hit) return
        hit = memory
        java.util.Arrays.fill(hotMark, false)
        hotN = 0
        for (i in 0 until n) if (memory[i] > 0.0) addHot(i)
    }

    fun setImmune(stamp: IntArray, now: Int) { immStamp = stamp; immNow = now }

    private fun growing(i: Int): Boolean = i < immStamp.size && immStamp[i] == immNow

    private fun addHot(i: Int) {
        hotMark[i] = true
        if (hotN == hotList.size) hotList = hotList.copyOf(hotN * 2)
        hotList[hotN++] = i
    }

    /** Раз в подшаг, перед первым проходом: память удара затухает. */
    fun beginSubstep() {
        val keep = IMPACT_MEMORY_KEEP
        val hit = hit
        var m = 0
        for (x in 0 until hotN) {
            val i = hotList[x]
            val v = hit[i] * keep
            if (v > IMPACT_FROM) { hit[i] = v; hotList[m++] = i } else { hit[i] = 0.0; hotMark[i] = false }
        }
        hotN = m
    }

    private fun note(i: Int, speed: Double) {
        if (speed <= IMPACT_FROM || speed <= hit[i]) return
        hit[i] = speed
        if (!hotMark[i]) addHot(i)
    }

    /** Свой ли это проход для контакта вида [kind]: упоры в неподвижное — отдельно от пар разных тел. */
    private fun wanted(kind: Byte, fixedKind: Boolean): Boolean =
        if (fixedKind) kind == BoundaryContacts.KIND_FIXED else kind == BoundaryContacts.KIND_SOFT

    /**
     * Память удара по перекрытым парам своего вида и клинья их горячих концов. true — клинья есть.
     * [qx]/[qy] — положения начала подшага.
     */
    fun collect(fixedKind: Boolean, cN: Int, cI: IntArray, cJ: IntArray, cRR: DoubleArray, cKind: ByteArray,
                px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray, invMass: DoubleArray,
                adjStart: IntArray, adjEnd: IntArray, adj: IntArray, boneOf: IntArray?, meanLink: Double): Boolean {
        if (kPass == Int.MAX_VALUE) { java.util.Arrays.fill(kStamp, 0); kPass = 0 }
        kPass++; kN = 0; kLen = 0
        val unit = SUBSTEPS / meanLink
        for (c in 0 until cN) {
            if (!wanted(cKind[c], fixedKind)) continue
            val i = cI[c]; val j = cJ[c]
            if (growing(i) || growing(j)) continue
            // Одиночная частица без связей клина не имеет: пара двух таких — целиком дело обычного касания.
            val li = adjEnd[i] > adjStart[i]; val lj = adjEnd[j] > adjStart[j]
            if (!li && !lj) continue
            var nx = px[i] - px[j]; var ny = py[i] - py[j]
            val d = sqrt(nx * nx + ny * ny)
            if (d >= cRR[c] || d < 1e-12) continue
            nx /= d; ny /= d
            // Насколько пара сблизилась за этот подшаг вдоль линии центров, в связях за тик.
            val ap = -(((px[i] - qx[i]) - (px[j] - qx[j])) * nx + ((py[i] - qy[i]) - (py[j] - qy[j])) * ny) * unit
            if (li && invMass[i] > 0.0) note(i, ap)
            if (lj && invMass[j] > 0.0) note(j, ap)
        }
        if (hotN == 0) return false
        for (c in 0 until cN) {
            if (!wanted(cKind[c], fixedKind)) continue
            val i = cI[c]; val j = cJ[c]
            if (growing(i) || growing(j)) continue
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            if (dx * dx + dy * dy >= cRR[c] * cRR[c]) continue
            build(i, invMass, adjStart, adjEnd, adj, boneOf)
            build(j, invMass, adjStart, adjEnd, adj, boneOf)
        }
        return kN > 0
    }

    /** Клин клетки касания [i]: обход по связям в ширину до глубины, заданной её памятью удара. */
    private fun build(i: Int, invMass: DoubleArray, adjStart: IntArray, adjEnd: IntArray, adj: IntArray, boneOf: IntArray?) {
        if (kStamp[i] == kPass) return
        kStamp[i] = kPass; kOf[i] = -1
        if (invMass[i] <= 0.0 || (boneOf != null && boneOf[i] >= 0)) return
        val reach = (hit[i] - IMPACT_FROM) / IMPACT_PER_LINK
        if (reach <= 0.0) return
        val capped = if (reach > IMPACT_MAX_DEPTH) IMPACT_MAX_DEPTH.toDouble() else reach
        val depth = ceil(capped).toInt()
        val span = capped + 1.0
        if (stampNow == Int.MAX_VALUE) { java.util.Arrays.fill(stamp, 0); stampNow = 0 }
        stampNow++
        var head = 0; var tail = 0
        queue[tail++] = i; stamp[i] = stampNow; dist[i] = 0
        val k = kN
        if (k + 2 > kStart.size) {
            kStart = kStart.copyOf(k * 2 + 2); kInvS = kInvS.copyOf(k * 2 + 2); kQ = kQ.copyOf(k * 2 + 2)
        }
        kStart[k] = kLen
        var s = 0.0; var s2 = 0.0
        while (head < tail) {
            val a = queue[head++]
            val da = dist[a]
            val g = 1.0 - da / span
            if (g > 0.0) {
                if (kLen == kCell.size) { kCell = kCell.copyOf(kLen * 2); kW = kW.copyOf(kLen * 2); kMw = kMw.copyOf(kLen * 2) }
                val m = g / invMass[a]
                kCell[kLen] = a; kW[kLen] = g; kMw[kLen] = m; kLen++
                s += m; s2 += m * g
            }
            if (da >= depth) continue
            for (e in adjStart[a] until adjEnd[a]) {
                val b = adj[e]
                if (stamp[b] == stampNow) continue
                stamp[b] = stampNow
                if (invMass[b] <= 0.0 || (boneOf != null && boneOf[b] >= 0) || growing(b)) continue
                dist[b] = da + 1
                if (tail == queue.size) queue = queue.copyOf(tail * 2)
                queue[tail++] = b
            }
        }
        // Клин из одной клетки — не клин: такая клетка касается по-старому.
        if (kLen - kStart[k] < 2) { kLen = kStart[k]; return }
        kStart[k + 1] = kLen
        kInvS[k] = 1.0 / s; kQ[k] = s2 / (s * s)
        kOf[i] = k; kN = k + 1
    }

    /** Среднее по массе и доле смещение клина [k] за подшаг вдоль (nx, ny); без клина — смещение самой клетки [i]. */
    private fun meanAlong(k: Int, i: Int, nx: Double, ny: Double, px: DoubleArray, py: DoubleArray,
                          qx: DoubleArray, qy: DoubleArray): Double {
        if (k < 0) return (px[i] - qx[i]) * nx + (py[i] - qy[i]) * ny
        var sum = 0.0
        for (e in kStart[k] until kStart[k + 1]) {
            val a = kCell[e]
            sum += kMw[e] * ((px[a] - qx[a]) * nx + (py[a] - qy[a]) * ny)
        }
        return sum * kInvS[k]
    }

    /** Проход по парам по очереди: сближение пары за подшаг (не больше перекрытия) снимается клиньями. */
    fun solve(fixedKind: Boolean, cN: Int, cI: IntArray, cJ: IntArray, cRR: DoubleArray, cNx: DoubleArray, cNy: DoubleArray,
              cKind: ByteArray, px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray, invMass: DoubleArray,
              boneOf: IntArray?, meanLink: Double, backwards: Boolean) {
        val unit = SUBSTEPS / meanLink
        val step = if (backwards) -1 else 1
        var c = if (backwards) cN - 1 else 0
        var left = cN
        while (left > 0) {
            left--
            val at = c
            c += step
            if (!wanted(cKind[at], fixedKind)) continue
            val i = cI[at]; val j = cJ[at]
            val ki = if (kStamp[i] == kPass) kOf[i] else -1
            val kj = if (kStamp[j] == kPass) kOf[j] else -1
            if (ki < 0 && kj < 0) continue
            if (growing(i) || growing(j)) continue
            if (boneOf != null && (boneOf[i] >= 0 || boneOf[j] >= 0)) continue
            var nx = px[i] - px[j]; var ny = py[i] - py[j]
            val d = sqrt(nx * nx + ny * ny)
            val o = cRR[at] - d
            if (o <= 0.0) continue
            if (d > 1e-12) { nx /= d; ny /= d } else { nx = cNx[at]; ny = cNy[at] }
            // Сближение клиньев как целых за этот подшаг (по текущим положениям). Пока оно есть, память удара
            // держится; и толчок клина не больше того, что это сближение гасит.
            val whole = -(meanAlong(ki, i, nx, ny, px, py, qx, qy) - meanAlong(kj, j, nx, ny, px, py, qx, qy))
            if (invMass[i] > 0.0) note(i, whole * unit)
            if (invMass[j] > 0.0) note(j, whole * unit)
            if (whole <= 0.0) continue
            val ap = -(((px[i] - qx[i]) - (px[j] - qx[j])) * nx + ((py[i] - qy[i]) - (py[j] - qy[j])) * ny)
            if (ap <= 0.0) continue
            val take = if (ap < o) ap else o
            val wi = if (ki >= 0) kInvS[ki] else invMass[i]
            val wj = if (kj >= 0) kInvS[kj] else invMass[j]
            val w = wi + wj
            if (w <= 0.0) continue
            // ЭНЕРГИЯ ТОЛЬКО УБЫВАЕТ. Толчок p меняет кинетическую энергию двух клиньев на
            // (−p·whole + p²·q / 2) / h², q = Σmg²/(Σmg)² по обоим клиньям (у одиночной клетки — её обратная
            // масса). Наибольшая потеря — при p = whole / q (клинья как целые перестали сближаться); больше
            // 2·whole / q энергия уже росла бы. Поэтому толчок — меньший из двух: «снять сближение передней
            // пары» и «остановить сближение клиньев». Этой границей урезаются четыре толчка из десяти.
            val q = (if (ki >= 0) kQ[ki] else invMass[i]) + (if (kj >= 0) kQ[kj] else invMass[j])
            var p = take / w
            val stop = whole / q
            if (stop < p) p = stop
            if (ki >= 0) push(ki, nx * p * wi, ny * p * wi, px, py) else { px[i] += nx * p * wi; py[i] += ny * p * wi }
            if (kj >= 0) push(kj, -nx * p * wj, -ny * p * wj, px, py) else { px[j] -= nx * p * wj; py[j] -= ny * p * wj }
        }
    }

    private fun push(k: Int, sx: Double, sy: Double, px: DoubleArray, py: DoubleArray) {
        for (e in kStart[k] until kStart[k + 1]) {
            val a = kCell[e]; val g = kW[e]
            px[a] += sx * g; py[a] += sy * g
        }
    }

    /** Распорки по области клиньев: расстояния начала тика не дают сжаться. До суда гибели, как связи. */
    fun struts(px: DoubleArray, py: DoubleArray, invMass: DoubleArray, adjStart: IntArray, adjEnd: IntArray, adj: IntArray) {
        if (regPass == Int.MAX_VALUE) { java.util.Arrays.fill(regStamp, 0); regPass = 0 }
        regPass++; regN = 0
        // Распорки — только в передней части клина (доля не меньше IMPACT_STRUT_FROM_SHARE): см. там.
        for (e in 0 until kLen) {
            val a = kCell[e]; val g = kW[e]
            if (g < IMPACT_STRUT_FROM_SHARE) continue
            if (regStamp[a] != regPass) {
                regStamp[a] = regPass; regW[a] = g
                if (regN == regList.size) regList = regList.copyOf(regN * 2)
                regList[regN++] = a
            } else if (g > regW[a]) regW[a] = g
        }
        for (x in 0 until regN) {
            val a = regList[x]
            if (stampNow == Int.MAX_VALUE) { java.util.Arrays.fill(stamp, 0); stampNow = 0 }
            stampNow++
            var head = 0; var tail = 0
            queue[tail++] = a; stamp[a] = stampNow; dist[a] = 0
            while (head < tail) {
                val u = queue[head++]
                val du = dist[u]
                if (du == 2) { if (u > a && regStamp[u] == regPass) strut(a, u, px, py, invMass); continue }
                for (e in adjStart[u] until adjEnd[u]) {
                    val b = adj[e]
                    if (stamp[b] == stampNow) continue
                    stamp[b] = stampNow; dist[b] = du + 1
                    if (tail == queue.size) queue = queue.copyOf(tail * 2)
                    queue[tail++] = b
                }
            }
        }
    }

    private fun strut(a: Int, c: Int, px: DoubleArray, py: DoubleArray, invMass: DoubleArray) {
        val w = invMass[a] + invMass[c]
        if (w <= 0.0) return
        val rx = startX[a] - startX[c]; val ry = startY[a] - startY[c]
        val l02 = rx * rx + ry * ry
        var dx = px[a] - px[c]; var dy = py[a] - py[c]
        val len2 = dx * dx + dy * dy
        if (len2 >= l02 || len2 < 1e-24) return
        val len = sqrt(len2); val l0 = sqrt(l02)
        val sigma = IMPACT_STRUT_SHARE * (if (regW[a] < regW[c]) regW[a] else regW[c])
        val dL = sigma * (l0 - len) / w
        dx /= len; dy /= len
        px[a] += dx * dL * invMass[a]; py[a] += dy * dL * invMass[a]
        px[c] -= dx * dL * invMass[c]; py[c] -= dy * dL * invMass[c]
    }
}
