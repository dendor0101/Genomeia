package io.github.some_example_name.physics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Применить заявки на смерть, накопленные контактом за проход.
 *
 * Смерть клетки это смерть всех её связей: тогда её треугольники распадаются сами,
 * компоненты пересобираются, и кусок вокруг дыры живёт дальше по обычным правилам
 * разрушения. Отдельного механизма для этого не нужно вовсе.
 *
 * Заявки применяются ПОСЛЕ прохода, а не внутри: список контактов и смежность
 * собраны на текущую топологию, и менять её посреди обхода нельзя.
 */
/**
 * ПРОБИТАЯ МЕМБРАНА РВЁТСЯ. См. pierceN в контактах.
 *
 * Контур был задуман непроницаемым, и это оказалось неверно: контакт — сила, а силу
 * всегда можно пересилить. Правильная посылка другая: мембрана либо не пускает, либо
 * РВЁТСЯ. Пересечение ловится геометрией (отрезок пути против отрезка ребра), а здесь
 * найденное ребро превращается в порванную связь — тем же путём, что обычный разрыв,
 * то есть ребро гаснет сразу, а структура пересобирается в конце тика.
 */
fun BodyState.applyPierce(ct: BoundaryContacts) {
    // Как при смерти клетки: исчезающая топология ещё должна успеть вернуть
    // вывернутый отломок на внешнюю сторону ткани. После killEdgeNow уже поздно.
    fractureOrientation.solvePierced(ct, conOfEdge, conDead, conCount, px, py, invMass)
    for (k in 0 until ct.pierceN) {
        val e = baked.edgeOf(ct.pierceA[k], ct.pierceB[k])
        if (e < 0 || baked.edgeDead[e]) continue
        val c = conOfEdge[e]
        if (c < 0 || c >= conCount || conDead[c]) continue
        // Временную связь роста клетка прошла насквозь — ткань там уже есть: связь снята тихо (TempLinks.kt).
        if (isTempEdge(e)) { retireTempEdge(e); continue }
        linkTorn[c] = true; conDead[c] = true; tearsPending = true
        killEdgeNow(e)
        piercedCount++
        dbgTear(c, conA[c], conB[c], "пробой", 0.0f)
    }
    ct.clearPierce()
    refreshSurface()
}

fun BodyState.applyKills(ct: BoundaryContacts) {
    // После удаления связей их перевёрнутые треугольники исчезнут: отломок может
    // остаться внутри ткани без ограничения, способного вернуть его наружу.
    fractureOrientation.solve(ct.killList, ct.killN, px, py, invMass)
    var any = false
    for (k in 0 until ct.killN) {
        val v = ct.killList[k]
        // Неподвижная бессмертна: заявку на неё контакт пишет, как на любую (у него нет масс
        // покоя), партнёр по заявке гибнет своей.
        if (cellDead[v] || isFixed(v)) continue
        cellDead[v] = true
        deadCount++
        any = true
        killIncidentEdges(v)
        // МЁРТВАЯ КЛЕТКА ЗАМОРАЖИВАЕТСЯ, А НЕ ПРОСТО ОСТАНАВЛИВАЕТСЯ.
        //
        // Обнулить скорость мало: следующий же тик снова приложит к ней поток и
        // тягу, и невидимая клетка (отрисовка мёртвых пропускает) полетит по сцене
        // без контактного радиуса — сквозь всё. Ноль обратной массы выводит её из
        // интегрирования так же, как неподвижные частицы бассейна.
        vx[v] = 0.0f; vy[v] = 0.0f; invMass[v] = 0.0f
        // Из контактов клетка выводится сразу, а СТРУКТУРА перестраивается один раз
        // в конце тика: смерть приходит изнутри итерации решателя, и пересборка на
        // каждую смерть означала бы до 64 пересборок за тик.
        ct.dropFromContacts(v)
    }
    // Просто отложить контур до конца тика уже пробовали: на таране оставалось
    // внутри 109 клеток против 0. За погибшей открывается новая поверхность,
    // и её радиус должен появиться до следующего прохода контактов.
    if (any) refreshSurface()
}

/**
 * КЛЕТКУ УБИВАЕТ КЛИЕНТ (игра: голод, поедание, смерть по своим правилам) — тем же путём,
 * что глубокий контакт: связи гаснут, организм делится, клетка замерзает и выходит из
 * контактов, контур закрывается. Без решения отломков (fractureOrientation): смерть пришла
 * не из итерации решателя, вывернутых треугольников от неё нет. Зовётся между тиками.
 */
fun BodyState.killCell(v: Int) {
    if (cellDead[v] || isFixed(v)) return
    if (dragId == v) { matchWeight[v] = 1.0f; dragId = -1 }
    cellDead[v] = true
    deadCount++
    killIncidentEdges(v)
    vx[v] = 0.0f; vy[v] = 0.0f; invMass[v] = 0.0f
    contacts?.dropFromContacts(v)
    refreshSurface()
    structureVersion++
    // Клиент зовёт между тиками: кость погибшей клетки пересматривается сразу.
    splitDirtyBones()
}

fun BodyState.killIncidentEdges(v: Int) {
    if (cellDeathN == cellDeathLog.size) cellDeathLog = cellDeathLog.copyOf(cellDeathN * 2)
    cellDeathLog[cellDeathN++] = v
    localComponents.removeCell(v)
    // Клетка кости: она выйдет из кости, а кость проверят на связность между тиками. См. BoneSplit.kt.
    if (boneClusterOf[v] >= 0) markBoneDirty(boneClusterOf[v])
    for (s in baked.vertStart[v] until baked.vertStart[v + 1]) {
        val e = baked.vertEdge[s]
        val c = conOfEdge[e]
        if (c >= 0) conDead[c] = true
        killEdgeNow(e)
    }
    // Свободная клетка не имеет рёбер, но её смерть тоже меняет поверхность.
    localSurface.update(v)
    surfaceOne[0] = v
    contacts?.updateSurface(localSurface, surfaceOne, 1)
    dbgSealMinR = contacts?.minContactRadius ?: 0.0f
}

fun BodyState.applyPlasticity(dt: Float) {
    if (PLASTIC_RATE <= 0.0f) return
    var k = PLASTIC_RATE * dt
    if (k > 1.0f) k = 1.0f
    // Выдержка принадлежит конкретной связи/треугольнику и переезжает вместе
    // с ними при упаковке; разрыв в другом месте не сбрасывает накопленную пластику.
    if (conHold.size < conA.size) conHold = conHold.copyOf(conA.size)
    if (triHold.size < triA.size) triHold = triHold.copyOf(triA.size)

    // Каждая связь и каждый треугольник текут независимо от остальных: порядок обхода
    // на итог не влияет. Пока никто не спит — прежний сплошной проход; со спящими —
    // только блоки, где есть бодрствующие частицы, и шов. У спящего тела длины
    // заморожены вместе с телом: течь ему, как и двигаться, не с чего. См. BodySleep.
    val layout = constraintBlocks
    val w = activeWorkers
    if (w != null) { parallelPhysics(w).plasticity(k, layout); return }
    if (sleep.asleepCount == 0 || layout == null) {
        plasticLinksRange(0, conCount, k)
        plasticTrianglesRange(0, triCount, k)
        return
    }
    for (block in 0 until layout.buckets) {
        if (block < layout.seam && layout.awake[block] == 0) continue
        plasticBlock(block, layout, k)
    }
}

/*
 * ЦИКЛ ПЛАСТИКИ — В ОДНОМ МЕТОДЕ СО СВОИМ ЯДРОМ (23.11.2026). Раньше связь и треугольник текли вызовом функции на
 * каждый элемент, и та на каждом вызове заново читала десяток полей состояния и длины их массивов — на телефоне это
 * чтение заголовков массивов мимо кэша (см. lengthsBeforeLoop). Теперь ядра plasticLinkAt и plasticTriangleAt —
 * `inline` с массивами в параметрах, а циклы (по номерам и по блоку раскладки) держат массивы в локальных
 * переменных. Арифметика та же, порядок обхода тот же; одним и тем же кодом идут и один поток, и воркеры.
 */

/** Связи [c0, c1) по номерам: без сна. Кусок параллельного прохода — см. ParallelPhysics.plasticity. */
internal fun BodyState.plasticLinksRange(c0: Int, c1: Int, k: Float) {
    val edgeDead = baked.edgeDead; val conEdge = conEdge; val conMuscle = conMuscle
    val csOn = cellScalesOn; val cs = conCellScale; val conRest = conRest
    val px = px; val py = py; val conA = conA; val conB = conB; val hold = conHold
    lengthsBeforeLoop(edgeDead.size or conEdge.size or conMuscle.size or cs.size or conRest.size or px.size or py.size or
        conA.size or conB.size or hold.size)
    for (c in c0 until c1) plasticLinkAt(c, k, edgeDead, conEdge, conMuscle, csOn, cs, conRest, px, py, conA, conB, hold)
}

/** Треугольники [t0, t1) по номерам: без сна. Кусок параллельного прохода. */
internal fun BodyState.plasticTrianglesRange(t0: Int, t1: Int, k: Float) {
    val triAlive = baked.triAlive; val triId = triId; val triMuscle = triMuscle
    val tsOn = cellScalesOn; val ts = triCellScale; val rest2 = triRestArea2
    val px = px; val py = py; val triA = triA; val triB = triB; val triC = triC; val hold = triHold
    lengthsBeforeLoop(triAlive.size or triId.size or triMuscle.size or ts.size or rest2.size or px.size or py.size or
        triA.size or triB.size or triC.size or hold.size)
    for (t in t0 until t1) plasticTriangleAt(t, k, triAlive, triId, triMuscle, tsOn, ts, rest2, px, py, triA, triB, triC, hold)
}

/** Связи и треугольники блока или шва [block] раскладки: со сном — только бодрствующие блоки. */
internal fun BodyState.plasticBlock(block: Int, layout: ConstraintBlocks, k: Float) {
    plasticBlockLinks(block, layout, k)
    plasticBlockTriangles(block, layout, k)
}

private fun BodyState.plasticBlockLinks(block: Int, layout: ConstraintBlocks, k: Float) {
    val edgeDead = baked.edgeDead; val conEdge = conEdge; val conMuscle = conMuscle
    val csOn = cellScalesOn; val cs = conCellScale; val conRest = conRest
    val px = px; val py = py; val conA = conA; val conB = conB; val hold = conHold
    val links = layout.links
    val from = links.start[block]; val to = from + links.count[block]
    val items = links.items
    lengthsBeforeLoop(edgeDead.size or conEdge.size or conMuscle.size or cs.size or conRest.size or px.size or py.size or
        conA.size or conB.size or hold.size or items.size)
    for (slot in from until to) {
        val c = items[slot]
        if (c >= 0) plasticLinkAt(c, k, edgeDead, conEdge, conMuscle, csOn, cs, conRest, px, py, conA, conB, hold)
    }
}

private fun BodyState.plasticBlockTriangles(block: Int, layout: ConstraintBlocks, k: Float) {
    val triAlive = baked.triAlive; val triId = triId; val triMuscle = triMuscle
    val tsOn = cellScalesOn; val ts = triCellScale; val rest2 = triRestArea2
    val px = px; val py = py; val triA = triA; val triB = triB; val triC = triC; val hold = triHold
    val areas = layout.areas
    val from = areas.start[block]; val to = from + areas.count[block]
    val items = areas.items
    lengthsBeforeLoop(triAlive.size or triId.size or triMuscle.size or ts.size or rest2.size or px.size or py.size or
        triA.size or triB.size or triC.size or hold.size or items.size)
    for (slot in from until to) {
        val t = items[slot]
        if (t >= 0) plasticTriangleAt(t, k, triAlive, triId, triMuscle, tsOn, ts, rest2, px, py, triA, triB, triC, hold)
    }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun plasticLinkAt(c: Int, k: Float, edgeDead: BooleanArray, conEdge: IntArray, conMuscle: IntArray,
                                  csOn: Boolean, cs: FloatArray, conRest: FloatArray, px: FloatArray, py: FloatArray,
                                  conA: IntArray, conB: IntArray, hold: ByteArray) {
    if (edgeDead[conEdge[c]]) return
    // МЫШЦЫ НЕ ТЕКУТ, и это не осторожность, а необходимость.
    //
    // У сокращённой мышцы цель заметно короче (MUSCLE_CONTRACTION = 0.50), а
    // окружающая ткань дотянуться не даёт. Деформация относительно цели выходит
    // в сотни процентов — не потому что ткань смялась, а потому что мышца
    // работает. Пластика на этом разносит длину покоя, мышца «забывает» свою
    // длину и тянуть перестаёт. Проверка ловила это сразу: тело переставало
    // останавливаться вовсе (замедление 1.0x вместо 20x за минуту), потому что
    // ползущая длина покоя вечно шевелила границу, а среда это выпрямляла в тягу.
    if (conMuscle[c] >= 0 || (csOn && cs[c] != 1f)) return
    val target = conRest[c]
    if (target < 1e-12f) return
    val dx = px[conA[c]] - px[conB[c]]
    val dy = py[conA[c]] - py[conB[c]]
    val len = sqrt(dx * dx + dy * dy)
    val strain = len / target - 1.0f
    val over = when {
        strain > PLASTIC_YIELD -> strain - PLASTIC_YIELD
        strain < -PLASTIC_YIELD -> strain + PLASTIC_YIELD
        else -> 0.0f
    }
    if (over == 0.0f) { hold[c] = 0; return }
    if (hold[c] < PLASTIC_HOLD) { hold[c]++; return }
    // Предел длины идёт ЗА длиной покоя сам собой: он и есть
    // LINK_MAX_STRETCH * conRest, считается на месте. См. conMaxLen.
    conRest[c] *= 1.0f + over * k
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun plasticTriangleAt(t: Int, k: Float, triAlive: BooleanArray, triId: IntArray, triMuscle: IntArray,
                                      tsOn: Boolean, ts: FloatArray, triRestArea2: FloatArray, px: FloatArray, py: FloatArray,
                                      triA: IntArray, triB: IntArray, triC: IntArray, hold: ByteArray) {
    if (!triAlive[triId[t]]) return
    if (triMuscle[t] >= 0 || (tsOn && ts[t] != 1f)) return          // мышечные — по той же причине
    val rest2 = triRestArea2[t].toFloat()
    if (abs(rest2) < 1e-18f) return
    val i0 = triA[t]; val i1 = triB[t]; val i2 = triC[t]
    val area2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) -
        (py[i1] - py[i0]) * (px[i2] - px[i0])
    // ВЫВЕРНУТЫМ ТЕЧЬ НЕЛЬЗЯ: подогнав площадь покоя под сложенное состояние,
    // пластика УЗАКОНИТ складку, и та не разойдётся уже никогда. Проверка
    // ловила это прямо: складка 50% и 75% переставали расходиться за 8 секунд.
    if (area2 <= 0.0f) return
    val strain = area2 / rest2 - 1.0f
    val over = when {
        strain > PLASTIC_YIELD -> strain - PLASTIC_YIELD
        strain < -PLASTIC_YIELD -> strain + PLASTIC_YIELD
        else -> 0.0f
    }
    if (over == 0.0f) { hold[t] = 0; return }
    if (hold[t] < PLASTIC_HOLD) { hold[t]++; return }
    triRestArea2[t] = (triRestArea2[t] * (1.0f + over * k)).toFloat()
}

// Возврат застрявших и запрет разрыва удалены: возврат повышал дребезг,
// а запрет разрыва давал пролёт 15.4 связи при пределе 1.0. Детали отвергнутых
// опытов сохранены в docs/physics/realbody-history-2026-10-13.md.
fun BodyState.burstByPressure() {
    if (!tearingOn || contactPressure.size != n) return
    // Порванное в последнем проходе предела длины ещё не пересобрано, а признак
    // «осталась без связей» берётся из пересборки.
    if (trapKillTicks >= 0) { tick0(); scanTrapped(); tick1(10) }
    val lim = pressureBurst * linkScale
    var any = false
    // Спящие и так пропускаются по invMass <= 0: обходим только бодрствующих. См. BodySleep.
    prepareAwakeSpans()
    val s = sleep
    // Массивы и условия прохода — в локальные, длины — до цикла (см. lengthsBeforeLoop): проход идёт по всем
    // бодрствующим частицам раз в тик и читал девять массивов через поля состояния. Гибель клетки (редкая ветка
    // ниже) пишет в те же массивы по месту и их не пересоздаёт.
    val cellDead = cellDead; val boneOf = boneOf; val invMass = invMass
    val pressure = contactPressure; val pressureN = contactPressureN; val pnOn = pressureN.size == n
    val crushed = crushedCell; val isFree = isFree; val trap = trapTicks; val fold = foldTicks
    val burst = pressureBurst; val crushOn = crushBurstOn; val killTicks = trapKillTicks
    val trapOn = killTicks > 0 && trap.size == n
    val foldOn = killTicks > 0 && trapKillFolds && fold.size == n
    lengthsBeforeLoop(cellDead.size or boneOf.size or invMass.size or pressure.size or pressureN.size or crushed.size or
        isFree.size or trap.size or fold.size)
    for (k in 0 until s.spanCount) for (i in s.spanStart[k] until s.spanEnd[k]) {
        if (cellDead[i] || boneOf[i] >= 0 || invMass[i] <= 0.0f) continue
        // ДАВЛЕНИЕ НА ОДИН КОНТАКТ, а не сумма. Сумма не умеет отличить клетку,
        // раздавленную в чужой ткани, от клетки в плотной толпе: у второй толчков
        // много, но каждый мелкий. По журналу 9-й сцены сумма выносила всю засыпку
        // ёмкости — из 663 частиц оставалось живых 10.
        val pn = if (pnOn) pressureN[i] else 1
        val byPressure = burst > 0.0f && pressure[i] > lim * maxOf(1, pn)
        // РАЗДАВЛЕННАЯ КЛЕТКА ЛОПАЕТСЯ: связь порвалась на сжатии, и клетка осталась
        // совсем без связей. Иначе она свободной частицей остаётся внутри своей же
        // смятой ткани, где с ней ничто не сталкивается.
        val byCrush = crushOn && crushed[i] && isFree[i]
        val byTrap = trapOn && trap[i] >= killTicks
        val byFold = foldOn && fold[i] >= killTicks
        if (!byPressure && !byCrush && !byTrap && !byFold) continue
        cellDead[i] = true; deadCount++
        when { byPressure -> pressureBurstCount++; byCrush -> crushBurstCount++; byTrap -> trapKillCount++; else -> foldKillCount++ }
        // Заморозка, а не остановка. Причина та же, см. applyKills.
        vx[i] = 0.0f; vy[i] = 0.0f; invMass[i] = 0.0f
        contacts?.invalidateStatic()
        killIncidentEdges(i)
        any = true
    }
    if (!any) return
    refreshSurface()
}
