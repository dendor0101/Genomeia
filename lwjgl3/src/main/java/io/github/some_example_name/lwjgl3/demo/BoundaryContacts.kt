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
    /** Средняя длина связи — масштаб, в котором задан потолок поправки. */
    private val meanLink: Double,
    /** Потолок поправки контакта за подшаг, в долях средней связи. */
    private val contactMaxStep: Double,
    /**
     * РАДИУС КОНТАКТА, взятый из ГЕОМЕТРИИ ГРАНИЦЫ, а не из радиуса клетки.
     *
     * Непроницаемость мембраны держится на одном условии: круги двух СОСЕДНИХ
     * граничных клеток обязаны перекрываться, иначе между ними остаётся щель, и
     * пройти сквозь неё можно не нарушив ни одного правила. Радиус клетки этого не
     * гарантирует — он рисовальный и задаётся в редакторе как угодно. В теле с
     * клетками 0.5 и 0.2 при шаге решётки 0.6 круги мелких по прежней формуле
     * накрывали 0.48 из 0.6, то есть в мембране была дыра шириной 0.12.
     *
     * Поэтому радиус берётся как ПОЛОВИНА САМОГО ДЛИННОГО ГРАНИЧНОГО РЕБРА клетки,
     * умноженная на небольшой запас. Тогда соседи по контуру перекрываются ВСЕГДА,
     * какие бы радиусы ни нарисовал игрок.
     *
     * Окно для этого числа узкое с обеих сторон, и обе границы измерены:
     * длиннейшее граничное ребро 0.0364 — ниже него щель; ближайшая НЕСВЯЗАННАЯ
     * пара граничных клеток 0.0418 — выше него они попадают в вечный ложный
     * контакт и тело само себя распирает. Запас между ними всего 1.15x.
     *
     * ПРИ ПЕРЕНОСЕ: тело растёт, и длина рёбер меняется. Радиус надо пересчитывать
     * при изменении топологии, а не один раз на старте. И проверять, что запас
     * 1.15x не съеден: если у какой-то клетки граничное ребро станет длиннее
     * ближайшей несвязанной пары, кругами на вершинах мембрану уже не сшить, и
     * придётся сталкиваться с РЕБРОМ как с отрезком.
     */
    private val contactRadius: DoubleArray,
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

    /** Пары, перекрытые уже в позе покоя. См. bonded. */
    private val restTouching = HashSet<Long>()

    // --- жёсткие кластеры: масса, центр, момент инерции на подшаг ---
    private var boneOf: IntArray? = null
    private var boneM = DoubleArray(0)
    private var boneCx = DoubleArray(0)
    private var boneCy = DoubleArray(0)
    private var boneI = DoubleArray(0)
    private var boneIds: Array<IntArray> = emptyArray()

    // --- буфер DDA ---
    private val ddaX = IntArray(DDA_MAX)
    private val ddaY = IntArray(DDA_MAX)

    /** Диагностика: сколько контактов и сколько раз CCD обрезал подшаг. */
    var lastContacts = 0
        private set
    var lastToiClamps = 0
        private set

    /**
     * Сколько перекрывшихся пар НЕ попало в список контактов.
     *
     * Полный перебор, как и maxPenetration, — то есть истина, не зависящая от
     * широкой фазы. Отличает две совсем разные причины проникновения: если число
     * ноль, а проникновение есть, значит решатель не справился (мягкость, нехватка
     * подшагов, потолок поправки). Если число большое — пары до решателя вовсе не
     * дошли, и виновата широкая фаза или момент, в который её строят.
     */
    fun missedOverlaps(px: DoubleArray, py: DoubleArray): Int {
        val inList = HashSet<Long>(cN * 2)
        for (c in 0 until cN) {
            val a = minOf(cI[c], cJ[c]).toLong(); val b = maxOf(cI[c], cJ[c]).toLong()
            inList.add(a * 1000003L + b)
        }
        var missed = 0
        for (a in verts.indices) {
            val i = verts[a]
            for (b in a + 1 until verts.size) {
                val j = verts[b]
                if (bonded(i, j)) continue
                val dx = px[i] - px[j]; val dy = py[i] - py[j]
                val rr = contactRadius[i] + contactRadius[j]
                if (dx * dx + dy * dy >= rr * rr) continue
                val k = minOf(i, j).toLong() * 1000003L + maxOf(i, j).toLong()
                if (!inList.contains(k)) missed++
            }
        }
        return missed
    }

    /**
     * Наибольшее проникновение среди НЕСВЯЗАННЫХ граничных пар, в долях порога контакта.
     * 0 — никто никого не касается, 1 — пара сошлась в точку. Прямая проверка того,
     * что контакты работают: в нормальной работе должно оставаться заметно меньше 1.
     */
    /** Радиус контакта клетки — для отрисовки настоящей геометрии, а не рисовального радиуса. */
    fun contactRadiusOf(i: Int): Double = contactRadius[i]

    fun restTouchingCount(): Int = restTouching.size

    /** Запоминает пары, перекрытые в позе покоя. Зовётся один раз при сборке. */
    fun markRestTouching(restX: FloatArray, restY: FloatArray) {
        restTouching.clear()
        for (a in verts.indices) {
            val i = verts[a]
            for (b in a + 1 until verts.size) {
                val j = verts[b]
                var linked = false
                for (k in adjStart[i] until adjStart[i + 1]) if (adj[k] == j) { linked = true; break }
                if (linked) continue
                val dx = (restX[i] - restX[j]).toDouble()
                val dy = (restY[i] - restY[j]).toDouble()
                val rr = contactRadius[i] + contactRadius[j]
                if (dx * dx + dy * dy < rr * rr) restTouching.add(pairKey(i, j))
            }
        }
    }

    fun maxPenetration(px: DoubleArray, py: DoubleArray): Double {
        var worst = 0.0
        for (a in verts.indices) {
            val i = verts[a]
            for (b in a + 1 until verts.size) {
                val j = verts[b]
                if (bonded(i, j)) continue
                val dx = px[i] - px[j]; val dy = py[i] - py[j]
                val rr = contactRadius[i] + contactRadius[j]
                val d2 = dx * dx + dy * dy
                if (d2 >= rr * rr) continue
                val pen = 1.0 - sqrt(d2) / rr
                if (pen > worst) worst = pen
            }
        }
        return worst
    }

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
    /**
     * Пара НЕ сталкивается: либо связана, либо перекрыта уже в позе покоя.
     *
     * Про связанные было с самого начала: соседи по решётке стоят ближе суммы
     * радиусов и были бы в вечном контакте.
     *
     * Про ПОКОЙ пришлось добавить после кирпича — тела целиком из кости. Радиус
     * контакта берётся по самому длинному граничному ребру клетки, иначе мембрану
     * не сшить; но тот же радиус накрывает и другие, более близкие соседства той
     * же клетки, если они не связаны. На теле с мягкой тканью запас был 1.62x и
     * всё сходилось, на кирпиче окна нет вовсе: в покое насчитывалось 134
     * постоянных контакта глубиной 0.0585.
     *
     * Постоянный контакт сам по себе не двигал бы тело — он симметричен. Но
     * позиционный решатель обходит контакты по Гауссу-Зейделю, порядок обхода
     * несимметричен, и за подшаг накапливается момент. Кирпич от этого крутился
     * со скоростью 0.066 рад/с из полного покоя, а с выключенными контактами
     * стоял идеально — по этому расхождению причина и нашлась.
     *
     * Пары, перекрытые в покое, — это ТКАНЬ, а не столкновение, и сталкивать их
     * не нужно: они и должны оставаться рядом. Считаются один раз при сборке.
     *
     * ПРИ ПЕРЕНОСЕ: тело растёт, и набор таких пар меняется вместе с топологией.
     * Пересчитывать вместе с contactRadius.
     */
    private fun bonded(i: Int, j: Int): Boolean {
        for (k in adjStart[i] until adjStart[i + 1]) if (adj[k] == j) return true
        return restTouching.contains(pairKey(i, j))
    }

    private fun pairKey(i: Int, j: Int): Long =
        (if (i < j) i else j).toLong() * 1000003L + (if (i < j) j else i).toLong()

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
            val rc = ccdCore * (contactRadius[a] + contactRadius[j])

            // ОБРЕЗКА ТОЛЬКО ПРИ РЕАЛЬНОМ РИСКЕ ПРОСКОКА, и это не оптимизация.
            //
            // ccdClamp двигает КАЖДУЮ частицу отдельно, по своему минимальному toi.
            // Это несимметричная позиционная правка: у пары она сдвигает центр масс,
            // а updateVelocities делает из сдвига импульс. То есть CCD — источник
            // паразитного движения, и включается он ровно там, где идут столкновения,
            // то есть когда тело сложилось само в себя. Именно это и давало блуждание
            // при загибе.
            //
            // При этом он почти всегда НЕ НУЖЕН. Проскочить мимо контакта за подшаг
            // можно, только если относительное смещение за этот подшаг больше ядра.
            // На нынешних настройках путь за подшаг равен 4 клеткам за тик, делённым
            // на 16 подшагов, то есть 0.25 клетки, а контакт срабатывает на 1.0 —
            // запас четырёхкратный. Условие ниже это и проверяет: пока смещение
            // меньше ядра, дискретная проверка в конце подшага поймает перекрытие
            // сама, и обрезка не нужна.
            //
            // Проверяется квадрат, чтобы не считать корень на каждую пару.
            val move2 = dvx * dvx + dvy * dvy
            if (move2 <= rc * rc) continue

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
            val rr = contactRadius[i] + contactRadius[j]
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
    /**
     * КОНТАКТ НА ЖЁСТКОЙ КОСТИ РЕШАЕТСЯ КАК КОНТАКТ ТВЁРДОГО ТЕЛА.
     *
     * Иначе контакт вдевятеро слабее нужного, и это арифметика, а не ощущение.
     * Поправка считается по invMass ОДНОЙ клетки, а projectBone на следующем
     * подшаге подгоняет весь кластер под жёсткую позу: от поправки в каждой клетке
     * остаётся примерно k/N, где k это число касающихся клеток, а N размер
     * кластера. При десяти касаниях на девяносто клеток теряется девять десятых.
     *
     * Наблюдалось это на телах ЦЕЛИКОМ ИЗ КОСТИ: одиночный таран проходил чисто,
     * а после четырёх подряд тела начинали проваливаться друг в друга — 2.38 связи
     * насквозь при проникновении 0.871. У мягкой ткани такого нет, потому что там
     * ответом служит разрыв, а кость порваться не может.
     *
     * Правильно — считать сопротивление ВСЕГО кластера. Сила в точке на расстоянии
     * r от центра тела ещё и крутит, поэтому эффективная обратная масса вдоль
     * нормали равна 1/M + (r x n)^2/I. Поправка после этого разносится по кластеру
     * жёстко: поступательная часть всем поровну, вращательная по радиусу. Поза при
     * этом не нарушается, и проекции нечего исправлять.
     *
     * Зовётся каждый подшаг перед решателем: центр и момент инерции меняются с
     * движением тела, а кластеров десятки, так что это дёшево.
     */
    fun updateBones(px: DoubleArray, py: DoubleArray, invMass: DoubleArray,
                    boneOfArg: IntArray?, ids: Array<IntArray>) {
        boneOf = boneOfArg
        boneIds = ids
        if (boneOfArg == null || ids.isEmpty()) return
        if (boneM.size != ids.size) {
            boneM = DoubleArray(ids.size); boneCx = DoubleArray(ids.size)
            boneCy = DoubleArray(ids.size); boneI = DoubleArray(ids.size)
        }
        for (b in ids.indices) {
            var m = 0.0; var cx = 0.0; var cy = 0.0
            for (k in ids[b]) {
                if (invMass[k] <= 0.0) continue
                val w = 1.0 / invMass[k]
                m += w; cx += w * px[k]; cy += w * py[k]
            }
            boneM[b] = m
            if (m <= 0.0) { boneI[b] = 0.0; continue }
            cx /= m; cy /= m
            boneCx[b] = cx; boneCy[b] = cy
            var inert = 0.0
            for (k in ids[b]) {
                if (invMass[k] <= 0.0) continue
                val rx = px[k] - cx; val ry = py[k] - cy
                inert += (rx * rx + ry * ry) / invMass[k]
            }
            boneI[b] = inert
        }
    }

    /** Эффективная обратная масса точки i вдоль нормали, с учётом жёсткой кости. */
    private fun effInvMass(i: Int, nx: Double, ny: Double, px: DoubleArray, py: DoubleArray,
                           invMass: DoubleArray): Double {
        val bo = boneOf ?: return invMass[i]
        val b = bo[i]
        if (b < 0 || b >= boneM.size || boneM[b] <= 0.0) return invMass[i]
        val rx = px[i] - boneCx[b]; val ry = py[i] - boneCy[b]
        val rn = rx * ny - ry * nx
        val rot = if (boneI[b] > 1e-18) rn * rn / boneI[b] else 0.0
        return 1.0 / boneM[b] + rot
    }

    /** Разносит поправку по жёсткому кластеру, сохраняя позу. */
    private fun applyRigid(i: Int, jx: Double, jy: Double, px: DoubleArray, py: DoubleArray,
                           invMass: DoubleArray): Boolean {
        val bo = boneOf ?: return false
        val b = bo[i]
        if (b < 0 || b >= boneM.size || boneM[b] <= 0.0) return false
        val rx = px[i] - boneCx[b]; val ry = py[i] - boneCy[b]
        val dOmega = if (boneI[b] > 1e-18) (rx * jy - ry * jx) / boneI[b] else 0.0
        val tx = jx / boneM[b]; val ty = jy / boneM[b]
        for (k in boneIds[b]) {
            if (invMass[k] <= 0.0) continue
            val kx = px[k] - boneCx[b]; val ky = py[k] - boneCy[b]
            px[k] += tx - dOmega * ky
            py[k] += ty + dOmega * kx
        }
        return true
    }

    fun solvePositions(px: DoubleArray, py: DoubleArray, invMass: DoubleArray) {
        for (c in 0 until cN) {
            val i = cI[c]; val j = cJ[c]
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d = sqrt(dx * dx + dy * dy)
            val rr = contactRadius[i] + contactRadius[j]
            val nx: Double; val ny: Double; val cc: Double
            if (d < 1e-12) { nx = cNx[c]; ny = cNy[c]; cc = -rr }
            else { nx = dx / d; ny = dy / d; cc = d - rr }
            cNx[c] = nx; cNy[c] = ny      // нормаль нужна скоростному проходу
            if (cc >= 0) continue
            // Сопротивление считается по ТЕЛУ, а не по клетке: для клетки в жёсткой
            // кости это масса всего кластера плюс вклад вращения. См. updateBones.
            val wi = effInvMass(i, nx, ny, px, py, invMass)
            val wj = effInvMass(j, nx, ny, px, py, invMass)
            val w = wi + wj
            if (w <= 0) continue
            // ПОТОЛОК ПОПРАВКИ ЗА ПОДШАГ, и он тут не для мягкости.
            //
            // Контакт жёсткий: глубокое проникновение он разгребает целиком за один
            // подшаг. А updateVelocities делает из поправки скорость делением на h,
            // и h крошечное. При загибе тела в себя так и выходило: замер показал
            // поправку около пяти клеток за подшаг, то есть скорость под 84 клетки
            // за тик при обычном рабочем уровне около трёх.
            //
            // Раньше этот выброс срезал потолок скорости — но он масштабирует КАЖДУЮ
            // частицу отдельно, импульс не сохраняет, и потому сам превращался в
            // источник блуждания (243 срабатывания за один загиб). Здесь предел
            // ставится на ПОПРАВКУ и делится по паре в тех же долях, что и сама
            // поправка, поэтому сумма импульсов пары остаётся нулевой.
            //
            // Проникновение при этом не игнорируется, а разбирается за несколько
            // подшагов — их 16, и контакт держится не один подшаг.
            var dl = -cc / w
            val cap = contactMaxStep * meanLink / w
            if (dl > cap) dl = cap
            cLam[c] += dl
            if (!applyRigid(i, dl * nx, dl * ny, px, py, invMass)) {
                px[i] += invMass[i] * dl * nx; py[i] += invMass[i] * dl * ny
            }
            if (!applyRigid(j, -dl * nx, -dl * ny, px, py, invMass)) {
                px[j] -= invMass[j] * dl * nx; py[j] -= invMass[j] * dl * ny
            }
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
         * Запас на сшивание мембраны. Окно у этого числа узкое с обеих сторон:
         * ниже единицы круги соседей по контуру перестают перекрываться и в
         * мембране появляется щель, выше 1.15 несвязанные граничные клетки
         * попадают в вечный ложный контакт и тело распирает само себя.
         * Замер на теле 947 клеток: длиннейшее граничное ребро 0.0364,
         * ближайшая несвязанная граничная пара 0.0418.
         */
        private const val CONTACT_SEAL = 1.05

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
            /** Позиции покоя — из них берутся длины граничных рёбер. */
            restX: FloatArray, restY: FloatArray,
            meanLink: Double, contactMaxStep: Double,
            /** Клетки без единой связи в ПОЛНОМ графе связей. */
            isolated: BooleanArray? = null,
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
            // Одиночные клетки тоже участвуют в контактах, хотя ни на каком контуре
            // не лежат: связей у них нет вовсе, поэтому граничным ребром их не поймать.
            //
            // Признак приходит СНАРУЖИ и не выводится из смежности выше. Смежность
            // построена по conA/conB, а там намеренно НЕТ внутрикостных связей, и
            // «степень ноль» пометила бы всю внутренность костей — они полезли бы в
            // контакты со своим же телом. На этом уже спотыкались, когда по conA/conB
            // считали связные компоненты и получили 126 организмов вместо двух.
            if (isolated != null) for (i in 0 until n) if (isolated[i]) onBound[i] = true
            val verts = (0 until n).filter { onBound[it] }.toIntArray()

            // Сторона ячейки — наибольший диаметр контакта. Меньше нельзя: пара из
            // соседних ячеек тогда могла бы не попасть в перебор 3x3.
            var maxR = 0.0
            // Радиус контакта — половина самого длинного граничного ребра клетки,
            // с запасом. См. contactRadius: круги соседей по контуру обязаны
            // перекрываться, иначе в мембране остаётся щель.
            val contactRadius = DoubleArray(n)
            for (e in 0 until boundCount) {
                val a = boundA[e]; val b = boundB[e]
                val dx = (restX[a] - restX[b]).toDouble()
                val dy = (restY[a] - restY[b]).toDouble()
                val half = 0.5 * Math.sqrt(dx * dx + dy * dy) * CONTACT_SEAL
                if (half > contactRadius[a]) contactRadius[a] = half
                if (half > contactRadius[b]) contactRadius[b] = half
            }
            // Одиночные клетки граничных рёбер не имеют вовсе — им остаётся
            // собственный радиус: они не мембрана, а пробники.
            if (isolated != null) for (i in 0 until n) {
                if (isolated[i]) contactRadius[i] = radius[i] * contactScale
            }

            for (v in verts) if (contactRadius[v] > maxR) maxR = contactRadius[v]
            val cell = maxOf(2.0 * maxR, 1e-6)

            val bc = BoundaryContacts(
                n, verts, start, adj, radius, cell,
                contactScale, ccdCore, restitution, friction, meanLink, contactMaxStep,
                contactRadius,
            )
            bc.markRestTouching(restX, restY)
            return bc
        }
    }
}
