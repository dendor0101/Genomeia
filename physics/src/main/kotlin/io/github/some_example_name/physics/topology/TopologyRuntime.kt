package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

fun BodyState.probeCells(): BooleanArray = if (probeOn) probe else isFree

/**
 * Пары изгиба вдоль границы: соседи «через одну» и расстояние между ними в позе покоя.
 *
 * Обходить контур по порядку не требуется и было бы хрупко — у рваных краёв и
 * развилок порядок неоднозначен. Достаточно локального признака: вершина, у которой
 * ровно ДВА граничных ребра, лежит внутри гладкого участка контура, и её соседи
 * через одну образуют пару. Вершины развилок (три и более граничных ребра)
 * пропускаются: там «изгиб» не определён.
 */
/**
 * МАССА ИЗ РАДИУСА КЛЕТКИ. Раньше у всех стояла единица, а body.radius разбирался
 * из файла и не использовался нигде.
 *
 * Масса берётся пропорционально ПЛОЩАДИ, то есть квадрату радиуса — мир двумерный,
 * и клетка вдвое большего радиуса весит вчетверо больше, а не вдвое.
 *
 * НОРМИРОВКА НА СРЕДНЮЮ МАССУ ОБЯЗАТЕЛЬНА. Все подобранные константы — податливости,
 * сопротивление среды, вязкость, скорости мышц — взаимодействуют с массой. Если
 * задать массу в абсолютных единицах радиуса, весь набор придётся подбирать заново
 * просто оттого, что редактор сохранил клетки другого размера. При нормировке
 * средняя масса всегда единица, и константы остаются переносимыми между телами.
 *
 * НА ТЕКУЩЕМ ТЕЛЕ ЭТО ТОЖДЕСТВЕННОЕ ПРЕОБРАЗОВАНИЕ: у всех 370 клеток радиус 0.5,
 * значит после нормировки у всех ровно 1.0, и ни одно измеренное число не меняется.
 * Это и хорошо — механизм заводится, а настройки не сбиваются. Разница появится
 * на телах, где геном вырастил клетки разного размера.
 */
/**
 * Строит самоконтакт и предельные длины связей.
 *
 * Предел длины считается на КАЖДУЮ связь отдельно, а не общим числом: радиусы у
 * клеток разные, и общий предел был бы неверен для обеих сторон сразу.
 */
/**
 * Связные компоненты графа связей — это и есть отдельные организмы.
 *
 * ПОЛНЫЙ ОБХОД ТОЛЬКО ПРИ РАСПАДЕ. Разрыв чаще всего ничего не разделяет: концы
 * порванного ребра остаются связаны через соседний треугольник. Поэтому сначала
 * дешёвая проверка (см. splits), и лишь если кусок действительно отвалился —
 * разметка заново. Смежность берётся из запечённой топологии, строить её на каждую
 * пересборку больше не нужно.
 *
 * Смежность по ПОЛНОМУ списку связей, а не по conA/conB: внутрикостные связи в
 * решатель намеренно не попадают (их держит проекция кости), и по ним кости
 * распались бы на отдельные компоненты. На двух телах это давало 126 «организмов»
 * вместо двух.
 */
fun BodyState.buildOrganisms(relabel: Boolean = true) {
    if (!isorganismOfInitialized() || organismOf.size != n) {
        organismOf = IntArray(n); orgQueue = IntArray(n)
    }
    var comp = organismCount
    if (relabel) {
        // Кто чем был ДО перенумерации — по этому куски унаследуют запас среды.
        if (orgPrev.size != n) { orgPrev = IntArray(n); orgSeed = IntArray(n) }
        System.arraycopy(organismOf, 0, orgPrev, 0, n)
        java.util.Arrays.fill(organismOf, -1)
        val vs = baked.vertStart; val vo = baked.vertOther; val ve = baked.vertEdge
        val dead = baked.edgeDead
        val queue = orgQueue
        comp = 0
        for (s in 0 until n) {
            if (organismOf[s] != -1) continue
            orgSeed[comp] = s
            var head = 0; var tail = 0
            organismOf[s] = comp; queue[tail++] = s
            while (head < tail) {
                val v = queue[head++]
                for (k in vs[v] until vs[v + 1]) {
                    if (dead[ve[k]]) continue
                    val u = vo[k]
                    if (organismOf[u] == -1) { organismOf[u] = comp; queue[tail++] = u }
                }
            }
            comp++
        }
        organismCount = comp
    }
    // Ёмкость держится по числу клеток: больше компонент, чем клеток, не бывает.
    val fresh = !isorganismSizeInitialized() || organismSize.size < n
    if (fresh) {
        organismSize = IntArray(n); organismMass = DoubleArray(n)
        flowVX = DoubleArray(n); flowVY = DoubleArray(n)
        comAccX = DoubleArray(n); comAccY = DoubleArray(n)
    }
    // КУСОК УНАСЛЕДУЕТ ЗАПАС СРЕДЫ У ТОГО, ИЗ КОГО ВЫШЕЛ.
    //
    // Раньше запас здесь просто обнулялся всем. А зовётся эта разметка при ЛЮБОМ
    // разрыве, и обнуление стояло вне проверки на распад, поэтому одна порванная
    // связь где угодно в сцене стирала накат у ВСЕХ тел, включая целые и далёкие.
    // Замер (стенд PERF_FLOW): медуза после 10 с гребка, запас 0.663; рвём одну
    // связь, ничего не распадается, организмов как было 8 — запас 0.068, и 2.4
    // секунды обратно. Тело вдруг снова чувствует полное сопротивление и тормозит
    // из-за события, к нему не относящегося.
    //
    // Запас — это СКОРОСТЬ увлечённой воды, а не её импульс: за разгон среды тело уже
    // заплатило (см. FLOW_MASS в applyNormalDrag), и ничего не дублируется оттого, что
    // обе половины получат одну и ту же скорость. Сразу после распада половины и
    // движутся примерно с прежней скоростью, так что это как раз непрерывное
    // продолжение, а обнуление было разрывом.
    if (relabel && !fresh) {
        if (orgFlowX.size < n) { orgFlowX = DoubleArray(n); orgFlowY = DoubleArray(n) }
        System.arraycopy(flowVX, 0, orgFlowX, 0, n)
        System.arraycopy(flowVY, 0, orgFlowY, 0, n)
        for (o in 0 until comp) {
            val was = orgPrev[orgSeed[o]]
            if (was < 0) { flowVX[o] = 0.0; flowVY[o] = 0.0 }
            else { flowVX[o] = orgFlowX[was]; flowVY[o] = orgFlowY[was] }
        }
    }
    java.util.Arrays.fill(comAccX, 0, comp, 0.0)
    java.util.Arrays.fill(comAccY, 0, comp, 0.0)
    // БЕЗ ПЕРЕНУМЕРАЦИИ РАЗМЕРЫ И МАССЫ НЕ МЕНЯЮТСЯ: кто в каком организме — то же
    // самое, массы берутся из позы покоя. Значит и считать нечего. Раньше два прохода
    // по всем клеткам шли на КАЖДУЮ пересборку, а их бывает под два десятка за тик.
    if (!relabel && organismsCounted) return
    organismsCounted = true
    java.util.Arrays.fill(organismSize, 0, comp, 0)
    java.util.Arrays.fill(organismMass, 0, comp, 0.0)
    for (i in 0 until n) organismSize[organismOf[i]]++
    // МАССА организма, а не число клеток: запас среды заряжается от скорости
    // ЦЕНТРА МАСС, а она есть сумма m*v делённая на сумму m. См. applyNormalDrag.
    for (i in 0 until n) {
        if (restInvMass[i] <= 0.0) continue
        organismMass[organismOf[i]] += 1.0 / restInvMass[i]
    }
    if (!rebuilding && !quiet) println("[DemoSession] organisms = $comp (own flow reservoir each)")
}

fun BodyState.buildContacts() {
    if (!isradiusInitialized()) radius = DoubleArray(n) { body.radius[it].toDouble() }
    if (!isisFreeInitialized()) isFree = BooleanArray(n)
    isFree.fill(true)
    for (k in 0 until lnkCount) { isFree[lnkA[k]] = false; isFree[lnkB[k]] = false }
    buildProbes()
    // Ёмкость по целому телу, длина — в conCount: после разрыва списки сжимаются на месте.
    val cap = conA.size
    if (linkTorn.size != cap) {
        linkTorn = BooleanArray(cap); conDead = BooleanArray(cap)
        conBoundary = BooleanArray(cap); conTendon = BooleanArray(cap)
    } else {
        linkTorn.fill(false); conDead.fill(false)
    }
    // Рабочая связь каждого ребра нужна отрисовке, чтобы покрасить ТРЕУГОЛЬНИК по его
    // рёбрам. Раньше это была HashMap пар на пять тысяч записей, заново на каждой пересборке.
    // Связи на контуре (свой порог разрыва, см. MEMBRANE_TEAR_STRAIN) — рёбра не больше чем
    // с одним живым треугольником. Всё это заполняется ОДНИМ проходом по связям.
    if (conOfEdge.size != baked.edgeCount) { conOfEdge = IntArray(baked.edgeCount); conOfEdge.fill(-1) }
    conOfEdge.fill(-1)
    if (conNextForEdge.size < conA.size) conNextForEdge = IntArray(conA.size)
    val use = baked.triUse
    for (c in 0 until conCount) {
        val e = conEdge[c]
        conNextForEdge[c] = conOfEdge[e]
        conOfEdge[e] = c
        conBoundary[c] = use[e] <= 1
        // СУХОЖИЛИЕ: связь мышцы с костью. Мышца тянет, кость не поддаётся, и всю
        // деформацию берёт на себя эта связь. См. TENDON_TEAR_FACTOR.
        val a = conA[c]; val b = conB[c]
        conTendon[c] = (muscleOf[a] >= 0) != (muscleOf[b] >= 0)
    }
    if (everLinked == null) everLinked = BoundaryContacts.linkedPairs(conA, conB, conCount)
    // Кандидаты касания в позе покоя считаются по ЦЕЛОМУ телу и от разрывов не зависят:
    // радиус контакта клетки не бывает больше половины её самой длинной связи с запасом шва.
    if (restPairs == null) restPairs = BoundaryContacts.bakeRestPairs(
        n,
        BoundaryContacts.maxContactRadius(n, body.linkA, body.linkB, body.linkCount,
            radius, CONTACT_SCALE, body.x, body.y, config.contacts.CONTACT_SEAL),
        body.x, body.y, everLinked!!)
    val ct = contacts
    if (ct == null) {
        contacts = BoundaryContacts.build(
            n, conA, conB, conCount, boundA, boundB, boundCount, boundOpp, radius,
            CONTACT_SCALE, CCD_CORE, CONTACT_RESTITUTION, CONTACT_FRICTION,
            body.x, body.y,
            body.meanLinkLength.toDouble(), CONTACT_MAX_STEP, probeCells(), everLinked,
            cellDead, restPairs, config.contacts,
        )
    } else {
        // Объект переиспользуется: заводить его заново на каждой пересборке стоило
        // дороже всего остального разрыва вместе взятого. См. reconfigure.
        ct.reconfigure(conA, conB, conCount, boundA, boundB, boundCount, boundOpp,
            body.x, body.y, probeCells(), cellDead, restPairs, everLinked)
    }
    localSurface = LocalSurface(body, baked, boneOf, cellDead, CONTACT_SCALE,
        config.contacts.CONTACT_SEAL, PROBE_ANGLE_EPS, isFree, probe, probeOn)
    localBoundary = LocalBoundaryConstraints(body, baked, boneOf)
    for (v in 0 until n) { localSurface.update(v); localBoundary.updateVertex(v) }
    syncBoundaryArrays()
    contacts!!.attachSurface(localSurface)
    localComponents = LocalComponents(baked, organismOf, organismSize, organismMass,
        restInvMass, cellDead, flowVX, flowVY, organismCount)
    componentTearCursor = 0
    baked.clearDirty()
    // Наименьший радиус контакта в сцене — мерка «кто вообще способен пролезть».
    // См. dbgSeal. Свободная частица меньше мембранной клетки в разы, поэтому мерить
    // проходимость щели надо именно по ней.
    dbgSealMinR = contacts!!.minContactRadius
    contacts!!.also {
        // ПРАВИЛО ОБЩЕЕ, А НЕ ОПЦИЯ СТЕНДА: центр внутри чужого радиуса это
        // проникновение, и оно запрещено везде, а не только в лаборатории.
        it.killOnDeep = KILL_ON_DEEP_OVERLAP
        it.killDepth = KILL_DEPTH
        if (internalMom?.n != n) internalMom = InternalMomentum(n)
        if (contactPressure.size != n) contactPressure = DoubleArray(n)
        it.pressure = contactPressure
        it.organismOf = organismOf
        if (contactPressureN.size != n) contactPressureN = IntArray(n)
        it.pressureN = contactPressureN
        if (crushedCell.size != n) crushedCell = BooleanArray(n)
    }
    if (!rebuilding && !quiet) println("[DemoSession] contact particles = ${boundCount} boundary edges, " +
        "contact radius from boundary edges, seal margin")
    // ПРАВИЛО СМЕРТИ ВИДНО В ЖУРНАЛЕ: иначе замер легко сделать с выключенным
    // правилом и объявить результат.
    if (!rebuilding && !quiet) println("[DemoSession] смерть от проникновения: " +
        (if (contacts!!.killOnDeep) "ВКЛ, порог ${KILL_DEPTH} радиуса, умирают обе клетки (кость не умирает)"
         else "выкл (RB_KILLDEEP=0)"))
}

/**
 * КЛЕТКИ, КОТОРЫЕ ТКАНЬ НЕ ЗАКРЫВАЕТ, — ЗОНДЫ. См. probe.
 *
 * Контур выводится из рёбер: ребро с одним живым треугольником — граница. На
 * ПРАВИЛЬНОЙ сетке этого достаточно, но выгрузка правильной не бывает: у body-export
 * 118 рёбер несут ТРИ треугольника, потому что мелкие клетки (радиус 0.2 против 0.5)
 * вшиты в сетку крупных, и крупный треугольник проходит поверх мелкой клетки. Для
 * рёберной проверки такая клетка «внутри», и контакта у неё нет вовсе — а лежит она
 * СНАРУЖИ контура, до 2.7 средней связи от него. Игрок это и поймал: чужие клетки
 * проходят сквозь мелкие, как через воздух, и только потом упираются в настоящий
 * контур (журнал 30.09, 24 метки — все с радиусом 0.2, все снаружи контура).
 *
 * Второй, независимый признак поверхности: СУММА УГЛОВ треугольников вокруг клетки.
 * У клетки внутри сплошной ткани она равна 2pi, у клетки на краю — меньше. Признак
 * не опирается на манифольдность и ловит 18 из 24 помеченных, а всего добавляет к
 * контуру 26 клеток из 947 — то есть дёшево.
 *
 * Шесть клеток не ловит и он: у них сумма углов БОЛЬШЕ 2pi (до 137%), потому что
 * налегающие треугольники накрывают их с запасом. Это уже поломка сетки, и чинить её
 * надо в редакторе — здесь остаётся записать, что такие клетки существуют.
 */
fun BodyState.buildProbes() {
    if (probe.size != n) probe = BooleanArray(n)
    if (probeAngle.size != n) probeAngle = DoubleArray(n)
    java.util.Arrays.fill(probeAngle, 0.0)
    for (t in 0 until triCount) {
        val a = triA[t]; val b = triB[t]; val c = triC[t]
        probeAngle[a] += angleAt(a, b, c)
        probeAngle[b] += angleAt(b, a, c)
        probeAngle[c] += angleAt(c, a, b)
    }
    val full = 2.0 * Math.PI - PROBE_ANGLE_EPS
    for (i in 0 until n) probe[i] = isFree[i] || probeAngle[i] < full
}

/** Угол при вершине v в треугольнике (v, p, q) в позе ПОКОЯ: топология, не движение. */
fun BodyState.angleAt(v: Int, p: Int, q: Int): Double {
    val ax = (body.x[p] - body.x[v]).toDouble(); val ay = (body.y[p] - body.y[v]).toDouble()
    val bx = (body.x[q] - body.x[v]).toDouble(); val by = (body.y[q] - body.y[v]).toDouble()
    val na = sqrt(ax * ax + ay * ay); val nb = sqrt(bx * bx + by * by)
    if (na < 1e-12 || nb < 1e-12) return 0.0
    var c = (ax * bx + ay * by) / (na * nb)
    if (c > 1.0) c = 1.0 else if (c < -1.0) c = -1.0
    return StrictMath.acos(c)
}

fun BodyState.buildMasses() {
    restInvMass = DoubleArray(n)
    var meanArea = 0.0
    for (i in 0 until n) {
        val r = body.radius[i].toDouble()
        meanArea += r * r
    }
    meanArea /= n
    for (i in 0 until n) {
        val r = body.radius[i].toDouble()
        val m = if (meanArea > 0.0) (r * r) / meanArea else 1.0
        restInvMass[i] = if (m > 1e-12) 1.0 / m else 1.0
    }
}

/**
 * ПОСЛЕДНЯЯ HashMap УБРАНА. Здесь стояла HashMap<Int, MutableList<Int>> со списком
 * голых соседей у каждой клетки: на каждую такую связь — Integer в куче, ArrayList на
 * клетку, и всё это заново на каждой пересборке, а их бывают десятки за тик. Заменено
 * на обычные CSR-массивы: степени, префиксные суммы, соседи подряд.
 *
 * Порядок ИЗМЕНИЛСЯ и не мог не измениться: обход HashMap идёт по корзинам, а теперь
 * клетки идут строго по возрастанию номера. Пары внутри клетки по-прежнему в порядке
 * связей. На нынешних телах лоскутов нет вовсе (их заводит только разрыв), а после
 * разрыва проверки и журналы сходятся.
 */
fun BodyState.buildFlaps() {
    // Ни одного голого ребра — лоскутов нет по определению, искать нечего.
    // См. BakedTopology.bareEdges: проход по всем связям стоил семь миллисекунд
    // из сорока на тяжёлом прогоне, и почти всегда впустую.
    if (baked.bareEdges == 0) {
        flapCount = 0
        if (!rebuilding && !quiet) println("[DemoSession] flap angles = 0")
        return
    }
    val use = baked.triUse
    if (flapDeg.size != n) { flapDeg = IntArray(n); flapStart = IntArray(n + 1) }
    java.util.Arrays.fill(flapDeg, 0)
    var bare = 0
    for (c in 0 until conCount) {
        if (use[conEdge[c]] > 0) continue
        flapDeg[conA[c]]++; flapDeg[conB[c]]++; bare++
    }
    if (bare == 0) {
        flapCount = 0
        if (!rebuilding && !quiet) println("[DemoSession] flap angles = 0")
        return
    }
    flapStart[0] = 0
    for (i in 0 until n) flapStart[i + 1] = flapStart[i] + flapDeg[i]
    val total = flapStart[n]
    if (flapNb.size < total) flapNb = IntArray(total)
    System.arraycopy(flapStart, 0, flapDeg, 0, n)   // курсоры записи
    for (c in 0 until conCount) {
        if (use[conEdge[c]] > 0) continue
        val i = conA[c]; val j = conB[c]
        flapNb[flapDeg[i]++] = j
        flapNb[flapDeg[j]++] = i
    }
    var m = 0
    for (v in 0 until n) {
        val d = flapStart[v + 1] - flapStart[v]
        if (d >= 2) m += d * (d - 1) / 2
    }
    if (flapA.size < m) { flapA = IntArray(m); flapB = IntArray(m); flapRest = DoubleArray(m) }
    var k = 0
    for (v in 0 until n) {
        val s = flapStart[v]; val e = flapStart[v + 1]
        if (e - s < 2) continue
        for (p in s until e) for (q in p + 1 until e) {
            val i = flapNb[p]; val j = flapNb[q]
            if (i == j) continue
            val dx = (body.x[i] - body.x[j]).toDouble()
            val dy = (body.y[i] - body.y[j]).toDouble()
            flapA[k] = i; flapB[k] = j; flapRest[k] = sqrt(dx * dx + dy * dy); k++
        }
    }
    flapCount = k
    if (!rebuilding && !quiet) println("[DemoSession] flap angles = $flapCount")
}

fun BodyState.buildBend() {
    if (bendNb0.size != n) { bendNb0 = IntArray(n); bendNb1 = IntArray(n); bendDeg = IntArray(n) }
    val nb0 = bendNb0; val nb1 = bendNb1; val deg = bendDeg
    java.util.Arrays.fill(nb0, -1); java.util.Arrays.fill(nb1, -1); java.util.Arrays.fill(deg, 0)
    for (e in 0 until boundCount) {
        val i = boundA[e]; val j = boundB[e]
        if (deg[i] == 0) nb0[i] = j else if (deg[i] == 1) nb1[i] = j
        deg[i]++
        if (deg[j] == 0) nb0[j] = i else if (deg[j] == 1) nb1[j] = i
        deg[j]++
    }
    if (!isbendAInitialized() || bendA.size < n) {
        bendA = IntArray(n); bendB = IntArray(n); bendRest = DoubleArray(n)
    }
    var m = 0
    for (v in 0 until n) {
        if (deg[v] != 2) continue
        val i = nb0[v]; val j = nb1[v]
        if (i < 0 || j < 0 || i == j) continue
        val dx = (body.x[i] - body.x[j]).toDouble()
        val dy = (body.y[i] - body.y[j]).toDouble()
        bendA[m] = i; bendB[m] = j; bendRest[m] = sqrt(dx * dx + dy * dy); m++
    }
    bendCount = m
    if (!rebuilding && !quiet) println("[DemoSession] bend pairs = $bendCount on $boundCount boundary edges")
}

/**
 * Поза покоя костей: смещения от центроида кластера, один раз и навсегда.
 *
 * Центроид считается в double НАМЕРЕННО: это подготовка, а не горячий путь, и
 * именно здесь дешевле всего убить сокращение разрядов. В движке это делает
 * запекатель BakedLayout, в рантайме double не появляется.
 *
 * Сумма смещений досаживается в ноль итерацией: после вычитания среднего во float
 * сумма остаётся порядка 1e-6, второй проход убирает и её. Без этого проекция кости
 * каждый подшаг сдвигала бы кость на этот остаток — а это и был дрейф.
 */
fun BodyState.buildBoneRestPose() {
    boneRestQx = Array(rigidBones.size) { DoubleArray(rigidBones[it].size) }
    boneRestQy = Array(rigidBones.size) { DoubleArray(rigidBones[it].size) }
    var maxSize = 1
    rigidBones.forEachIndexed { b, ids ->
        if (ids.size > maxSize) maxSize = ids.size
        val qx = boneRestQx[b]; val qy = boneRestQy[b]
        var cx = 0.0; var cy = 0.0
        for (i in ids) { cx += body.x[i]; cy += body.y[i] }
        cx /= ids.size; cy /= ids.size
        for (k in ids.indices) {
            qx[k] = body.x[ids[k]] - cx
            qy[k] = body.y[ids[k]] - cy
        }
        for (pass in 0 until 3) {
            var sx = 0.0; var sy = 0.0
            for (k in ids.indices) { sx += qx[k]; sy += qy[k] }
            if (sx == 0.0 && sy == 0.0) break
            val dx = sx / ids.size; val dy = sy / ids.size
            for (k in ids.indices) { qx[k] -= dx; qy[k] -= dy }
        }
    }
    boneDx = DoubleArray(maxSize)
    boneDy = DoubleArray(maxSize)
}

/**
 * Граница тела — рёбра, входящие ровно в ОДИН треугольник.
 *
 * Внутреннее ребро делят два треугольника, граничное — только один. Это работает на
 * любой топологии, включая рваные края и отростки, и не требует ни обхода контура,
 * ни знания порядка вершин.
 *
 * Считается по ВСЕМ треугольникам, а не по списку связей решателя: внутрикостных
 * связей мы не создаём, но поверхность кости средой омывается точно так же.
 */
fun BodyState.buildBoundary() {
    val use = baked.triUse
    // Обход идёт по ВСЕМ связям, а не по ключам карты треугольников. Связь в тонком
    // отростке (шириной в одну клетку) не входит НИ В ОДИН треугольник, в карту не
    // попадает вовсе — и, считая только по карте, я терял такие рёбра целиком.
    // А это как раз хвост и плавники, то есть основная гребущая поверхность.
    if (!isboundAInitialized() || boundA.size < lnkCount) {
        boundA = IntArray(lnkCount); boundB = IntArray(lnkCount); boundOpp = IntArray(lnkCount)
    }
    var m = 0
    for (k in 0 until lnkCount) {
        val e = lnkEdge[k]
        if (use[e] > 1) continue
        boundA[m] = lnkA[k]; boundB[m] = lnkB[k]
        // ТРЕТЬЯ ВЕРШИНА — ВНУТРЕННЯЯ СТОРОНА РЕБРА. У ребра с одним треугольником
        // она есть, и по ней контакт узнаёт, где «наружу»: см. solveContourSide.
        // У ГОЛОГО ребра (ни одного треугольника — тонкий отросток в одну клетку)
        // внутренней стороны нет вовсе, и там стоит -1: у нитки нет изнутри.
        boundOpp[m] = -1
        if (use[e] == 1) {
            val t = baked.liveTri(e)
            if (t >= 0) {
                val a = lnkA[k]; val b = lnkB[k]
                val t0 = body.triA[t]; val t1 = body.triB[t]; val t2 = body.triC[t]
                boundOpp[m] = when {
                    t0 != a && t0 != b -> t0
                    t1 != a && t1 != b -> t1
                    else -> t2
                }
            }
        }
        m++
    }
    boundCount = m
    if (!rebuilding && !quiet) println("[DemoSession] boundary edges = $boundCount")
}

/**
 * Кластер мышцы у связи и у треугольника. См. MUSCLE_TOUCH.
 *
 * Строгое правило: оба конца связи и все три вершины треугольника в ОДНОМ кластере.
 * Кольцо вокруг мышцы попадает внутрь не здесь, а раньше — расширением самого списка
 * клеток, см. growMuscles(). Бахрома (режим 2) считается прямо тут.
 *
 * Когда клетки в РАЗНЫХ кластерах, побеждает большинство вершин, а при равенстве —
 * меньший номер. Правило не зависит от того, в каком порядке записаны концы связи и
 * вершины треугольника: иначе физика зависела бы от порядка строк в выгрузке.
 */
/**
 * Связи решателя из полного списка рёбер: внутрикостные пропускаются, их держит
 * проекция кости. Раньше это место было списками ArrayList<Int> и ArrayList<Double>
 * — то есть на каждую связь по Integer и Double в куче, пять тысяч коробок на сборку,
 * и всё это сразу в мусор. Массивы заводятся по числу рёбер (больше связей не
 * бывает) и переиспользуются между сборками.
 */
fun BodyState.fillConstraints(): Int {
    var m = 0
    for (k in 0 until lnkCount) {
        val i = lnkA[k]; val j = lnkB[k]
        if (boneOf[i] != -1 && boneOf[i] == boneOf[j]) continue
        val dx = body.x[i] - body.x[j]; val dy = body.y[i] - body.y[j]
        conA[m] = i; conB[m] = j
        conRest[m] = sqrt((dx * dx + dy * dy).toDouble())
        conEdge[m] = lnkEdge[k]
        conMuscle[m] = muscleOfPair(i, j)
        m++
    }
    return m
}

fun BodyState.rebuildTimed() {
    val t0 = System.nanoTime()
    rebuilding = true
    try { rebuildAfterTear() } finally { rebuilding = false }
    dbgRebuildNs += System.nanoTime() - t0
    dbgRebuildN++
}

/** Погасить ребро немедленно и запомнить для пересборки в конце тика. */
fun BodyState.killEdgeNow(e: Int) {
    if (!baked.kill(e)) return
    // Все связи одной пары гаснут вместе; это не требует второй проверки
    // edgeDead на каждой связи в каждом проходе решателя.
    var c = conOfEdge[e]
    while (c >= 0) { conDead[c] = true; c = conNextForEdge[c] }
    if (tearQueueN == tearQueue.size) tearQueue = tearQueue.copyOf(tearQueue.size * 2)
    tearQueue[tearQueueN++] = e
    tearsPending = true
}

/**
 * Возврат топологии к целой. Обязателен при сбросе: без него порванное тело
 * оставалось бы порванным навсегда, и любой следующий замер шёл бы уже по
 * другому телу. В регрессионной проверке это било бы особенно тихо — тесты
 * идут подряд по одному экземпляру.
 */
fun BodyState.restoreTopology() {
    // Целое тело — это и живые клетки. Без этого лопнувшие при сбросе оставались
    // мёртвыми: не рисовались, не сталкивались, а первое же лопание снова рвало все
    // их восстановленные связи — в свежем теле появлялись дыры. Стенд столкновений
    // сбрасывается перед каждой вариацией, и мёртвые копились там от сцены к сцене.
    cellDead.fill(false)
    deadCount = 0
    crushedCell.fill(false)
    lnkCount = body.linkCount
    lnkA = body.linkA.copyOf(); lnkB = body.linkB.copyOf()
    triCount = body.triCount
    baked.restore()
    lnkEdge = baked.linkEdge.copyOf()
    triId = IntArray(triCount) { it }
    triA = body.triA.copyOf(); triB = body.triB.copyOf(); triC = body.triC.copyOf()
    triRestArea2 = body.triRestArea2.copyOf()
    triMuscle = IntArray(triCount) { t -> muscleOfTri(triA[t], triB[t], triC[t]) }
    triInverted = BooleanArray(triCount)

    if (conA.size < lnkCount) {
        conA = IntArray(lnkCount); conB = IntArray(lnkCount)
        conRest = DoubleArray(lnkCount); conMuscle = IntArray(lnkCount)
        conEdge = IntArray(lnkCount)
    }
    conCount = fillConstraints()

    tearsPending = false
    tearQueueN = 0
    tornTotal = 0
    conHold.fill(0); triHold.fill(0)
    buildBoundary(); buildBend(); buildFlaps(); buildOrganisms(); buildContacts()
}

fun BodyState.rebuildAfterTear() {
    tearsPending = false
    var t = System.nanoTime()
    fun lap(k: Int) {
        val now = System.nanoTime()
        dbgRebuildSec[k] += now - t; dbgRebuildTot[k] += now - t; t = now
    }
    dbgRebuildCount++

    // Мёртвые рёбра гасятся в запечённой топологии: треугольники на них умирают сами,
    // счётчики треугольников у соседних рёбер уменьшаются. Раньше мёртвые пары
    // сортировались, и каждая связь и каждый треугольник искались среди них двоичным
    // поиском. Ребро — пара клеток, поэтому все связи одной пары умирают вместе, как и
    // тогда. Порядок живых списков прежний, результат побитово тот же — проверено
    // контрольными суммами по каждому тику шести журналов игрока.
    // Очередь уже погашенных рёбер — её набрал killEdgeNow по ходу тика.
    refreshSurface()
    val nd = tearQueueN
    tearQueueN = 0
    componentTearCursor = 0
    if (nd == 0) return
    tornTotal += nd
    val edgeDead = baked.edgeDead

    // 1. Полный список связей. Сжатие НА МЕСТЕ: пишущий индекс никогда не обгоняет
    //    читающий, а массивы остаются прежней ёмкости — длина живёт в счётчике.
    //
    // НАЧИНАЕМ С ПЕРВОГО МЁРТВОГО, А НЕ С НУЛЯ. До него список не меняется вовсе:
    // ни один элемент не сдвигается, и переписывать их на самих себя незачем. Рвётся
    // за раз одна-две связи, так что в среднем это половина прохода, а при разрыве
    // в хвосте — почти ничего. Результат тот же до бита.
    run {
        var m = 0
        while (m < lnkCount && !edgeDead[lnkEdge[m]]) m++
        var k = m
        while (k < lnkCount) {
            if (!edgeDead[lnkEdge[k]]) {
                lnkA[m] = lnkA[k]; lnkB[m] = lnkB[k]; lnkEdge[m] = lnkEdge[k]; m++
            }
            k++
        }
        lnkCount = m
    }

    // 2. Треугольники: умерли вместе с любым своим ребром — см. BakedTopology.kill
    run {
        val alive = baked.triAlive
        var m = 0
        while (m < triCount && alive[triId[m]]) m++
        var k = m
        while (k < triCount) {
            if (alive[triId[k]]) {
                triA[m] = triA[k]; triB[m] = triB[k]; triC[m] = triC[k]
                if (triHold.size > k) triHold[m] = triHold[k]
                triRestArea2[m] = triRestArea2[k]; triMuscle[m] = triMuscle[k]; triId[m] = triId[k]
                m++
            }
            k++
        }
        triCount = m
        java.util.Arrays.fill(triInverted, false)
    }

    // 3. Рабочие связи решателя — тем же правилом, что при загрузке.
    //
    // ДЛИНА ПОКОЯ ПЕРЕЕЗЖАЕТ, А НЕ СЧИТАЕТСЯ ЗАНОВО. При пластичности она уже не
    // равна длине в позе покоя: ткань текла. Пересчёт из body.x стирал бы всё
    // накопленное течение на каждом разрыве, то есть ровно там, где оно и нужно.
    // Треугольникам это уже сделано выше — их площадь покоя переносится из старого
    // массива, а не берётся из файла.
    //
    // Рабочие связи — это полный список без внутрикостных, в том же порядке. Новый
    // полный список — старый без мёртвых, значит новые рабочие связи — ровно старые
    // рабочие без мёртвых, в том же порядке. Их и оставляем, вместе с длиной покоя.
    run {
        var m = 0
        while (m < conCount && !edgeDead[conEdge[m]]) m++
        var c = m
        while (c < conCount) {
            if (!edgeDead[conEdge[c]]) {
                conA[m] = conA[c]; conB[m] = conB[c]; conRest[m] = conRest[c]
                if (conHold.size > c) conHold[m] = conHold[c]
                conMuscle[m] = conMuscle[c]; conEdge[m] = conEdge[c]; m++
            }
            c++
        }
        conCount = m
    }

    // Контур, пары покоя и компоненты уже обновлены локально в подшаге.
    // Здесь только упаковка рабочих массивов с сохранением порядка и пластики.
    conOfEdge.fill(-1)
    for (c in 0 until conCount) {
        conNextForEdge[c] = conOfEdge[conEdge[c]]
        conOfEdge[conEdge[c]] = c
        conDead[c] = false; linkTorn[c] = false
        conBoundary[c] = baked.triUse[conEdge[c]] <= 1
        conTendon[c] = (muscleOf[conA[c]] >= 0) != (muscleOf[conB[c]] >= 0)

    }
    lap(0)
}

fun BodyState.refreshSurface() {
    // Отдельный таймер: обновление вызывается и внутри других измеряемых стадий.
    val surfaceT0 = if (DEBUG_CHECKS && dbgTimeOn) System.nanoTime() else 0L
    while (componentTearCursor < tearQueueN) localComponents.split(tearQueue[componentTearCursor++])
    organismCount = localComponents.count
    val count = baked.dirtyCount
    if (count == 0) {
        if (DEBUG_CHECKS && dbgTimeOn) dbgTimeNs[12] += System.nanoTime() - surfaceT0
        return
    }
    val changed = baked.dirtyVertices
    for (k in 0 until count) {
        val v = changed[k]
        localSurface.update(v)
        localBoundary.updateVertex(v)
        for (s in baked.vertStart[v] until baked.vertStart[v + 1]) {
            val e = baked.vertEdge[s]
            val boundary = baked.triUse[e] <= 1
            var c = conOfEdge[e]
            while (c >= 0) { conBoundary[c] = boundary; c = conNextForEdge[c] }
        }
    }
    syncBoundaryArrays()
    contacts?.updateSurface(localSurface, changed, count)
    dbgSealMinR = contacts?.minContactRadius ?: 0.0
    baked.clearDirty()
    if (DEBUG_CHECKS && dbgTimeOn) dbgTimeNs[12] += System.nanoTime() - surfaceT0
}

fun BodyState.syncBoundaryArrays() {
    boundA = localBoundary.a; boundB = localBoundary.b; boundOpp = localBoundary.opposite
    boundCount = localBoundary.count
    bendA = localBoundary.bend.a; bendB = localBoundary.bend.b; bendRest = localBoundary.bend.rest
    bendCount = localBoundary.bend.count
    flapA = localBoundary.flap.a; flapB = localBoundary.flap.b; flapRest = localBoundary.flap.rest
    flapCount = localBoundary.flap.count
}

/** Порвалась ли хоть одна из трёх сторон треугольника. */
fun BodyState.triTorn(t: Int): Boolean {
    val te = baked.triEdge; val o = 3 * triId[t]
    for (k in 0..2) {
        val e = te[o + k]
        if (e < 0) continue
        val c = conOfEdge[e]
        if (c >= 0 && linkTorn[c]) return true
    }
    return false
}

/** Рабочая связь пары клеток или -1. Разбору журнала. */
fun BodyState.conIndexOf(i: Int, j: Int): Int {
    val e = baked.edgeOf(i, j)
    return if (e < 0) -1 else conOfEdge[e]
}
