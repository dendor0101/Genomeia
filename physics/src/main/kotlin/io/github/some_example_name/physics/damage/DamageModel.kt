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
    for (k in 0 until ct.pierceN) {
        val e = baked.edgeOf(ct.pierceA[k], ct.pierceB[k])
        if (e < 0 || baked.edgeDead[e]) continue
        val c = conOfEdge[e]
        if (c < 0 || c >= conCount || conDead[c]) continue
        linkTorn[c] = true; conDead[c] = true; tearsPending = true
        killEdgeNow(e)
        piercedCount++
        dbgTear(c, conA[c], conB[c], "пробой", 0.0)
    }
    ct.clearPierce()
    refreshSurface()
}

fun BodyState.applyKills(ct: BoundaryContacts) {
    var any = false
    for (k in 0 until ct.killN) {
        val v = ct.killList[k]
        if (cellDead[v]) continue
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
        vx[v] = 0.0; vy[v] = 0.0; invMass[v] = 0.0
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

fun BodyState.killIncidentEdges(v: Int) {
    localComponents.removeCell(v)
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
    dbgSealMinR = contacts?.minContactRadius ?: 0.0
}

fun BodyState.applyPlasticity(dt: Double) {
    if (PLASTIC_RATE <= 0.0) return
    var k = PLASTIC_RATE * dt
    if (k > 1.0) k = 1.0
    // Выдержка принадлежит конкретной связи/треугольнику и переезжает вместе
    // с ними при упаковке; разрыв в другом месте не сбрасывает накопленную пластику.
    if (conHold.size < conA.size) conHold = conHold.copyOf(conA.size)
    if (triHold.size < triA.size) triHold = triHold.copyOf(triA.size)

    for (c in 0 until conCount) {
        if (baked.edgeDead[conEdge[c]]) continue
        // МЫШЦЫ НЕ ТЕКУТ, и это не осторожность, а необходимость.
        //
        // У сокращённой мышцы цель заметно короче (MUSCLE_CONTRACTION = 0.50), а
        // окружающая ткань дотянуться не даёт. Деформация относительно цели выходит
        // в сотни процентов — не потому что ткань смялась, а потому что мышца
        // работает. Пластика на этом разносит длину покоя, мышца «забывает» свою
        // длину и тянуть перестаёт. Проверка ловила это сразу: тело переставало
        // останавливаться вовсе (замедление 1.0x вместо 20x за минуту), потому что
        // ползущая длина покоя вечно шевелила границу, а среда это выпрямляла в тягу.
        if (conMuscle[c] >= 0) continue
        val target = conRest[c]
        if (target < 1e-12) continue
        val dx = px[conA[c]] - px[conB[c]]
        val dy = py[conA[c]] - py[conB[c]]
        val len = sqrt(dx * dx + dy * dy)
        val strain = len / target - 1.0
        val over = when {
            strain > PLASTIC_YIELD -> strain - PLASTIC_YIELD
            strain < -PLASTIC_YIELD -> strain + PLASTIC_YIELD
            else -> 0.0
        }
        if (over == 0.0) { conHold[c] = 0; continue }
        if (conHold[c] < PLASTIC_HOLD) { conHold[c]++; continue }
        // Предел длины идёт ЗА длиной покоя сам собой: он и есть
        // LINK_MAX_STRETCH * conRest, считается на месте. См. conMaxLen.
        conRest[c] *= 1.0 + over * k
    }

    for (t in 0 until triCount) {
        if (!baked.triAlive[triId[t]]) continue
        if (triMuscle[t] >= 0) continue          // мышечные — по той же причине
        val rest2 = triRestArea2[t].toDouble()
        if (abs(rest2) < 1e-18) continue
        val i0 = triA[t]; val i1 = triB[t]; val i2 = triC[t]
        val area2 = (px[i1] - px[i0]) * (py[i2] - py[i0]) -
            (py[i1] - py[i0]) * (px[i2] - px[i0])
        // ВЫВЕРНУТЫМ ТЕЧЬ НЕЛЬЗЯ: подогнав площадь покоя под сложенное состояние,
        // пластика УЗАКОНИТ складку, и та не разойдётся уже никогда. Проверка
        // ловила это прямо: складка 50% и 75% переставали расходиться за 8 секунд.
        if (area2 <= 0.0) continue
        val strain = area2 / rest2 - 1.0
        val over = when {
            strain > PLASTIC_YIELD -> strain - PLASTIC_YIELD
            strain < -PLASTIC_YIELD -> strain + PLASTIC_YIELD
            else -> 0.0
        }
        if (over == 0.0) { triHold[t] = 0; continue }
        if (triHold[t] < PLASTIC_HOLD) { triHold[t]++; continue }
        triRestArea2[t] = (triRestArea2[t] * (1.0 + over * k)).toFloat()
    }
}

// Возврат застрявших и запрет разрыва удалены: возврат повышал дребезг,
// а запрет разрыва давал пролёт 15.4 связи при пределе 1.0. Детали отвергнутых
// опытов сохранены в docs/physics/realbody-history-2026-10-13.md.
fun BodyState.burstByPressure() {
    if (!tearingOn || contactPressure.size != n) return
    // Порванное в последнем проходе предела длины ещё не пересобрано, а признак
    // «осталась без связей» берётся из пересборки.
    if (trapKillTicks >= 0) { tick0(); scanTrapped(); tick1(10) }
    val lim = pressureBurst * body.meanLinkLength
    var any = false
    for (i in 0 until n) {
        if (cellDead[i] || boneOf[i] >= 0 || invMass[i] <= 0.0) continue
        // ДАВЛЕНИЕ НА ОДИН КОНТАКТ, а не сумма. Сумма не умеет отличить клетку,
        // раздавленную в чужой ткани, от клетки в плотной толпе: у второй толчков
        // много, но каждый мелкий. По журналу 9-й сцены сумма выносила всю засыпку
        // ёмкости — из 663 частиц оставалось живых 10.
        val pn = if (contactPressureN.size == n) contactPressureN[i] else 1
        val byPressure = pressureBurst > 0.0 && contactPressure[i] > lim * maxOf(1, pn)
        // РАЗДАВЛЕННАЯ КЛЕТКА ЛОПАЕТСЯ: связь порвалась на сжатии, и клетка осталась
        // совсем без связей. Иначе она свободной частицей остаётся внутри своей же
        // смятой ткани, где с ней ничто не сталкивается.
        val byCrush = crushBurstOn && crushedCell[i] && isFree[i]
        val byTrap = trapKillTicks > 0 && trapTicks.size == n && trapTicks[i] >= trapKillTicks
        val byFold = trapKillTicks > 0 && trapKillFolds && foldTicks.size == n && foldTicks[i] >= trapKillTicks
        if (!byPressure && !byCrush && !byTrap && !byFold) continue
        cellDead[i] = true; deadCount++
        when { byPressure -> pressureBurstCount++; byCrush -> crushBurstCount++; byTrap -> trapKillCount++; else -> foldKillCount++ }
        // Заморозка, а не остановка. Причина та же, см. applyKills.
        vx[i] = 0.0; vy[i] = 0.0; invMass[i] = 0.0
        killIncidentEdges(i)
        any = true
    }
    if (!any) return
    refreshSurface()
}
