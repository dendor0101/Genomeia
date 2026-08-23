package io.github.some_example_name.lwjgl3.demo

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * САМОКОНТАКТ ГРАНИЧНОГО КОНТУРА: хеш-сетка, DDA, CCD и импульсное разрешение.
 *
 * Перенесено из прототипа prototypes/xpbd-ccd-demo.html. Здесь ОДНА реализация на всех:
 * ею пользуются и RealBodyDemo, и SwimSolver. Дублировать её было нельзя — за время
 * работы над стендом копия решателя трижды молча разъезжалась с оригиналом, и каждый
 * раз это стоило часа поисков. Общий класс делает совпадение структурным, а не удачей.
 *
 * ЧТО СЮДА ВХОДИТ И ПОЧЕМУ ИМЕННО ТАК
 * -----------------------------------
 *
 * ТОЛЬКО ГРАНИЧНЫЕ КЛЕТКИ. Внутренние в контактах не участвуют вообще — их держат
 * связи и площади. Это главная оптимизация: у тела 947 клеток и около 200 граничных,
 * то есть работы впятеро меньше, чем при честном переборе всех. Внутренняя клетка
 * физически не может оказаться снаружи, не протащив за собой границу, а границу мы
 * и стережём.
 *
 * СВЯЗАННЫЕ ПАРЫ НЕ СТАЛКИВАЮТСЯ. Это не оптимизация, а необходимость: в покое соседи
 * стоят на 0.6 при сумме радиусов 1.0, то есть перекрываются на 40% и были бы в
 * вечном контакте. Проверка идёт по списку смежности, а не по расстоянию.
 *
 * DDA ПО СВИП-ПУТИ (Amanatides & Woo). В сетку кладётся не точка, а весь отрезок,
 * пройденный за подшаг. Отсюда отсутствие туннелирования: даже если частица за подшаг
 * перелетела двадцать ячеек, обойдутся все двадцать, а не только концы.
 *
 * CCD ОБРЕЗАЕТ ПОДШАГ ПО ВРЕМЕНИ ПЕРВОГО КОНТАКТА. Ищется наименьшее t, при котором
 * круги сходятся до «ядра» core*(ri+rj). Обрезка только УМЕНЬШАЕТ перемещение, значит
 * энергии не добавляет никогда.
 *
 * ЭНЕРГИЯ. Позиционный решатель делает удар абсолютно неупругим — вся нормальная
 * скорость съедена. Скоростной проход возвращает ровно долю e от той скорости, с
 * которой тела РЕАЛЬНО сближались (она снимается ДО позиционного решателя, в cVn).
 * При e <= 1 энергия не может вырасти. Трение ограничено накопленным нормальным
 * импульсом — кулоновский конус, а не произвольное торможение.
 */
class BoundaryContacts(
    private val n: Int,
    /** Индексы граничных клеток. Только они участвуют в контактах. */
    private val verts: IntArray,
    /** Смежность в формате CSR: adjStart[i]..adjStart[i+1] — соседи клетки i. */
    private val adjStart: IntArray,
    private val adj: IntArray,
    private val radius: DoubleArray,
    /** Сторона ячейки сетки. Должна быть не меньше наибольшего диаметра контакта. */
    private val cellSize: Double,
    private val contactScale: Double,
    private val ccdCore: Double,
    private val restitution: Double,
    private val friction: Double,
) {
    // --- сетка ---
    private val grid = HashMap<Long, IntArray>(verts.size * 4)
    private val usedKeys = LongArray(verts.size * 64)
    private var usedN = 0

    // --- пары-кандидаты ---
    private var pairA = IntArray(4096)
    private var pairB = IntArray(4096)
    private var pairN = 0
    private val mark = IntArray(n)
    private var stamp = 0

    // --- контакты ---
    private var cI = IntArray(4096)
    private var cJ = IntArray(4096)
    private var cNx = DoubleArray(4096)
    private var cNy = DoubleArray(4096)
    private var cVn = DoubleArray(4096)
    private var cLam = DoubleArray(4096)
    private var cN = 0

    private val toi = DoubleArray(n)

    // --- буфер DDA ---
    private val ddaX = IntArray(DDA_MAX)
    private val ddaY = IntArray(DDA_MAX)

    /** Диагностика: сколько контактов и сколько раз CCD обрезал подшаг. */
    var lastContacts = 0
        private set

    /**
     * Наибольшее проникновение среди НЕСВЯЗАННЫХ граничных пар, в долях порога контакта.
     * 0 — никто никого не касается, 1 — пара сошлась в точку. Прямая проверка того,
     * что контакты работают: в нормальной работе должно оставаться заметно меньше 1.
     */
    fun maxPenetration(px: DoubleArray, py: DoubleArray): Double {
        var worst = 0.0
        for (a in verts.indices) {
            val i = verts[a]
            for (b in a + 1 until verts.size) {
                val j = verts[b]
                if (bonded(i, j)) continue
                val dx = px[i] - px[j]; val dy = py[i] - py[j]
                val rr = contactScale * (radius[i] + radius[j])
                val d2 = dx * dx + dy * dy
                if (d2 >= rr * rr) continue
                val pen = 1.0 - sqrt(d2) / rr
                if (pen > worst) worst = pen
            }
        }
        return worst
    }
    var lastToiClamps = 0
        private set

    private fun key(ix: Int, iy: Int): Long =
        ((ix + BIAS).toLong() shl 32) or ((iy + BIAS).toLong() and 0xFFFFFFFFL)

    private fun gridClear() {
        for (k in 0 until usedN) grid[usedKeys[k]]!![0] = 0
        usedN = 0
    }

    private fun gridInsert(k: Long, id: Int) {
        var arr = grid[k]
        if (arr == null) { arr = IntArray(9); grid[k] = arr }
        if (arr[0] == 0 && usedN < usedKeys.size) { usedKeys[usedN] = k; usedN++ }
        if (arr[0] + 1 >= arr.size) {
            val bigger = IntArray(arr.size * 2)
            arr.copyInto(bigger)
            arr = bigger
            grid[k] = arr
        }
        arr[0]++
        arr[arr[0]] = id
    }

    /**
     * Растеризация отрезка в ячейки. Возвращает число ячеек, координаты в ddaX/ddaY.
     * Это и есть защита от туннелирования — обходятся ВСЕ ячейки пути, а не концы.
     */
    private fun dda(x0: Double, y0: Double, x1: Double, y1: Double): Int {
        var ix = floor(x0 / cellSize).toInt()
        var iy = floor(y0 / cellSize).toInt()
        val ex = floor(x1 / cellSize).toInt()
        val ey = floor(y1 / cellSize).toInt()
        val dx = x1 - x0
        val dy = y1 - y0
        val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0
        val adx = abs(dx); val ady = abs(dy)
        val tDX = if (adx > 1e-12) cellSize / adx else Double.MAX_VALUE
        val tDY = if (ady > 1e-12) cellSize / ady else Double.MAX_VALUE
        var tMX = if (adx > 1e-12)
            (if (dx > 0) (ix + 1) * cellSize - x0 else x0 - ix * cellSize) / adx else Double.MAX_VALUE
        var tMY = if (ady > 1e-12)
            (if (dy > 0) (iy + 1) * cellSize - y0 else y0 - iy * cellSize) / ady else Double.MAX_VALUE
        var c = 0
        ddaX[c] = ix; ddaY[c] = iy; c++
        while ((ix != ex || iy != ey) && c < DDA_MAX) {
            if (tMX < tMY) { tMX += tDX; ix += stepX } else { tMY += tDY; iy += stepY }
            ddaX[c] = ix; ddaY[c] = iy; c++
        }
        return c
    }

    /** Связаны ли клетки напрямую. Линейный поиск: степень вершины не больше восьми. */
    private fun bonded(i: Int, j: Int): Boolean {
        for (k in adjStart[i] until adjStart[i + 1]) if (adj[k] == j) return true
        return false
    }

    private fun addPair(i: Int, j: Int) {
        if (pairN >= pairA.size) {
            pairA = pairA.copyOf(pairA.size * 2)
            pairB = pairB.copyOf(pairB.size * 2)
        }
        pairA[pairN] = i; pairB[pairN] = j; pairN++
    }

    /** Широкая фаза по свип-путям. prevX/prevY — позиция на начало подшага. */
    private fun broadphase(px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray) {
        gridClear()
        for (v in verts) {
            val c = dda(qx[v], qy[v], px[v], py[v])
            for (k in 0 until c) gridInsert(key(ddaX[k], ddaY[k]), v)
        }
        pairN = 0
        for (v in verts) {
            stamp++
            mark[v] = stamp
            val c = dda(qx[v], qy[v], px[v], py[v])
            for (k in 0 until c) {
                val cx = ddaX[k]; val cy = ddaY[k]
                for (ox in -1..1) for (oy in -1..1) {
                    val bucket = grid[key(cx + ox, cy + oy)] ?: continue
                    for (b in 1..bucket[0]) {
                        val j = bucket[b]
                        if (mark[j] == stamp) continue
                        mark[j] = stamp
                        if (j < v) continue           // пара берётся ровно один раз
                        if (bonded(v, j)) continue    // соседи по ткани не сталкиваются
                        addPair(v, j)
                    }
                }
            }
        }
    }

    /**
     * Обрезка подшага по времени первого контакта. Только уменьшает перемещение,
     * поэтому энергии не добавляет.
     */
    private fun ccdClamp(px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray) {
        for (v in verts) toi[v] = 1.0
        for (p in 0 until pairN) {
            val a = pairA[p]; val j = pairB[p]
            val d0x = qx[a] - qx[j]; val d0y = qy[a] - qy[j]
            val dvx = (px[a] - qx[a]) - (px[j] - qx[j])
            val dvy = (py[a] - qy[a]) - (py[j] - qy[j])
            val rc = ccdCore * (radius[a] + radius[j])
            val c = d0x * d0x + d0y * d0y - rc * rc
            if (c <= 0) continue                       // уже внутри ядра — дело решателя
            val aa = dvx * dvx + dvy * dvy
            if (aa < 1e-18) continue
            val bb = 2.0 * (d0x * dvx + d0y * dvy)
            if (bb >= 0) continue                      // расходятся
            val disc = bb * bb - 4.0 * aa * c
            if (disc < 0) continue
            val t = (-bb - sqrt(disc)) / (2.0 * aa)
            if (t < 0 || t >= 1) continue
            if (t < toi[a]) toi[a] = t
            if (t < toi[j]) toi[j] = t
        }
        var clamps = 0
        for (v in verts) {
            val tt = toi[v]
            if (tt >= 1.0) continue
            px[v] = qx[v] + (px[v] - qx[v]) * tt
            py[v] = qy[v] + (py[v] - qy[v]) * tt
            clamps++
        }
        lastToiClamps = clamps
    }

    private fun buildContacts(px: DoubleArray, py: DoubleArray, vx: DoubleArray, vy: DoubleArray) {
        cN = 0
        for (p in 0 until pairN) {
            val i = pairA[p]; val j = pairB[p]
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d2 = dx * dx + dy * dy
            val rr = contactScale * (radius[i] + radius[j])
            if (d2 >= rr * rr) continue
            val d = sqrt(d2)
            val nx: Double; val ny: Double
            if (d > 1e-12) { nx = dx / d; ny = dy / d } else { nx = 1.0; ny = 0.0 }
            if (cN >= cI.size) {
                cI = cI.copyOf(cI.size * 2); cJ = cJ.copyOf(cJ.size * 2)
                cNx = cNx.copyOf(cNx.size * 2); cNy = cNy.copyOf(cNy.size * 2)
                cVn = cVn.copyOf(cVn.size * 2); cLam = cLam.copyOf(cLam.size * 2)
            }
            cI[cN] = i; cJ[cN] = j; cNx[cN] = nx; cNy[cN] = ny
            // Скорость сближения снимается ДО позиционного решателя: именно она задаёт
            // отскок. Возьми её после — и энергия появится из воздуха.
            cVn[cN] = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
            cLam[cN] = 0.0
            cN++
        }
        lastContacts = cN
    }

    /**
     * Подготовка подшага: сетка, CCD, список контактов. Вызывается ПОСЛЕ integrate
     * и ДО позиционных ограничений.
     */
    fun prepare(
        px: DoubleArray, py: DoubleArray,
        qx: DoubleArray, qy: DoubleArray,
        vx: DoubleArray, vy: DoubleArray,
    ) {
        broadphase(px, py, qx, qy)
        ccdClamp(px, py, qx, qy)
        buildContacts(px, py, vx, vy)
    }

    /**
     * Позиционное разрешение. Жёсткое (податливость ноль): проникать граница не должна.
     * Ставится ПОСЛЕ projectBone — иначе проекция кости затрёт результат, и кость
     * будет проходить сквозь тело.
     */
    fun solvePositions(px: DoubleArray, py: DoubleArray, invMass: DoubleArray) {
        for (c in 0 until cN) {
            val i = cI[c]; val j = cJ[c]
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d = sqrt(dx * dx + dy * dy)
            val rr = contactScale * (radius[i] + radius[j])
            val nx: Double; val ny: Double; val cc: Double
            if (d < 1e-12) { nx = cNx[c]; ny = cNy[c]; cc = -rr }
            else { nx = dx / d; ny = dy / d; cc = d - rr }
            cNx[c] = nx; cNy[c] = ny      // нормаль нужна скоростному проходу
            if (cc >= 0) continue
            val w = invMass[i] + invMass[j]
            if (w <= 0) continue
            val dl = -cc / w
            cLam[c] += dl
            px[i] += invMass[i] * dl * nx; py[i] += invMass[i] * dl * ny
            px[j] -= invMass[j] * dl * nx; py[j] -= invMass[j] * dl * ny
        }
    }

    /**
     * Скоростной проход: трение и отскок. Вызывается ПОСЛЕ updateVelocities.
     *
     * Позиционный решатель уже сделал удар абсолютно неупругим. Здесь возвращается
     * доля e от скорости РЕАЛЬНОГО сближения (cVn, снятая до решателя), поэтому при
     * e <= 1 энергия вырасти не может.
     */
    fun solveVelocities(
        vx: DoubleArray, vy: DoubleArray, invMass: DoubleArray, h: Double,
    ) {
        for (c in 0 until cN) {
            val i = cI[c]; val j = cJ[c]
            val nx = cNx[c]; val ny = cNy[c]
            val wi = invMass[i]; val wj = invMass[j]
            val w = wi + wj
            if (w <= 0) continue

            var rvx = vx[i] - vx[j]
            var rvy = vy[i] - vy[j]
            var vn = rvx * nx + rvy * ny

            // Кулоновское трение, ограниченное накопленным нормальным импульсом.
            val tvx = rvx - vn * nx
            val tvy = rvy - vn * ny
            val tl = sqrt(tvx * tvx + tvy * tvy)
            if (tl > 1e-12 && friction > 0 && cLam[c] > 0) {
                val maxDv = friction * cLam[c] * w / h
                val dvt = min(tl, maxDv)
                val pfx = -(tvx / tl) * dvt / w
                val pfy = -(tvy / tl) * dvt / w
                vx[i] += pfx * wi; vy[i] += pfy * wi
                vx[j] -= pfx * wj; vy[j] -= pfy * wj
                rvx = vx[i] - vx[j]; rvy = vy[i] - vy[j]
                vn = rvx * nx + rvy * ny
            }

            val target = maxOf(-restitution * cVn[c], 0.0)
            val dvn = target - vn
            if (dvn > 0) {                    // только расталкиваем, никогда не притягиваем
                val pnx = nx * dvn / w
                val pny = ny * dvn / w
                vx[i] += pnx * wi; vy[i] += pny * wi
                vx[j] -= pnx * wj; vy[j] -= pny * wj
            }
        }
    }

    companion object {
        private const val BIAS = 1 shl 20
        private const val DDA_MAX = 4096

        /**
         * Строит CSR-смежность и список граничных вершин из рёбер границы и связей.
         *
         * Смежность берётся по ВСЕМ связям, а не только граничным: связанные клетки не
         * сталкиваются независимо от того, лежит связь на контуре или уходит внутрь.
         */
        fun build(
            n: Int,
            conA: IntArray, conB: IntArray, conCount: Int,
            boundA: IntArray, boundB: IntArray, boundCount: Int,
            radius: DoubleArray,
            contactScale: Double, ccdCore: Double, restitution: Double, friction: Double,
        ): BoundaryContacts {
            val deg = IntArray(n)
            for (c in 0 until conCount) { deg[conA[c]]++; deg[conB[c]]++ }
            val start = IntArray(n + 1)
            for (i in 0 until n) start[i + 1] = start[i] + deg[i]
            val fill = start.copyOf()
            val adj = IntArray(start[n])
            for (c in 0 until conCount) {
                adj[fill[conA[c]]++] = conB[c]
                adj[fill[conB[c]]++] = conA[c]
            }

            val onBound = BooleanArray(n)
            for (e in 0 until boundCount) { onBound[boundA[e]] = true; onBound[boundB[e]] = true }
            val verts = (0 until n).filter { onBound[it] }.toIntArray()

            // Сторона ячейки — наибольший диаметр контакта. Меньше нельзя: пара из
            // соседних ячеек тогда могла бы не попасть в перебор 3x3.
            var maxR = 0.0
            for (v in verts) if (radius[v] > maxR) maxR = radius[v]
            val cell = maxOf(2.0 * maxR * contactScale, 1e-6)

            return BoundaryContacts(
                n, verts, start, adj, radius, cell,
                contactScale, ccdCore, restitution, friction,
            )
        }
    }
}
